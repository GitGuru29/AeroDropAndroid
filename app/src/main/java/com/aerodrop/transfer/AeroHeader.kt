package com.aerodrop.transfer

// AeroHeader.kt — AeroDrop Android  [Phase 2: Transport]
// Binary 64-byte wire protocol header — MUST match the macOS AeroServer.h exactly.
//
// Layout (little-endian, packed):
//   Offset  Size  Field
//   ──────  ────  ─────────────────────────────────────────
//    0       4    magic    — 0x41 0x45 0x52 0x4F ('AERO')
//    4       4    version  — uint32, currently 1
//    8       8    fileSize — uint64, total payload bytes
//   16      44    filename — UTF-8, null-padded
//   60       4    checksum — Adler-32 of filename bytes
//   ──      ──    total: 64 bytes
//
// The macOS side is the authority and it is strict about two things:
//
//   • The name field is 44 bytes but at most 43 are used, so a NUL always
//     terminates it. AeroServer.cpp:376 does `min(size, sizeof(filename) - 1)`
//     and AeroServer.cpp:200 re-derives the length with `strnlen(..., 44)`.
//
//   • The checksum covers exactly the bytes that landed in the field — not the
//     whole name. So a name that does not fit MUST be truncated *before* the
//     Adler-32 is computed. Checksumming the full name while writing only its
//     first 43 bytes is a silent mismatch and macOS drops the connection with
//     "Filename checksum mismatch".

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AeroHeader(
    val fileSize: Long,
    filename: String,
    val version: Int = VERSION,
    private val magic: ByteArray = MAGIC,
    private val nameBytes: ByteArray = encodeName(filename),
    private val wireChecksum: Int = adler32(nameBytes),
) {

    /**
     * What the peer will actually see. Derived from the field bytes rather than
     * the caller's string, so a name that had to be shortened is never reported
     * back at its full length.
     */
    val filename: String = String(nameBytes, Charsets.UTF_8)

    val checksum: Int get() = adler32(nameBytes)

    /** The exact bytes written into (or read out of) the 44-byte name field. */
    fun rawName(): ByteArray = nameBytes.copyOf()

    fun isValid(): Boolean =
        magic.contentEquals(MAGIC) && version == VERSION && wireChecksum == adler32(nameBytes)

    /** Serialise for the Android → Mac direction. */
    fun toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(magic)
        bb.putInt(version)
        bb.putLong(fileSize)
        bb.put(nameBytes)
        bb.put(ByteArray(NAME_FIELD - nameBytes.size))   // NUL padding fills the field
        bb.putInt(checksum)
        return bb.array()
    }

    companion object {
        const val SIZE        = 64
        const val VERSION     = 1
        const val NAME_FIELD  = 44
        const val MAX_NAME    = NAME_FIELD - 1   // keep room for the NUL terminator

        val MAGIC = "AERO".toByteArray(Charsets.US_ASCII)

        /**
         * UTF-8 encode [name] into at most [MAX_NAME] bytes, never splitting a
         * multi-byte character. Returns at least one byte so a non-Latin name
         * that cannot fit still produces a decodable field rather than an
         * empty one that macOS would reject.
         */
        fun encodeName(name: String): ByteArray {
            val full = name.toByteArray(Charsets.UTF_8)
            if (full.size <= MAX_NAME) return full

            // Find the largest cut point <= MAX_NAME that does not slice a
            // character in half. A partial sequence at the end is either a
            // continuation byte whose lead is already outside, or a lead byte
            // whose continuations are; in both cases the whole sequence is
            // dropped, so the field never ends mid-character and the Mac never
            // decodes a mangled name.
            var end = MAX_NAME
            while (end > 0) {
                var lead = end - 1
                while (lead >= 0 && (full[lead].toInt() and 0xC0) == 0x80) lead--
                if (lead < 0) { end = 0; break }          // no lead byte at all
                val b = full[lead].toInt() and 0xFF
                val seqLen = when {
                    b and 0x80 == 0x00 -> 1              // ASCII
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1                           // invalid byte, keep as-is
                }
                if (lead + seqLen <= end) break          // last sequence is whole
                end = lead                               // drop the partial one
            }
            return full.copyOf(maxOf(end, 1))
        }

        /** Adler-32 over raw bytes — must match adler32() in AeroServer.cpp. */
        fun adler32(data: ByteArray, length: Int = data.size): Int {
            var a = 1L
            var b = 0L
            for (i in 0 until length) {
                a = (a + (data[i].toLong() and 0xFF)) % 65521
                b = (b + a) % 65521
            }
            return ((b shl 16) or a).toInt()
        }

        /**
         * Parse a header received from the Mac. The checksum is validated in
         * [isValid] against the raw field bytes so that a non-ASCII name — which
         * would not survive a decode/re-encode round trip — is still checked
         * exactly the way macOS computed it.
         */
        fun fromBytes(buf: ByteArray): AeroHeader {
            require(buf.size >= SIZE) { "Buffer too small: ${buf.size}" }
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val magic    = ByteArray(4).also { bb.get(it) }
            val version  = bb.int
            val fileSize = bb.long
            val field    = ByteArray(NAME_FIELD).also { bb.get(it) }
            val wireSum  = bb.int

            // Mirror macOS strnlen(filename, 44): the name is the bytes before
            // the first NUL, or the whole field when it is not NUL-terminated.
            var len = field.indexOfFirst { it.toInt() == 0 }
            if (len < 0) len = NAME_FIELD
            val nameBytes = field.copyOf(len)

            return AeroHeader(
                fileSize    = fileSize,
                filename    = String(nameBytes, Charsets.UTF_8),
                version     = version,
                magic       = magic,
                nameBytes   = nameBytes,
                wireChecksum = wireSum,
            )
        }
    }
}
