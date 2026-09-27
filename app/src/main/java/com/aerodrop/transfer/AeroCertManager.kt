package com.aerodrop.transfer

// AeroCertManager.kt — AeroDrop Android  [Phase 2: Transport]
// A persistent RSA-2048 self-signed certificate in the Android Keystore, used
// as the TLS server certificate when the Mac sends files to this device.
//
// Why a Keystore-backed key is required:
//   SSLContext.init(null, trustManagers, null) produces a context with no
//   private key. An SSLServerSocket built from it has nothing to present as its
//   certificate, so every TLS handshake from macOS fails and the Mac's sendFile
//   thread waits forever. The key must come from a KeyManager.
//
// Trust model, matching macOS CertManager.cpp exactly:
//   • The Mac runs SSL_VERIFY_NONE on both its server and client contexts.
//   • So does this file — there is no CA to validate against, because each
//     device mints its own self-signed certificate.
//   • The channel is therefore confidential but NOT authenticated. Peer
//     identity is confirmed out of band by comparing the SHA-256 fingerprint
//     shown in each app's UI.

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

object AeroCertManager {

    private const val KEY_ALIAS        = "aerodrop_tls_rsa_v3_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TAG              = "AeroCert"

    /**
     * macOS sets SSL_CTX_set_min_proto_version(TLS1_3_VERSION), so anything
     * below 1.3 is refused outright. We pin the same floor on both ends.
     */
    val TLS_PROTOCOLS = arrayOf("TLSv1.3")

    // ── Key provisioning ──────────────────────────────────────────────────────

    /**
     * Idempotent — the key pair and its self-signed certificate are created on
     * the first call. The alias is versioned, so changing the algorithm below
     * generates a fresh key rather than reusing an incompatible one.
     */
    @Synchronized
    fun ensureCertExists() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            // A key left behind by an earlier build can be unusable for signing
            // yet still present, in which case every handshake would fail. Prove
            // the key can sign, and replace it if it cannot.
            if (canSign(ks)) return
            Log.w(TAG, "stored key cannot sign; regenerating")
            runCatching { ks.deleteEntry(KEY_ALIAS) }
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE
        )
        val now = System.currentTimeMillis()
        // PURPOSE_SIGN only. An RSA key cannot be authorised for signing and
        // encryption at the same time: the Keystore accepts the spec, generates
        // the key, and then refuses the signature operation at handshake time
        // with "RSA routines:OPENSSL_internal:internal error". TLS 1.3 only ever
        // asks the server to sign, so nothing else is needed here.
        kpg.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            .setCertificateSubject(X500Principal("CN=aerodrop.local, O=AeroDrop"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(now - 1_000))
            .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 3600 * 1000))
            .build()
        )
        kpg.generateKeyPair()
    }

    /**
     * Whether the stored key can actually produce a signature, which is what the
     * TLS 1.3 handshake needs and what a mis-specified key silently cannot do.
     */
    private fun canSign(ks: KeyStore): Boolean = runCatching {
        val key = ks.getKey(KEY_ALIAS, null) as? PrivateKey ?: return false
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(key) }
        signer.update("aerodrop".toByteArray())
        signer.sign() != null
    }.getOrDefault(false)

    // ── SSLContext factories ───────────────────────────────────────────────────

    /**
     * Server context for AeroReceiverService.
     *
     * Built from "TLS" rather than "TLSv1.3": on Android the protocol name in
     * SSLContext.getInstance() is not a reliable way to pin a version, and
     * "TLSv1.3" has historically produced sockets with an unusable protocol
     * list. The floor is enforced explicitly by [newServerSocket] and
     * [pinTls13] instead.
     */
    fun serverSslContext(): SSLContext {
        ensureCertExists()
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val kmf = KeyManagerFactory.getInstance("X509").apply { init(ks, null) }

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustAll()), null)
        }
    }

    /** Client context for AeroTransferClient, when sending to the Mac. */
    fun clientSslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll()), null) }

    /**
     * Bind a server socket that only speaks TLS 1.3 and never demands a client
     * certificate — the Mac's CertManager sets SSL_VERIFY_NONE, so it presents
     * none.
     */
    fun newServerSocket(port: Int): SSLServerSocket {
        val socket = serverSslContext().serverSocketFactory
            .createServerSocket(port) as SSLServerSocket
        // SSLServerSocket is not an SSLSocket — they are siblings under Socket —
        // so the protocol floor has to be set through the server API.
        socket.enabledProtocols = TLS_PROTOCOLS
        // macOS sets SSL_VERIFY_NONE, so it presents no client certificate.
        socket.needClientAuth = false
        socket.wantClientAuth = false
        return socket
    }

    /** Apply the TLS 1.3 floor to an accepted connection. */
    fun pinTls13(socket: SSLSocket) {
        socket.enabledProtocols = TLS_PROTOCOLS
        socket.useClientMode = false
    }

    // ── Identity ───────────────────────────────────────────────────────────────

    /**
     * SHA-256 fingerprint of this device's certificate, formatted the same way
     * as CertManager::fingerprint() on macOS (uppercase colon-separated hex).
     * Shown in the UI so two devices can be compared out of band.
     */
    @Synchronized
    fun fingerprint(): String = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
        digest.joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        ""
    }

    /** PEM of the certificate chain, for sharing over an out-of-band channel. */
    @Synchronized
    fun certificatePem(): String = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS) ?: return ""
        val pem = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        pem.chunked(64).joinToString("\n") { "-----BEGIN CERTIFICATE-----\n$it\n-----END CERTIFICATE-----" }
    } catch (e: Exception) {
        ""
    }

    /** Not used directly, but keeps the CertificateFactory import honest for PEM parsing. */
    internal fun parsePem(pem: String): X509Certificate? = try {
        val body = pem.substringAfter("-----BEGIN CERTIFICATE-----")
            .substringBefore("-----END CERTIFICATE-----")
            .replace("\n", "")
        CertificateFactory.getInstance("X.509")
            .generateCertificate(java.io.ByteArrayInputStream(Base64.decode(body, Base64.DEFAULT)))
            as X509Certificate
    } catch (e: Exception) {
        null
    }

    // ── Trust-all manager ─────────────────────────────────────────────────────

    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
