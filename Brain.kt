package com.mumflorence.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Online brain (Claude). Returns null whenever offline, no key, or anything goes wrong. */
object Brain {
    const val MODEL = "claude-sonnet-5-5"
    @Volatile var lastStatus = "Not tested yet"
    private val history = ArrayList<Pair<String, String>>()

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun systemPrompt(store: Store): String {
        val name = store.get("assistant_name", "Mum Florence")
        val sb = StringBuilder()
        sb.append("You are $name, the user's personal assistant AND close friend, living in their phone. ")
        sb.append("Talk like a warm friend in a real conversation: natural, caring, sometimes ask a short follow-up question. ")
        sb.append("Never be robotic and never start with 'How can I help you'. Keep replies to one to three short spoken sentences. ")
        sb.append("No markdown, no emojis, no lists. ")
        val facts = store.allFacts()
        if (facts.isNotEmpty()) sb.append("Facts about the user: ").append(facts.entries.joinToString("; ") { "${it.key} = ${it.value}" }).append(". ")
        val notes = store.notes(15)
        if (notes.isNotEmpty()) sb.append("Things the user asked you to remember: ").append(notes.joinToString("; ")).append(". ")
        if (history.isEmpty()) {
            val past = store.recentConvo(10)
            if (past.isNotEmpty()) sb.append("Earlier conversation: ").append(past.joinToString(" | ") { "${it.first}: ${it.second}" }).append(". ")
        }
        sb.append("Use what you know naturally. If the user tells you something about themselves, acknowledge it warmly.")
        return sb.toString()
    }

    private fun post(key: String, body: JSONObject): Pair<Int, String> {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    private fun errorText(code: Int, resp: String): String {
        val msg = try { JSONObject(resp).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
        return "Error $code: ${msg ?: resp.take(120)}"
    }

    /** Blocking: call from a background thread. */
    fun ask(ctx: Context, store: Store, q: String, system: String? = null, keep: Boolean = true): String? {
        val key = store.get("api_key").trim()
        if (key.isEmpty()) { lastStatus = "No API key entered"; return null }
        if (!online(ctx)) { lastStatus = "Phone is offline"; return null }
        return try {
            val msgs = JSONArray()
            if (keep) for ((r, c) in history) msgs.put(JSONObject().put("role", r).put("content", c))
            msgs.put(JSONObject().put("role", "user").put("content", q))
            val body = JSONObject().put("model", MODEL).put("max_tokens", 500)
                .put("system", system ?: systemPrompt(store)).put("messages", msgs)
            val (code, resp) = post(key, body)
            if (code !in 200..299) { lastStatus = errorText(code, resp); return null }
            val content = JSONObject(resp).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            val text = sb.toString().trim()
            if (keep && text.isNotEmpty()) {
                history.add("user" to q)
                history.add("assistant" to text)
                while (history.size > 16) history.removeAt(0)
            }
            lastStatus = "Working ✓"
            text.ifEmpty { null }
        } catch (e: Exception) {
            lastStatus = "Failed: ${e.message}"
            null
        }
    }

    /** For the "Test API key" button. */
    fun test(ctx: Context, key: String): Pair<Boolean, String> {
        if (key.isBlank()) return false to "No key entered"
        if (!online(ctx)) return false to "Phone is offline"
        return try {
            val body = JSONObject().put("model", MODEL).put("max_tokens", 10)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Say OK")))
            val (code, resp) = post(key.trim(), body)
            if (code in 200..299) { lastStatus = "Working ✓"; true to "Working ✓ (key accepted)" }
            else { lastStatus = errorText(code, resp); false to lastStatus }
        } catch (e: Exception) {
            lastStatus = "Failed: ${e.message}"
            false to lastStatus
        }
    }

    fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        null
    }

    /** Optional: your own database/API. We call  URL?q=<question>  and read the answer. */
    fun askDatabase(url: String, q: String): String? {
        val sep = if (url.contains("?")) "&" else "?"
        return get(url + sep + "q=" + java.net.URLEncoder.encode(q, "UTF-8"))?.take(500)
    }
}
