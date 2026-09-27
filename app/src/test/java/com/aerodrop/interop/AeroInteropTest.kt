package com.aerodrop.interop

// AeroInteropTest.kt — end-to-end checks against the REAL macOS transport.
//
// The peer is the unmodified AeroServer.cpp / CertManager.cpp from the macOS
// project, compiled into tools/macpeer by tools/build-mac-peer.sh. These are the
// only tests that prove the two apps actually talk to each other: the header
// unit tests prove the bytes, and these prove the bytes survive a real TLS 1.3
// session with the C++ adler32 validating the other end.
//
// Each test binds its own ephemeral port, because AeroServer sets SO_REUSEPORT
// and a fixed port would let a not-yet-dead peer from the previous test accept
// the next test's connection.

import com.aerodrop.transfer.AeroHeader
import com.aerodrop.transfer.AeroProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

class AeroInteropTest {

    companion object {
        /** Set by app/build.gradle.kts — see tools/build-mac-peer.sh. */
        private fun peerPath(): File =
            File(System.getProperty("aerodrop.macPeer") ?: "tools/macpeer")

        private fun trustAll() = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        }

        private fun clientContext(): SSLContext =
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll()), null) }

        /**
         * A self-signed RSA certificate, so the test can play the Android side's
         * TLS *server* off-device. The real app uses the Android Keystore, which
         * does not exist on a desktop JVM; the certificate is only a fixture.
         *
         * Generated once — keygen is the slowest thing in this class.
         */
        private val serverContext: SSLContext by lazy { buildServerContext() }

        private fun buildServerContext(): SSLContext {
            val dir = Files.createTempDirectory("aerodrop-test").toFile()
            val store = File(dir, "keystore.p12")          // must not exist yet
            val password = "changeit".toCharArray()

            val keytool = listOf(
                System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-noprompt", "-alias", "aerodrop",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-dname", "CN=aerodrop.local, O=AeroDrop",
                "-storetype", "PKCS12",
                "-keystore", store.absolutePath,
                "-storepass", "changeit", "-keypass", "changeit",
            )
            val proc = ProcessBuilder(keytool).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "keytool failed:\n$output" }

            val ks = KeyStore.getInstance("PKCS12").apply {
                store.inputStream().use { load(it, password) }
            }
            store.delete()
            dir.delete()

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(ks, password) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        /** An unused port, released before the peer binds it. */
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * Skip rather than fail when the macOS peer has not been compiled, so
         * the suite still runs on a machine without the macOS sources.
         */
        private fun assumePeer() {
            assumeTrue(
                "macpeer not built at ${peerPath().absolutePath} — run tools/build-mac-peer.sh",
                peerPath().exists(),
            )
        }
    }

    // ── Android → Mac ──────────────────────────────────────────────────────────

    @Test
    fun `macOS accepts a file sent by the Android client`() {
        withPeer { port, _ ->
            val payload = Random(42).nextBytes(700 * 1024)     // spans 512 KiB chunks
            runBlocking { send(port, "interop-report.bin", payload) }
        }
    }

    /** The bug that used to break every send: a name longer than the field. */
    @Test
    fun `macOS accepts a file whose name overflows the 44-byte field`() {
        withPeer { port, dl ->
            val payload = "hello".toByteArray()
            val longName = "an-extremely-long-file-name-that-cannot-possibly-fit.bin"

            runBlocking { send(port, longName, payload) }

            // macOS keeps the first 43 bytes of the name, exactly as sent.
            val expected = longName.take(43)
            val out = File(dl, expected)
            assertTrue(
                "expected '$expected', but the directory holds ${dl.list()?.toList()}",
                out.exists(),
            )
            assertArrayEquals(payload, out.readBytes())
        }
    }

    @Test
    fun `macOS accepts a zero-length file`() {
        withPeer { port, dl ->
            runBlocking { send(port, "empty.bin", ByteArray(0)) }
            val out = File(dl, "empty.bin")
            assertTrue("no output file, dir holds ${dl.list()?.toList()}", out.exists())
            assertEquals(0L, out.length())
        }
    }

    @Test
    fun `macOS accepts a non-ascii name`() {
        withPeer { port, dl ->
            val payload = "unicode".toByteArray()
            runBlocking { send(port, "rapport-ünïcode-café.bin", payload) }

            val files = dl.listFiles()!!.map { it.name }
            assertEquals("expected one file, got $files", 1, files.size)
            assertArrayEquals(payload, File(dl, files[0]).readBytes())
        }
    }

    @Test
    fun `macOS rejects a corrupted header and does not hang`() {
        withPeer { port, _ ->
            // A header whose checksum does not match must be refused promptly;
            // macOS returns before reading any payload.
            val raw = Socket()
            raw.connect(InetSocketAddress("127.0.0.1", port), 10_000)
            val ssl = clientContext().socketFactory
                .createSocket(raw, "127.0.0.1", port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.enabledProtocols = arrayOf("TLSv1.3")
            ssl.startHandshake()

            val good = AeroHeader(fileSize = 4, filename = "x.bin").toBytes()
            good[60] = 0x00; good[61] = 0x00; good[62] = 0x00; good[63] = 0x00

            ssl.outputStream.write(good)
            ssl.outputStream.flush()
            // The Mac closes without sending anything back.
            assertEquals(-1, ssl.inputStream.read())
            ssl.close()
        }
    }

    // ── Mac → Android ──────────────────────────────────────────────────────────

    @Test
    fun `android accepts a file pushed by the macOS client`() {
        assumePeer()
        val port = freePort()

        val payload = Random(7).nextBytes(1_200_000)          // 1.2 MB
        val source = Files.createTempFile("mac-sends", ".bin").toFile()
        source.writeBytes(payload)

        val received = LinkedBlockingQueue<ByteArray>()
        val nameSeen = LinkedBlockingQueue<String>()
        val headerErr = LinkedBlockingQueue<String>()

        val server = serverContext.serverSocketFactory
            .createServerSocket(port) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = false

        val thread = Thread {
            try {
                server.accept().use { raw ->
                    val ssl = raw as SSLSocket
                    ssl.useClientMode = false
                    ssl.enabledProtocols = arrayOf("TLSv1.3")
                    ssl.startHandshake()
                    if (ssl.session.protocol != "TLSv1.3") {
                        headerErr.put("negotiated ${ssl.session.protocol}, not TLSv1.3")
                        return@use
                    }

                    val header = AeroProtocol.readHeader(ssl.inputStream)
                    if (header == null) {
                        headerErr.put("no header from the Mac")
                        return@use
                    }
                    if (!header.isValid()) {
                        headerErr.put("Mac sent an invalid header: ${header.filename}")
                        return@use
                    }
                    nameSeen.put(header.filename)

                    val sink = java.io.ByteArrayOutputStream()
                    val got = runBlocking { AeroProtocol.receive(ssl.inputStream, header, sink) }
                    if (got != header.fileSize) {
                        headerErr.put("short read $got of ${header.fileSize}")
                        return@use
                    }
                    received.put(sink.toByteArray())
                }
            } catch (e: Throwable) {
                // macOS close()s the socket as soon as the payload is written,
                // without reading our close_notify, so the JVM sees a TCP reset
                // *after* a fully successful transfer. Only a failure before the
                // bytes landed is a real problem.
                if (received.isEmpty()) headerErr.put(e.stackTraceToString())
            }
        }
        thread.isDaemon = true
        thread.start()

        val proc = peer("send", port, source.absolutePath, "127.0.0.1")
        val peerLog = StringBuilder()
        val pump = Thread {
            proc.inputStream.bufferedReader().forEachLine { peerLog.appendLine(it) }
        }
        pump.isDaemon = true
        pump.start()

        val out = received.poll(60, TimeUnit.SECONDS)
        val err = headerErr.poll(1, TimeUnit.SECONDS)
        proc.waitFor(30, TimeUnit.SECONDS)
        server.close()

        assertNull("the Android side reported: $err\nmacOS peer said:\n$peerLog", err)
        assertNotNull("macOS never delivered a payload; peer said:\n$peerLog", out)
        assertEquals("payload size differs", payload.size, out!!.size)
        assertArrayEquals("payload differs byte-for-byte", payload, out)
        assertEquals(source.name, nameSeen.poll(5, TimeUnit.SECONDS))
    }

    // ── Harness ────────────────────────────────────────────────────────────────

    /** Starts the Mac in receive mode and yields its port and download dir. */
    private fun withPeer(body: (port: Int, downloadDir: File) -> Unit) {
        assumePeer()
        val port = freePort()
        val dl = Files.createTempDirectory("aerodrop-dl").toFile()
        val proc = peer("recv", port, dl.absolutePath)

        val log = StringBuilder()
        val pump = Thread {
            proc.inputStream.bufferedReader().forEachLine { log.appendLine(it) }
        }
        pump.isDaemon = true
        pump.start()

        try {
            awaitListening(port, proc) { log.toString() }
            body(port, dl)
        } finally {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    private fun peer(vararg args: Any): Process =
        ProcessBuilder(listOf(peerPath().absolutePath) + args.map { it.toString() })
            .redirectErrorStream(true)
            .start()

    private fun awaitListening(port: Int, proc: Process, log: () -> String) {
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) throw AssertionError("macpeer exited early:\n${log()}")
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return
            }
            Thread.sleep(100)
        }
        throw AssertionError("macpeer never listened on $port:\n${log()}")
    }

    /** Drives the real outbound path: TLS 1.3, header, streamed payload. */
    private suspend fun send(port: Int, name: String, payload: ByteArray) {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress("127.0.0.1", port), 10_000)
        val ssl = clientContext().socketFactory
            .createSocket(raw, "127.0.0.1", port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.enabledProtocols = arrayOf("TLSv1.3")
        ssl.startHandshake()
        assertEquals("TLSv1.3", ssl.session.protocol)

        val header = AeroHeader(fileSize = payload.size.toLong(), filename = name)
        val sent = AeroProtocol.send(payload.inputStream(), ssl.outputStream, header)
        assertEquals(payload.size.toLong(), sent)
        ssl.close()
    }

    private fun assertNull(message: String, value: Any?) =
        org.junit.Assert.assertNull(message, value)
}
