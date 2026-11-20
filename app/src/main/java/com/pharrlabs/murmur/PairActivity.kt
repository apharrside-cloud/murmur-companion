package com.pharrlabs.murmur

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * First-run screen. The app does nothing until it is paired with a Murmur Companion relay that is running next to
 * the user's own Murmur, so this screen says so plainly and links the setup guide.
 */
class PairActivity : Activity() {
    private lateinit var code: EditText
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Ui.page(this, "Connect to your Murmur") { col ->
            col.addView(Ui.text(this, "Murmur Companion is an unofficial phone companion for Murmur, the web interface for Hermes Agent. It does not include Murmur or Hermes, and it does nothing until Murmur and the companion relay are running on your own computer.", 15f))
            col.addView(Ui.button(this, "Open the setup guide") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE))) })
            col.addView(Ui.section(this, "Pair"))
            col.addView(Ui.text(this, "On the computer that runs the relay, open http://127.0.0.1:3010/pair (or run: node relay.mjs pair). Copy the pairing code, then paste it here.", 14f))
            code = EditText(this).apply {
                hint = "Pairing code"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3; maxLines = 6; textSize = 12f
            }
            col.addView(code)
            col.addView(Ui.button(this, "Paste from clipboard") {
                val t = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                if (t.isNotBlank()) code.setText(t)
            })
            status = Ui.text(this, "", 14f)
            col.addView(Ui.button(this, "Pair") { pair() })
            col.addView(status)
        }
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun handle(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "murmurcompanion") {
            code.setText(d.toString())
            val p = Pairing.parse(d.toString())
            status.text = if (p != null) "Link received for ${p.relay}. Check that this is your own relay, then tap Pair." else "That link is not a pairing link."
        }
    }

    private fun pair() {
        val p = Pairing.parse(code.text.toString())
        if (p == null) { status.text = "That is not a pairing code. Copy the whole code from the pairing page."; return }
        status.text = "Checking ${p.relay} ..."
        Thread {
            val msg = check(p)
            runOnUiThread {
                if (msg == null) {
                    Pairing.apply(this, p)
                    PushService.start(this)
                    Thread { try { Relay.refreshCaps(this) } catch (e: Exception) { } ; Widgets.refreshAll(this) }.start()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                } else status.text = msg
            }
        }.start()
    }

    /** null = the relay answered and accepted the code, otherwise what to tell the user. */
    private fun check(p: PairInfo): String? = try {
        val conn = URL(p.relay + "/api/capabilities").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        conn.setRequestProperty("Authorization", "Bearer " + p.token)
        try {
            when (conn.responseCode) {
                200 -> null
                401 -> "The relay refused this code. If you reset the relay, copy a fresh code from its pairing page."
                else -> "The relay answered with HTTP ${conn.responseCode}. Is that address really the relay? Run: node relay.mjs doctor"
            }
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        "Could not reach ${p.relay}. Is the relay running, and is this phone on the network or VPN that address needs? Run node relay.mjs doctor on the computer."
    }

    companion object { const val GUIDE = "https://murmur.pharrlabs.com/setup" }
}
