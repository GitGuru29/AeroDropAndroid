package com.aerodrop.transfer

// AeroTransferClient.kt — AeroDrop Android  [Phase 2: Transport]
// Connects to the macOS AeroServer via TLS 1.3 and sends a file.
// Returns a cold Flow<TransferEvent> so the caller collects progress reactively.
//
// Large-file fixes applied:
//   1. WifiLock (HIGH_PERF) — prevents WiFi from sleeping during transfer
//   2. Socket buffer tuning — 4 MB SO_SNDBUF to batch TLS records
//   3. TCP_NODELAY — disables Nagle, reduces latency for the last partial chunk
//   4. soTimeout = 0 — infinite I/O timeout (default is already 0, made explicit)
//   5. Progress throttled to every 1 MB so emit() never slows the write loop

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ── Transfer event types ──────────────────────────────────────────────────────

sealed class TransferEvent {
    data class Progress(
        val filename:  String,
        val bytes:     Long,
        val total:     Long,
        val speedMbps: Double
    ) : TransferEvent()

    data class Success(val filename: String) : TransferEvent()
    data class Failure(val reason: String)  : TransferEvent()
}

// ── Client ────────────────────────────────────────────────────────────────────

object AeroTransferClient {

    private const val TAG         = "AeroTransfer"
    private const val BUFFER_SIZE = 256 * 1024          // 256 KiB per read
    private const val SOCK_BUF    = 4  * 1024 * 1024   // 4 MB TCP send buffer
    private const val PROG_EVERY  = 1  * 1024 * 1024   // emit progress every 1 MB

    // Trust-all TLS context — fingerprint shown in UI for manual verification
    private fun trustAllSslContext(): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }

    /**
     * Send the file at [uri] to the Mac at [host]:[port].
     * Collect the returned Flow on Dispatchers.Main to update UI.
     *
     * A HIGH_PERF WifiLock is held for the duration to prevent the radio
     * from throttling or suspending during large transfers.
     */
    fun sendFile(
        context: Context,
        uri:     Uri,
        host:    String,
        port:    Int
    ): Flow<TransferEvent> = flow {

        val cr       = context.contentResolver
        val filename = uri.lastPathSegment?.substringAfterLast('/') ?: "aerodrop_file"
        val fileSize = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: run {
            emit(TransferEvent.Failure("Cannot read file size")); return@flow
        }

        Log.d(TAG, "Sending '$filename' (${fileSize / 1_048_576} MB) → $host:$port")

        // ── WifiLock: keeps the radio at full power for the whole transfer ──
        val wifiMgr  = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiLock = wifiMgr.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AeroDrop::SendLock"
        ).also { it.acquire() }

        try {
            val socket = trustAllSslContext().socketFactory
                .createSocket(host, port) as SSLSocket

            // ── Socket tuning ────────────────────────────────────────────
            socket.sendBufferSize    = SOCK_BUF   // 4 MB TCP buffer
            socket.tcpNoDelay        = true        // flush immediately on partial chunk
            socket.soTimeout         = 0           // no I/O timeout (explicit)
            socket.enabledProtocols  = arrayOf("TLSv1.3")
            socket.startHandshake()

            val out = socket.outputStream

            // Send 64-byte AeroHeader
            out.write(AeroHeader(fileSize = fileSize, filename = filename).toBytes())

            // Stream payload
            val input = cr.openInputStream(uri) ?: run {
                emit(TransferEvent.Failure("Cannot open file stream"))
                socket.close()
                return@flow
            }

            val buf       = ByteArray(BUFFER_SIZE)
            var sent      = 0L
            var lastProg  = 0L          // last byte count we emitted progress for
            val t0        = System.currentTimeMillis()

            input.use {
                while (true) {
                    val rd = it.read(buf)
                    if (rd < 0) break
                    out.write(buf, 0, rd)
                    sent += rd

                    // Throttle progress: emit only every PROG_EVERY bytes
                    if (sent - lastProg >= PROG_EVERY || sent == fileSize) {
                        val sec   = (System.currentTimeMillis() - t0) / 1000.0
                        val speed = if (sec > 0) (sent / 1_000_000.0) / sec else 0.0
                        emit(TransferEvent.Progress(filename, sent, fileSize, speed))
                        lastProg = sent
                    }
                }
            }

            out.flush()
            socket.close()

            if (sent == fileSize) {
                emit(TransferEvent.Success(filename))
            } else {
                emit(TransferEvent.Failure("Incomplete: $sent / $fileSize bytes sent"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transfer error", e)
            emit(TransferEvent.Failure(e.message ?: "Unknown error"))
        } finally {
            if (wifiLock.isHeld) wifiLock.release()
        }

    }.flowOn(Dispatchers.IO)
}
