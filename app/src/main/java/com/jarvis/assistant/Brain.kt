package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What the brain decided: words to say, plus an optional action for the phone to perform. */
data class Decision(
    val reply: String,
    val actionType: String? = null,
    val actionArg: String? = null,
    val remember: String? = null
)

/**
 * Free-tier reasoning with automatic fallback: Gemini first, Groq second.
 * If one provider is out of quota or offline, the other takes over.
 */
class Brain(private val prefs: Prefs, private val memory: Memory) {

    private val persona = """
        You are JARVIS, the personal AI system on the user's Android phone. Personality: sharp, bold, ambitious,
        confident, dry wit, never servile. Do not use any title or honorific for the user (no "sir", no "boss").
        Language: reply in the language the user spoke. Understand Modern Standard Arabic, Moroccan Darija and English;
        if the user mixes them, mix naturally. Keep replies short enough to be spoken aloud (1-3 sentences) unless asked for more.

        Respond ONLY with one JSON object, no markdown, with these keys:
          "reply": string, what you say to the user.
          "action": null or {"type": string, "arg": string}. Supported types:
              "open_app" (arg = app name), "set_alarm" (arg = "HH:MM"), "set_timer" (arg = seconds),
              "web_search" (arg = query), "call" (arg = phone number), "pay"/"delete_file" (these need confirmation).
          "remember": null or a short fact about the user worth storing long-term.
        Never invent capabilities you do not have. If you cannot do something yet, say so plainly.
    """.trimIndent()

    private fun systemPrompt(): String {
        val facts = memory.facts()
        return if (facts.isEmpty()) persona
        else persona + "\n\nKnown facts about the user:\n" + facts.joinToString("\n") { "- $it" }
    }

    suspend fun think(userText: String): Decision = withContext(Dispatchers.IO) {
        val history = memory.recentTurns()
        val errors = ArrayList<String>()

        val raw: String? = run {
            if (prefs.geminiKey.isNotEmpty()) {
                try { return@run callGemini(userText, history) } catch (e: Exception) { errors.add("Gemini: ${e.message}") }
            }
            if (prefs.groqKey.isNotEmpty()) {
                try { return@run callGroq(userText, history) } catch (e: Exception) { errors.add("Groq: ${e.message}") }
            }
            null
        }

        if (raw == null) {
            val why = if (errors.isEmpty()) "No API key is set. Open settings and add a free Gemini or Groq key."
            else "All brains failed. " + errors.joinToString(" | ")
            return@withContext Decision(why)
        }
        parse(raw)
    }

    private fun parse(raw: String): Decision {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val o = JSONObject(cleaned)
            val act = o.optJSONObject("action")
            Decision(
                reply = o.optString("reply", cleaned),
                actionType = act?.optString("type")?.takeIf { it.isNotEmpty() },
                actionArg = act?.optString("arg")?.takeIf { it.isNotEmpty() },
                remember = o.optString("remember", "").takeIf { it.isNotEmpty() && it != "null" }
            )
        } catch (e: Exception) {
            Decision(cleaned)
        }
    }

    private fun callGemini(userText: String, history: List<Turn>): String {
        val contents = JSONArray()
        for (t in history) {
            contents.put(JSONObject()
                .put("role", if (t.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText))))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.7))

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        val resp = post(url, body.toString(), mapOf("x-goog-api-key" to prefs.geminiKey))
        return JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun callGroq(userText: String, history: List<Turn>): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
        for (t in history) {
            msgs.put(JSONObject().put("role", if (t.role == "user") "user" else "assistant").put("content", t.text))
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("messages", msgs)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.7)

        val resp = post("https://api.groq.com/openai/v1/chat/completions", body.toString(),
            mapOf("Authorization" to "Bearer ${prefs.groqKey}"))
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun post(url: String, json: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}
