package com.pharrlabs.murmur

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                for (id in ids) Widgets.render(c, id, fetch = false) // instant: cached data
                for (id in ids) Widgets.render(c, id, fetch = true)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDeleted(c: Context, ids: IntArray) = Widgets.forget(c, ids)
}

class AskWidget : BaseWidget()
class LastReplyWidget : BaseWidget()
class StatusWidget : BaseWidget()
class BriefWidget : BaseWidget()
class StatWidget : BaseWidget()
class CaptureWidget : BaseWidget()

data class Theme(val bg: Int, val chip: Int, val text: Int, val sub: Int, val accent: Int)

object Widgets {
    const val ASK = "ask"
    const val LAST = "last"
    const val STATUS = "status"
    const val BRIEF = "brief"
    const val STAT = "stat"
    const val CAPTURE = "capture"
    val THEMES = linkedMapOf(
        "cream" to Theme(R.drawable.bg_w_cream, R.drawable.bg_chip, Color.parseColor("#1A1A1F"), Color.parseColor("#80000000"), Color.parseColor("#7C6CF0")),
        "violet" to Theme(R.drawable.bg_w_violet, R.drawable.bg_chip_light, Color.WHITE, Color.parseColor("#CCFFFFFF"), Color.WHITE),
        "dark" to Theme(R.drawable.bg_w_dark, R.drawable.bg_chip_light, Color.parseColor("#F2F2F5"), Color.parseColor("#99FFFFFF"), Color.parseColor("#B3A8FF")),
    )
    val CARD_KEYS = listOf("approvals", "overdue", "today", "working")
    private val RED = Color.parseColor("#C0392B")
    private val GREEN = Color.parseColor("#1E8E4E")

    private fun p(c: Context) = Cfg.prefs(c)
    fun agent(c: Context, id: Int) = p(c).getString("w${id}_agent", null) ?: Cfg.profile(c)
    fun themeName(c: Context, id: Int) = p(c).getString("w${id}_theme", "cream") ?: "cream"
    fun cardMask(c: Context, id: Int) = p(c).getInt("w${id}_cards", 15)

    fun save(c: Context, id: Int, agent: String, theme: String, cards: Int) {
        p(c).edit().putString("w${id}_agent", agent).putString("w${id}_theme", theme).putInt("w${id}_cards", cards).apply()
    }

    fun forget(c: Context, ids: IntArray) {
        val e = p(c).edit()
        for (id in ids) for (k in listOf("agent", "theme", "cards", "cache")) e.remove("w${id}_$k")
        e.apply()
    }

    fun kindOf(c: Context, id: Int): String? {
        val cls = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: return null
        return when (cls) {
            AskWidget::class.java.name -> ASK
            LastReplyWidget::class.java.name -> LAST
            StatusWidget::class.java.name -> STATUS
            BriefWidget::class.java.name -> BRIEF
            StatWidget::class.java.name -> STAT
            CaptureWidget::class.java.name -> CAPTURE
            else -> null
        }
    }

    /** Refresh every placed widget that shows live data (called when a push arrives, after actions, and every few minutes). */
    fun refreshAll(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        Thread {
            if (Cfg.paired(c)) try { Relay.refreshCaps(c) } catch (e: Exception) { /* offline: keep the last answer */ }
            for (cls in listOf(LastReplyWidget::class.java, StatusWidget::class.java, BriefWidget::class.java, StatWidget::class.java, CaptureWidget::class.java)) {
                for (id in mgr.getAppWidgetIds(ComponentName(c, cls))) render(c, id, fetch = true)
            }
        }.start()
    }

    private fun open(c: Context, req: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(c, req, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun refreshIntent(c: Context, cls: Class<*>, id: Int): PendingIntent {
        val i = Intent(c, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
        return PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun render(c: Context, id: Int, fetch: Boolean) {
        val kind = kindOf(c, id) ?: return
        val mgr = AppWidgetManager.getInstance(c)
        val th = THEMES[themeName(c, id)] ?: THEMES.getValue("cream")
        val views = when (kind) {
            ASK -> ask(c, id, th)
            LAST -> last(c, id, th, fetch)
            BRIEF -> brief(c, id, th, fetch)
            STAT -> stat(c, id, th, fetch)
            CAPTURE -> capture(c, id)
            else -> status(c, id, th, fetch)
        }
        try { mgr.updateAppWidget(id, views) } catch (e: Exception) { /* widget removed meanwhile */ }
    }

    private fun ask(c: Context, id: Int, th: Theme): RemoteViews {
        val agent = agent(c, id)
        val name = Relay.agentName(c, agent)
        val v = RemoteViews(c.packageName, R.layout.widget_ask)
        v.setInt(R.id.root, "setBackgroundResource", th.bg)
        v.setInt(R.id.ask_tap, "setBackgroundResource", th.chip)
        v.setTextViewText(R.id.ask_label, "Ask $name...")
        v.setTextColor(R.id.ask_label, th.text)
        v.setOnClickPendingIntent(R.id.ask_tap, open(c, id * 10 + 1, Intent(c, QuickAskActivity::class.java).putExtra("agent", agent)))
        v.setOnClickPendingIntent(R.id.ask_mic, open(c, id * 10 + 2, Intent(c, QuickAskActivity::class.java).putExtra("agent", agent).putExtra("voice", true)))
        return v
    }

    private fun last(c: Context, id: Int, th: Theme, fetch: Boolean): RemoteViews {
        val agent = agent(c, id)
        val key = "w${id}_cache"
        var text = ""
        var at = 0L
        var href = ""
        var stale = false
        if (fetch) {
            try {
                val l = Relay.last(c, agent)
                text = l.text; at = l.at; href = l.href
                p(c).edit().putString(key, JSONObject().put("text", text).put("at", at).put("href", href).toString()).apply()
            } catch (e: Exception) { stale = true }
        }
        if (!fetch || stale) {
            p(c).getString(key, null)?.let {
                val j = JSONObject(it); text = j.optString("text"); at = j.optLong("at"); href = j.optString("href")
            }
        }
        val v = RemoteViews(c.packageName, R.layout.widget_last)
        v.setInt(R.id.root, "setBackgroundResource", th.bg)
        v.setTextViewText(R.id.last_agent, Relay.agentName(c, agent))
        v.setTextColor(R.id.last_agent, th.accent)
        v.setTextViewText(R.id.last_when, (if (stale) "offline " else "") + ago(at))
        v.setTextColor(R.id.last_when, th.sub)
        v.setTextViewText(R.id.last_text, if (text.isEmpty()) (if (stale) "Can't reach Murmur. Check your connection." else "No replies yet") else text)
        v.setTextColor(R.id.last_text, th.text)
        val url = if (href.isNotEmpty()) Cfg.murmurUrl(c) + href else Cfg.murmurUrl(c) + "/chat/" + agent + "/new"
        v.setOnClickPendingIntent(R.id.root, open(c, id * 10 + 3, Intent(c, MainActivity::class.java).putExtra("url", url)))
        v.setOnClickPendingIntent(R.id.last_refresh, refreshIntent(c, LastReplyWidget::class.java, id))
        return v
    }

    private fun status(c: Context, id: Int, th: Theme, fetch: Boolean): RemoteViews {
        val key = "w${id}_cache"
        var cd: Cards? = null
        if (fetch) {
            try {
                cd = Relay.cards(c)
                p(c).edit().putString(key, JSONObject().put("a", cd.approvals).put("o", cd.tasksOverdue).put("t", cd.tasksToday).put("w", cd.working).toString()).apply()
            } catch (e: Exception) { /* fall to cache */ }
        }
        var nums = intArrayOf(-1, -1, -1, -1)
        if (cd != null) nums = intArrayOf(cd.approvals, cd.tasksOverdue, cd.tasksToday, cd.working)
        else p(c).getString(key, null)?.let {
            val j = JSONObject(it); nums = intArrayOf(j.optInt("a"), j.optInt("o"), j.optInt("t"), j.optInt("w"))
        }
        val mask = (cardMask(c, id) and (if (Cfg.caps(c).tasklists) 15 else 9)).let { if (it == 0) 9 else it }
        val v = RemoteViews(c.packageName, R.layout.widget_status)
        v.setInt(R.id.root, "setBackgroundResource", th.bg)
        val cardIds = intArrayOf(R.id.card_approvals, R.id.card_overdue, R.id.card_today, R.id.card_working)
        val numIds = intArrayOf(R.id.num_approvals, R.id.num_overdue, R.id.num_today, R.id.num_working)
        val lblIds = intArrayOf(R.id.lbl_approvals, R.id.lbl_overdue, R.id.lbl_today, R.id.lbl_working)
        for (i in 0..3) {
            val on = mask and (1 shl i) != 0
            v.setViewVisibility(cardIds[i], if (on) View.VISIBLE else View.GONE)
            v.setInt(cardIds[i], "setBackgroundResource", th.chip)
            v.setTextViewText(numIds[i], if (nums[i] < 0) "-" else nums[i].toString())
            v.setTextColor(numIds[i], th.text)
            v.setTextColor(lblIds[i], th.text)
        }
        v.setViewVisibility(R.id.row_a, if (mask and 3 != 0) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.row_b, if (mask and 12 != 0) View.VISIBLE else View.GONE)
        // Tap anywhere: the Needs you list, where each item can be dealt with.
        v.setOnClickPendingIntent(R.id.root, open(c, id * 10 + 4, Intent(c, NeedsActivity::class.java)))
        return v
    }

    /** The morning glance: what needs you, the first few things, and the one number you chose (if any). */
    private fun brief(c: Context, id: Int, th: Theme, fetch: Boolean): RemoteViews {
        var needs: Needs? = Relay.cachedNeeds(c)
        var trade: Stat? = if (Cfg.caps(c).statLabel != null) Relay.cachedStat(c) else null
        var stale = false
        if (fetch) {
            try { needs = Relay.needs(c) } catch (e: Exception) { stale = true }
            try { trade = Relay.stat(c) } catch (e: Exception) { stale = true }
        }
        val v = RemoteViews(c.packageName, R.layout.widget_brief)
        v.setInt(R.id.root, "setBackgroundResource", th.bg)
        v.setTextViewText(R.id.brief_date, SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date()))
        v.setTextColor(R.id.brief_date, th.accent)
        v.setTextViewText(R.id.brief_when, if (stale) "offline" else SimpleDateFormat("h:mm a", Locale.US).format(Date()))
        v.setTextColor(R.id.brief_when, th.sub)
        val n = needs
        val sum = when {
            n == null -> "Can't reach Murmur"
            n.asks + n.waiting + n.open == 0 -> "Nothing open"
            else -> listOfNotNull(
                if (n.asks > 0) "${n.asks} waiting on you" else null,
                if (n.waiting > 0) "${n.waiting} need a go-ahead" else null,
                if (n.open > 0) "${n.open} open" else null,
            ).joinToString(" \u00b7 ")
        }
        v.setTextViewText(R.id.brief_sum, sum)
        v.setTextColor(R.id.brief_sum, th.text)
        val lines = intArrayOf(R.id.brief_i1, R.id.brief_i2, R.id.brief_i3)
        for (i in 0..2) {
            val it = n?.items?.getOrNull(i)
            v.setViewVisibility(lines[i], if (it != null) View.VISIBLE else View.GONE)
            if (it != null) v.setTextViewText(lines[i], "\u2022 " + it.title)
            v.setTextColor(lines[i], th.text)
        }
        val t = trade
        if (t == null) {
            v.setViewVisibility(R.id.brief_trade, View.GONE)
        } else {
            v.setViewVisibility(R.id.brief_trade, View.VISIBLE)
            val arrow = when (t.trend) { "up" -> "\u25B2 "; "down" -> "\u25BC "; else -> "" }
            v.setTextViewText(R.id.brief_trade, if (t.ok) "${t.label} ${t.headline}  $arrow${t.delta}".trim() else "\u26A0 ${t.label}: " + t.problems.joinToString(", "))
            v.setTextColor(R.id.brief_trade, if (t.ok) th.sub else RED)
        }
        v.setOnClickPendingIntent(R.id.root, open(c, id * 10 + 5, Intent(c, NeedsActivity::class.java)))
        v.setOnClickPendingIntent(R.id.brief_refresh, refreshIntent(c, BriefWidget::class.java, id))
        return v
    }

    private fun stat(c: Context, id: Int, th: Theme, fetch: Boolean): RemoteViews {
        var t: Stat? = Relay.cachedStat(c)
        var stale = false
        var none = false
        if (fetch) { try { t = Relay.stat(c); if (t == null) none = true } catch (e: Exception) { stale = true } }
        if (!fetch && Cfg.caps(c).statLabel == null) t = null
        val v = RemoteViews(c.packageName, R.layout.widget_stat)
        val bad = t != null && !t.ok
        v.setInt(R.id.root, "setBackgroundResource", if (bad) R.drawable.bg_w_red else th.bg)
        val ink = if (bad) Color.parseColor("#1A1A1F") else th.text
        val sub = if (bad) Color.parseColor("#80000000") else th.sub
        val label = t?.label ?: Cfg.caps(c).statLabel ?: "One number"
        v.setTextViewText(R.id.tr_label, if (stale) "$label (offline)" else label)
        v.setTextColor(R.id.tr_label, sub)
        if (t == null) {
            v.setTextViewText(R.id.tr_total, "-")
            v.setTextViewText(R.id.tr_day, "")
            v.setTextViewText(R.id.tr_note, if (none || !Cfg.paired(c)) "Not set up. See the setup guide (stat)." else "Can't reach Murmur")
        } else {
            v.setTextViewText(R.id.tr_total, t.headline)
            val up = t.trend == "up"
            v.setTextViewText(R.id.tr_day, (if (t.trend == "up") "\u25B2 " else if (t.trend == "down") "\u25BC " else "") + t.delta)
            v.setTextColor(R.id.tr_day, if (t.trend == "flat") sub else if (up) GREEN else RED)
            v.setTextViewText(R.id.tr_note, if (bad) "\u26A0 " + t.problems.joinToString(", ") else t.note)
        }
        v.setTextColor(R.id.tr_total, ink)
        v.setTextColor(R.id.tr_note, if (bad) RED else sub)
        v.setOnClickPendingIntent(R.id.root, refreshIntent(c, StatWidget::class.java, id))
        return v
    }

    private fun capture(c: Context, id: Int): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_capture)
        val q = Capture.pending(c)
        v.setTextViewText(R.id.cap_label, if (q > 0) "Capture \u00b7 $q queued" else "Capture")
        v.setOnClickPendingIntent(R.id.root, open(c, id * 10 + 6, Intent(c, CaptureActivity::class.java)))
        return v
    }
}
