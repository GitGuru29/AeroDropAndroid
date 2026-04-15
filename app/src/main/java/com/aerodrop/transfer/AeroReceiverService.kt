package com.aerodrop.transfer

// AeroReceiverService.kt — AeroDrop Android  [Phase 2: Transport]
// Foreground Service that keeps a TLS ServerSocket alive to receive
// incoming files from Mac. Holds PARTIAL_WAKE_LOCK during transfers.
// foregroundServiceType=dataSync is mandatory on Android 14+.

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aerodrop.MainActivity
import com.aerodrop.system.MediaStoreHelper
import kotlinx.coroutines.*
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class AeroReceiverService : Service() {

    companion object {
        private const val TAG            = "AeroReceiver"
        const  val PORT                  = 7770
        private const val NOTIF_CHANNEL  = "aerodrop_rx"
        private const val NOTIF_ID       = 2001
    }

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: SSLServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mediaStore by lazy { MediaStoreHelper(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Listening on port $PORT…"))
        acquireWakeLock()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        server?.close()
        wakeLock?.release()
        super.onDestroy()
    }

    private fun startListening() {
        scope.launch {
            try {
                // AeroCertManager.serverSslContext() provides a context with a
                // real ECDSA server certificate from AndroidKeyStore.
                // trustAllContext() with null KeyManager had NO cert and caused
                // every TLS handshake from macOS to fail immediately.
                server = AeroCertManager.serverSslContext()
                    .serverSocketFactory.createServerSocket(PORT) as SSLServerSocket
                Log.d(TAG, "TLS 1.3 server ready on port $PORT")

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
        try {
            socket.startHandshake()
            val inp = socket.inputStream

            // Read exactly 64 bytes (AeroHeader)
            val headerBuf = ByteArray(AeroHeader.SIZE)
            var read = 0
            while (read < AeroHeader.SIZE) {
                val n = inp.read(headerBuf, read, AeroHeader.SIZE - read)
                if (n < 0) { Log.e(TAG, "EOF reading header"); return }
                read += n
            }

            val header = AeroHeader.fromBytes(headerBuf)
            if (!header.isValid()) { Log.e(TAG, "Invalid AeroHeader"); return }

            Log.d(TAG, "Receiving '${header.filename}' (${header.fileSize} B)")
            notify("Receiving ${header.filename}…")

            val buf      = ByteArray(512 * 1024) // 512 KiB — large file throughput
            var received = 0L
            val t0       = System.currentTimeMillis()

            mediaStore.openOutputStream(header.filename)?.use { out ->
                while (received < header.fileSize) {
                    val toRead = minOf(buf.size.toLong(), header.fileSize - received).toInt()
                    val n = inp.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    received += n
                    val pct   = (received * 100 / header.fileSize).toInt()
                    val sec   = (System.currentTimeMillis() - t0) / 1000.0
                    val speed = if (sec > 0) String.format("%.1f MB/s", (received / 1e6) / sec) else ""
                    notify("${header.filename} — $pct% $speed")
                }
            }

            val ok = received == header.fileSize
            Log.d(TAG, if (ok) "Transfer complete" else "Transfer FAILED ($received/${header.fileSize})")
            notify(if (ok) "✓ ${header.filename} saved to Downloads/AeroDrop" else "✗ Transfer failed")

        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}", e)
        } finally {
            socket.close()
        }
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AeroDrop::Receiver")
        wakeLock?.acquire(10 * 60 * 1000L) // max 10 min
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
