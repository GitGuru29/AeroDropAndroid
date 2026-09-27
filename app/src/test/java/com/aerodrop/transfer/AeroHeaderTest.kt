package com.aerodrop.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every WIRE_* literal below is a verbatim 64-byte dump produced by compiling
 * the macOS header construction from AeroServer.cpp and printing the packed
 * struct. These tests are the interop contract: if they pass, the Android
 * encoder emits exactly what the Mac expects, in both directions.
 */
class AeroHeaderTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun parse(h: String) = h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun wire(name: String, size: Long) = AeroHeader(fileSize = size, filename = name).toBytes()

    // ── Byte-for-byte agreement with the macOS sender ──────────────────────────

    @Test
    fun `short ascii name matches macOS byte for byte`() {
        assertEquals(WIRE_SHORT_ASCII, hex(wire("photo.jpg", 123456)))
    }

    /** Exactly 43 bytes: fits, so nothing is truncated and a NUL still terminates. */
    @Test
    fun `name that exactly fills the field matches macOS`() {
        val name = "1234567890123456789012345678901234567890123"
        assertEquals(43, name.length)
        assertEquals(WIRE_MAX_FIT_43, hex(wire(name, 1)))
    }

    /**
     * The regression that broke interop: the checksum must cover the bytes that
     * actually reached the field. Checksumming the whole name and truncating
     * afterwards makes macOS reject the transfer with "checksum mismatch".
     */
    @Test
    fun `overlong name is truncated before the checksum is computed`() {
        val name = "a-very-long-file-name-that-will-not-fit-in-the-field.bin"
        val header = AeroHeader(fileSize = 7, filename = name)

        assertEquals("a-very-long-file-name-that-will-not-fit-in-", header.filename)
        assertEquals(43, header.rawName().size)
        assertEquals("5ce20faf", "%08x".format(header.checksum))
        assertEquals(WIRE_LONG_60, hex(header.toBytes()))
    }

    /**
     * A name whose 43-byte cut lands on a character boundary: the Android
     * encoder must then be byte-identical to the Mac's blind memcpy.
     */
    @Test
    fun `multibyte name cut on a boundary matches macOS byte for byte`() {
        val name = "üñïçødé-fïlé-nåme-wîth-accents-that-overflows.bin"
        val header = AeroHeader(fileSize = 9, filename = name)
        assertEquals(43, header.rawName().size)
        assertEquals(WIRE_UTF8_BOUNDARY, hex(header.toBytes()))
    }

    @Test
    fun `truncation backs off rather than splitting a character`() {
        // 22 two-byte characters: a 43-byte cut lands mid-character, so the
        // encoder must drop the 22nd entirely and keep 21 whole characters.
        val raw = AeroHeader.encodeName("é".repeat(22))
        assertEquals(42, raw.size)
        assertArrayEquals(raw, String(raw, Charsets.UTF_8).toByteArray(Charsets.UTF_8))
        assertEquals("é".repeat(21), String(raw, Charsets.UTF_8))
    }

    // ── Receiver path: Mac → Android ───────────────────────────────────────────

    @Test
    fun `parses a header produced by the Mac`() {
        val header = AeroHeader.fromBytes(parse(WIRE_SHORT_ASCII))

        assertEquals("photo.jpg", header.filename)
        assertEquals(123456L, header.fileSize)
        assertEquals(1, header.version)
        assertTrue(header.isValid())
    }

    @Test
    fun `parses an overlong name produced by the Mac`() {
        val header = AeroHeader.fromBytes(parse(WIRE_LONG_60))
        assertEquals("a-very-long-file-name-that-will-not-fit-in-", header.filename)
        assertTrue(header.isValid())
    }

    /**
     * macOS truncates blindly, so a name can arrive ending mid-character. The
     * checksum must be judged on the raw field bytes, never on a re-encoded
     * copy, or a perfectly good transfer gets thrown away.
     */
    @Test
    fun `accepts a macOS-truncated name that ends mid character`() {
        // "a"×42 then a 3-byte euro sign: cutting at 43 leaves a lead byte with
        // no continuation behind it, which is not decodable on its own.
        val field = ("a".repeat(42) + "€").toByteArray(Charsets.UTF_8).copyOf(43)

        val wire = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            .put("AERO".toByteArray())
            .putInt(1)
            .putLong(99L)
            .put(field)
            .put(0)                                   // NUL at index 43
            .putInt(AeroHeader.adler32(field))       // checksummed over 43 bytes
            .array()

        val parsed = AeroHeader.fromBytes(wire)
        assertEquals(99L, parsed.fileSize)
        assertTrue("checksum must be judged on raw field bytes", parsed.isValid())
    }

    @Test
    fun `rejects a corrupted checksum`() {
        val wire = parse(WIRE_SHORT_ASCII).also { it[60] = 0xDE.toByte(); it[61] = 0xAD.toByte() }
        assertFalse(AeroHeader.fromBytes(wire).isValid())
    }

    @Test
    fun `rejects a bad magic`() {
        val wire = parse(WIRE_SHORT_ASCII).also { it[0] = 'X'.code.toByte() }
        assertFalse(AeroHeader.fromBytes(wire).isValid())
    }

    @Test
    fun `rejects an unsupported version`() {
        val wire = parse(WIRE_SHORT_ASCII).also {
            it[4] = 2; it[5] = 0; it[6] = 0; it[7] = 0
        }
        assertFalse(AeroHeader.fromBytes(wire).isValid())
    }

    // ── Adler-32 conformance ───────────────────────────────────────────────────

    @Test
    fun `adler32 matches the reference implementation`() {
        assertEquals(1, AeroHeader.adler32(ByteArray(0)))
        assertEquals(0x024d0127, AeroHeader.adler32("abc".toByteArray()))
        assertEquals(0x11e60398, AeroHeader.adler32("Wikipedia".toByteArray()))
    }

    @Test
    fun `adler32 treats bytes as unsigned`() {
        // A signed byte would corrupt the running sums.
        val data = byteArrayOf(-1, -128, 127, -1, 0)
        assertEquals(adler32Ref(data), AeroHeader.adler32(data))
    }

    private fun adler32Ref(data: ByteArray): Int {
        var a = 1L
        var b = 0L
        for (byte in data) {
            a = (a + (byte.toLong() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return ((b shl 16) or a).toInt()
    }

    // ── Structural guarantees ──────────────────────────────────────────────────

    @Test
    fun `encoded size is always exactly 64 bytes`() {
        for (name in listOf("", "a", "x".repeat(43), "x".repeat(44), "y".repeat(500), "日".repeat(40))) {
            assertEquals(64, wire(name, 1).size)
        }
    }

    @Test
    fun `the name field always keeps a NUL terminator`() {
        for (name in listOf("a", "x".repeat(43), "x".repeat(44), "z".repeat(300), "日".repeat(40))) {
            // The name field occupies bytes 16..59. macOS re-derives its length
            // with strnlen(field, 44), so index 59 must be a NUL in every case.
            assertEquals("missing NUL terminator for '$name'", 0, wire(name, 1)[16 + AeroHeader.MAX_NAME].toInt())
        }
    }

    @Test
    fun `empty name still yields a valid header`() {
        val header = AeroHeader(fileSize = 0, filename = "")
        assertEquals(0, header.rawName().size)
        assertTrue(header.isValid())
    }

    private companion object {
        const val WIRE_SHORT_ASCII =
            "4145524f0100000040e201000000000070686f746f2e6a706700000000000000" +
            "000000000000000000000000000000000000000000000000000000009a036212"

        const val WIRE_MAX_FIT_43 =
            "4145524f01000000010000000000000031323334353637383930313233343536" +
            "37383930313233343536373839303132333435363738393031323300cb0885c1"

        const val WIRE_LONG_60 =
            "4145524f010000000700000000000000612d766572792d6c6f6e672d66696c65" +
            "2d6e616d652d746861742d77696c6c2d6e6f742d6669742d696e2d00af0fe25c"

        const val WIRE_UTF8_BOUNDARY =
            "4145524f010000000900000000000000c3bcc3b1c3afc3a7c3b864c3a92d66c3" +
            "af6cc3a92d6ec3a56d652d77c3ae74682d616363656e74732d746800d516aa34"
    }


    // ── UTF-8 cut boundaries ───────────────────────────────────────────────────
    // The encoder must never emit a lead byte without its continuations: the
    // Mac would decode a mangled name and the checksum would cover bytes that
    // are not valid UTF-8.

    private fun assertEndsOnBoundary(name: String) {
        val encoded = AeroHeader.encodeName(name)
        assertTrue(
            "name '$name' produced ${encoded.size} bytes that are not valid UTF-8",
            encoded.toString(Charsets.UTF_8).let {
                String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == String(it.toByteArray(Charsets.UTF_8), Charsets.UTF_8)
            },
        )
        // Round-trip: decoding must give back a prefix of the original.
        val decoded = String(encoded, Charsets.UTF_8)
        assertTrue(
            "decoded '$decoded' is not a prefix of '$name'",
            name.startsWith(decoded),
        )
        assertTrue("field is ${encoded.size} bytes, max ${AeroHeader.MAX_NAME}",
                   encoded.size <= AeroHeader.MAX_NAME)
        assertTrue("a 2-byte character was lost entirely", encoded.size >= 1)
    }

    @Test
    fun `two byte character cut at the last field byte backs off`() {
        // 42 ASCII + a 2-byte char starting at index 42, so index 43 is a
        // continuation and must be dropped along with its lead byte.
        assertEndsOnBoundary("a".repeat(42) + "é" + "tail")
    }

    @Test
    fun `three byte character starting at the last field byte backs off`() {
        // 43 ASCII + a 3-byte CJK char: index 43 is a LEAD byte whose two
        // continuations would both be dropped. This is the case the old
        // boundary walk missed, because it only inspected continuation bytes.
        assertEndsOnBoundary("a".repeat(43) + "世" + "tail")
    }

    @Test
    fun `four byte character starting at the last field byte backs off`() {
        // 43 ASCII + a 4-byte emoji.
        assertEndsOnBoundary("a".repeat(43) + "😀" + "tail")
    }

    @Test
    fun `three byte character straddling the boundary backs off completely`() {
        // 41 ASCII + a 3-byte char at indices 41,42,43: all three must go.
        assertEndsOnBoundary("a".repeat(41) + "世" + "tail")
    }

    @Test
    fun `an ascii name filling the field is never cut`() {
        val exact = "a".repeat(AeroHeader.MAX_NAME)
        assertEquals(AeroHeader.MAX_NAME, AeroHeader.encodeName(exact).size)
        assertEndsOnBoundary(exact + "extra")
    }

    @Test
    fun `a name of only huge characters keeps as many whole characters as fit`() {
        // 4-byte emoji: 43 bytes holds 10 complete characters (40 bytes) and a
        // 3-byte remainder that must be dropped rather than sliced.
        val encoded = AeroHeader.encodeName("😀".repeat(30))
        assertEquals(40, encoded.size)
        assertEquals("😀".repeat(10), String(encoded, Charsets.UTF_8))
        assertTrue(encoded.size <= AeroHeader.MAX_NAME)
    }

    @Test
    fun `a name whose first character cannot fit still yields a decodable field`() {
        // 45 ASCII then a 2-byte character: the character is dropped, not split.
        val encoded = AeroHeader.encodeName("a".repeat(43) + "é")
        assertEquals(43, encoded.size)
        assertEquals("a".repeat(43), String(encoded, Charsets.UTF_8))
    }
}
