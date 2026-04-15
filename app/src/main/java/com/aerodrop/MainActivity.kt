package com.aerodrop

// MainActivity.kt — AeroDrop Android  [Phase 4: UI]
// Jetpack Compose cyberpunk UI matching the macOS drop zone aesthetic.
// Features: mDNS peer list, file picker, live transfer progress, receive toggle.

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aerodrop.discovery.AeroAdvertiser
import com.aerodrop.discovery.AeroDiscoveryService
import com.aerodrop.discovery.AeroPeer
import com.aerodrop.transfer.AeroReceiverService
import com.aerodrop.transfer.AeroTransferClient
import com.aerodrop.transfer.TransferEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── Colour palette (mirrors macOS AeroDrop) ───────────────────────────────────

private val BgDark   = Color(0xFF090910)
private val Cyan     = Color(0xFF00E5FF)
private val Magenta  = Color(0xFFFF00CC)
private val NeonGreen = Color(0xFF00FFA3)
private val CardBg   = Color(0xFF111820)

// ── Transfer state ────────────────────────────────────────────────────────────

sealed class UiTransferState {
    object Idle : UiTransferState()
    data class Active(val filename: String, val progress: Float, val speedMbps: Double) : UiTransferState()
    data class Done(val filename: String, val success: Boolean, val reason: String = "") : UiTransferState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

// AndroidViewModel provides Application context in the constructor —
// discovery is initialised synchronously before the first Compose frame,
// eliminating the lateinit race that caused the crash.
class AeroViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery   = AeroDiscoveryService(app)
    private val advertiser  = AeroAdvertiser(app)      // Advertise this device on _aerodrop._tcp

    // Exposed as a stable StateFlow — safe to collect on frame 1
    val peers: StateFlow<List<AeroPeer>> = discovery.peers

    private val _selectedPeer  = MutableStateFlow<AeroPeer?>(null)
    val selectedPeer = _selectedPeer.asStateFlow()

    private val _transferState = MutableStateFlow<UiTransferState>(UiTransferState.Idle)
    val transferState = _transferState.asStateFlow()

    private val _receiving = MutableStateFlow(false)
    val receiving = _receiving.asStateFlow()

    init {
        // Browse for Mac peers + advertise this device so the Mac finds us
        discovery.startDiscovery()
        advertiser.startAdvertising()
    }

    fun selectPeer(peer: AeroPeer) { _selectedPeer.value = peer }

    fun sendFile(context: android.content.Context, uri: Uri) {
        val peer = _selectedPeer.value ?: return
        viewModelScope.launch {
            AeroTransferClient.sendFile(context, uri, peer.host, peer.port)
                .collect { event ->
                    _transferState.value = when (event) {
                        is TransferEvent.Progress -> UiTransferState.Active(
                            event.filename,
                            (event.bytes.toFloat() / event.total).coerceIn(0f, 1f),
                            event.speedMbps)
                        is TransferEvent.Success  -> UiTransferState.Done(event.filename, true)
                        is TransferEvent.Failure  -> UiTransferState.Done("", false, event.reason)
                    }
                }
            kotlinx.coroutines.delay(3000)
            _transferState.value = UiTransferState.Idle
        }
    }

    fun toggleReceiver(context: android.content.Context) {
        val intent = Intent(context, AeroReceiverService::class.java)
        if (_receiving.value) {
            context.stopService(intent)
            _receiving.value = false
        } else {
            context.startForegroundService(intent)
            _receiving.value = true
        }
    }

    override fun onCleared() {
        super.onCleared()
        discovery.stopDiscovery()
        advertiser.stopAdvertising()
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AeroDropScreen()
            }
        }
    }
}

// ── Root screen ───────────────────────────────────────────────────────────────

@Composable
fun AeroDropScreen(vm: AeroViewModel = viewModel()) {
    val context       = androidx.compose.ui.platform.LocalContext.current
    val peers         by vm.peers.collectAsStateWithLifecycle(emptyList())
    val selectedPeer  by vm.selectedPeer.collectAsStateWithLifecycle()
    val transferState by vm.transferState.collectAsStateWithLifecycle()
    val receiving     by vm.receiving.collectAsStateWithLifecycle()

    // No vm.init() needed — discovery starts in AndroidViewModel.init{}

    // File picker launcher
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.sendFile(context, it) } }

    // Handle incoming Share intent (fires once after first composition)
    LaunchedEffect(Unit) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val uri = activity.intent
            ?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        uri?.let { vm.sendFile(context, it) }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgDark)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────
            AeroHeader()

            // ── Peer list ─────────────────────────────────────────────────
            PeerListCard(peers, selectedPeer, onSelect = vm::selectPeer)

            // ── Transfer zone ─────────────────────────────────────────────
            TransferZone(
                transferState = transferState,
                selectedPeer  = selectedPeer,
                onPickFile    = { filePicker.launch("*/*") }
            )

            // ── Receiver toggle ───────────────────────────────────────────
            ReceiverToggle(receiving) { vm.toggleReceiver(context) }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
fun AeroHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text       = "AERO",
            color      = Cyan,
            fontSize   = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text       = "DROP",
            color      = Magenta,
            fontSize   = 28.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(8.dp).clip(CircleShape).background(NeonGreen))
        Spacer(Modifier.width(6.dp))
        Text("ONLINE", color = NeonGreen, fontSize = 9.sp,
            fontFamily = FontFamily.Monospace)
    }
}

// ── Peer list ─────────────────────────────────────────────────────────────────

@Composable
fun PeerListCard(
    peers:       List<AeroPeer>,
    selected:    AeroPeer?,
    onSelect:    (AeroPeer) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape  = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("(·)  NEARBY", color = Cyan, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(if (peers.isEmpty()) "scanning…" else "${peers.size} found",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(8.dp))

            if (peers.isEmpty()) {
                Text("Scanning local network for macs…",
                    color    = Color.White.copy(alpha = 0.35f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            } else {
                LazyColumn(
                    modifier     = Modifier.heightIn(max = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(peers) { peer ->
                        PeerRow(
                            peer     = peer,
                            selected = peer == selected,
                            onTap    = { onSelect(peer) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PeerRow(peer: AeroPeer, selected: Boolean, onTap: () -> Unit) {
    val borderColor = if (selected) Cyan.copy(alpha = 0.7f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .background(if (selected) Cyan.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(Cyan.copy(alpha = if (selected) 0.25f else 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text("⌘", color = Cyan, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("${peer.host}:${peer.port}",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SignalBars()
    }
}

@Composable
fun SignalBars() {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(4, 7, 10, 13).forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.dp))
                .background(if (i < 3) NeonGreen else NeonGreen.copy(alpha = 0.3f)))
        }
    }
}

// ── Transfer zone ─────────────────────────────────────────────────────────────

@Composable
fun TransferZone(
    transferState: UiTransferState,
    selectedPeer:  AeroPeer?,
    onPickFile:    () -> Unit
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = CardBg),
        shape    = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier.fillMaxWidth().padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            when (transferState) {
                is UiTransferState.Idle   -> IdleZone(selectedPeer, onPickFile)
                is UiTransferState.Active -> ActiveZone(transferState)
                is UiTransferState.Done   -> DoneZone(transferState)
            }
        }
    }
}

@Composable
fun IdleZone(selectedPeer: AeroPeer?, onPickFile: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(80.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Cyan.copy(0.15f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) {
            Text("↑", color = Cyan, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (selectedPeer != null) "Send to ${selectedPeer.name}"
                 else "Select a Mac device above",
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("TLS 1.3  ·  AES-256-GCM  ·  No cloud",
                color = NeonGreen.copy(alpha = 0.6f), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
        }
        if (selectedPeer != null) {
            Button(
                onClick = onPickFile,
                colors  = ButtonDefaults.buttonColors(containerColor = Cyan),
                shape   = RoundedCornerShape(12.dp)
            ) {
                Text("Pick File", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ActiveZone(state: UiTransferState.Active) {
    val animProg by animateFloatAsState(state.progress, animationSpec = spring(), label = "prog")
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("↑ SENDING", color = Cyan, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 3.sp)
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress     = { animProg },
                modifier     = Modifier.size(120.dp),
                color        = Cyan,
                trackColor   = Cyan.copy(alpha = 0.1f),
                strokeWidth  = 6.dp
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(state.progress * 100).toInt()}%",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
                Text(String.format("%.1f MB/s", state.speedMbps),
                    color = Cyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text(state.filename, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DoneZone(state: UiTransferState.Done) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.05f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "scale")
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (state.success) "✓" else "✗",
            color    = if (state.success) NeonGreen else Color(0xFFFF4455),
            fontSize = 52.sp,
            modifier = Modifier.scale(scale))
        Text(if (state.success) "Transfer Complete" else "Transfer Failed",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(if (state.success) state.filename else state.reason,
            color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Receiver toggle ───────────────────────────────────────────────────────────

@Composable
fun ReceiverToggle(receiving: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("↓ RECEIVE FROM MAC", color = if (receiving) NeonGreen else Color.White.copy(0.4f),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (receiving) NeonGreen else Color.Gray)
        )
        Spacer(Modifier.width(6.dp))
        Text(if (receiving) "ON" else "OFF",
            color = if (receiving) NeonGreen else Color.Gray,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}
