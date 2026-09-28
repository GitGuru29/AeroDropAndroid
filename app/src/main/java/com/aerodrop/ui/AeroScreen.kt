package com.aerodrop.ui

// AeroScreen.kt — AeroDrop Android  [Phase 4: UI]
// The page, as pure data in and callbacks out.
//
// RootScreen.kt does nothing but collect StateFlows and call this. The split is
// what makes the screen previewable: there is no ViewModel to fake, so every
// state the app can reach — offline, scanning, one peer, two peers, sending,
// receiving, a failed transfer — can be rendered in a @Preview without a device.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aerodrop.AeroViewModel
import com.aerodrop.Direction
import com.aerodrop.TransferUi
import com.aerodrop.discovery.AeroPeer
import com.aerodrop.ui.components.AeroHeader
import com.aerodrop.ui.components.DeviceCard
import com.aerodrop.ui.components.DropZone
import com.aerodrop.ui.components.LiveTransfers
import com.aerodrop.ui.components.PeerCard
import com.aerodrop.ui.components.QueueCard
import com.aerodrop.ui.theme.AeroColors
import com.aerodrop.ui.theme.AeroSpace
import com.aerodrop.ui.theme.AeroTheme

@Composable
fun AeroScreen(
    peers:        List<AeroPeer>,
    selected:     AeroPeer?,
    browsing:     Boolean,
    advertised:   String?,
    listening:    Boolean,
    running:      List<TransferUi.Running>,
    history:      List<TransferUi.Finished>,
    busy:         Boolean,
    fingerprint:  String,
    receivedDir:  String,
    onSelect:     (AeroPeer) -> Unit,
    onSend:       () -> Unit,
    onSendMany:   () -> Unit,
    onClear:      () -> Unit,
) {
    val bars = WindowInsets.safeDrawing.asPaddingValues()
    val gutter = AeroTheme.gutter

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AeroColors.Background),
        // The activity calls enableEdgeToEdge(), so the window draws behind the
        // status and navigation bars. Taking the inset from the window — rather
        // than a flat top padding — is what keeps the wordmark clear of the
        // clock and the device card clear of the gesture bar. The leftovers go
        // here so the last card can still scroll fully into view.
        contentPadding = PaddingValues(
            start = gutter,
            end = gutter,
            top = bars.calculateTopPadding() + AeroSpace.lg,
            bottom = bars.calculateBottomPadding() + AeroSpace.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(AeroSpace.lg),
    ) {
        item(key = "header") {
            AeroHeader(listening = listening, advertised = advertised)
        }

        item(key = "peers") {
            PeerCard(
                peers = peers,
                selected = selected,
                browsing = browsing,
                onSelect = onSelect,
            )
        }

        item(key = "drop") {
            DropZone(
                selected = selected,
                receiving = running.any { it.direction == Direction.Incoming },
                busy = busy,
                onSend = onSend,
                onSendMany = onSendMany,
            )
        }

        if (running.isNotEmpty()) {
            item(key = "live") {
                LiveTransfers(running = running)
            }
        }

        if (history.isNotEmpty()) {
            item(key = "queue") {
                QueueCard(history = history, onClear = onClear)
            }
        }

        item(key = "device") {
            DeviceCard(fingerprint = fingerprint, receivedDir = receivedDir)
        }
    }
}

@Composable
fun RootScreen(
    vm:             AeroViewModel,
    onPickFiles:    () -> Unit,
    onPickMultiple: () -> Unit,
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

    val outgoingRun = outgoing as? TransferUi.Running
    val incomingRun = incoming as? TransferUi.Running

    AeroScreen(
        peers       = peers,
        selected    = selected,
        browsing    = browsing,
        advertised  = advertised,
        listening   = listening,
        running     = listOfNotNull(outgoingRun, incomingRun),
        history     = history,
        busy        = sending || outgoing != null || incoming != null,
        fingerprint = vm.fingerprint,
        receivedDir = receivedDir,
        onSelect    = vm::selectPeer,
        onSend      = onPickFiles,
        onSendMany  = onPickMultiple,
        onClear     = vm::clearHistory,
    )
}
