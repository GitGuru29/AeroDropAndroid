package com.aerodrop.transfer

// AeroTransferClient.kt — AeroDrop Android  [Phase 2: Transport]
// Connects to the macOS AeroServer via TLS 1.3 and sends a file.
// Returns a cold Flow<TransferEvent> so the caller collects progress reactively.
//
// Trust model: Accept-all TrustManager — MAC cert is self-signed.
// Fingerprint comparison happens in the UI pairing flow (Phase 4).

import android.content.Context
import android.net.Uri
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
    private const val BUFFER_SIZE = 128 * 1024 // 128 KiB

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

        Log.d(TAG, "Sending '$filename' ($fileSize bytes) → $host:$port")

        try {
            val socket = trustAllSslContext().socketFactory
                .createSocket(host, port) as SSLSocket
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.startHandshake()

            val out = socket.outputStream

            // Send binary AeroHeader (64 bytes)
            out.write(AeroHeader(fileSize = fileSize, filename = filename).toBytes())

            // Stream file payload
            val input = cr.openInputStream(uri)
                ?: run { emit(TransferEvent.Failure("Cannot open file")); socket.close(); return@flow }

            val buf      = ByteArray(BUFFER_SIZE)
            var sent     = 0L
            val t0       = System.currentTimeMillis()

            input.use {
                while (true) {
                    val rd = it.read(buf)
                    if (rd < 0) break
                    out.write(buf, 0, rd)
                    sent += rd
                    val sec   = (System.currentTimeMillis() - t0) / 1000.0
                    val speed = if (sec > 0) (sent / 1_000_000.0) / sec else 0.0
                    emit(TransferEvent.Progress(filename, sent, fileSize, speed))
                }
            }

            out.flush()
            socket.close()

            if (sent == fileSize) emit(TransferEvent.Success(filename))
            else emit(TransferEvent.Failure("Incomplete: $sent / $fileSize bytes"))

        } catch (e: Exception) {
            Log.e(TAG, "Transfer error", e)
            emit(TransferEvent.Failure(e.message ?: "Unknown error"))
        }

    }.flowOn(Dispatchers.IO)
}
