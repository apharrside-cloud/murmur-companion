package com.pharrlabs.murmur

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What we're in the middle of: agent approvals and questions waiting right now, then chats waiting on your go-ahead, then work still open. */
class NeedsActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    /** Cards showing "Marked done · Undo": a refresh would wipe them, so it waits. */
    private var undoRows = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (undoRows == 0) load()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.page(this, "Needs you") { col ->
            summary = Ui.text(this, "", 15f, bold = true)
            col.addView(summary)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun small(label: String, go: () -> Unit) = Button(this).apply {
                text = label; isAllCaps = false; setOnClickListener { go() }
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
            }
            row.addView(small("Capture") { startActivity(Intent(this, CaptureActivity::class.java)) })
            row.addView(small("Open Murmur") { startActivity(Intent(this, MainActivity::class.java)) })
            row.addView(small("Refresh") { load() })
            col.addView(row)
            status = Ui.text(this, "", 12f, color = Color.parseColor("#80000000"))
            col.addView(status)
            list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(list)
        }
        Relay.cachedNeeds(this)?.let { render(it) }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun load() {
        Thread {
            val n = try { Relay.needs(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing || undoRows > 0) return@runOnUiThread
                if (n != null) {
                    render(n)
                    status.text = "Updated " + SimpleDateFormat("h:mm:ss a", Locale.US).format(Date())
                } else {
                    status.text = "Can't reach Murmur (check your connection). Showing the last list."
                }
            }
        }.start()
    }

    private fun render(n: Needs) {
        val bits = ArrayList<String>()
        if (n.asks > 0) bits.add("${n.asks} waiting on you")
        if (n.waiting > 0) bits.add("${n.waiting} need your go-ahead")
        if (n.open > 0) bits.add("${n.open} still open")
        summary.text = if (bits.isEmpty()) "Nothing open" else bits.joinToString(" \u00b7 ")
        list.removeAllViews()
        for (it in n.items) list.addView(card(it))
        if (n.more > 0) list.addView(Ui.text(this, "+${n.more} older chats not shown", 13f, color = Color.parseColor("#80000000")))
    }

    private fun card(item: Item): View {
        val kindWord = when (item.kind) { "approval" -> "APPROVAL"; "question" -> "QUESTION"; "secret" -> "NEEDS A LOGIN"; "group" -> "GROUP CHAT"; "waiting" -> "WAITING ON YOU"; else -> "STILL OPEN" }
        val hot = item.kind == "approval" || item.kind == "waiting"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#E5DCC3")) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        card.addView(Ui.text(this, kindWord, 11f, bold = true, color = if (hot) Color.parseColor("#C0392B") else Ui.VIOLET).apply { setPadding(0, 0, 0, 0) })
        card.addView(Ui.text(this, item.title, 15f, bold = true))
        if (item.sub.isNotEmpty()) card.addView(Ui.text(this, item.sub, 13f, color = Color.parseColor("#80000000")).apply { setPadding(0, 0, 0, dp(6)) })
        val err = Ui.text(this, "", 12f, color = Color.parseColor("#C0392B")).apply { visibility = View.GONE }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<Button>()
        for ((idx, a) in item.actions.withIndex()) {
            val btn = Button(this).apply {
                text = a.second; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) }
                if (idx == 0) { setBackgroundResource(R.drawable.bg_btn); setTextColor(Color.WHITE) }
            }
            btn.setOnClickListener { act(item, a.first, card, buttons, err) }
            buttons.add(btn); row.addView(btn)
        }
        card.addView(row)
        card.addView(err)
        return card
    }

    private fun act(item: Item, key: String, card: View, buttons: List<Button>, err: TextView) {
        if (key == "open") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("url", Notifier.urlFor(this, item.href, "")))
            return
        }
        buttons.forEach { it.isEnabled = false }
        err.visibility = View.GONE
        Thread {
            val r: JSONObject? = try { Relay.post(this, "/api/act", JSONObject().put("id", item.id).put("action", key)) } catch (e: Exception) { null }
            runOnUiThread {
                if (r?.optBoolean("ok") == true) {
                    if (item.id.startsWith("thread:") && (key == "done" || key == "later")) {
                        showUndo(item, key, card)
                        Widgets.refreshAll(this)
                        return@runOnUiThread
                    }
                    list.removeView(card)
                    Widgets.refreshAll(this)
                    ApprovalsTile.requestRefresh(this)
                    load()
                } else {
                    buttons.forEach { it.isEnabled = true }
                    val e = r?.optString("error")
                    err.text = when {
                        r == null -> "Couldn't reach Murmur."
                        e == "recurring" -> "This one repeats. Use Done."
                        e == "not_waiting" || e == "no_approval" || e == "no_such_choice" -> "Already handled."
                        e.isNullOrEmpty() -> "Didn't go through."
                        else -> "Didn't go through ($e)."
                    }
                    err.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    /** The card turns into a receipt with Undo for 6 s, then goes and the list refreshes. */
    private fun showUndo(item: Item, key: String, card: View) {
        val idx = list.indexOfChild(card)
        if (idx < 0) return
        undoRows++
        val label = if (key == "done") "Marked done" else "Back tomorrow morning"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F3EEDD")); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        }
        val msg = Ui.text(this, "$label · ${item.title}", 13f).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f); maxLines = 2 }
        val undo = Button(this).apply { text = "Undo"; isAllCaps = false }
        row.addView(msg); row.addView(undo)
        list.removeViewAt(idx)
        list.addView(row, idx)
        var settled = false
        fun settle() {
            if (settled) return
            settled = true
            undoRows--
            if (!isFinishing) { list.removeView(row); load(); ApprovalsTile.requestRefresh(this) }
        }
        val expire = Runnable { settle() }
        handler.postDelayed(expire, 6000)
        undo.setOnClickListener {
            if (settled) return@setOnClickListener
            undo.isEnabled = false
            handler.removeCallbacks(expire)
            Thread {
                try { Relay.post(this, "/api/act", JSONObject().put("id", item.id).put("action", "undo")) } catch (e: Exception) { }
                runOnUiThread { settle(); Widgets.refreshAll(this) }
            }.start()
        }
    }
}
