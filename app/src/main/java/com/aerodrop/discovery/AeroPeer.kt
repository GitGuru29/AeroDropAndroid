package com.aerodrop.discovery

// AeroPeer.kt — AeroDrop Android  [Phase 1: Discovery]
// Immutable value type representing a discovered Mac running AeroDrop.
// Populated by AeroDiscoveryService from NsdManager resolve results.

data class AeroPeer(
    val name: String,   // mDNS service instance name  e.g. "MacBook Pro – siluna"
    val host: String,   // resolved IPv4/IPv6 address   e.g. "192.168.1.10"
    val port: Int       // advertised port              e.g. 7770
) {
    val displayAddress: String get() = "$host:$port"
}
