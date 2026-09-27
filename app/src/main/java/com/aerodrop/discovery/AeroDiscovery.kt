package com.aerodrop.discovery

// AeroDiscovery.kt — AeroDrop Android  [Phase 1: Discovery]
// Both halves of mDNS for AeroDrop: advertise this device as _aerodrop._tcp so
// the Mac can find us, and browse for the Mac's service so we can find it.
//
// Advertise and discover live in one object on purpose. A device that registers
// _aerodrop._tcp will also *see* that registration in its own browse results, so
// the two halves have to agree on which instance name is "us". Split across two
// classes that knowledge gets duplicated and the phone ends up listed as its own
// peer — at which point sending a file to "yourself" silently loops back into
// the local receiver. The Mac has the same problem and solves it the same way,
// by comparing against its own instance name (AeroDiscoveryBrowser.swift ①).
//
// Address preference: the Mac deliberately resolves IPv4 first — it skips
// AF_INET6 results because "Android binds to 0.0.0.0". We mirror that by
// preferring IPv4 here too, and fall back to IPv6 so a IPv6-only LAN still works.

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class AeroDiscovery(private val context: Context) {

    companion object {
        private const val TAG          = "AeroDiscovery"
        const val SERVICE_TYPE          = "_aerodrop._tcp"
        private const val SERVICE_DOMAIN = "local."
        private const val MAX_RESOLVES  = 4

        /** The port AeroReceiverService listens on. */
        const val PORT = 7770

        fun instanceName(): String {
            val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
            val trimmed = model.take(28)
            return "$trimmed – AeroDrop"
        }
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val discovered = ConcurrentHashMap<String, AeroPeer>()

    private val _peers = MutableStateFlow<List<AeroPeer>>(emptyList())
    val peers: StateFlow<List<AeroPeer>> = _peers.asStateFlow()

    private val _advertised = MutableStateFlow<String?>(null)
    val advertised: StateFlow<String?> = _advertised.asStateFlow()

    private val _browsing = MutableStateFlow(false)
    val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    private var discovering  = false
    private var registering  = false
    private var inFlight     = 0

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        startAdvertising()
        startBrowsing()
    }

    fun stop() {
        stopBrowsing()
        stopAdvertising()
    }

    // ── Advertise ─────────────────────────────────────────────────────────────

    private fun startAdvertising() {
        if (registering) return
        registering = true

        val info = NsdServiceInfo().apply {
            serviceName = instanceName()
            serviceType = SERVICE_TYPE
            port = PORT
        }

        // NsdManager renames on collision (appending " (2)"), so the effective
        // name is only known once the callback fires — hence _advertised.
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            registering = false
            Log.e(TAG, "registerService threw: ${it.message}")
        }
    }

    private fun stopAdvertising() {
        if (!registering) return
        registering = false
        runCatching { nsdManager.unregisterService(registrationListener) }
            .onFailure { Log.e(TAG, "unregisterService failed: ${it.message}") }
        _advertised.value = null
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registering = true
            _advertised.value = info.serviceName
            Log.i(TAG, "Advertising '${info.serviceName}' on port ${info.port}")
            // The browse may already have surfaced us under the requested name.
            dropSelf(info.serviceName)
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {
            registering = false
            Log.e(TAG, "Registration failed: $err")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            registering = false
            _advertised.value = null
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {
            Log.e(TAG, "Unregistration failed: $err")
        }
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    private fun startBrowsing() {
        if (discovering) return
        discovering = true
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            discovering = false
            Log.e(TAG, "discoverServices threw: ${it.message}")
        }
    }

    private fun stopBrowsing() {
        if (!discovering) return
        discovering = false
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            .onFailure { Log.e(TAG, "stopServiceDiscovery failed: ${it.message}") }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {

        override fun onDiscoveryStarted(serviceType: String) {
            discovering = true
            _browsing.value = true
            Log.i(TAG, "Browsing for $serviceType")
        }

        override fun onServiceFound(info: NsdServiceInfo) {
            if (!info.serviceType.contains(SERVICE_TYPE.trimEnd('.'))) return
            if (isSelf(info.serviceName)) {
                Log.d(TAG, "Skipping self: ${info.serviceName}")
                return
            }
            // NsdManager allows only a handful of concurrent resolves; queue
            // the rest by simply not starting more than MAX_RESOLVES.
            if (inFlight >= MAX_RESOLVES) return
            resolve(info)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            if (discovered.remove(info.serviceName) != null) publish()
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discovering = false
            _browsing.value = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discovering = false
            _browsing.value = false
            Log.e(TAG, "Discovery start failed: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery stop failed: $errorCode")
        }
    }

    // ── Resolve ───────────────────────────────────────────────────────────────

    /**
     * NsdManager requires a *fresh* ResolveListener per call — reusing one
     * throws IllegalArgumentException. Deprecated in API 34 but still the only
     * call available across the whole minSdk range this app supports.
     */
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        inFlight++
        runCatching {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {

                override fun onServiceResolved(svc: NsdServiceInfo) {
                    inFlight--
                    val name = svc.serviceName
                    if (isSelf(name)) return

                    val host = bestAddress(svc) ?: run {
                        Log.w(TAG, "No usable address for $name")
                        return
                    }
                    val peer = AeroPeer(name, host, svc.port)
                    if (discovered[name] == peer) return
                    discovered[name] = peer
                    Log.i(TAG, "Resolved $name @ $host:${svc.port}")
                    publish()
                }

                override fun onResolveFailed(svc: NsdServiceInfo, errorCode: Int) {
                    inFlight--
                    Log.e(TAG, "Resolve failed for ${svc.serviceName}: $errorCode")
                }
            })
        }.onFailure {
            inFlight--
            Log.e(TAG, "resolveService threw: ${it.message}")
        }
    }

    /**
     * Prefer IPv4, matching the Mac's own resolution order, then take the first
     * usable address otherwise.
     *
     * getHostAddresses() only exists from API 34, and below that the platform
     * exposes a single host via getHost(). Both details have to be respected:
     * calling the missing method throws NoSuchMethodError, so discovery would
     * silently resolve nothing on Android 10-13.
     */
    private fun bestAddress(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addrs = info.hostAddresses
            // IPv4 first, matching the Mac's resolution order
            // (AeroDiscoveryBrowser skips AF_INET6 results because Android binds
            // to 0.0.0.0).
            addrs.firstOrNull { it is Inet4Address }?.let { return it.hostAddress }
            return addrs.firstOrNull()?.hostAddress
        }
        return legacyHost(info)
    }

    /**
     * NsdServiceInfo.getHost() changed its return type from String to InetAddress
     * in API 34. A normal call is compiled against the new signature and would
     * throw ClassCastException on an older device, so the value is read
     * untyped and whatever comes back is used.
     */
    private fun legacyHost(info: NsdServiceInfo): String? = runCatching {
        when (val v = NsdServiceInfo::class.java.getMethod("getHost").invoke(info)) {
            null -> null
            is String -> v.takeIf { it.isNotBlank() }
            is InetAddress -> v.hostAddress
            else -> null
        }
    }.getOrNull()

    // ── Self-filter ───────────────────────────────────────────────────────────

    private fun isSelf(name: String): Boolean {
        val mine = _advertised.value ?: return name == instanceName()
        return name == mine || name.substringBefore(" (") == mine
    }

    private fun dropSelf(name: String) {
        if (discovered.remove(name) != null) publish()
    }

    private fun publish() {
        _peers.value = discovered.values.sortedBy { it.name }
    }
}
