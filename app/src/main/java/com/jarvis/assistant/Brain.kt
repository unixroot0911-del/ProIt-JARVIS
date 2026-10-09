package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

/** What the brain decided: words to say, plus an optional action for the phone to perform. */
data class Decision(
    val reply: String,
    val actionType: String? = null,
    val actionArg: String? = null,
    val remember: String? = null,
    /** True when the brain could not be reached, so [reply] is an error message, not an answer. */
    val failed: Boolean = false,
    val speakResult: Boolean = false
)

class HttpError(val code: Int, val body: String) : RuntimeException(describe(code, body)) {
    companion object {
        private fun describe(code: Int, body: String): String {
            val msg = try {
                JSONObject(body).getJSONObject("error").getString("message")
            } catch (e: Exception) {
                body
            }
            return "HTTP $code" + if (msg.isNotBlank()) ": " + msg.replace("\n", " ").take(140) else ""
        }
    }
}

/**
 * Free-tier reasoning with automatic fallback: Gemini first, Groq second.
 * Model names are never hard-wired: each provider is asked which models it currently serves,
 * the best one is chosen and remembered, and if it is retired the next one is tried automatically.
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
              "open_app" (arg = app name), "open_url" (arg = address), "web_search" (arg = query),
              "set_alarm" (arg = "HH:MM"), "set_timer" (arg = seconds),
              "call_contact" (arg = contact name or number), "send_sms" (arg = "contact or number|message text"),
              "calendar_add" (arg = "title|yyyy-MM-dd HH:mm|minutes"),
              "media" (arg = "play", "pause", "next" or "previous"), "volume" (arg = "up", "down", "mute" or 0-100),
              "flashlight" (arg = "on" or "off"), "battery" (arg = ""), "where_am_i" (arg = ""),
              "reply_notification" (arg = "App|Contact|message text": answers an existing chat through its notification),
              "log_expense" (arg = "amount|category|note"), "log_habit" (arg = habit name),
              "note" (arg = "topic|what to remember or review"), "set_mode" (arg = "study" or "normal"),
              "run_agent" (arg = the goal in plain words; Jarvis then operates the phone screen step by step by itself),
              "stop_agent" (arg = ""),
              "pay" / "delete_file" (always need the user's confirmation).
          "remember": null or a short fact about the user worth storing long-term.
        A "Phone context" block follows: current time, battery, calendar, recent notifications, today's spending and habits.
        Answer questions about messages, calendar or what the user missed ONLY from that block; never invent anything.
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
            Decision(e.message ?: "The brain is unreachable.", failed = true)
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
            try { return@withContext geminiRun(system, geminiContents(history, userText, imageB64), json) }
            catch (e: Exception) { errors.add("Gemini: ${e.message}") }
        }
        if (imageB64 == null && prefs.groqKey.isNotEmpty()) {
            try { return@withContext groqRun(system, groqMessages(system, history, userText), json) }
            catch (e: Exception) { errors.add("Groq: ${e.message}") }
        }
        val why = when {
            errors.isNotEmpty() -> "All brains failed. " + errors.joinToString(" | ") + "  (Settings > TEST BRAINS shows details)"
            imageB64 != null -> "Vision needs a Gemini key. Add one in Settings."
            else -> "No API key is set. Open Settings and add a free Gemini or Groq key."
        }
        throw IllegalStateException(why)
    }

    // ---- speech to text ----

    /** Turns a recorded WAV into text. Gemini first (best with Darija), Groq Whisper as the fallback. */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        if (prefs.geminiKey.isNotEmpty()) {
            try {
                val parts = JSONArray()
                    .put(JSONObject().put("text",
                        "Transcribe this audio exactly as spoken. The speaker may use Modern Standard Arabic, Moroccan Darija, " +
                            "French or English, possibly mixed. Write Arabic and Darija in Arabic script. " +
                            "Output ONLY the transcript, nothing else. If there is no intelligible speech, output nothing."))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
                val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
                return@withContext geminiRun("", contents, false).trim()
            } catch (e: Exception) { errors.add("Gemini: ${e.message}") }
        }
        if (prefs.groqKey.isNotEmpty()) {
            try { return@withContext groqTranscribe(wav).trim() }
            catch (e: Exception) { errors.add("Groq: ${e.message}") }
        }
        throw IllegalStateException(
            if (errors.isEmpty()) "Voice needs a free Gemini or Groq key. Add one in Settings, or type instead."
            else "Could not transcribe. " + errors.joinToString(" | ")
        )
    }

    // ---- diagnostics ----

    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("GEMINI: ")
        if (prefs.geminiKey.isEmpty()) sb.append("no key set\n") else {
            try {
                val r = geminiRun("Reply with the single word OK.", geminiContents(emptyList(), "ping", null), false)
                sb.append("working with ${prefs.geminiModel} (answered: ${r.trim().take(20)})\n")
            } catch (e: Exception) {
                sb.append("FAILED, ${e.message}\n")
                try { sb.append("  models it offers: ").append(discoverGemini().take(6).joinToString(", ").ifEmpty { "none" }).append('\n') }
                catch (e2: Exception) { sb.append("  could not list models: ${e2.message}\n") }
            }
        }
        sb.append("GROQ: ")
        if (prefs.groqKey.isEmpty()) sb.append("no key set\n") else {
            try {
                val r = groqRun("Reply with the single word OK.", groqMessages("Reply with the single word OK.", emptyList(), "ping"), false)
                sb.append("working with ${prefs.groqModel} (answered: ${r.trim().take(20)})\n")
            } catch (e: Exception) {
                sb.append("FAILED, ${e.message}\n")
                try { sb.append("  models it offers: ").append(discoverGroq().take(6).joinToString(", ").ifEmpty { "none" }).append('\n') }
                catch (e2: Exception) { sb.append("  could not list models: ${e2.message}\n") }
            }
        }
        sb.toString()
    }

    // ---- parsing ----

    private fun parse(raw: String): Decision {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val o = JSONObject(cleaned)
            val act = o.optJSONObject("action")
            Decision(
                reply = o.optString("reply", cleaned),
                actionType = act?.optString("type")?.takeIf { it.isNotEmpty() && it != "null" },
                actionArg = act?.optString("arg", "")?.takeIf { it != "null" } ?: if (act != null) "" else null,
                remember = o.optString("remember", "").takeIf { it.isNotEmpty() && it != "null" }
            )
        } catch (e: Exception) {
            Decision(cleaned)
        }
    }

    // ---- model selection ----

    private val geminiFallbacks = listOf(
        "gemini-flash-latest", "gemini-3-flash", "gemini-2.5-flash",
        "gemini-flash-lite-latest", "gemini-3.1-flash-lite", "gemini-2.0-flash"
    )
    private val groqPreferred = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b", "qwen/qwen3.8-27b")

    private fun <T> tryModels(
        cached: String,
        discover: () -> List<String>,
        fallbacks: List<String>,
        save: (String) -> Unit,
        call: (String) -> T
    ): T {
        val tried = HashSet<String>()
        val queue = ArrayDeque<String>()
        if (cached.isNotEmpty()) queue.add(cached)
        var expanded = false
        var last: Exception = IllegalStateException("No usable model found")
        while (true) {
            if (queue.isEmpty()) {
                if (expanded) break
                expanded = true
                try {
                    discover().forEach { if (it !in tried) queue.add(it) }
                } catch (e: HttpError) {
                    if (e.code != 404) throw e   // bad key, quota or outage: other models will not help
                    last = e
                } catch (e: Exception) {
                    last = e
                }
                fallbacks.forEach { if (it !in tried && it !in queue) queue.add(it) }
                continue
            }
            val m = queue.removeFirst()
            if (!tried.add(m)) continue
            try {
                val r = call(m)
                save(m)
                return r
            } catch (e: HttpError) {
                last = e
                val modelProblem = e.code == 404 || (e.code == 400 && e.body.contains("model", ignoreCase = true))
                if (!modelProblem) throw e
            }
        }
        throw last
    }

    private fun discoverGemini(): List<String> {
        val resp = request("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", null, null,
            mapOf("x-goog-api-key" to prefs.geminiKey))
        val arr = JSONObject(resp).optJSONArray("models") ?: return emptyList()
        val names = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var ok = false
            for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
            if (ok) names.add(m.getString("name").removePrefix("models/"))
        }
        return rankGemini(names)
    }

    private fun rankGemini(names: List<String>): List<String> {
        val banned = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "native", "learnlm", "gemma", "customtools")
        val ok = names.filter { n -> n.contains("flash") && banned.none { n.contains(it) } }
        fun ver(n: String) = Regex("""gemini-(\d+(?:\.\d+)?)""").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return ok.sortedWith(compareBy<String>(
            { it.contains("preview") || it.contains("exp") },
            { it.contains("lite") },
            { -ver(it) },
            { it.length }
        ))
    }

    private fun discoverGroq(): List<String> {
        val resp = request("GET", "https://api.groq.com/openai/v1/models", null, null,
            mapOf("Authorization" to "Bearer ${prefs.groqKey}"))
        val arr = JSONObject(resp).optJSONArray("data") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) ids.add(arr.getJSONObject(i).getString("id"))
        val banned = listOf("whisper", "orpheus", "guard", "tts", "embed", "compound", "safeguard")
        return ids.filter { id -> banned.none { id.contains(it) } }
            .sortedBy { id -> groqPreferred.indexOf(id).let { if (it >= 0) it else 100 + (if (id.contains("llama")) 1 else 0) } }
    }

    // ---- Gemini ----

    private fun geminiContents(history: List<Turn>, userText: String, imageB64: String?): JSONArray {
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
        return contents
    }

    private fun geminiRun(system: String, contents: JSONArray, json: Boolean): String =
        tryModels(prefs.geminiModel, { discoverGemini() }, geminiFallbacks, { prefs.geminiModel = it }) { model ->
            val gen = JSONObject().put("temperature", 0.7)
            if (json) gen.put("responseMimeType", "application/json")
            val body = JSONObject().put("contents", contents).put("generationConfig", gen)
            if (system.isNotBlank()) {
                body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            }
            val resp = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                body.toString().toByteArray(Charsets.UTF_8), "application/json", mapOf("x-goog-api-key" to prefs.geminiKey))
            val cands = JSONObject(resp).optJSONArray("candidates")
            val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
            sb.toString()
        }

    // ---- Groq ----

    private fun groqMessages(system: String, history: List<Turn>, userText: String): JSONArray {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (t in history) {
            msgs.put(JSONObject().put("role", if (t.role == "user") "user" else "assistant").put("content", t.text))
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))
        return msgs
    }

    private fun groqRun(system: String, messages: JSONArray, json: Boolean): String =
        tryModels(prefs.groqModel, { discoverGroq() }, groqPreferred, { prefs.groqModel = it }) { model ->
            fun send(withJson: Boolean): String {
                val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.7)
                if (withJson) body.put("response_format", JSONObject().put("type", "json_object"))
                if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
                val resp = request("POST", "https://api.groq.com/openai/v1/chat/completions",
                    body.toString().toByteArray(Charsets.UTF_8), "application/json",
                    mapOf("Authorization" to "Bearer ${prefs.groqKey}"))
                return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "")
            }
            if (!json) send(false)
            else try {
                send(true)
            } catch (e: HttpError) {
                // some models reject JSON mode; the prompt already demands JSON, so retry without it
                if (e.code == 400 && e.body.contains("response_format", ignoreCase = true)) send(false) else throw e
            }
        }

    private fun groqTranscribe(wav: ByteArray): String {
        val lang = prefs.speechLocale.substringBefore('-').lowercase().takeIf { it.length in 2..3 }
        var last: Exception = IllegalStateException("No Whisper model available")
        for (model in listOf("whisper-large-v3-turbo", "whisper-large-v3")) {
            val boundary = "----jarvis" + System.currentTimeMillis()
            val out = ByteArrayOutputStream()
            fun field(n: String, v: String) =
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
            field("model", model)
            field("response_format", "json")
            if (lang != null) field("language", lang)
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray())
            out.write(wav)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            try {
                val resp = request("POST", "https://api.groq.com/openai/v1/audio/transcriptions", out.toByteArray(),
                    "multipart/form-data; boundary=$boundary", mapOf("Authorization" to "Bearer ${prefs.groqKey}"))
                return JSONObject(resp).optString("text", "")
            } catch (e: HttpError) {
                last = e
                if (e.code != 404 && e.code != 400) throw e
            }
        }
        throw last
    }

    // ---- HTTP ----

    private fun request(method: String, url: String, body: ByteArray?, contentType: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 60000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }
}
