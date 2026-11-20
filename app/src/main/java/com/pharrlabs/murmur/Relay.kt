package com.pharrlabs.murmur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Agent(val id: String, val name: String)
data class Last(val agent: String, val text: String, val at: Long, val href: String)
data class Item(val id: String, val kind: String, val title: String, val sub: String, val overdue: Boolean, val href: String, val actions: List<Pair<String, String>>)
data class Needs(val items: List<Item>, val asks: Int, val waiting: Int, val open: Int, val more: Int, val raw: String)
data class Stat(val label: String, val headline: String, val delta: String, val trend: String, val note: String, val ok: Boolean, val problems: List<String>)
data class Caps(val tasks: Boolean, val notes: Boolean, val tasklists: Boolean, val statLabel: String?, val threads: Boolean)
data class Cards(val approvals: Int, val working: Int, val chatsToday: Int, val tasksOpen: Int, val tasksOverdue: Int, val tasksToday: Int)

/** Settings: written by pairing (or edited in Settings). */
object Cfg {
    fun prefs(c: Context) = c.applicationContext.getSharedPreferences("murmur", Context.MODE_PRIVATE)
    fun relayUrl(c: Context) = (prefs(c).getString("relayUrl", null) ?: "").trimEnd('/')
    fun relayToken(c: Context) = prefs(c).getString("relayToken", null) ?: ""
    fun murmurUrl(c: Context) = (prefs(c).getString("murmurUrl", null) ?: "").trimEnd('/')
    fun paired(c: Context) = relayUrl(c).isNotEmpty() && relayToken(c).isNotEmpty() && murmurUrl(c).isNotEmpty()
    /** What the user calls their agent (from the relay's config; used in prompts and widget text). */
    fun assistant(c: Context) = prefs(c).getString("assistantName", null)?.takeIf { it.isNotBlank() } ?: "Hermes"
    fun profile(c: Context) = prefs(c).getString("defaultProfile", null)?.takeIf { it.isNotBlank() } ?: "default"
    fun caps(c: Context): Caps {
        val j = try { JSONObject(prefs(c).getString("caps", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val cap = j.optJSONObject("capture")
        val st = j.optJSONObject("stat")
        return Caps(cap?.optBoolean("tasks") ?: false, cap?.optBoolean("notes") ?: false, j.optBoolean("tasklists"), st?.optString("label")?.ifBlank { "Stat" }, j.optBoolean("threads"))
    }
    fun biometric(c: Context) = prefs(c).getBoolean("biometric", false)
    fun tileAgent(c: Context) = prefs(c).getString("tileAgent", null) ?: profile(c)
    fun setState(c: Context, s: String) = prefs(c).edit().putString("state", s).apply()
    fun state(c: Context) = prefs(c).getString("state", "starting") ?: "starting"
}

/** Calls to the PC relay (relay/relay.mjs). All blocking: call from a background thread. */
object Relay {
    class NotPaired : java.io.IOException("Not paired yet: open Murmur Companion and pair it with your relay.")

    fun open(c: Context, path: String, method: String = "GET", readTimeout: Int = 15000): HttpURLConnection {
        if (!Cfg.paired(c)) throw NotPaired()
        val conn = URL(Cfg.relayUrl(c) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10000
        conn.readTimeout = readTimeout
        conn.setRequestProperty("Authorization", "Bearer " + Cfg.relayToken(c))
        return conn
    }

    fun getJson(c: Context, path: String): JSONObject {
        val conn = open(c, path)
        try {
            if (conn.responseCode != 200) throw RuntimeException("relay HTTP " + conn.responseCode)
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    fun agents(c: Context): List<Agent> {
        val p = Cfg.prefs(c)
        try {
            val arr = getJson(c, "/api/agents").getJSONArray("agents")
            p.edit().putString("agents_json", arr.toString()).apply()
            return parseAgents(arr)
        } catch (e: Exception) {
            val cached = p.getString("agents_json", null)
            if (cached != null) return parseAgents(JSONArray(cached))
            return listOf(Agent(Cfg.profile(c), Cfg.assistant(c)))
        }
    }

    private fun parseAgents(arr: JSONArray): List<Agent> =
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { Agent(it.getString("id"), it.optString("name", it.getString("id"))) }

    fun agentName(c: Context, id: String): String =
        (try { agents(c) } catch (e: Exception) { emptyList() }).firstOrNull { it.id == id }?.name ?: id.replaceFirstChar { it.uppercase() }

    fun last(c: Context, agent: String): Last {
        val j = getJson(c, "/api/last?agent=" + java.net.URLEncoder.encode(agent, "UTF-8"))
        return Last(agent, j.optString("text"), j.optLong("at"), j.optString("href"))
    }

    fun cards(c: Context): Cards {
        val j = getJson(c, "/api/cards")
        return Cards(j.optInt("approvals"), j.optInt("working"), j.optInt("chatsToday"), j.optInt("tasksOpen"), j.optInt("tasksOverdue"), j.optInt("tasksToday"))
    }

    fun post(c: Context, path: String, body: JSONObject? = null, readTimeout: Int = 12000): JSONObject {
        val conn = open(c, path, "POST", readTimeout)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw RuntimeException("relay HTTP " + conn.responseCode)
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    fun parseNeeds(j: JSONObject): Needs {
        val arr = j.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val acts = o.optJSONArray("actions") ?: JSONArray()
            Item(
                o.getString("id"), o.optString("kind"), o.optString("title"), o.optString("sub"), o.optBoolean("overdue"), o.optString("href"),
                (0 until acts.length()).map { acts.getJSONObject(it) }.map { it.getString("k") to it.optString("label") },
            )
        }
        return Needs(items, j.optInt("asks"), j.optInt("waiting"), j.optInt("open"), j.optInt("more"), j.toString())
    }

    /** What is waiting on you: live agent approvals/questions first, then (if the chat scan is on) chats waiting on you or still open. Caches the last answer. */
    fun needs(c: Context): Needs {
        val j = getJson(c, "/api/needs")
        Cfg.prefs(c).edit().putString("needs_cache", j.toString()).apply()
        return parseNeeds(j)
    }

    fun cachedNeeds(c: Context): Needs? =
        try { Cfg.prefs(c).getString("needs_cache", null)?.let { parseNeeds(JSONObject(it)) } } catch (e: Exception) { null }

    fun parseStat(j: JSONObject): Stat {
        val r = j.optJSONArray("problems") ?: JSONArray()
        return Stat(j.optString("label", "Stat"), j.optString("headline"), j.optString("delta"), j.optString("trend", "flat"), j.optString("note"), j.optBoolean("ok", true), (0 until r.length()).map { r.getString(it) })
    }

    /** The one-number widget, or null when the relay has no stat configured. */
    fun stat(c: Context): Stat? {
        val conn = open(c, "/api/stat")
        try {
            if (conn.responseCode == 404) { Cfg.prefs(c).edit().remove("stat_cache").apply(); return null }
            if (conn.responseCode != 200) throw RuntimeException("relay HTTP " + conn.responseCode)
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            Cfg.prefs(c).edit().putString("stat_cache", j.toString()).apply()
            return parseStat(j)
        } finally { conn.disconnect() }
    }

    fun cachedStat(c: Context): Stat? =
        try { Cfg.prefs(c).getString("stat_cache", null)?.let { parseStat(JSONObject(it)) } } catch (e: Exception) { null }

    /** Ask the relay what it can do (and what the agent is called); remembered for widgets and prompts. */
    fun refreshCaps(c: Context): Caps {
        val j = getJson(c, "/api/capabilities")
        Cfg.prefs(c).edit().putString("caps", j.toString())
            .putString("assistantName", j.optString("assistantName", "Hermes"))
            .putString("defaultProfile", j.optString("defaultProfile", "default")).apply()
        return Cfg.caps(c)
    }

    fun test(c: Context, ask: Boolean = false): Boolean {
        val conn = open(c, "/api/test" + if (ask) "?ask=1" else "", "POST")
        try { return conn.responseCode == 200 } finally { conn.disconnect() }
    }
}

fun ago(at: Long): String {
    if (at <= 0) return ""
    val s = (System.currentTimeMillis() - at) / 1000
    return when {
        s < 60 -> "now"
        s < 3600 -> (s / 60).toString() + "m"
        s < 86400 -> (s / 3600).toString() + "h"
        else -> (s / 86400).toString() + "d"
    }
}
