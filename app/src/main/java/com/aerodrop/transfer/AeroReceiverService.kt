package com.aerodrop.transfer

// AeroReceiverService.kt — AeroDrop Android  [Phase 2: Transport]
// Foreground service holding a TLS 1.3 server socket open so the Mac can push
// files to this device whenever the app is running.
//
// The Mac connects *to us* in this direction, which makes the listener fragile
// in ways the outbound path is not. Three things matter and all three were
// bugs at some point:
//
//   1. A failed handshake must never kill the listener. macOS opens a probe
//      connection during some network transitions; if that throws out of the
//      accept loop the port closes and every later send hangs forever. Each
//      connection is handled on its own coroutine and the loop re-binds on any
//      socket-level failure.
//
//   2. The socket must be bound to the wildcard address. AeroDiscoveryBrowser
//      on macOS deliberately resolves IPv4 first — it skips AF_INET6 results
//      precisely because "Android binds to 0.0.0.0" — so binding loopback or
//      IPv6-only would make us invisible to the sender.
//
//   3. The declared fileSize is authoritative for the read loop, so a truncated
//      or over-long stream terminates instead of hanging.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aerodrop.MainActivity
import com.aerodrop.system.MediaStoreHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class AeroReceiverService : Service() {

    companion object {
        private const val TAG           = "AeroReceiver"
        const  val PORT                  = 7770
        private const val NOTIF_CHANNEL   = "aerodrop_rx"
        private const val NOTIF_ID        = 2001
        private const val PROGRESS_MS     = 100L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val REBIND_DELAY_MS = 1_500L
        private const val WAKE_LOCK_MS    = 2 * 60 * 60 * 1000L

        fun start(context: android.content.Context) {
            val intent = Intent(context, AeroReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, AeroReceiverService::class.java))
        }
    }

    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: SSLServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mediaStore by lazy { MediaStoreHelper(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat("Listening on port $PORT…")
        AeroInbound.setReceivedDir(this)
        acquireWakeLock()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AeroInbound.setListening(false)
        scope.cancel()
        runCatching { server?.close() }
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ── Listener ───────────────────────────────────────────────────────────────

    /**
     * Binds, then serves forever. Any failure closes the socket, reports it and
     * retries after a short delay, because the port being closed is the one
     * failure mode that makes the Mac hang rather than fail.
     */
    private fun startListening() {
        scope.launch {
            while (isActive) {
                var bound = false
                try {
                    server = AeroCertManager.newServerSocket(PORT)
                    bound = true
                    AeroInbound.setListening(true)
                    Log.i(TAG, "TLS 1.3 server listening on [::]:$PORT")
                    notify("Ready to receive from Mac")

                    while (isActive) {
                        val socket = try {
                            server?.accept()
                        } catch (e: Exception) {
                            // Closed underneath us — fall through and re-bind.
                            Log.w(TAG, "accept() failed: ${e.message}")
                            null
                        } ?: break

                        launch { handleClient(socket as SSLSocket) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not bind port $PORT: ${e.message}", e)
                    notify("Cannot listen on port $PORT")
                } finally {
                    if (bound) {
                        AeroInbound.setListening(false)
                        runCatching { server?.close() }
                        server = null
                    }
                }
                if (isActive) delay(REBIND_DELAY_MS)
            }
        }
    }

    // ── Incoming file handler ──────────────────────────────────────────────────

    private suspend fun handleClient(socket: SSLSocket) {
        var name = "file"
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            AeroCertManager.pinTls13(socket)
            socket.startHandshake()
            // Clear the read timeout now that the session is established; a
            // large file can legitimately take longer than the handshake did.
            socket.soTimeout = 0

            val inp = socket.getInputStream()

            val header = AeroProtocol.readHeader(inp)
                ?: throw IllegalStateException("EOF before the 64-byte header")
            if (!header.isValid()) throw IllegalStateException("Invalid AeroHeader")

            name = header.filename
            val expected = header.fileSize
            Log.i(TAG, "Receiving '$name' ($expected bytes) from ${socket.inetAddress?.hostAddress}")

            AeroInbound.begin(name, expected)
            notify("Receiving $name…")

            val started = System.currentTimeMillis()
            var received = 0L
            var lastReport = 0L
            var done = false

            val out = mediaStore.create(name)
                ?: throw IllegalStateException("Cannot create a file in Downloads/AeroDrop")

            out.use { sink ->
                received = AeroProtocol.receive(inp, header, sink) { bytes ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport > PROGRESS_MS || bytes == expected) {
                        lastReport = now
                        val secs = (now - started) / 1000.0
                        val speed = if (secs > 0) (bytes / 1_000_000.0) / secs else 0.0
                        AeroInbound.advance(InboundProgress(name, bytes, expected, speed))
                        if (bytes < expected) {
                            notify("${pct(bytes, expected)}%  $name")
                        }
                    }
                }
                done = received == expected
            }

            if (done) {
                Log.i(TAG, "Saved '$name' ($received bytes)")
                val result = InboundResult.Saved(name, received, AeroInbound.receivedDir.value)
                AeroInbound.finish(result)
                notify("✓ $name saved")
            } else {
                val reason = "Incomplete: $received of $expected bytes"
                Log.w(TAG, "$reason for '$name'")
                AeroInbound.finish(InboundResult.Failed(name, reason))
                notify("✗ $name failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receive failed for '$name': ${e.message}", e)
            AeroInbound.finish(InboundResult.Failed(name, e.message ?: "Receive failed"))
            notify("✗ ${e.message ?: "Receive failed"}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun pct(received: Long, total: Long) =
        if (total > 0) ((received * 100) / total).toInt() else 0

    // ── WakeLock ───────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AeroDrop::Receiver")
            .apply { acquire(WAKE_LOCK_MS) }
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(NOTIF_CHANNEL, "AeroDrop Transfers",
            NotificationManager.IMPORTANCE_LOW)
            .apply { description = "AeroDrop incoming file transfers" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AeroDrop")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }
}
