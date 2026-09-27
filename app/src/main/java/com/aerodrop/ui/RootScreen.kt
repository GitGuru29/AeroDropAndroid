package com.aerodrop.ui

// RootScreen.kt — AeroDrop Android  [Phase 4: UI]
// The whole screen: peer sidebar, drop zone, live transfer and queue.
//
// The macOS app's organising idea is that a transfer is water filling a vessel
// and a completed one is a ripple. The same two metaphors are reproduced here
// with Compose primitives rather than Canvas, so the phone stays smooth while
// showing both directions through identical components.

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aerodrop.AeroViewModel
import com.aerodrop.Direction
import com.aerodrop.TransferUi
import com.aerodrop.discovery.AeroPeer

@Composable
fun RootScreen(
    vm:              AeroViewModel,
    onPickFiles:     () -> Unit,
    onPickMultiple:  () -> Unit,
) {
    val peers        by vm.orderedPeers.collectAsStateWithLifecycle(emptyList())
    val selected     by vm.selectedPeer.collectAsStateWithLifecycle(null)
    val browsing     by vm.browsing.collectAsStateWithLifecycle(false)
    val advertised   by vm.advertised.collectAsStateWithLifecycle(null)
    val listening    by vm.listening.collectAsStateWithLifecycle(false)
    val outgoing     by vm.outgoing.collectAsStateWithLifecycle(null)
    val incoming     by vm.incoming.collectAsStateWithLifecycle(null)
    val history      by vm.history.collectAsStateWithLifecycle(emptyList())
    val sending      by vm.sending.collectAsStateWithLifecycle(false)
    val receivedDir  by vm.receivedDir.collectAsStateWithLifecycle("")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AeroTheme.Background)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(listening, advertised) }

        item {
            PeerCard(
                peers = peers,
                selected = selected,
                browsing = browsing,
                onSelect = vm::selectPeer,
            )
        }

        item {
            DropCard(
                selected = selected,
                outgoing = outgoing,
                incoming = incoming,
                busy = sending || incoming != null,
                onSend = onPickFiles,
                onSendMany = onPickMultiple,
            )
        }

        if (outgoing != null || incoming != null) {
            item {
                LiveCard(
                    outgoing = outgoing,
                    incoming = incoming,
                )
            }
        }

        if (history.isNotEmpty()) {
            item {
                QueueCard(history = history, onClear = vm::clearHistory)
            }
        }

        item { FooterCard(vm.fingerprint, listening, receivedDir) }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun Header(listening: Boolean, advertised: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "AERO", color = AeroTheme.Accent, fontSize = 26.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
        )
        Text(
            "DROP", color = AeroTheme.Secondary, fontSize = 26.sp,
            fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (listening) AeroTheme.Success else AeroTheme.Muted)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (listening) "LISTENING" else "OFFLINE",
            color = if (listening) AeroTheme.Success else AeroTheme.Muted,
            fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        )
        Spacer(Modifier.weight(1f))
        advertised?.let {
            Text(
                it, color = AeroTheme.Muted, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Peers ──────────────────────────────────────────────────────────────────────

@Composable
private fun PeerCard(
    peers:    List<AeroPeer>,
    selected: AeroPeer?,
    browsing: Boolean,
    onSelect: (AeroPeer) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AeroTheme.Surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "NEARBY", color = AeroTheme.Accent, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        peers.isNotEmpty() -> "${peers.size} found"
                        browsing -> "scanning…"
                        else -> "no signal"
                    },
                    color = AeroTheme.Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = AeroTheme.Hairline)
            Spacer(Modifier.height(8.dp))

            if (peers.isEmpty()) {
                Text(
                    if (browsing) "Looking for Macs running AeroDrop…"
                    else "Discovery is not running. Both devices need to be on the same Wi-Fi.",
                    color = AeroTheme.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            } else {
                peers.forEach { peer ->
                    PeerRow(
                        peer = peer,
                        selected = peer.name == selected?.name,
                        onClick = { onSelect(peer) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: AeroPeer, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) AeroTheme.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) AeroTheme.Accent.copy(alpha = 0.55f) else AeroTheme.Hairline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AeroTheme.Accent.copy(alpha = if (selected) 0.25f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("⌘", color = AeroTheme.Accent, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.name, color = AeroTheme.OnSurface, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                peer.displayAddress, color = AeroTheme.Muted, fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Text(
                "SELECTED", color = AeroTheme.Accent, fontSize = 8.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
            )
        }
    }
}

// ── Drop zone ──────────────────────────────────────────────────────────────────

@Composable
private fun DropCard(
    selected: AeroPeer?,
    outgoing: TransferUi?,
    incoming: TransferUi?,
    busy:     Boolean,
    onSend:   () -> Unit,
    onSendMany: () -> Unit,
) {
    val busyNow = busy || outgoing != null || incoming != null
    Card(
        colors = CardDefaults.cardColors(containerColor = AeroTheme.Surface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RippleMark(active = busyNow)

            Text(
                when {
                    busyNow -> "Transfer in progress"
                    selected != null -> "Send to ${selected.name}"
                    else -> "No Mac selected"
                },
                color = AeroTheme.OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "TLS 1.3  ·  local network only",
                color = AeroTheme.Success.copy(alpha = 0.7f), fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )

            if (selected != null && !busyNow) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AeroTheme.Accent, contentColor = Color.Black),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Send a file", fontWeight = FontWeight.Bold) }

                    Button(
                        onClick = onSendMany,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AeroTheme.SurfaceAlt, contentColor = AeroTheme.OnSurface),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Send many") }
                }
            }
        }
    }
}

/**
 * Concentric rings on the idle/drop zone — three of them, staggered, so it reads
 * as a splash rather than a spinner. Mirrors RippleLayer on macOS.
 */
@Composable
private fun RippleMark(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        for (ring in 0 until 3) {
            val t = ((phase + ring) % 3f) / 3f
            val scale = 0.42f + 0.58f * t
            val alpha = ((1f - t) * if (active) 0.55f else 0.30f).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(96.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .border(1.dp, AeroTheme.Accent.copy(alpha = alpha), CircleShape)
            )
        }
        Text(
            if (active) "↓" else "↑",
            color = AeroTheme.Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        )
    }
}

// ── Live transfers ────────────────────────────────────────────────────────────

@Composable
private fun LiveCard(outgoing: TransferUi?, incoming: TransferUi?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (outgoing as? TransferUi.Running)?.let { TransferRow(it) }
        (incoming as? TransferUi.Running)?.let { TransferRow(it) }
    }
}

@Composable
private fun TransferRow(state: TransferUi.Running) {
    val verb = if (state.direction == Direction.Outgoing) "SENDING" else "RECEIVING"
    val arrow = if (state.direction == Direction.Outgoing) "↑" else "↓"
    val animated by animateFloatAsState(
        state.fraction,
        animationSpec = tween(220),   // smooths the ~100 ms progress ticks
        label = "progress",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = AeroTheme.Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$arrow $verb", color = AeroTheme.Accent, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatSpeed(state.speedMBs), color = AeroTheme.Muted, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                state.filename, color = AeroTheme.OnSurface, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            WaveProgress(fraction = animated)
            Text(
                formatPercent(animated), color = AeroTheme.Accent, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * A waterline rather than a filled rectangle: the filled region is a rounded
 * track and the crest is a bright edge, so a stalled transfer still reads as
 * "holding water" instead of "broken". The Mac's WaveProgressBar is the same
 * idea drawn on Canvas.
 */
@Composable
private fun WaveProgress(fraction: Float) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(AeroTheme.SurfaceAlt),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f.coerceAtLeast(0.02f))
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(AeroTheme.AccentDeep, AeroTheme.Accent)
                    )
                )
        )
    }
}

// ── Queue ──────────────────────────────────────────────────────────────────────

@Composable
private fun QueueCard(history: List<TransferUi.Finished>, onClear: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AeroTheme.Surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "TRANSFERS", color = AeroTheme.Accent, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "clear", color = AeroTheme.Muted, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable(onClick = onClear).padding(4.dp),
                )
            }
            history.take(8).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.direction == Direction.Outgoing) "↑" else "↓",
                        color = AeroTheme.Muted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.filename.ifBlank { "(unnamed)" },
                            color = AeroTheme.OnSurface, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (item.detail.isNotBlank()) {
                            Text(
                                item.detail, color = AeroTheme.Muted, fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        if (item.success) "✓" else "✗",
                        color = if (item.success) AeroTheme.Success else AeroTheme.Danger,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

// ── Footer ─────────────────────────────────────────────────────────────────────

@Composable
private fun FooterCard(fingerprint: String, listening: Boolean, receivedDir: String) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AeroTheme.Surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "THIS DEVICE", color = AeroTheme.Accent, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            if (fingerprint.isNotBlank()) {
                Text(
                    // Grouped so a human can compare it with the Mac's footer.
                    fingerprint.chunked(8).joinToString(" "),
                    color = AeroTheme.Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Copy ID", color = AeroTheme.Accent, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable { copyToClipboard(context, "AeroDrop ID", fingerprint) }
                        .padding(vertical = 2.dp),
                )
            }
            if (receivedDir.isNotBlank()) {
                Text(
                    "Received files land in $receivedDir",
                    color = AeroTheme.Muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
