package com.pharrlabs.murmur

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Shared: open an activity from a tile and collapse the shade. */
private fun TileService.launch(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (android.os.Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        @Suppress("DEPRECATION") startActivityAndCollapse(intent)
    }
}

private fun TileService.paint(label: String, sub: String?, state: Int) {
    val t = qsTile ?: return
    t.label = label
    t.subtitle = sub
    t.state = state
    t.updateTile()
}

class NewChatTile : TileService() {
    override fun onStartListening() {
        val agent = Cfg.tileAgent(this)
        paint("Murmur chat", agent.replaceFirstChar { it.uppercase() }, Tile.STATE_INACTIVE)
        Thread { val n = Relay.agentName(this, agent); android.os.Handler(mainLooper).post { paint("Murmur chat", n, Tile.STATE_INACTIVE) } }.start()
    }

    override fun onClick() = launch(Intent(this, MainActivity::class.java).putExtra("agent", Cfg.tileAgent(this)))
}

class VoiceTile : TileService() {
    override fun onStartListening() = paint("Murmur voice", "Ask by voice", Tile.STATE_INACTIVE)

    override fun onClick() = launch(Intent(this, QuickAskActivity::class.java).putExtra("agent", Cfg.tileAgent(this)).putExtra("voice", true))
}

class CaptureTile : TileService() {
    override fun onStartListening() {
        val q = Capture.pending(this)
        paint("Capture", if (q > 0) "$q queued" else "Voice to task", Tile.STATE_INACTIVE)
    }

    override fun onClick() = launch(Intent(this, CaptureActivity::class.java))
}

class ApprovalsTile : TileService() {
    override fun onStartListening() {
        paint("Approvals", "Checking...", Tile.STATE_INACTIVE)
        Thread {
            val handler = android.os.Handler(mainLooper)
            try {
                val n = Relay.cards(this).approvals
                handler.post { paint("Approvals", if (n == 0) "None waiting" else "$n waiting", if (n == 0) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE) }
            } catch (e: Exception) {
                handler.post { paint("Approvals", "Offline", Tile.STATE_UNAVAILABLE) }
            }
        }.start()
    }

    override fun onClick() = launch(Intent(this, NeedsActivity::class.java))

    companion object {
        fun requestRefresh(c: Context) {
            try { requestListeningState(c, ComponentName(c, ApprovalsTile::class.java)) } catch (e: Exception) { /* tile not added */ }
        }
    }
}
