package com.pharrlabs.murmur

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioGroup

/** Shown when a widget is placed (and when reconfigured): choose agent, colour and, for Status, which cards. */
class WidgetConfigActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val kind = Widgets.kindOf(this, id) ?: Widgets.ASK
        val title = when (kind) { Widgets.ASK -> "Ask widget"; Widgets.LAST -> "Last reply widget"; Widgets.BRIEF -> "Brief widget"; Widgets.STAT -> "One-number widget"; else -> "Status widget" }
        val wantsAgent = kind == Widgets.ASK || kind == Widgets.LAST

        var agents: RadioGroup? = null
        val cardBoxes = ArrayList<CheckBox>()
        val colours = Ui.radios(this, listOf("cream" to "Cream", "violet" to "Violet", "dark" to "Dark"), Widgets.themeName(this, id))
        val savedAgent = Widgets.agent(this, id)

        Ui.page(this, title) { col ->
            if (wantsAgent) {
                col.addView(Ui.section(this, "Agent"))
                agents = Ui.radios(this, listOf(Cfg.profile(this) to Cfg.assistant(this) + " (default)"), savedAgent)
                col.addView(agents)
            } else if (kind == Widgets.STATUS) {
                col.addView(Ui.section(this, "Cards to show"))
                val mask = Widgets.cardMask(this, id)
                val labels = listOf("Pending approvals", "Overdue tasks (needs task lists)", "Tasks due today (needs task lists)", "Agents working now")
                for (i in labels.indices) {
                    val cb = CheckBox(this).apply { text = labels[i]; isChecked = mask and (1 shl i) != 0; textSize = 15f; setTextColor(Ui.INK) }
                    cardBoxes.add(cb); col.addView(cb)
                }
            }
            col.addView(Ui.section(this, "Colour"))
            col.addView(colours)
            col.addView(Ui.button(this, "Save") {
                val agent = agents?.let { Ui.checkedTag(it) } ?: Cfg.profile(this)
                var mask = 0
                cardBoxes.forEachIndexed { i, cb -> if (cb.isChecked) mask = mask or (1 shl i) }
                if (kind == Widgets.STATUS && mask == 0) mask = 15
                Widgets.save(this, id, agent, Ui.checkedTag(colours) ?: "cream", if (kind == Widgets.STATUS) mask else 15)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                Thread {
                    Widgets.render(this, id, fetch = false)
                    runOnUiThread { finish() }
                    Widgets.render(this, id, fetch = true)
                }.start()
            })
        }

        if (wantsAgent) loadAgents(this) { list ->
            agents?.let { g -> Ui.setRadios(this, g, list.map { it.id to it.name }, savedAgent) }
        }
    }
}
