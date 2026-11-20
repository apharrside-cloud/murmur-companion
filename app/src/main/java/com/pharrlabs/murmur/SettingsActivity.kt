package com.pharrlabs.murmur

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        lateinit var murmur: EditText
        lateinit var relay: EditText
        lateinit var token: EditText
        Ui.page(this, "Companion settings") { col ->
            status = Ui.text(this, "", 15f)
            col.addView(Ui.section(this, "Connection"))
            col.addView(status)
            col.addView(Ui.button(this, "Send test notification") {
                Thread {
                    val ok = try { Relay.test(this) } catch (e: Exception) { false }
                    runOnUiThread { Toast.makeText(this, if (ok) "Sent: it should arrive in a second" else "Could not reach the relay. Check your connection.", Toast.LENGTH_LONG).show() }
                }.start()
            })
            col.addView(Ui.button(this, "Restart connection") { stopService(Intent(this, PushService::class.java)); PushService.start(this) })

            col.addView(Ui.section(this, "Reliability"))
            col.addView(Ui.button(this, "Notification settings") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            })
            col.addView(Ui.button(this, "Allow background activity (battery)") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })

            col.addView(Ui.section(this, "Privacy"))
            col.addView(CheckBox(this).apply {
                text = "Lock the app with fingerprint / screen lock"
                isChecked = Cfg.biometric(this@SettingsActivity)
                textSize = 15f; setTextColor(Ui.INK)
                setOnCheckedChangeListener { _, on -> Cfg.prefs(this@SettingsActivity).edit().putBoolean("biometric", on).apply() }
            })

            col.addView(Ui.section(this, "Pairing"))
            col.addView(Ui.text(this, "Paired with " + Cfg.relayUrl(this).ifEmpty { "nothing" } + "\nAgent: " + Cfg.assistant(this), 14f))
            col.addView(Ui.button(this, "Pair again with a new code") { startActivity(Intent(this, PairActivity::class.java)) })
            col.addView(Ui.button(this, "Setup guide") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PairActivity.GUIDE))) })
            col.addView(Ui.button(this, "Unpair this phone") {
                Pairing.unpair(this); stopService(Intent(this, PushService::class.java))
                startActivity(Intent(this, PairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
            })

            col.addView(Ui.section(this, "Addresses (advanced)"))
            col.addView(Ui.text(this, "Murmur", 13f))
            murmur = EditText(this).apply { setText(Cfg.murmurUrl(this@SettingsActivity)); setSingleLine() }
            col.addView(murmur)
            col.addView(Ui.text(this, "Relay", 13f))
            relay = EditText(this).apply { setText(Cfg.relayUrl(this@SettingsActivity)); setSingleLine() }
            col.addView(relay)
            col.addView(Ui.text(this, "Relay token", 13f))
            token = EditText(this).apply { setText(Cfg.relayToken(this@SettingsActivity)); setSingleLine() }
            col.addView(token)
            col.addView(Ui.button(this, "Save addresses") {
                Cfg.prefs(this).edit().putString("murmurUrl", murmur.text.toString().trim()).putString("relayUrl", relay.text.toString().trim()).putString("relayToken", token.text.toString().trim()).apply()
                stopService(Intent(this, PushService::class.java)); PushService.start(this)
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            })
        }
    }

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(PowerManager::class.java)
        val battery = if (pm.isIgnoringBatteryOptimizations(packageName)) "battery: unrestricted" else "battery: optimised (may delay pushes)"
        status.text = "Push channel: " + Cfg.state(this) + "\n" + battery + "\nVersion " + BuildConfig.VERSION_NAME
    }
}
