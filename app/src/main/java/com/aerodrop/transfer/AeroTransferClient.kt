package com.aerodrop.transfer

// AeroTransferClient.kt — AeroDrop Android  [Phase 2: Transport]
// Connects to the macOS AeroServer via TLS 1.3 and sends a file.
// Returns a cold Flow<TransferEvent> so the caller collects progress reactively.
//
// Trust model: Accept-all TrustManager — MAC cert is self-signed.
// Fingerprint comparison happens in the UI pairing flow (Phase 4).
//
// Large-file fixes (v2):
//   1. Filename: query OpenableColumns.DISPLAY_NAME — works for all URI schemes
//      including content://, file://, and storage framework URIs.
//   2. File size: query OpenableColumns.SIZE first, fallback to statSize.
//      statSize returns -1 for many providers (Google Drive, Downloads).
//      Sending -1 in the header causes the Mac to loop forever trying to
//      read 18 exabytes → connection hangs.
//   3. Raw Socket tuning: create a plain Socket, set SO_SNDBUF=4MB and
//      TCP_NODELAY=true BEFORE wrapping in TLS. SSLSocket inherits the
//      OS-level buffer, eliminating the default 8 KB bottleneck.
//   4. Write loop bounded by fileSize — stops exactly at EOF even if the
//      stream continues beyond the declared size.
//   5. Connect timeout of 10 s prevents indefinite hangs on unreachable peers.

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetSocketAddress
import java.net.Socket
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
    data class Failure(val reason: String)   : TransferEvent()
}

// ── Client ────────────────────────────────────────────────────────────────────

object AeroTransferClient {

    private const val TAG              = "AeroTransfer"
    private const val BUFFER_SIZE      = 512 * 1024      // 512 KiB — large-file throughput
    private const val SEND_BUF_SIZE    = 4 * 1024 * 1024 // 4 MB OS-level send buffer
    private const val CONNECT_TIMEOUT  = 10_000           // 10 s connect timeout

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
     * Query filename AND size from ContentResolver.
     * Works for all URI schemes: content://, file://, storage-framework URIs.
     *
     * Bug fixed: uri.lastPathSegment gives "%2F…" encoded garbage for content://
     * URIs and statSize returns -1 for cloud-backed URIs (Drive, Downloads).
     */
    private data class FileInfo(val name: String, val size: Long)

    private fun queryFileInfo(context: Context, uri: Uri): FileInfo {
        var name = "aerodrop_file"
        var size = -1L

        // Primary: ContentResolver query (works for all providers)
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) cursor.getString(ni)?.let { name = it }
                if (si >= 0 && !cursor.isNull(si)) size = cursor.getLong(si)
            }
        }

        // Fallback: ParcelFileDescriptor.statSize (works for local files)
        if (size <= 0) {
            size = context.contentResolver
                .openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }

        return FileInfo(name, size)
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

        // ── Resolve filename + size ───────────────────────────────────────────
        val (filename, fileSize) = queryFileInfo(context, uri)

        if (fileSize <= 0) {
            emit(TransferEvent.Failure(
                "Cannot determine file size (tried ContentResolver + statSize). " +
                "Got: $fileSize bytes."
            ))
            return@flow
        }

        Log.d(TAG, "Sending '$filename' ($fileSize bytes) → $host:$port")

        try {
            // ── Raw socket — tune OS buffers BEFORE TLS wrapping ─────────────
            // SSLSocket inherits these from the underlying Socket, so setting
            // them on SSLSocket directly has no effect on some Android versions.
            val rawSocket = Socket()
            rawSocket.tcpNoDelay        = true              // Disable Nagle — no latency spikes
            rawSocket.sendBufferSize    = SEND_BUF_SIZE     // 4 MB — eliminates 8 KB bottleneck
            rawSocket.receiveBufferSize = 256 * 1024        // 256 KB RCV for ACK throughput
            rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)

            // ── Wrap in TLS 1.3 ──────────────────────────────────────────────
            val sslSocket = trustAllSslContext().socketFactory
                .createSocket(rawSocket, host, port, /* autoClose = */ true) as SSLSocket
            sslSocket.enabledProtocols = arrayOf("TLSv1.3")
            sslSocket.startHandshake()

            val out = sslSocket.outputStream

            // ── Send AeroHeader (64 bytes) ────────────────────────────────────
            val header = AeroHeader(fileSize = fileSize, filename = filename)
            out.write(header.toBytes())

            // ── Stream file payload ───────────────────────────────────────────
            val input = context.contentResolver.openInputStream(uri) ?: run {
                emit(TransferEvent.Failure("Cannot open InputStream for URI"))
                sslSocket.close()
                return@flow
            }

            val buf   = ByteArray(BUFFER_SIZE)
            var sent  = 0L
            val t0    = System.currentTimeMillis()

            input.use { stream ->
                while (sent < fileSize) {
                    // Never read more than what's declared in the header
                    val remaining = fileSize - sent
                    val toRead    = minOf(buf.size.toLong(), remaining).toInt()
                    val rd        = stream.read(buf, 0, toRead)
                    if (rd < 0) break           // EOF before fileSize — fail gracefully
                    out.write(buf, 0, rd)
                    sent += rd
                    val sec   = (System.currentTimeMillis() - t0) / 1000.0
                    val speed = if (sec > 0) (sent / 1_000_000.0) / sec else 0.0
                    emit(TransferEvent.Progress(filename, sent, fileSize, speed))
                }
            }

            out.flush()
            sslSocket.close()

            if (sent == fileSize) {
                emit(TransferEvent.Success(filename))
            } else {
                emit(TransferEvent.Failure("Incomplete: sent $sent of $fileSize bytes"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Transfer error", e)
            emit(TransferEvent.Failure(e.message ?: "Unknown error"))
        }

    }.flowOn(Dispatchers.IO)
}
