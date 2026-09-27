package com.aerodrop.transfer

// AeroInbound.kt — AeroDrop Android  [Phase 5: Inbound]
// Process-wide state for files arriving from the Mac.
//
// AeroReceiverService runs in the same process as the UI, so a plain object
// holding StateFlows is enough — no bound service or IPC needed. This is the
// mirror image of AeroTransferClient's outbound Flow: the UI observes both with
// the same components, and only the arrow and the verb differ, matching
// TransferStyle on macOS where direction is never carried by colour.

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Progress of a file the Mac is pushing to this device. */
data class InboundProgress(
    val filename: String,
    val received: Long,
    val total: Long,
    val speedMBs: Double,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (received.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/** Terminal state of an inbound transfer. */
sealed class InboundResult {
    data class Saved(val filename: String, val bytes: Long, val location: String) : InboundResult()
    data class Failed(val filename: String, val reason: String) : InboundResult()
}

object AeroInbound {

    private const val MAX_HISTORY = 20

    private val _active = MutableStateFlow<InboundProgress?>(null)
    val active: StateFlow<InboundProgress?> = _active.asStateFlow()

    private val _lastResult = MutableStateFlow<InboundResult?>(null)
    val lastResult: StateFlow<InboundResult?> = _lastResult.asStateFlow()

    private val _log = MutableStateFlow<List<InboundResult>>(emptyList())
    val log: StateFlow<List<InboundResult>> = _log.asStateFlow()

    private val _listening = MutableStateFlow(false)
    /** Whether the TLS listener is actually bound, as opposed to merely enabled. */
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _receivedDir = MutableStateFlow<String>("")
    val receivedDir: StateFlow<String> = _receivedDir.asStateFlow()

    private val history = CopyOnWriteArrayList<InboundResult>()

    fun setListening(value: Boolean) { _listening.value = value }

    fun setReceivedDir(context: Context) {
        _receivedDir.value = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ).absolutePath + File.separator + "AeroDrop"
    }

    internal fun begin(filename: String, total: Long) {
        _active.value = InboundProgress(filename, 0, total, 0.0)
    }

    internal fun advance(progress: InboundProgress) { _active.value = progress }

    internal fun finish(result: InboundResult) {
        _active.value = null
        _lastResult.value = result
        history.add(0, result)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        _log.value = history.toList()
    }

    /** Plain-text label for the "where did it land" line. */
    fun locationOf(uri: Uri?): String = uri?.lastPathSegment ?: "Downloads/AeroDrop"
}
