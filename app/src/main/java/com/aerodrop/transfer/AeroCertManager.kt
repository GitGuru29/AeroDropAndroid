package com.aerodrop.transfer

// AeroCertManager.kt — AeroDrop Android  [Phase 2: Transport]
// Manages a persistent ECDSA P-256 self-signed certificate in the Android
// Keystore for use as a TLS server certificate in AeroReceiverService.
//
// Why this matters:
//   SSLContext.init(null, trustManagers, null) gives a context with NO
//   private key. An SSLServerSocket built from such a context cannot complete
//   a TLS handshake because it has nothing to present as its server certificate.
//   The client (macOS AeroServer) immediately drops the connection.
//
// Fix:
//   Use KeyPairGenerator with the "AndroidKeyStore" provider to generate
//   a hardware-backed (or software-backed) ECDSA P-256 key. Android
//   automatically generates a matching self-signed X.509 v3 certificate.
//   KeyManagerFactory wraps the key so SSLContext can present it during
//   the TLS handshake.

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import java.security.cert.X509Certificate

object AeroCertManager {

    private const val KEY_ALIAS        = "aerodrop_tls_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    // ── Key provisioning ──────────────────────────────────────────────────────

    /**
     * Idempotent — generates the key pair + self-signed cert on the first
     * call; subsequent calls return immediately because the alias exists.
     */
    fun ensureCertExists() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        val now = System.currentTimeMillis()
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=aerodrop.local, O=AeroDrop"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(now - 1_000))
            .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 3600 * 1000))
            .build()
        )
        kpg.generateKeyPair()
    }

    // ── SSLContext factories ───────────────────────────────────────────────────

    /**
     * TLS 1.3 server context with our AndroidKeyStore certificate.
     * Accepts any client (macOS self-signed cert) — fingerprint auth in UI.
     */
    fun serverSslContext(): SSLContext {
        ensureCertExists()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        // "X509" is the standard alias; works on all Android versions.
        val kmf = KeyManagerFactory.getInstance("X509")
        kmf.init(ks, null) // AndroidKeyStore entries need no password

        return SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, arrayOf(trustAll()), null)
        }
    }

    /**
     * TLS 1.3 client context — no client cert, trust-all server certs.
     * Used by AeroTransferClient when connecting to the macOS server.
     */
    fun clientSslContext(): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf(trustAll()), null)
        }

    // ── Trust-all manager ─────────────────────────────────────────────────────

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
