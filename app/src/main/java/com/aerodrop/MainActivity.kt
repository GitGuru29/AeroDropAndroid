package com.aerodrop

// MainActivity.kt — AeroDrop Android  [Phase 4: UI]
// Hosts the Compose UI and owns the two entry points that arrive from outside
// the app: the notification permission prompt, and files shared in from other
// apps.
//
// Sharing has to survive the activity already being open. ACTION_SEND delivered
// to a running task goes to onNewIntent, not onCreate, so the first
// implementation — reading the intent inside a LaunchedEffect(Unit) — silently
// dropped every share after the app had been opened once. Both paths funnel into
// one handler here, and the manifest sets singleTop to make onNewIntent the path.

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerodrop.ui.RootScreen
import com.aerodrop.ui.theme.AeroDropTheme
import java.io.File
import java.util.ArrayDeque

class MainActivity : ComponentActivity() {

    private var pendingUris by mutableStateOf(emptyList<Uri>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingUris = urisFrom(intent)

        setContent {
            AeroDropTheme {
                val vm: AeroViewModel = viewModel()
                val context = LocalContext.current

                // Consume whatever arrived from a share, then clear it so a
                // recomposition cannot send the same file twice.
                val incoming = pendingUris
                LaunchedEffect(incoming) {
                    if (incoming.isNotEmpty()) {
                        vm.send(context, incoming)
                        pendingUris = emptyList()
                    }
                }

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* Transfers work either way; this only gates the banner. */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        vm.send(context, listOf(it))
                    }
                }

                val multiPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris: List<Uri> ->
                    if (uris.isNotEmpty()) vm.send(context, uris)
                }

                RootScreen(
                    vm = vm,
                    onPickFiles = { picker.launch(arrayOf("*/*")) },
                    onPickMultiple = { multiPicker.launch(arrayOf("*/*")) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uris = urisFrom(intent)
        if (uris.isNotEmpty()) pendingUris = uris
    }

    /**
     * Pull every stream URI out of an ACTION_SEND / ACTION_SEND_MULTIPLE intent.
     * Bounded because a hostile or buggy sender can put an arbitrary number of
     * extras in a Bundle and a transaction-size crash is not a useful failure.
     */
    private fun urisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val found = ArrayDeque<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                streamExtra(intent)?.let { found.addLast(it) }
                // Shared *text* is not a URI. Uri.parse("some note") yields a
                // scheme-less relative reference that nothing can open, so the
                // text is staged into a real file and that is what gets sent.
                textExtra(intent)?.let { textToUri(single = it)?.let(found::addLast) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                streamListExtra(intent)?.forEach { found.addLast(it) }
                textListExtra(intent)?.forEach { text ->
                    textToUri(single = text)?.let(found::addLast)
                }
            }
            else -> return emptyList()
        }

        // Fall back to the data URI, which is how a browser "share link" arrives.
        if (found.isEmpty()) {
            intent.data?.let { found.addLast(it) }
        }
        return found.take(MAX_SHARED).toList()
    }

    /**
     * Write shared text into the cache and return a file:// URI for it.
     * Returns null if the text is unusable or cannot be written, so a share of
     * something that is not text simply transfers nothing instead of failing.
     */
    private fun textToUri(single: String): Uri? {
        val text = single.take(MAX_SHARED_TEXT).ifBlank { return null }
        return runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "shared-${System.currentTimeMillis()}.txt")
            file.writeText(text)
            Uri.fromFile(file)
        }.onFailure {
            Log.w(TAG, "could not stage shared text: ${it.message}")
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }

    private fun textExtra(intent: Intent): String? =
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }

    private fun textListExtra(intent: Intent): List<String>? =
        intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.filter { it.isNotBlank() }

    private companion object {
        const val MAX_SHARED = 32

        /** Shared text is staged into a file, so keep the staged copy bounded. */
        const val MAX_SHARED_TEXT = 1 shl 20   // 1 MiB

        private const val TAG = "AeroShare"
    }
}
