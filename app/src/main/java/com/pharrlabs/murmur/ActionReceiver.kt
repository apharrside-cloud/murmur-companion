package com.pharrlabs.murmur

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.net.URLEncoder

/** The buttons on notifications: answer an approval or question, or snooze it, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val nid = i.getIntExtra("nid", 0)
        val tag = i.getStringExtra("tag") ?: ""
        val note = try { JSONObject(i.getStringExtra("note") ?: "{}") } catch (e: Exception) { JSONObject() }
        when (i.action) {
            ACT_CLEAR_ROUTINES -> { Notifier.clearRoutines(c); return }
            ACT_ASK, ACT_SNOOZE -> {
                val pending = goAsync()
                val choice = i.getStringExtra("choice") ?: ""
                val snooze = i.action == ACT_SNOOZE
                Thread {
                    try {
                        val enc = URLEncoder.encode(tag, "UTF-8")
                        val r = if (snooze) Relay.post(c, "/api/snooze?tag=$enc&min=15", readTimeout = 8000)
                                else Relay.post(c, "/api/respond?tag=$enc&choice=" + URLEncoder.encode(choice, "UTF-8"), readTimeout = 9000)
                        val nm = c.getSystemService(NotificationManager::class.java)
                        when {
                            r.optBoolean("ok") -> {
                                Notifier.receipt(c, nid, if (snooze) "Reminding you in 15 min" else when (choice) {
                                    "once" -> "Approved \u2713"
                                    "deny" -> "Rejected"
                                    else -> "Sent: " + r.optString("answered", "answer")
                                })
                                if (snooze) nm.cancel(nid)
                            }
                            r.optString("error") in setOf("not_waiting", "no_approval", "no_such_choice", "not_open") -> Notifier.receipt(c, nid, "Already handled")
                            else -> Notifier.show(c, note, "Didn't go through (" + r.optString("error", "unknown") + "). Open to answer.")
                        }
                    } catch (e: Exception) {
                        Notifier.show(c, note, "Couldn't reach Murmur. Check your connection, or open to answer.")
                    } finally {
                        Widgets.refreshAll(c)
                        ApprovalsTile.requestRefresh(c)
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    companion object {
        const val ACT_ASK = "com.pharrlabs.murmur.ACT_ASK"
        const val ACT_SNOOZE = "com.pharrlabs.murmur.ACT_SNOOZE"
        const val ACT_CLEAR_ROUTINES = "com.pharrlabs.murmur.ACT_CLEAR_ROUTINES"
    }
}
