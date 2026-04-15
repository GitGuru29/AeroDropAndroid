package com.aerodrop.discovery

// AeroDiscoveryService.kt — AeroDrop Android  [Phase 1: Discovery]
// Browses the local network for _aerodrop._tcp services using NsdManager.
// Publishes discovered peers as a StateFlow for ViewModel consumption.
//
// Android NsdManager quirk: each resolve call requires a *fresh* ResolveListener
// instance — reusing one throws IllegalArgumentException.

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AeroDiscoveryService(context: Context) {

    companion object {
        private const val TAG          = "AeroDiscovery"
        private const val SERVICE_TYPE = "_aerodrop._tcp"
    }

    private val nsdManager       = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredPeers  = mutableMapOf<String, AeroPeer>()
    private var isDiscovering    = false

    private val _peers = MutableStateFlow<List<AeroPeer>>(emptyList())
    val peers: StateFlow<List<AeroPeer>> = _peers.asStateFlow()

    // ── Discovery listener ────────────────────────────────────────────────────

    private val discoveryListener = object : NsdManager.DiscoveryListener {

        override fun onStartDiscoveryFailed(type: String, err: Int) {
            Log.e(TAG, "Discovery start failed: $err")
            isDiscovering = false
        }

        override fun onStopDiscoveryFailed(type: String, err: Int) {
            Log.e(TAG, "Discovery stop failed: $err")
        }

        override fun onDiscoveryStarted(type: String) {
            Log.d(TAG, "Discovery started: $type")
            isDiscovering = true
        }

        override fun onDiscoveryStopped(type: String) {
            Log.d(TAG, "Discovery stopped")
            isDiscovering = false
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            Log.d(TAG, "Found service: ${info.serviceName}")
            if (info.serviceType.contains(SERVICE_TYPE.trimEnd('.'))) {
                resolveService(info)
            }
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            Log.d(TAG, "Lost service: ${info.serviceName}")
            discoveredPeers.remove(info.serviceName)
            _peers.value = discoveredPeers.values.toList()
        }
    }

    // ── Resolve ───────────────────────────────────────────────────────────────
    // Each call gets a fresh listener — NsdManager requirement.

    private fun resolveService(info: NsdServiceInfo) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {

            override fun onResolveFailed(svc: NsdServiceInfo, err: Int) {
                Log.e(TAG, "Resolve failed for ${svc.serviceName}: $err")
            }

            override fun onServiceResolved(svc: NsdServiceInfo) {
                val host = svc.host?.hostAddress ?: return
                Log.d(TAG, "Resolved: ${svc.serviceName} @ $host:${svc.port}")
                val peer = AeroPeer(svc.serviceName, host, svc.port)
                discoveredPeers[svc.serviceName] = peer
                _peers.value = discoveredPeers.values.toList()
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (isDiscovering) return
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try { nsdManager.stopServiceDiscovery(discoveryListener) }
        catch (e: Exception) { Log.e(TAG, "Stop discovery error: ${e.message}") }
    }
}
