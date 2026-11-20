package com.pharrlabs.murmur

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Holds one SSE connection to the PC relay (over your own https address) and turns each note into a notification.
 * The relay's catch-up replay (Last-Event-ID) means anything that arrived while the phone was offline is delivered on reconnect.
 */
class PushService : Service() {
    @Volatile private var running = false
    private var lastRefresh = 0L
    private var worker: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.channels(this)
        startForeground(1, statusNotification("Connecting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        if (worker?.isAlive != true) {
            running = true
            worker = Thread({ loop() }, "murmur-sse").also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun statusNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, Notifier.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setContentTitle("Murmur")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(text: String) {
        Cfg.setState(this, text)
        getSystemService(android.app.NotificationManager::class.java).notify(1, statusNotification(text))
    }

    private fun loop() {
        var backoff = 2000L
        val prefs = Cfg.prefs(this)
        while (running) {
            try {
                val since = prefs.getLong("lastEventId", 0)
                val conn = Relay.open(this, "/events?since=$since", readTimeout = 70000)
                conn.setRequestProperty("Accept", "text/event-stream")
                if (conn.responseCode != 200) throw RuntimeException("HTTP " + conn.responseCode)
                setStatus("Connected")
                Thread { Capture.flush(this) }.start()
                backoff = 2000L
                val r = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                var event = ""
                var data = StringBuilder()
                var id = 0L
                while (running) {
                    val line = r.readLine() ?: break
                    if (System.currentTimeMillis() - lastRefresh > 300_000L) {
                        lastRefresh = System.currentTimeMillis()
                        Widgets.refreshAll(this)
                        ApprovalsTile.requestRefresh(this)
                    }
                    when {
                        line.startsWith("id:") -> id = line.substring(3).trim().toLongOrNull() ?: id
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                        line.isEmpty() -> {
                            if (event == "note" && data.isNotEmpty()) {
                                try {
                                    val note = JSONObject(data.toString())
                                    if (!MainActivity.visible) Notifier.show(this, note)
                                    Widgets.refreshAll(this)
                                    ApprovalsTile.requestRefresh(this)
                                } catch (e: Exception) {
                                    Log.w("Murmur", "bad note", e)
                                }
                                if (id > 0) prefs.edit().putLong("lastEventId", id).apply()
                            }
                            event = ""; data = StringBuilder()
                        }
                    }
                }
                conn.disconnect()
                setStatus("Reconnecting...")
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                setStatus("Offline: " + (e.message ?: e.javaClass.simpleName))
            }
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { return }
            backoff = minOf(backoff * 2, 30000L)
        }
    }

    companion object {
        fun start(c: Context) {
            c.startForegroundService(Intent(c, PushService::class.java))
        }
    }
}
