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

    private val base = """
        You are JARVIS, the personal AI system on the user's Android phone. Personality: sharp, bold, ambitious,
        confident, dry wit, never servile. Do not use any title or honorific for the user (no "sir", no "boss").
        Language: reply in the language the user spoke. Understand Modern Standard Arabic, Moroccan Darija and English;
        if the user mixes them, mix naturally. Keep replies short enough to be spoken aloud (1-3 sentences) unless asked for more.
    """.trimIndent()

    private val jsonRules = """

        Respond ONLY with one JSON object, no markdown, with these keys:
          "reply": string, what you say to the user.
          "action": null or {"type": string, "arg": string}. Supported types:
              "open_app" (arg = app name), "set_alarm" (arg = "HH:MM"), "set_timer" (arg = seconds),
              "web_search" (arg = query), "call" (arg = phone number),
              "reply_notification" (arg = "App|Contact|message text": answers an existing chat through its notification),
              "log_expense" (arg = "amount|category|note"), "log_habit" (arg = habit name),
              "note" (arg = "topic|what to remember or review"), "set_mode" (arg = "study" or "normal"),
              "run_agent" (arg = the goal in plain words; Jarvis then operates the phone screen step by step by itself),
              "pay" / "delete_file" (always need the user's confirmation).
          "remember": null or a short fact about the user worth storing long-term.
        A "Phone context" block may follow: recent notifications, today's spending and habits, current time.
        Answer questions about what the user missed ONLY from that block; never invent messages.
        Never invent capabilities you do not have. If you cannot do something, say so plainly.
    """.trimIndent()

    private fun studyRules(): String =
        if (prefs.mode == "study")
            "\n\nSTUDY MODE is ON: act as a sharp tutor. Explain simply, then test the user with one question at a time, " +
                "correct them directly, and save weak points with the \"note\" action so they can be reviewed later."
        else ""

    private fun factsBlock(): String {
        val facts = memory.facts()
        return if (facts.isEmpty()) "" else "\n\nKnown facts about the user:\n" + facts.joinToString("\n") { "- $it" }
    }

    /** Persona without the JSON contract, for free-form text such as briefings. */
    fun plainSystem(): String = base + studyRules() + factsBlock()

    suspend fun think(userText: String, context: String, imageB64: String?): Decision {
        val system = base + jsonRules + studyRules() + factsBlock() + "\n\nPhone context:\n" + context
        return try {
            parse(complete(system, userText, memory.recentTurns(), imageB64, json = true))
        } catch (e: Exception) {
            Decision(e.message ?: "The brain is unreachable.")
        }
    }

    /** One completion with provider fallback. Throws with a readable reason when every provider fails. */
    suspend fun complete(
        system: String,
        userText: String,
        history: List<Turn> = emptyList(),
        imageB64: String? = null,
        json: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        if (prefs.geminiKey.isNotEmpty()) {
            try { return@withContext callGemini(system, userText, history, imageB64, json) }
            catch (e: Exception) { errors.add("Gemini: ${e.message}") }
        }
        if (imageB64 == null && prefs.groqKey.isNotEmpty()) {
            try { return@withContext callGroq(system, userText, history, json) }
            catch (e: Exception) { errors.add("Groq: ${e.message}") }
        }
        val why = when {
            errors.isNotEmpty() -> "All brains failed. " + errors.joinToString(" | ")
            imageB64 != null -> "Vision needs a Gemini key. Add one in settings."
            else -> "No API key is set. Open settings and add a free Gemini or Groq key."
        }
        throw IllegalStateException(why)
    }

    private fun parse(raw: String): Decision {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val o = JSONObject(cleaned)
            val act = o.optJSONObject("action")
            Decision(
                reply = o.optString("reply", cleaned),
                actionType = act?.optString("type")?.takeIf { it.isNotEmpty() && it != "null" },
                actionArg = act?.optString("arg")?.takeIf { it.isNotEmpty() && it != "null" },
                remember = o.optString("remember", "").takeIf { it.isNotEmpty() && it != "null" }
            )
        } catch (e: Exception) {
            Decision(cleaned)
        }
    }

    private fun callGemini(system: String, userText: String, history: List<Turn>, imageB64: String?, json: Boolean): String {
        val contents = JSONArray()
        for (t in history) {
            contents.put(JSONObject()
                .put("role", if (t.role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        val parts = JSONArray().put(JSONObject().put("text", userText))
        if (imageB64 != null) {
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)))
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val gen = JSONObject().put("temperature", 0.7)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
        val resp = post(url, body.toString(), mapOf("x-goog-api-key" to prefs.geminiKey))
        return JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun callGroq(system: String, userText: String, history: List<Turn>, json: Boolean): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (t in history) {
            msgs.put(JSONObject().put("role", if (t.role == "user") "user" else "assistant").put("content", t.text))
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val body = JSONObject()
            .put("model", "llama-3.3-70b-versatile")
            .put("messages", msgs)
            .put("temperature", 0.7)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))

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
            c.readTimeout = 45000
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
