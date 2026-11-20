package com.pharrlabs.murmur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Voice dump: a spoken line becomes a Todoist task or a note, filed by the PC relay.
 * If the relay can't be reached the line waits in a small on-phone queue and is filed on the next connect.
 * Every line carries its own id, so a retry after a dropped reply never files it twice.
 */
object Capture {
    data class Result(val ok: Boolean, val queued: Boolean, val id: String, val line: String, val title: String, val canUndo: Boolean = true)

    private const val KEY = "capq"
    private val lock = Any()

    private fun load(c: Context): JSONArray = try { JSONArray(Cfg.prefs(c).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
    private fun save(c: Context, a: JSONArray) = Cfg.prefs(c).edit().putString(KEY, a.toString()).apply()

    fun pending(c: Context): Int = synchronized(lock) { load(c).length() }

    private fun describe(c: Context, j: JSONObject): String {
        val kind = j.optString("kind")
        val where = j.optString("where")
        val due = j.optString("due").take(10)
        if (kind == "chat") return Cfg.assistant(c) + " \u2192 new chat started."
        val head = if (kind == "note") "Note" else "Task"
        return head + " \u2192 " + where + (if (due.isNotEmpty()) " \u00b7 due $due" else "") + (if (j.optBoolean("repeat")) " (already filed)" else "")
    }

    private fun post(c: Context, text: String, cid: String): JSONObject =
        Relay.post(c, "/api/capture", JSONObject().put("text", text).put("cid", cid), readTimeout = 20000)

    fun file(c: Context, text: String): Result {
        val cid = UUID.randomUUID().toString().replace("-", "").take(12)
        return try {
            val j = post(c, text, cid)
            if (j.optBoolean("ok")) Result(true, false, j.optString("id", cid), describe(c, j), j.optString("title"), !j.optBoolean("noUndo"))
            else Result(false, false, cid, "Couldn't file it: " + j.optString("error", "unknown"), "")
        } catch (e: IOException) {
            enqueue(c, text, cid)
            Result(true, true, cid, "Saved on your phone. It files itself when Murmur is reachable.", text)
        } catch (e: Exception) {
            // The relay answered with an error: queueing would only repeat it.
            Result(false, false, cid, "Murmur refused it (" + (e.message ?: "error") + ")", "")
        }
    }

    private fun enqueue(c: Context, text: String, cid: String) = synchronized(lock) {
        val a = load(c)
        a.put(JSONObject().put("cid", cid).put("text", text).put("at", System.currentTimeMillis()))
        save(c, a)
    }

    /** File whatever is waiting, oldest first. Stops at the first unreachable try. Returns how many were filed. */
    fun flush(c: Context): Int {
        var done = 0
        while (true) {
            val head: JSONObject = synchronized(lock) { load(c).optJSONObject(0) } ?: break
            try {
                post(c, head.getString("text"), head.getString("cid"))
            } catch (e: IOException) {
                break
            } catch (e: Exception) {
                // A line the relay rejects must not block the ones behind it.
            }
            synchronized(lock) {
                val a = load(c)
                val rest = JSONArray()
                for (i in 1 until a.length()) rest.put(a.get(i))
                save(c, rest)
            }
            done++
        }
        if (done > 0) Widgets.refreshAll(c)
        return done
    }

    fun undo(c: Context, id: String): Boolean = try {
        Relay.post(c, "/api/undo", JSONObject().put("id", id)).optBoolean("ok")
    } catch (e: Exception) { false }
}
