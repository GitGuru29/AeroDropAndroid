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

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AeroHeader(
    val fileSize: Long,
    val filename: String,
    val version:  Int       = 1,
    val magic:    ByteArray = MAGIC,
    val checksum: Int       = adler32(filename)
) {
    companion object {
        const val SIZE = 64
        val MAGIC = "AERO".toByteArray(Charsets.US_ASCII)

        // Adler-32 — must match the C implementation in AeroServer.cpp
        fun adler32(s: String): Int {
            val data = s.toByteArray(Charsets.UTF_8)
            var a = 1L
            var b = 0L
            for (byte in data) {
                a = (a + (byte.toLong() and 0xFF)) % 65521
                b = (b + a) % 65521
            }
            return ((b shl 16) or a).toInt()
        }

        // Deserialise an incoming header (Mac → Android direction)
        fun fromBytes(buf: ByteArray): AeroHeader {
            require(buf.size >= SIZE) { "Buffer too small: ${buf.size}" }
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val magic    = ByteArray(4).also { bb.get(it) }
            val version  = bb.int
            val fileSize = bb.long
            val fnBytes  = ByteArray(44).also { bb.get(it) }
            val filename = fnBytes.decodeToString().trimEnd('\u0000')
            val checksum = bb.int
            return AeroHeader(fileSize, filename, version, magic, checksum)
        }
    }

    // Serialise for sending (Android → Mac direction)
    fun toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(magic.copyOf(4))
        bb.putInt(version)
        bb.putLong(fileSize)
        // filename field: 44 bytes, UTF-8, null-padded
        val fnBytes = filename.toByteArray(Charsets.UTF_8).copyOf(44)
        bb.put(fnBytes)
        bb.putInt(checksum)
        return bb.array()
    }

    fun isValid(): Boolean =
        magic.contentEquals(MAGIC) && checksum == adler32(filename)
}
