package com.aerodrop.transfer

// AeroReceiverService.kt — AeroDrop Android  [Phase 2: Transport]
// Foreground Service that keeps a TLS ServerSocket alive to receive
// incoming files from Mac.
//
// Large-file fixes applied:
//   1. WifiLock (HIGH_PERF) per accepted connection — radio stays awake
//   2. Socket buffer tuning — 4 MB SO_RCVBUF on accepted clients
//   3. WakeLock is now indefinite (0 = no timeout) — no expiry mid-transfer
//   4. Notification update throttled to every 2 MB, not every 128 KB chunk
//   5. BufferedOutputStream wraps MediaStore write for consistent I/O sizing
//   6. Server socket SO_REUSEADDR allows fast restart without TIME_WAIT

import android.app.*
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aerodrop.MainActivity
import com.aerodrop.system.MediaStoreHelper
import kotlinx.coroutines.*
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.io.BufferedOutputStream
import java.security.cert.X509Certificate

class AeroReceiverService : Service() {

    companion object {
        private const val TAG           = "AeroReceiver"
        const  val PORT                 = 7770
        private const val NOTIF_CHANNEL = "aerodrop_rx"
        private const val NOTIF_ID      = 2001
        private const val SOCK_BUF      = 4  * 1024 * 1024  // 4 MB TCP receive buffer
        private const val NOTIFY_EVERY  = 2  * 1024 * 1024L // update notification every 2 MB
        private const val BUF_SIZE      = 256 * 1024         // 256 KiB read buffer
    }

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server:   SSLServerSocket?          = null
    private var wakeLock: PowerManager.WakeLock?    = null
    private var wifiLock: WifiManager.WifiLock?     = null

    private val mediaStore by lazy { MediaStoreHelper(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Listening on port $PORT…"))
        acquireLocks()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        server?.close()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        super.onDestroy()
    }

    // ── TLS server socket ─────────────────────────────────────────────────────

    private fun trustAllContext(): SSLContext =
        SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), null)
        }

    private fun startListening() {
        scope.launch {
            try {
                server = (trustAllContext().serverSocketFactory.createServerSocket(PORT)
                    as SSLServerSocket).also {
                    it.reuseAddress = true   // survive TIME_WAIT on fast restart
                }
                Log.d(TAG, "TLS receiver on port $PORT")

                while (isActive) {
                    val client = server?.accept() ?: break
                    launch { handleClient(client as SSLSocket) }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server error: ${e.message}", e)
            }
        }
    }

    // ── Incoming file handler ─────────────────────────────────────────────────

    private suspend fun handleClient(socket: SSLSocket) {
        // Per-connection WifiLock — keeps radio alive for this specific transfer
        @Suppress("DEPRECATION")
        val connWifi = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AeroDrop::RxConn")
            .also { it.acquire() }

        try {
            // Tune receive buffer BEFORE handshake
            socket.receiveBufferSize = SOCK_BUF
            socket.soTimeout         = 0           // no read timeout
            socket.startHandshake()

            val inp = socket.inputStream

            // ── Read 64-byte AeroHeader ───────────────────────────────────
            val headerBuf = ByteArray(AeroHeader.SIZE)
            var hRead = 0
            while (hRead < AeroHeader.SIZE) {
                val n = inp.read(headerBuf, hRead, AeroHeader.SIZE - hRead)
                if (n < 0) { Log.e(TAG, "EOF reading header"); return }
                hRead += n
            }

            val header = AeroHeader.fromBytes(headerBuf)
            if (!header.isValid()) { Log.e(TAG, "Invalid AeroHeader"); return }

            val sizeMb = header.fileSize / 1_048_576
            Log.d(TAG, "Receiving '${header.filename}' (${sizeMb} MB)")
            notify("Receiving ${header.filename} (${sizeMb} MB)…")

            // ── Stream payload into MediaStore ────────────────────────────
            val buf          = ByteArray(BUF_SIZE)
            var received     = 0L
            var lastNotify   = 0L
            val t0           = System.currentTimeMillis()

            mediaStore.openOutputStream(header.filename)?.let { rawOut ->
                BufferedOutputStream(rawOut, BUF_SIZE).use { out ->
                    while (received < header.fileSize) {
                        val toRead = minOf(buf.size.toLong(), header.fileSize - received).toInt()
                        val n = inp.read(buf, 0, toRead)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n

                        // Throttle notification updates — Binder calls are expensive
                        if (received - lastNotify >= NOTIFY_EVERY || received == header.fileSize) {
                            val pct   = (received * 100 / header.fileSize).toInt()
                            val sec   = (System.currentTimeMillis() - t0) / 1000.0
                            val speed = if (sec > 0) String.format("%.1f MB/s", (received / 1e6) / sec) else ""
                            notify("${header.filename} — $pct%  $speed")
                            lastNotify = received
                        }
                    }
                } // BufferedOutputStream.close() flushes + closes rawOut
            } ?: Log.e(TAG, "Cannot open MediaStore stream for '${header.filename}'")

            val ok = received == header.fileSize
            Log.d(TAG, if (ok) "Complete: ${header.filename}" else
                "FAILED: $received / ${header.fileSize} bytes")
            notify(if (ok) "✓ ${header.filename} saved to Downloads/AeroDrop"
                   else "✗ Transfer incomplete (${received}/${header.fileSize} bytes)")

        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}", e)
            notify("✗ Transfer error: ${e.message}")
        } finally {
            socket.close()
            if (connWifi.isHeld) connWifi.release()
        }
    }

    // ── Locks ─────────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        // Indefinite WakeLock (timeout = 0) — released in onDestroy
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AeroDrop::ReceiverCpu")
        wakeLock?.acquire(0) // 0 = no timeout

        // Service-level WifiLock (keeps radio active while service is alive)
        @Suppress("DEPRECATION")
        wifiLock = (getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AeroDrop::ReceiverWifi")
        wifiLock?.acquire()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "AeroDrop Transfers",
            NotificationManager.IMPORTANCE_LOW)
            .apply { description = "AeroDrop incoming file transfers" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AeroDrop")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notify(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
