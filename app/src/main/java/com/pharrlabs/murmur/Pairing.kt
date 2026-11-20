package com.pharrlabs.murmur

import android.content.Context
import org.json.JSONObject
import java.util.Base64

/** What the relay's pairing page hands over: where the relay and Murmur are, the relay's token, and the agent's name. */
data class PairInfo(val relay: String, val token: String, val murmur: String, val name: String)

object Pairing {
    /** Accepts the bare code, a murmurcompanion://pair?c=... link, or text with the code somewhere in it. */
    fun parse(input: String): PairInfo? {
        var code = input.trim()
        val q = Regex("[?&]c=([A-Za-z0-9_-]+)").find(code)
        if (q != null) code = q.groupValues[1]
        code = code.replace(Regex("\\s+"), "")
        if (code.length < 20) return null
        return try {
            val j = JSONObject(String(Base64.getUrlDecoder().decode(code), Charsets.UTF_8))
            if (j.optInt("v") != 1) return null
            val r = j.optString("r").trimEnd('/'); val t = j.optString("t"); val m = j.optString("m").trimEnd('/')
            if (!r.startsWith("https://") || t.isEmpty() || !m.startsWith("https://")) return null
            PairInfo(r, t, m, j.optString("n").ifBlank { "Hermes" })
        } catch (e: Exception) { null }
    }

    fun apply(c: Context, p: PairInfo) {
        Cfg.prefs(c).edit()
            .putString("relayUrl", p.relay).putString("relayToken", p.token).putString("murmurUrl", p.murmur)
            .putString("assistantName", p.name)
            .remove("needs_cache").remove("stat_cache").remove("agents_json").remove("caps").remove("since")
            .apply()
    }

    fun unpair(c: Context) {
        Cfg.prefs(c).edit().remove("relayUrl").remove("relayToken").remove("murmurUrl").remove("assistantName").remove("defaultProfile")
            .remove("needs_cache").remove("stat_cache").remove("agents_json").remove("caps").remove("since").apply()
    }
}
