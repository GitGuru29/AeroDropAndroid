package com.aerodrop.system

// MediaStoreHelper.kt — AeroDrop Android  [Phase 3: System Integration]
// Writes received files into MediaStore Downloads/AeroDrop (API 29+).
//
// The IS_PENDING locking pattern is used: the row is invisible to Files and the
// gallery while bytes are still arriving, then made visible atomically on close.
// A half-written file that another app can see is worse than no file at all.

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

/** A row that is being written but is not yet visible to other apps. */
class PendingFile(val uri: Uri, private val raw: OutputStream) : OutputStream() {

    private var closed = false

    override fun write(b: Int) = raw.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = raw.write(b, off, len)

    override fun write(b: ByteArray) = raw.write(b)

    override fun flush() = raw.flush()

    override fun close() {
        if (closed) return
        closed = true
        // Publish even if the payload was short: the caller decides whether a
        // partial file is a failure, and hiding it forever would be worse.
        runCatching { raw.close() }
        onClose?.invoke()
    }

    /** Set by the caller to clear IS_PENDING once the bytes are on disk. */
    var onClose: (() -> Unit)? = null
}

class MediaStoreHelper(private val context: Context) {

    companion object {
        private const val TAG       = "MediaStore"
        val RELATIVE_DIR: String = "${Environment.DIRECTORY_DOWNLOADS}/AeroDrop"

        /** @see AeroFileName.sanitize */
        fun sanitize(raw: String): String = AeroFileName.sanitize(raw)

        /** @see AeroFileName.mimeTypeOf */
        fun mimeTypeOf(name: String): String = AeroFileName.mimeTypeOf(name)
    }

    /**
     * Opens a write stream to Downloads/AeroDrop/[sanitize(filename)].
     *
     * The caller MUST close the returned [PendingFile] — the IS_PENDING flag is
     * cleared in its close().
     */
    fun create(filename: String): PendingFile? {
        val safeName = sanitize(filename)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(safeName))
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_DIR)
        }

        val uri = try {
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "insert() failed for '$safeName': ${e.message}", e)
            null
        } ?: return null

        val raw = try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "openOutputStream() failed for '$safeName': ${e.message}", e)
            // Do not leave a pending row behind if we cannot write to it.
            runCatching { context.contentResolver.delete(uri, null, null) }
            return null
        } ?: return null

        return PendingFile(uri, raw).apply {
            onClose = {
                runCatching {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null, null
                    )
                }.onFailure { Log.e(TAG, "could not publish '$safeName': ${it.message}") }
            }
        }
    }
}
