package com.aerodrop.ui

// Previews.kt — AeroDrop Android  [Phase 4: UI]
// Every state the screen can reach, rendered without a device.
//
// In src/debug rather than src/main: a @Preview has no runtime use, and release
// is unminified, so keeping them in main would ship the fixtures to users.
//
// Each preview is a state that used to be impossible to see without a second
// physical Mac on the network — which is why the old UI had no previews at all
// and every visual change needed a hardware round trip.

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.aerodrop.Direction
import com.aerodrop.TransferUi
import com.aerodrop.discovery.AeroPeer
import com.aerodrop.ui.theme.AeroDropTheme

private val macBook = AeroPeer("Ari's MacBook Pro", "192.168.1.24", 48642)
private val macMini = AeroPeer("Studio", "192.168.1.31", 48642)

private val idleHistory = listOf(
    TransferUi.Finished(Direction.Outgoing, "Q3-roadmap.key", true),
    TransferUi.Finished(Direction.Incoming, "IMG_4471.HEIC", true, "Downloads/AeroDrop"),
    TransferUi.Finished(
        Direction.Outgoing, "archive-2019.zip", false, "The Mac closed the connection"
    ),
    TransferUi.Finished(Direction.Incoming, "keynote-final.key", true, "Downloads/AeroDrop"),
)

private const val FINGERPRINT =
    "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"

@Composable
private fun Screen(
    peers: List<AeroPeer> = emptyList(),
    selected: AeroPeer? = null,
    browsing: Boolean = false,
    advertised: String? = "AeroDrop-9f2c",
    listening: Boolean = true,
    running: List<TransferUi.Running> = emptyList(),
    history: List<TransferUi.Finished> = emptyList(),
    busy: Boolean = false,
    fingerprint: String = FINGERPRINT,
    receivedDir: String = "Downloads/AeroDrop",
) {
    AeroDropTheme {
        AeroScreen(
            peers = peers,
            selected = selected,
            browsing = browsing,
            advertised = advertised,
            listening = listening,
            running = running,
            history = history,
            busy = busy,
            fingerprint = fingerprint,
            receivedDir = receivedDir,
            onSelect = {},
            onSend = {},
            onSendMany = {},
            onClear = {},
        )
    }
}

// ── The whole page ────────────────────────────────────────────────────────────

@Preview(name = "1 · Offline, nothing found", heightDp = 900)
@Composable
private fun Offline() {
    Screen(advertised = null, listening = false, fingerprint = "", receivedDir = "")
}

@Preview(name = "2 · Cold start, scanning", heightDp = 900)
@Composable
private fun Scanning() {
    Screen(browsing = true)
}

@Preview(name = "3 · Ready to send", heightDp = 900)
@Composable
private fun Ready() {
    Screen(peers = listOf(macBook, macMini), selected = macBook)
}

@Preview(name = "4 · Sending, with history", heightDp = 1400)
@Composable
private fun SendingWithHistory() {
    Screen(
        peers = listOf(macBook),
        selected = macBook,
        running = listOf(
            TransferUi.Running(
                Direction.Outgoing, "IMG_4471.HEIC", 0.47f, 8.4, 24_500_000L
            )
        ),
        history = idleHistory,
        busy = true,
    )
}

@Preview(name = "5 · Receiving from the Mac", heightDp = 1100)
@Composable
private fun Receiving() {
    Screen(
        peers = listOf(macBook),
        selected = macBook,
        running = listOf(
            TransferUi.Running(Direction.Incoming, "keynote-final.key", 0.12f, 0.0, 8_100_000L)
        ),
        busy = true,
    )
}

// ── Sections in isolation ─────────────────────────────────────────────────────

@Preview(name = "Peers · two, one selected", heightDp = 320)
@Composable
private fun PeersTwo() {
    Screen(peers = listOf(macBook, macMini), selected = macMini)
}

@Preview(name = "Peers · long names", heightDp = 380)
@Composable
private fun PeersLongNames() {
    Screen(
        peers = listOf(
            AeroPeer("Silunas-MacBook-Pro-(Retina,-14-inch,-2021)", "fe80::a1b2:c3d4:e5f6:1", 48642),
            macMini,
        ),
        selected = macMini,
    )
}

@Preview(name = "Drop zone · no peer", heightDp = 360)
@Composable
private fun DropNoPeer() {
    Screen()
}

@Preview(name = "Drop zone · ready", heightDp = 400)
@Composable
private fun DropReady() {
    Screen(peers = listOf(macBook), selected = macBook)
}

@Preview(name = "Live transfer · sending", heightDp = 260)
@Composable
private fun LiveSending() {
    Screen(
        peers = listOf(macBook), selected = macBook, busy = true,
        running = listOf(
            TransferUi.Running(Direction.Outgoing, "IMG_4471.HEIC", 0.47f, 8.4, 24_500_000L)
        ),
    )
}

@Preview(name = "Queue · mixed outcomes", heightDp = 400)
@Composable
private fun Queue() {
    Screen(history = idleHistory)
}

@Preview(name = "Device · no identity yet", heightDp = 220)
@Composable
private fun DeviceBare() {
    Screen(fingerprint = "", receivedDir = "")
}
