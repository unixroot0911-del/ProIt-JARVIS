package com.jarvis.assistant

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    val retriable: Boolean get() = code == 408 || code == 429 || code in 500..504

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
 * Free-tier reasoning built to stay fast and never stall:
 *  - Groq answers first (very fast); if it is slow, Gemini starts in parallel and the first good answer wins.
 *  - A provider that just failed or is overloaded is skipped for a minute.
 *  - Model names are never hard-wired: each provider is asked which models it serves, and retired or overloaded
 *    models are replaced automatically.
 */
class Brain(private val prefs: Prefs, private val memory: Memory) {

    companion object {
        private val badUntil = ConcurrentHashMap<String, Long>()
        private fun isBad(p: String) = (badUntil[p] ?: 0L) > System.currentTimeMillis()
        private fun markBad(p: String) { badUntil[p] = System.currentTimeMillis() + 60_000L }
        private fun markGood(p: String) { badUntil.remove(p) }

        /** Pulls the JSON object out of a reply that may contain extra words or markdown fences. */
        fun extractJson(raw: String): String {
            val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val a = t.indexOf('{')
            val b = t.lastIndexOf('}')
            return if (a >= 0 && b > a) t.substring(a, b + 1) else t
        }
    }

    private val base = """
        You are JARVIS, the personal AI system on the user's Android phone. Personality: sharp, bold, ambitious,
        confident, dry wit, never servile. Do not use any title or honorific for the user (no "sir", no "boss").
        Language: reply in the language the user spoke. Understand Modern Standard Arabic, Moroccan Darija and English;
        if the user mixes them, mix naturally. Be fast and brief: usually one or two sentences, never more than
        five unless the user asks for detail.
    """.trimIndent()

    private val jsonRules = """

        Respond ONLY with one JSON object, no markdown, no text outside it, with these keys:
          "reply": string, what you say to the user.
          "action": null or {"type": string, "arg": string}. Supported types:
              "send_message" (arg = "app|contact or number|message text"; app is whatsapp, sms, telegram, instagram... any app),
              "call_contact" (arg = contact name or number),
              "open_app" (arg = app name), "open_url" (arg = address), "web_search" (arg = query),
              "set_alarm" (arg = "HH:MM"), "set_timer" (arg = seconds),
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
            parse(complete(system, userText, memory.recentTurns(8), imageB64, json = true))
        } catch (e: Exception) {
            Decision(e.message ?: "The brain is unreachable.", failed = true)
        }
    }

    // ---- provider race ----

    private fun attempt(name: String, block: () -> String): Result<String> =
        try {
            val r = block()
            markGood(name)
            Result.success(r)
        } catch (e: Exception) {
            val transient = (e is HttpError && e.retriable) || e is IOException
            if (transient) markBad(name)
            Result.failure(IllegalStateException("$name: ${e.message}"))
        }

    private suspend fun race(runners: List<Pair<String, () -> String>>, hedgeMs: Long): String {
        val winner = CompletableDeferred<String>()
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val failed = AtomicInteger(0)
        val secondStarted = AtomicBoolean(false)

        fun launchRunner(i: Int) {
            Engine.scope.launch(Dispatchers.IO) {
                val r = attempt(runners[i].first, runners[i].second)
                if (r.isSuccess) {
                    winner.complete(r.getOrThrow())
                } else {
                    errors.add(r.exceptionOrNull()?.message ?: "failed")
                    if (i == 0 && runners.size > 1 && secondStarted.compareAndSet(false, true)) launchRunner(1)
                    if (failed.incrementAndGet() >= runners.size) {
                        winner.completeExceptionally(
                            IllegalStateException("All brains failed. " + errors.joinToString(" | ") + "  (Settings > TEST BRAINS shows details)")
                        )
                    }
                }
            }
        }

        launchRunner(0)
        if (runners.size > 1) {
            Engine.scope.launch {
                delay(hedgeMs)
                if (!winner.isCompleted && secondStarted.compareAndSet(false, true)) launchRunner(1)
            }
        }
        return winner.await()
    }

    /** One completion. Throws with a readable reason when every provider fails. */
    suspend fun complete(
        system: String,
        userText: String,
        history: List<Turn> = emptyList(),
        imageB64: String? = null,
        json: Boolean = true
    ): String {
        val haveGemini = prefs.geminiKey.isNotEmpty()
        val haveGroq = prefs.groqKey.isNotEmpty() && imageB64 == null
        if (!haveGemini && !haveGroq) {
            throw IllegalStateException(
                if (imageB64 != null && prefs.groqKey.isNotEmpty()) "Vision needs a Gemini key. Add one in Settings."
                else "No API key is set. Open Settings and add a free Gemini or Groq key."
            )
        }
        val runners = ArrayList<Pair<String, () -> String>>()
        if (haveGroq) runners.add("Groq" to { groqRun(system, groqMessages(system, history, userText), json) })
        if (haveGemini) runners.add("Gemini" to { geminiRun(system, geminiContents(history, userText, imageB64), json) })
        runners.sortBy { if (isBad(it.first)) 1 else 0 }     // healthy first; Groq leads when both are healthy
        return race(runners, hedgeMs = 6000)
    }

    // ---- speech to text ----

    /** Turns a recorded WAV into text. Groq Whisper is the fast default; Gemini is more accurate with Darija. */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        val order = if (prefs.sttGeminiFirst) listOf("Gemini", "Groq") else listOf("Groq", "Gemini")
        for (p in order) {
            if (p == "Groq" && prefs.groqKey.isNotEmpty()) {
                try { return@withContext groqTranscribe(wav).trim() }
                catch (e: Exception) { errors.add("Groq: ${e.message}") }
            }
            if (p == "Gemini" && prefs.geminiKey.isNotEmpty()) {
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
        }
        throw IllegalStateException(
            if (errors.isEmpty()) "Voice needs a free Gemini or Groq key. Add one in Settings, or type instead."
            else "Could not transcribe. " + errors.joinToString(" | ")
        )
    }

    // ---- diagnostics ----

    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun probe(name: String, hasKey: Boolean, model: () -> String, run: () -> String, list: () -> List<String>) {
            sb.append(name).append(": ")
            if (!hasKey) { sb.append("no key set\n"); return }
            val t0 = System.currentTimeMillis()
            try {
                val r = run()
                sb.append("working with ${model()} in ${System.currentTimeMillis() - t0} ms (answered: ${r.trim().take(20)})\n")
            } catch (e: Exception) {
                sb.append("FAILED after ${System.currentTimeMillis() - t0} ms, ${e.message}\n")
                try { sb.append("  models it offers: ").append(list().take(6).joinToString(", ").ifEmpty { "none" }).append('\n') }
                catch (e2: Exception) { sb.append("  could not list models: ${e2.message}\n") }
            }
        }
        probe("GROQ", prefs.groqKey.isNotEmpty(), { prefs.groqModel },
            { groqRun("Reply with the single word OK.", groqMessages("Reply with the single word OK.", emptyList(), "ping"), false) },
            { discoverGroq() })
        probe("GEMINI", prefs.geminiKey.isNotEmpty(), { prefs.geminiModel },
            { geminiRun("Reply with the single word OK.", geminiContents(emptyList(), "ping", null), false) },
            { discoverGemini() })
        sb.toString()
    }

    // ---- parsing ----

    private fun parse(raw: String): Decision {
        val cleaned = extractJson(raw)
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
            Decision(raw.trim())
        }
    }

    // ---- model selection ----

    private val geminiFallbacks = listOf(
        "gemini-flash-lite-latest", "gemini-flash-latest", "gemini-3.1-flash-lite", "gemini-3-flash",
        "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash"
    )
    private val groqPreferred = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b", "qwen/qwen3.8-27b")

    /**
     * Tries the remembered model first, then every model the provider offers, then known fallbacks.
     * Retired models (404) and overloaded ones (429, 5xx) move on to the next candidate; bad keys fail fast.
     */
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
        var cachedGone = false
        var attempts = 0
        var last: Exception = IllegalStateException("No usable model found")
        while (attempts < 5) {
            if (queue.isEmpty()) {
                if (expanded) break
                expanded = true
                try {
                    discover().forEach { if (it !in tried) queue.add(it) }
                } catch (e: HttpError) {
                    if (!e.retriable && e.code != 404) throw e   // bad key: other models will not help
                    last = e
                } catch (e: Exception) {
                    last = e
                }
                fallbacks.forEach { if (it !in tried && it !in queue) queue.add(it) }
                continue
            }
            val m = queue.removeFirst()
            if (!tried.add(m)) continue
            attempts++
            try {
                val r = call(m)
                if (cached.isEmpty() || cachedGone || m == cached) save(m)   // do not stick to a fallback after a brief overload
                return r
            } catch (e: HttpError) {
                last = e
                val gone = e.code == 404 || (e.code == 400 && e.body.contains("model", ignoreCase = true))
                if (gone && m == cached) cachedGone = true
                if (!gone && !e.retriable) throw e
            }
        }
        throw last
    }

    private fun discoverGemini(): List<String> {
        val resp = request("GET", "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", null, null,
            mapOf("x-goog-api-key" to prefs.geminiKey), 15000)
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
            { -ver(it) },
            { !it.contains("lite") },     // lite models answer fastest, so they lead within a version
            { it.length }
        ))
    }

    private fun discoverGroq(): List<String> {
        val resp = request("GET", "https://api.groq.com/openai/v1/models", null, null,
            mapOf("Authorization" to "Bearer ${prefs.groqKey}"), 15000)
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
            fun send(fast: Boolean): String {
                val gen = JSONObject().put("temperature", 0.7)
                if (json) gen.put("responseMimeType", "application/json")
                if (fast) {
                    // Gemini 2.5 and 3 "think" before answering, which costs seconds. Ask for no or minimal thinking.
                    gen.put("thinkingConfig",
                        if (model.contains("2.5")) JSONObject().put("thinkingBudget", 0)
                        else JSONObject().put("thinkingLevel", "minimal"))
                }
                val body = JSONObject().put("contents", contents).put("generationConfig", gen)
                if (system.isNotBlank()) {
                    body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                }
                val resp = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    body.toString().toByteArray(Charsets.UTF_8), "application/json",
                    mapOf("x-goog-api-key" to prefs.geminiKey), 30000)
                val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
                return sb.toString()
            }
            try {
                send(true)
            } catch (e: HttpError) {
                // this model may not accept the thinking setting: retry plainly
                if (e.code == 400) send(false) else throw e
            }
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

    /**
     * JSON mode is deliberately not used: it makes some models fail with "Failed to generate JSON".
     * The prompt demands JSON and [extractJson] recovers it from whatever the model returns.
     */
    private fun groqRun(system: String, messages: JSONArray, @Suppress("UNUSED_PARAMETER") json: Boolean): String =
        tryModels(prefs.groqModel, { discoverGroq() }, groqPreferred, { prefs.groqModel = it }) { model ->
            fun send(tuned: Boolean): String {
                val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.6)
                if (tuned) {
                    body.put("max_completion_tokens", 1024)
                    if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
                    else if (model.contains("qwen")) body.put("reasoning_effort", "none")
                }
                val resp = request("POST", "https://api.groq.com/openai/v1/chat/completions",
                    body.toString().toByteArray(Charsets.UTF_8), "application/json",
                    mapOf("Authorization" to "Bearer ${prefs.groqKey}"), 30000)
                return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "")
            }
            try {
                send(true)
            } catch (e: HttpError) {
                // an optional tuning parameter may be rejected by this model: retry plainly
                if (e.code == 400) send(false) else throw e
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
                    "multipart/form-data; boundary=$boundary", mapOf("Authorization" to "Bearer ${prefs.groqKey}"), 30000)
                return JSONObject(resp).optString("text", "")
            } catch (e: HttpError) {
                last = e
                if (e.code != 404 && e.code != 400 && !e.retriable) throw e
            }
        }
        throw last
    }

    // ---- HTTP ----

    private fun request(
        method: String, url: String, body: ByteArray?, contentType: String?,
        headers: Map<String, String>, readTimeoutMs: Int
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = readTimeoutMs
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
