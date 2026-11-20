package com.pharrlabs.murmur

import android.app.Activity
import android.os.Bundle
import android.widget.RadioGroup

/** Long-press a Murmur tile: choose which agent the chat and voice tiles talk to. */
class TileConfigActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val saved = Cfg.tileAgent(this)
        var group: RadioGroup? = null
        Ui.page(this, "Quick Settings tiles") { col ->
            col.addView(Ui.text(this, "The chat and voice tiles go to this agent. Approvals shows how many questions are waiting.", 14f))
            col.addView(Ui.section(this, "Agent"))
            group = Ui.radios(this, listOf(Cfg.profile(this) to Cfg.assistant(this) + " (default)"), saved)
            col.addView(group)
            col.addView(Ui.button(this, "Save") {
                Cfg.prefs(this).edit().putString("tileAgent", group?.let { Ui.checkedTag(it) } ?: Cfg.profile(this)).apply()
                finish()
            })
        }
        loadAgents(this) { list -> group?.let { Ui.setRadios(this, it, list.map { a -> a.id to a.name }, saved) } }
    }
}
