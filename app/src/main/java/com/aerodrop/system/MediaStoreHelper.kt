package com.aerodrop.system

// MediaStoreHelper.kt — AeroDrop Android  [Phase 3: System Integration]
// Writes received files into MediaStore Downloads (API 29+).
// Uses IS_PENDING locking pattern: file is hidden while being written,
// then made visible atomically when the stream is closed.

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

class MediaStoreHelper(private val context: Context) {

    /**
     * Opens a write stream to Downloads/AeroDrop/[filename].
     * IS_PENDING = 1 during the write prevents the file from appearing
     * in Files/Gallery half-written. It is cleared automatically on close.
     *
     * Caller MUST close the returned stream (use `.use {}`).
     */
    fun openOutputStream(filename: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,  filename)
            put(MediaStore.Downloads.IS_PENDING,    1)
            put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/AeroDrop")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        val rawStream = context.contentResolver.openOutputStream(uri) ?: return null

        // Wrap the stream to clear IS_PENDING atomically on close
        return object : OutputStream() {
            override fun write(b: Int)                          = rawStream.write(b)
            override fun write(b: ByteArray)                   = rawStream.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = rawStream.write(b, off, len)
            override fun flush()                                = rawStream.flush()
            override fun close() {
                rawStream.close()
                // File is now visible in Files/Gallery
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            }
        }
    }
}
