package com.aerodrop.discovery

// AeroAdvertiser.kt — AeroDrop Android  [Phase 1: Discovery]
// Registers this Android device as an _aerodrop._tcp service via NsdManager
// so macOS AeroDiscoveryBrowser (NWBrowser) can discover it on the LAN.
//
// Symmetric with BonjourBridge.mm on the macOS side.
// Port matches AeroReceiverService.PORT (7770).

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class AeroAdvertiser(private val context: Context) {

    companion object {
        private const val TAG          = "AeroAdvertiser"
        private const val SERVICE_TYPE = "_aerodrop._tcp"
        const  val PORT                = 7770
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var isRegistered = false

    private val registrationListener = object : NsdManager.RegistrationListener {

        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.d(TAG, "Registered: ${info.serviceName} on port ${info.port}")
            isRegistered = true
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {
            Log.e(TAG, "Registration failed: $err")
            isRegistered = false
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.d(TAG, "Unregistered: ${info.serviceName}")
            isRegistered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {
            Log.e(TAG, "Unregistration failed: $err")
        }
    }

    /**
     * Advertise this device as an AeroDrop peer on the local network.
     * The service name follows the convention "DeviceName – AeroDrop" and
     * NsdManager will automatically deduplicate if the name is taken
     * (e.g. appending " (2)").
     */
    fun startAdvertising() {
        if (isRegistered) return

        val deviceName = android.os.Build.MODEL

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$deviceName – AeroDrop"
            serviceType = SERVICE_TYPE
            port        = PORT
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        Log.d(TAG, "Registering '$deviceName – AeroDrop' on port $PORT")
    }

    fun stopAdvertising() {
        if (!isRegistered) return
        try {
            nsdManager.unregisterService(registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering: ${e.message}")
        }
    }
}
