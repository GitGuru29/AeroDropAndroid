package com.aerodrop.transfer

// AeroTransferClient.kt — AeroDrop Android  [Phase 2: Transport]
// Connects to the macOS AeroServer over TLS 1.3 and sends a file.
// Returns a cold Flow<TransferEvent> so the caller collects progress reactively.
//
// The one invariant that must never break: the fileSize written into the 64-byte
// header has to be exactly the number of payload bytes that follow. macOS reads
// in a `while (received < file_size)` loop (AeroServer.cpp:226), so an
// over-declared size hangs the Mac forever and an under-declared one leaves a
// truncated file behind with the Mac still waiting. Getting the size right is
// therefore not an optimisation, it is the difference between working and
// hanging — which is why an unknown size is spooled to a cache file rather than
// guessed from ContentResolver metadata that may be absent or wrong.
//
// Trust model: accept-all TrustManager, because the Mac's certificate is
// self-signed. See AeroCertManager for the full rationale.

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket

sealed class TransferEvent {
    data class Progress(
        val filename:  String,
        val bytes:     Long,
        val total:     Long,
        val speedMbps: Double,
    ) : TransferEvent()

    data class Success(val filename: String) : TransferEvent()
    data class Failure(val reason: String)   : TransferEvent()
}

object AeroTransferClient {

    private const val TAG             = "AeroTransfer"
    private const val BUFFER_SIZE     = AeroProtocol.CHUNK
    private const val SEND_BUF_SIZE   = 4 * 1024 * 1024
    private const val RCV_BUF_SIZE    = 256 * 1024
    private const val CONNECT_TIMEOUT = 10_000
    private const val PROGRESS_MS     = 100L

    private data class Source(val name: String, val size: Long, val open: () -> InputStream)

    /**
     * Send the file at [uri] to the Mac at [host]:[port].
     *
     * [nameOverride] lets a multi-file share label each transfer with its own
     * basename rather than the opaque name the provider reports.
     */
    fun sendFile(
        context: Context,
        uri:     Uri,
        host:    String,
        port:    Int,
        nameOverride: String? = null,
    ): Flow<TransferEvent> = flow {

        var spooled: File? = null
        try {
            val source = resolveSource(context, uri, nameOverride)
            Log.i(TAG, "Sending '${source.name}' (${source.size} bytes) → $host:$port")

            val header = AeroHeader(fileSize = source.size, filename = source.name)

            // A plain socket is created first so the OS send/receive buffers can
            // be raised before TLS wraps it; SSLSocket inherits them, and the
            // default 8 KB window otherwise throttles a LAN transfer badly.
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.sendBufferSize = SEND_BUF_SIZE
                raw.receiveBufferSize = RCV_BUF_SIZE
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)

                val ssl = AeroCertManager.clientSslContext().socketFactory
                    .createSocket(raw, host, port, /* autoClose = */ true) as SSLSocket
                ssl.useClientMode = true
                ssl.enabledProtocols = AeroCertManager.TLS_PROTOCOLS
                ssl.startHandshake()

                val negotiated = ssl.session?.protocol
                if (negotiated != "TLSv1.3") {
                    // The Mac refuses anything older, so this is a hard stop
                    // rather than a downgrade to something that would fail later.
                    throw IllegalStateException("Expected TLS 1.3, negotiated $negotiated")
                }

                val started = System.currentTimeMillis()
                var lastEmit = 0L
                val sent = source.open().use { input ->
                    AeroProtocol.send(input, ssl.outputStream, header) { bytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > PROGRESS_MS || bytes == source.size) {
                            lastEmit = now
                            val secs = (now - started) / 1000.0
                            val speed = if (secs > 0) (bytes / 1_000_000.0) / secs else 0.0
                            emit(TransferEvent.Progress(header.filename, bytes, source.size, speed))
                        }
                    }
                }

                if (sent == source.size) {
                    Log.i(TAG, "Sent ${header.filename} ($sent bytes)")
                    emit(TransferEvent.Success(header.filename))
                } else {
                    val reason = "Stream ended after $sent of ${source.size} bytes"
                    Log.w(TAG, reason)
                    emit(TransferEvent.Failure(reason))
                }
            } finally {
                runCatching { raw.close() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed", e)
            emit(TransferEvent.Failure(e.message ?: e.javaClass.simpleName))
        } finally {
            spooled?.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Work out the name and — critically — the exact byte count.
     *
     * ContentResolver metadata is tried first because it is free, but SIZE is
     * nullable and providers lie (Google Drive reports nothing, some return the
     * compressed size). When no trustworthy size is available the stream is
     * copied to a cache file so the header can state a number that is certainly
     * right; the alternative is hanging the Mac.
     */
    private fun resolveSource(context: Context, uri: Uri, nameOverride: String?): Source {
        var name = nameOverride
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "aerodrop_file"
        var size = -1L

        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (nameOverride == null) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) c.getString(ni)?.let { name = it }
                    }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }.onFailure { Log.w(TAG, "metadata query failed: ${it.message}") }

        if (size <= 0) {
            size = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1L
        }

        name = name.substringAfterLast('/').ifBlank { "aerodrop_file" }

        if (size > 0) {
            return Source(name, size) { openStream(context, uri) }
        }

        // Unknown length: spool to a private cache file and send that.
        val tmp = File.createTempFile("aerodrop_", ".bin", context.cacheDir)
        var total = 0L
        openStream(context, uri).use { input ->
            tmp.outputStream().use { sink ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    total += n
                }
            }
        }
        Log.i(TAG, "Spooled '$name' to cache to learn its size ($total bytes)")
        return Source(name, total) { tmp.inputStream() }
    }

    private fun openStream(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open $uri")
}
