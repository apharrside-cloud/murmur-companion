package com.pharrlabs.murmur

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.appwidget.AppWidgetManager
import android.view.ViewGroup

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

object Ui {
    val INK = Color.parseColor("#1A1A1F")
    val VIOLET = Color.parseColor("#7C6CF0")

    fun page(a: Activity, title: String, build: (LinearLayout) -> Unit) {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(28), a.dp(20), a.dp(28))
        }
        col.addView(text(a, title, 22f, bold = true))
        build(col)
        val sv = ScrollView(a).apply { addView(col); isFillViewport = true }
        a.setContentView(sv)
        sv.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            android.view.WindowInsets.CONSUMED
        }
    }

    fun text(c: Context, s: String, size: Float = 15f, bold: Boolean = false, color: Int = INK): TextView =
        TextView(c).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, c.dp(8), 0, c.dp(4))
        }

    fun section(c: Context, s: String) = text(c, s, 13f, bold = true, color = VIOLET).apply { setPadding(0, c.dp(20), 0, c.dp(4)) }

    fun button(c: Context, label: String, onClick: () -> Unit) = Button(c).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(8) }
    }

    /** Radio list; returns the group so callers can add choices later and read the checked tag. */
    fun radios(c: Context, items: List<Pair<String, String>>, checked: String): RadioGroup {
        val g = RadioGroup(c)
        setRadios(c, g, items, checked)
        return g
    }

    fun setRadios(c: Context, g: RadioGroup, items: List<Pair<String, String>>, checked: String) {
        g.removeAllViews()
        for ((tag, label) in items) {
            g.addView(RadioButton(c).apply { id = View.generateViewId(); text = label; this.tag = tag; textSize = 15f; setTextColor(INK) })
        }
        val i = items.indexOfFirst { it.first == checked }.let { if (it < 0) 0 else it }
        if (g.childCount > 0) g.check(g.getChildAt(i).id)
    }

    fun checkedTag(g: RadioGroup): String? = g.findViewById<RadioButton>(g.checkedRadioButtonId)?.tag as String?
}

/** Pick an agent, shared by widget setup and the tile preferences. */
fun loadAgents(a: Activity, onLoaded: (List<Agent>) -> Unit) {
    Thread {
        val list = Relay.agents(a)
        a.runOnUiThread { onLoaded(list) }
    }.start()
}
