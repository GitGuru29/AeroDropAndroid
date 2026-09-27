package com.aerodrop.system

import java.util.Locale

/**
 * Filename and MIME helpers for received files.
 *
 * These are pure functions with no Android dependency on purpose: the name comes
 * straight off the network, and this is the code that decides where a received
 * file lands on disk. Keeping it free of framework types means it can be
 * exercised by ordinary JVM unit tests rather than only on a device.
 */
object AeroFileName {

    private const val FALLBACK = "aerodrop_received"
    private const val MAX_LENGTH = 200

    /**
     * Make a peer-supplied name safe to hand to MediaStore.
     *
     * The name arrives over the network inside a fixed 44-byte field, so it can
     * hold anything a device might call a file — including path separators and
     * characters MediaStore rejects outright. Anything that could escape the
     * target directory or break the provider is replaced.
     */
    fun sanitize(raw: String): String {
        // Keep only the last path component, so "../.." cannot survive.
        val base = raw.substringAfterLast('/').substringAfterLast('\\')

        val cleaned = buildString(base.length) {
            for (ch in base) {
                when {
                    ch.isISOControl() -> append('_')
                    ch in "/\\:*?\"<>|" -> append('_')
                    else -> append(ch)
                }
            }
        }.trim()

        // "." and ".." are meaningless as file names; leading dots would hide
        // the file on disk.
        val safe = cleaned.trimStart('.').ifEmpty { FALLBACK }

        // Truncate without splitting a surrogate pair, which would leave a lone
        // high surrogate and a filename the filesystem cannot represent.
        if (safe.length > MAX_LENGTH) {
            val cut = safe.take(MAX_LENGTH)
            val clean = if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
            return clean.trimEnd().ifEmpty { FALLBACK }
        }
        return safe
    }

    /** Best-effort MIME type from the extension, used for gallery indexing. */
    fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"  -> "image/png"
            "gif"  -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf"  -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "mp4"  -> "video/mp4"
            "mov"  -> "video/quicktime"
            "mp3"  -> "audio/mpeg"
            "zip"  -> "application/zip"
            else   -> "application/octet-stream"
        }
    }
}
