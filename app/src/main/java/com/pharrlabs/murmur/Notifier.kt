package com.pharrlabs.murmur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import org.json.JSONArray
import org.json.JSONObject

object Notifier {
    const val CH_REPLIES = "replies"
    const val CH_ASKS = "asks"
    const val CH_STATUS = "status"
    const val CH_ROUTINES = "routines"
    const val ROUTINE_ID = 9001

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REPLIES, c.getString(R.string.channel_replies), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ASKS, c.getString(R.string.channel_asks), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, c.getString(R.string.channel_status), NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ROUTINES, c.getString(R.string.channel_routines), NotificationManager.IMPORTANCE_LOW))
    }

    /** The URL a note opens: its chat, on the Hermes it came from. */
    fun urlFor(c: Context, href: String, hermes: String): String {
        var u = Cfg.murmurUrl(c) + (if (href.startsWith("/")) href else "/$href")
        if (hermes.isNotEmpty() && !u.contains("hermes=")) u += (if (u.contains("?")) "&" else "?") + "hermes=" + hermes
        return u
    }

    fun idFor(n: JSONObject): Int {
        val tag = n.optString("tag", "")
        return if (tag.isNotEmpty()) tag.hashCode() else n.optInt("id")
    }

    private fun action(c: Context, nid: Int, slot: Int, label: String, act: String, n: JSONObject, extra: (Intent) -> Unit): Notification.Action {
        val i = Intent(c, ActionReceiver::class.java).setAction(act)
            .putExtra("nid", nid).putExtra("tag", n.optString("tag")).putExtra("note", n.toString())
        extra(i)
        val pi = PendingIntent.getBroadcast(c, nid * 8 + slot, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat_murmur), label, pi).build()
    }

    /** One-tap answers for an agent's ask: approve/reject (+ snooze), or the choices of a short question. */
    private fun answers(c: Context, nid: Int, n: JSONObject): List<Notification.Action> {
        val out = ArrayList<Notification.Action>()
        when (n.optString("kind")) {
            "approval" -> {
                out.add(action(c, nid, 0, "Approve", ActionReceiver.ACT_ASK, n) { it.putExtra("choice", "once") })
                out.add(action(c, nid, 1, "Reject", ActionReceiver.ACT_ASK, n) { it.putExtra("choice", "deny") })
                out.add(action(c, nid, 2, "Later", ActionReceiver.ACT_SNOOZE, n) { })
            }
            "question" -> {
                val ch = n.optJSONArray("choices") ?: JSONArray()
                for (i in 0 until minOf(ch.length(), 3)) {
                    out.add(action(c, nid, i, ch.getString(i), ActionReceiver.ACT_ASK, n) { it.putExtra("choice", "pick:$i") })
                }
            }
        }
        return out
    }

    fun show(c: Context, n: JSONObject, problem: String = "") {
        if (n.optBoolean("run") && problem.isEmpty()) { routine(c, n); return }
        val nm = c.getSystemService(NotificationManager::class.java)
        val id = idFor(n)
        val open = Intent(c, MainActivity::class.java)
            .setAction("com.pharrlabs.murmur.OPEN")
            .putExtra("url", urlFor(c, n.optString("href", "/"), n.optString("hermes", "")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ask = n.optBoolean("ask")
        val body = n.optString("body") + if (problem.isNotEmpty()) "\n\u26a0 $problem" else ""
        val nb = Notification.Builder(c, if (ask) CH_ASKS else CH_REPLIES)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setContentTitle(n.optString("title", "Murmur"))
            .setContentText(body.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setWhen(n.optLong("at", System.currentTimeMillis()))
            .setShowWhen(true)
        if (ask) {
            nb.setCategory(Notification.CATEGORY_MESSAGE)
            for (a in answers(c, id, n)) nb.addAction(a)
        }
        nm.notify(id, nb.build())
    }

    /** A short-lived receipt in place of the notification that was just answered. */
    fun receipt(c: Context, id: Int, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.notify(id, Notification.Builder(c, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setContentTitle(text)
            .setTimeoutAfter(4000)
            .build())
    }

    /** Routine runs (scheduled jobs finishing) fold into one quiet notification instead of one buzz each. */
    private fun routine(c: Context, n: JSONObject) {
        val p = Cfg.prefs(c)
        val titles = try { JSONArray(p.getString("routine_titles", "[]")) } catch (e: Exception) { JSONArray() }
        titles.put(n.optString("title", "Routine"))
        val keep = JSONArray()
        for (i in maxOf(0, titles.length() - 6) until titles.length()) keep.put(titles.get(i))
        val count = p.getInt("routine_count", 0) + 1
        p.edit().putString("routine_titles", keep.toString()).putInt("routine_count", count).apply()
        val inbox = Notification.InboxStyle()
        for (i in keep.length() - 1 downTo 0) inbox.addLine(keep.getString(i))
        val open = PendingIntent.getActivity(c, ROUTINE_ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val clear = PendingIntent.getBroadcast(c, ROUTINE_ID, Intent(c, ActionReceiver::class.java).setAction(ActionReceiver.ACT_CLEAR_ROUTINES), PendingIntent.FLAG_IMMUTABLE)
        c.getSystemService(NotificationManager::class.java).notify(ROUTINE_ID, Notification.Builder(c, CH_ROUTINES)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setContentTitle(if (count == 1) "A routine finished" else "$count routines finished")
            .setContentText(keep.getString(keep.length() - 1))
            .setStyle(inbox)
            .setContentIntent(open)
            .setDeleteIntent(clear)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    fun clearRoutines(c: Context) {
        Cfg.prefs(c).edit().remove("routine_titles").remove("routine_count").apply()
    }
}
