package com.aerodrop

// AeroViewModel.kt — AeroDrop Android  [Phase 4: UI]
// Owns discovery, the transfer queue and the remembered peer selection.
//
// Send and receive deliberately share one presentation model. Direction is
// carried by an arrow and a verb, never by colour, so the two can never drift
// apart — the same rule TransferStyle.swift enforces on macOS. Everything the
// UI needs is a StateFlow, so there is no lateinit initialisation race on the
// first composition.

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aerodrop.discovery.AeroDiscovery
import com.aerodrop.discovery.AeroPeer
import com.aerodrop.transfer.AeroCertManager
import com.aerodrop.transfer.AeroInbound
import com.aerodrop.transfer.AeroReceiverService
import com.aerodrop.transfer.AeroTransferClient
import com.aerodrop.transfer.InboundProgress
import com.aerodrop.transfer.InboundResult
import com.aerodrop.transfer.TransferEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AeroViewModel"
private const val PREFS = "aerodrop"
private const val KEY_LAST_PEER = "AeroDropDefaultPeerID"

enum class Direction { Outgoing, Incoming }

/** One row in the transfer queue. */
sealed class TransferUi {
    data class Running(
        val direction: Direction,
        val filename:  String,
        val fraction:  Float,
        val speedMBs:  Double,
        /** Total bytes on the wire, so the row can show how much is left. */
        val sizeBytes: Long = 0L,
    ) : TransferUi()

    data class Finished(
        val direction: Direction,
        val filename:  String,
        val success:   Boolean,
        val detail:    String = "",
    ) : TransferUi()
}

class AeroViewModel(app: Application) : AndroidViewModel(app) {

    private val discovery = AeroDiscovery(app)
    private val prefs     = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val peers:   StateFlow<List<AeroPeer>> = discovery.peers
    val browsing: StateFlow<Boolean>      = discovery.browsing
    val advertised: StateFlow<String?>    = discovery.advertised

    private val _selectedPeer = MutableStateFlow<AeroPeer?>(null)
    val selectedPeer: StateFlow<AeroPeer?> = _selectedPeer.asStateFlow()

    private val _outgoing = MutableStateFlow<TransferUi?>(null)
    val outgoing: StateFlow<TransferUi?> = _outgoing.asStateFlow()

    private val _incoming = MutableStateFlow<TransferUi?>(null)
    val incoming: StateFlow<TransferUi?> = _incoming.asStateFlow()

    private val _history = MutableStateFlow<List<TransferUi.Finished>>(emptyList())
    val history: StateFlow<List<TransferUi.Finished>> = _history.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    val listening: StateFlow<Boolean> = AeroInbound.listening
    val receivedDir: StateFlow<String> = AeroInbound.receivedDir

    val fingerprint: String by lazy { AeroCertManager.fingerprint() }

    /** Peers offered for selection: remembered device first, then the rest. */
    private val _ordered = MutableStateFlow<List<AeroPeer>>(emptyList())
    val orderedPeers: StateFlow<List<AeroPeer>> = _ordered.asStateFlow()

    init {
        discovery.start()
        startReceiver()
        AeroCertManager.ensureCertExists()

        // Re-select the remembered device, and keep the ordering stable so the
        // row the user picked does not move when another peer appears.
        viewModelScope.launch {
            discovery.peers.collect { all ->
                val remembered = prefs.getString(KEY_LAST_PEER, null)
                _ordered.value = all.sortedWith(
                    compareByDescending<AeroPeer> { it.name == remembered }.thenBy { it.name }
                )
                val current = _selectedPeer.value
                if (current == null) {
                    _selectedPeer.value = all.firstOrNull { it.name == remembered } ?: all.firstOrNull()
                } else {
                    // Keep the identity but refresh a changed address.
                    _selectedPeer.value = all.firstOrNull { it.name == current.name } ?: current
                }
            }
        }

        // Mirror inbound activity from the service into the shared model.
        viewModelScope.launch {
            AeroInbound.active.collect { p ->
                _incoming.value = p?.let {
                    TransferUi.Running(
                        Direction.Incoming, it.filename, it.fraction, it.speedMBs, it.total
                    )
                }
            }
        }
        viewModelScope.launch {
            AeroInbound.lastResult.collect { r -> if (r != null) push(r) }
        }
    }

    // ── Peer selection ─────────────────────────────────────────────────────────

    fun selectPeer(peer: AeroPeer) {
        _selectedPeer.value = peer
        prefs.edit().putString(KEY_LAST_PEER, peer.name).apply()
    }

    // ── Receiver ───────────────────────────────────────────────────────────────

    /**
     * Bring the listener up as soon as the app exists. The Mac dials *us* when
     * it sends, so a listener that only runs after a manual toggle makes the
     * whole receive path unreliable.
     */
    fun startReceiver() {
        runCatching { AeroReceiverService.start(getApplication()) }
            .onFailure { Log.e(TAG, "Could not start receiver: ${it.message}") }
    }

    fun stopReceiver() {
        runCatching { AeroReceiverService.stop(getApplication()) }
    }

    // ── Sending ────────────────────────────────────────────────────────────────

    /**
     * Queue [uris] to the selected peer. Multiple files are sent one after
     * another over a single connection each, which is what the Mac's queue
     * expects — there is no multi-file envelope in the protocol.
     */
    fun send(context: Context, uris: List<Uri>) {
        val peer = _selectedPeer.value
        if (peer == null) {
            Log.w(TAG, "send() with no peer selected")
            return
        }
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _sending.value = true
            try {
                for (uri in uris) {
                    AeroTransferClient
                        .sendFile(getApplication(), uri, peer.host, peer.port)
                        .collect { event ->
                            when (event) {
                                is TransferEvent.Progress -> _outgoing.value = TransferUi.Running(
                                    Direction.Outgoing,
                                    event.filename,
                                    (event.bytes.toFloat() / event.total).coerceIn(0f, 1f),
                                    event.speedMbps,
                                    event.total,
                                )
                                is TransferEvent.Success -> {
                                    _outgoing.value = null
                                    push(TransferUi.Finished(Direction.Outgoing, event.filename, true))
                                }
                                is TransferEvent.Failure -> {
                                    _outgoing.value = null
                                    push(TransferUi.Finished(
                                        Direction.Outgoing, "", false, event.reason))
                                }
                            }
                        }
                }
            } finally {
                _sending.value = false
            }
        }
    }

    // ── History ────────────────────────────────────────────────────────────────

    private fun push(item: TransferUi.Finished) {
        _history.value = (listOf(item) + _history.value).take(20)
    }

    private fun push(result: InboundResult) = when (result) {
        is InboundResult.Saved -> push(
            TransferUi.Finished(Direction.Incoming, result.filename, true, result.location)
        )
        is InboundResult.Failed -> push(
            TransferUi.Finished(Direction.Incoming, result.filename, false, result.reason)
        )
    }

    fun clearHistory() { _history.value = emptyList() }

    override fun onCleared() {
        super.onCleared()
        discovery.stop()
    }
}
