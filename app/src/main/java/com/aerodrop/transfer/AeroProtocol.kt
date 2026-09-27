package com.aerodrop.transfer

// AeroProtocol.kt — AeroDrop Android  [Phase 2: Transport]
// The wire loop, with no Android dependencies, so the exact code that talks to
// macOS can be exercised by JVM unit tests against the real AeroServer.cpp.
//
// Both directions are here on purpose: the framing is identical and the only
// difference is which side opens the socket. Keeping one implementation means a
// fix to the framing cannot land on one path and be forgotten on the other.

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object AeroProtocol {

    /** 512 KiB, matching the chunk size AeroServer.cpp uses in both directions. */
    const val CHUNK = 512 * 1024

    /**
     * macOS reads with `while (received < file_size)` and treats a short stream
     * as failure, so the declared size has to be exact. A zero-length file is
     * legal and must produce a header and nothing else.
     */
    suspend fun send(
        source: InputStream,
        sink:   OutputStream,
        header: AeroHeader,
        onProgress: suspend (sent: Long) -> Unit = {},
    ): Long {
        sink.write(header.toBytes())
        sink.flush()

        val buf = ByteArray(CHUNK)
        var sent = 0L
        while (sent < header.fileSize) {
            val toRead = minOf(buf.size.toLong(), header.fileSize - sent).toInt()
            val n = source.read(buf, 0, toRead)
            if (n < 0) break                       // stream ended early
            sink.write(buf, 0, n)
            sent += n
            onProgress(sent)
        }
        sink.flush()
        return sent
    }

    /**
     * Consume exactly [AeroHeader.fileSize] bytes into [sink].
     * Returns the number of bytes actually received, which the caller compares
     * against the declared size to decide success.
     *
     * A trailing IOException is deliberately swallowed once the whole declared
     * payload has arrived. AeroServer.cpp:418-420 finishes writing, then closes
     * the socket without waiting for a close_notify, which makes the peer's TCP
     * stack send an RST; a reader that is still draining its receive buffer then
     * sees "Connection reset" *after* a transfer that actually succeeded.
     * Treating that as a failure would report a perfectly good file as corrupt.
     */
    suspend fun receive(
        source: InputStream,
        header: AeroHeader,
        sink:   OutputStream,
        onProgress: suspend (received: Long) -> Unit = {},
    ): Long {
        val buf = ByteArray(CHUNK)
        var received = 0L
        while (received < header.fileSize) {
            val toRead = minOf(buf.size.toLong(), header.fileSize - received).toInt()
            val n = try {
                source.read(buf, 0, toRead)
            } catch (e: IOException) {
                if (received >= header.fileSize) break
                throw e
            }
            if (n < 0) break
            sink.write(buf, 0, n)
            received += n
            onProgress(received)
        }
        runCatching { sink.flush() }
        return received
    }

    /**
     * Read the 64-byte header, or null if the peer closed first.
     *
     * Loops because a stream read is free to return short even mid-message;
     * a single read() would truncate the header and fail validation for no
     * protocol reason.
     */
    fun readHeader(source: InputStream): AeroHeader? {
        val buf = readExactly(source, AeroHeader.SIZE) ?: return null
        return AeroHeader.fromBytes(buf)
    }

    fun readExactly(source: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = source.read(buf, read, count - read)
            if (n < 0) return null
            read += n
        }
        return buf
    }
}
