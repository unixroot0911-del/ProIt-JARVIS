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
    val speakResult: Boolean = false,
    /** A clarifying question the brain wants answered before it acts. */
    val ask: AskSpec? = null,
    val understood: String = "",
    val confidence: Double = 1.0
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
          "understood": string, one line: what the user wants, with the exact objects (who, what, when, where, how much), in your own words.
          "confidence": number 0 to 1: how sure you are that you understood exactly what they want.
          "ask": null or {"q": string, "options": [2 to 4 short likely answers], "why": short reason}: ONLY under the rules of UNDERSTANDING below.
          "reply": string, what you say to the user.
          "action": null or {"type": string, "arg": string}. Supported types:
              "send_message" (arg = "app|contact or number|message text"; app is whatsapp, sms, telegram, instagram... any app),
              "call_contact" (arg = contact name or number),
              "open_app" (arg = app name), "open_url" (arg = address), "web_search" (arg = query),
              "set_alarm" (arg = "HH:MM|label|repeat"; label and repeat optional, repeat is daily or weekly), "set_timer" (arg = seconds),
              "calendar_add" (arg = "title|yyyy-MM-dd HH:mm|minutes"),
              "media" (arg = "play", "pause", "next" or "previous"), "volume" (arg = "up", "down", "mute" or 0-100),
              "flashlight" (arg = "on" or "off"), "battery" (arg = ""), "where_am_i" (arg = ""),
              "reply_notification" (arg = "App|Contact|message text": answers an existing chat through its notification),
              "log_expense" (arg = "amount|category|note"), "log_habit" (arg = habit name),
              "note" (arg = "topic|what to remember or review"),
              "last_error" (arg = "": shows the last crash or internal error Jarvis recorded),
              "remember" (arg = "kind|name|key|value": files a fact in Jarvis' sectioned memory. kind = game, app, person, topic or preference (name "user" for preference);
                  key = short snake_case label such as rule_skip_cutscenes, plays_often, loading_time, note; value = the fact in plain words.
                  Use it whenever the user states a lasting rule, habit or fact: "always skip cutscenes in X", "I play X a lot", "never open Y", "Sara is my sister". Confirm in one short line),
              "forget_about" (arg = "name" or "name|key": removes what is filed about that game, app, person or topic),
              "memory_show" (arg = "" for the whole sectioned memory, or a name for just that one),
              "schedule_task" (arg = "when|what to do|optional repeat": when = minutes from now, HH:MM or yyyy-MM-dd HH:mm; what = any text request in plain words;
                  repeat = daily, weekly or hourly. Jarvis carries the request out at that time and reports the result),
              "web_answer" (arg = a question that needs fresh internet facts: news, prices, scores, weather, who holds a role now),
              "remind" (arg = "minutes from now|what to remind|repeat"; compute the minutes yourself from the current time; repeat optional: daily or weekly),
              "remind_at" (arg = "yyyy-MM-dd HH:mm|what to remind|repeat" or "HH:MM|what to remind|repeat"),
              "list_reminders" (arg = ""), "cancel_reminder" (arg = words from the reminder, or "all"),
              "build_app" (arg = "name|full description, or the change to make": Jarvis writes a complete playable game or tool and opens it on the phone),
              "open_creation" (arg = name of an app or game built earlier),
              "download" (arg = "what to download|optional direct link": searches the web for free, legal sources, follows download pages to the real file
                  and downloads it to the phone's Downloads folder: mods, maps, free apps, documents, music, books...),
              "update_self" (arg = ""): downloads the newest Jarvis version and starts its installation; "check_update" (arg = ""),
              "self_check" (arg = ""): reports which of Jarvis' own permissions, keys and services work or are missing,
              "alert_on" (arg = words to watch in notifications; "a+b" needs both; Jarvis alerts quietly when one arrives), "alert_off" (arg = words or "all"), "alert_list" (arg = ""),
              "navigate" (arg = place or address: opens turn-by-turn navigation),
              "play_music" (arg = "song, artist or playlist|optional app"), "share" (arg = "app|text": opens that app's share screen with the text; blank app = system share menu),
              "system_panel" (arg = "notifications", "quick_settings" or "collapse": pulls down / closes the phone's notification shade or quick settings; "pull down the notifications", "انزل الاشعارات" mean THIS, never open_app),
              "delete_file" (arg = the name of what to delete: a file, an app or game Jarvis built, or an installed app):
                  it always asks the user for confirmation. When you issue one, your reply must only say that you are asking, never that it is done.
          "remember": null or a short fact about the user worth storing long-term.
        A "Phone context" block follows: current time, battery, calendar, recent notifications, today's spending and habits.
        Answer questions about messages, calendar or what the user missed ONLY from that block; never invent anything.
        YOU CAN (use the action, never answer "I can't" for these): look things up on the web (web_answer); remind them later (remind);
        build real playable games and tools on this phone (build_app); find and download free files from the web (download), for example
        community mods and maps ("download Maghreb map for ETS2 1.75" means issue download now, never "I can't fetch that");
        update itself (update_self); diagnose itself (self_check: "why does X not work" starts there); watch notifications for words and alert
        quietly (alert_on); read and answer notifications; send messages (WhatsApp and SMS); call; open apps; look at photos via the LOOK button.
        JARVIS IS TEXT ONLY: it reads and writes. It does not speak, listen, play games by itself, tap or operate other apps, or watch the screen:
        those were removed. If asked for one of them, say so in one short line and offer what it can do (open the app, explain, search, write, remind, message).
        ACT, DO NOT TEACH: when the user asks for something Jarvis can do with its actions, it does it now with the matching action and reports the result
        in one line. It never answers with steps or "you can do this by..." for something it can do itself.
        If a message continues, repeats or relates to a task from the recent conversation ("do it", "again", "the same", "that one"), it performs that task now with the earlier details.
        Never tell the user coordinates or button positions.
        BUILDING: build_app writes one complete self-contained HTML5 game or app (intro cutscene, gameplay, saving, ending) and opens it.
        If the user asks for a copy of a commercial game (for example "The Forest as a 2D mobile game, same story"), never answer with a bare
        refusal: you cannot copy its exact characters, story text, art or assets, so build an ORIGINAL game with the same genre, mechanics
        and mood (new name, new story, new art), say that in one short line, and issue build_app. To improve something already built
        ("add caves", "make it harder"), reuse the same name so the existing version is upgraded.
        A question like "do you know X?" is conversation, not an open_app request.
        Alarms, timers and reminders are Jarvis' OWN discreet system: never open or mention the phone's clock or alarm app. Delivery is a quiet
        vibration and a silent pop-up, so it never embarrasses the user in public.
        NEVER say "I can't", "I cannot", "I am unable" or "لا أستطيع". If no single action fits, find a way: combine actions; build_app can create the missing tool; web_answer can research; remind can follow up later.
        Pick the closest route, do it, and say briefly what you did. Only genuinely harmful or illegal requests are declined, in one short line with a safe alternative.
        A "pair code", "pairing code" or "/pair 123456" is ALWAYS Jarvis' Telegram pairing, never Bluetooth: ignore it, never open settings because of it.
        Only act on what the user actually asked in the latest message. Bluetooth, Wi-Fi, mobile data, airplane mode, location, display and sound:
        open_app with that word opens its system settings screen, where the user flips the switch.
        Downloads use only free, legal sources. If the user asks for paid or cracked content, say so in one line and download the free demo, trial
        or legal free alternative instead.
        FIDELITY (never distort what the user said): the text of a message to send, a note, a search, a name, a number, an amount, a time or a place is copied EXACTLY as the
        user gave it: same language, same spelling, same emojis; never translate it, correct it, shorten it, embellish it or replace it with something that merely sounds similar.
        Do only what was asked in the latest message, nothing more and nothing different. A word that resembles an installed app or a contact is only a hint: use it when the sentence
        is really about opening or using that app or contact, never when it is just a word in a normal sentence. "do you know X?" is a conversation, not an order.
        UNDERSTANDING: the user types fast, with typos, slang, Arabic, Darija, French and English mixed. Interpret by INTENT, never by literal words or by examples you have seen.
        Before every answer, silently work out: what they want, the exact objects (who, what, when, where, how much), what is still missing, and whether the phone context, the memory,
        the recent conversation or the standing answers already supply it. Fill every gap you can by inference first, then decide. Reason about what is likely, not just what is possible.
        CURIOSITY: be smart, not nosy. Use "ask" ONLY when (1) a required detail is missing and cannot be inferred (who to message, what to write, when, which of two plausible apps or contacts),
        or (2) two readings lead to different results and being wrong would cost something (sending, calling, deleting, scheduling, downloading something large), or (3) your confidence is below 0.6.
        Never ask what the context answers, what has a safe obvious default, small talk, or something the user already told you or answered before. A "standing answer" in the memory
        means the question is already settled: use it. Ask at most ONE question. Give 2 to 4 concrete options in the user's language (the likeliest answers first), the user can always type
        their own, so never add an "other" option. When you ask, "action" is null and "reply" is the question in one short line. After the user answers, carry out the request at once and do not ask again.
        DECISION PROCEDURE (follow it every time): (1) Read ONLY the latest message; earlier turns are context, not orders. (2) Is it a question, chat, opinion or thanks? Answer it, action = null.
        (3) In the history, anything marked [ACTION DONE ...] is finished: never repeat it for "so?", "ok", "and?", "?" or similar; answer from its recorded result instead.
        (4) forget_about is ONLY for facts in Jarvis' memory. To get rid of an app, game, file or anything on the phone ("forget it", "remove it", "delete it", "get rid of it") use delete_file with its exact name.
        (5) Missing detail you cannot infer: one ask. Otherwise act. (6) The reply states only what the action will really do; never invent results.
        UNREADABLE OR TOO SHORT (a keyboard slip, random letters, one meaningless word): never show a menu of generic options like "what do you want me to do: send a message / open an app / search".
        Reply in one short line quoting what you saw and asking what they meant, action = null, ask = null. Use "ask" only for a specific missing detail of a real request.
        Never claim an action happened unless you issued it. For something truly outside all this, say the closest thing you can do and offer it.
    """.trimIndent()

    /** Old turns that would mislead the brain (pairing codes, past failed lookups) are dropped from its history. */
    private fun stale(t: String): Boolean {
        val s = t.lowercase()
        return s.contains("/pair") || s.contains("pair code") || s.contains("pairing code") ||
            s.contains("could not find an app called") || s.contains("bluetooth settings to pair")
    }

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
            parse(complete(system, userText, memory.recentTurns(10).filterNot { stale(it.text) }, imageB64, json = true, smart = true))
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
        json: Boolean = true,
        big: Boolean = false,
        play: Boolean = false,
        smart: Boolean = false
    ): String {
        val haveGemini = prefs.geminiKey.isNotEmpty()
        val haveGroq = prefs.groqKey.isNotEmpty() && imageB64 == null && !play
        if (!haveGemini && !haveGroq) {
            throw IllegalStateException(
                if (imageB64 != null && prefs.groqKey.isNotEmpty()) "Vision needs a Gemini key. Add one in Settings."
                else "No API key is set. Open Settings and add a free Gemini or Groq key."
            )
        }
        val runners = ArrayList<Pair<String, () -> String>>()
        if (haveGroq) runners.add("Groq" to { groqRun(system, groqMessages(system, history, userText), json, if (big) 8000 else 0, smart) })
        if (haveGemini) runners.add("Gemini" to { geminiRun(system, geminiContents(history, userText, imageB64), json, if (big) 16000 else 0, play || smart) })
        // healthy first; Groq leads when both are healthy; big generations prefer Gemini (much larger output limit)
        runners.sortBy { (if (isBad(it.first)) 2 else 0) + (if (big && it.first != "Gemini") 1 else 0) }
        return race(runners, hedgeMs = if (big) 70000 else if (smart) 9000 else 6000)
    }

    // ---- web answers ----

    /** Answers a question with live Google Search grounding through Gemini; falls back to memory when search is unavailable. */
    suspend fun search(question: String): String = withContext(Dispatchers.IO) {
        val fallback = plainSystem() + "\nWeb search is unavailable right now. Answer from memory and say clearly it may be outdated. Plain text."
        if (prefs.geminiKey.isEmpty()) return@withContext complete(fallback, question, emptyList(), null, json = false).trim()
        try {
            geminiSearch(question).trim()
        } catch (e: Exception) {
            complete(fallback, question, emptyList(), null, json = false).trim()
        }
    }

    private fun geminiSearch(q: String): String =
        tryModels(prefs.geminiModel, { discoverGemini() }, geminiFallbacks, { prefs.geminiModel = it }) { model ->
            val sys = plainSystem() + "\nAnswer using up-to-date web results. Be brief (at most 5 sentences), in the user's language, " +
                "plain text, no markdown. Name the source when it matters."
            val body = JSONObject()
                .put("contents", geminiContents(emptyList(), q, null))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("generationConfig", JSONObject().put("temperature", 0.3))
            val resp = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                body.toString().toByteArray(Charsets.UTF_8), "application/json",
                mapOf("x-goog-api-key" to prefs.geminiKey), 40000)
            val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
            if (sb.isBlank()) throw IllegalStateException("empty search answer")
            sb.toString()
        }

    // ---- finding downloads ----

    /** Asks Gemini (with live Google Search) where [what] can be downloaded legally and for free. Returns candidate URLs, best first. */
    suspend fun findLinks(what: String): List<String> = withContext(Dispatchers.IO) {
        if (prefs.geminiKey.isEmpty()) return@withContext emptyList<String>()
        try { groundedLinks(what) } catch (e: Exception) { emptyList() }
    }

    private fun groundedLinks(what: String): List<String> =
        tryModels(prefs.geminiModel, { discoverGemini() }, geminiFallbacks, { prefs.geminiModel = it }) { model ->
            val sys = "You find where a file can be downloaded legally and for free: the author's own site or forum thread, official mod hosts " +
                "(ModDB, Nexus Mods, Steam Workshop pages, itch.io), GitHub releases, F-Droid, or Drive/Mediafire links posted by the author. " +
                "Never pirated, cracked or paid-content links. Output ONLY up to 6 complete URLs, one per line, best first, " +
                "direct download links first when you know them. No commentary."
            val body = JSONObject()
                .put("contents", geminiContents(emptyList(), "Where can I download: $what", null))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
                .put("generationConfig", JSONObject().put("temperature", 0.2))
            val resp = request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                body.toString().toByteArray(Charsets.UTF_8), "application/json",
                mapOf("x-goog-api-key" to prefs.geminiKey), 40000)
            val cand = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
            val sb = StringBuilder()
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", "")).append('\n')
            val urls = ArrayList<String>()
            Regex("""https?://[^\s)\]>"'<,]+""").findAll(sb).forEach { urls.add(it.value.trimEnd('.', ';')) }
            val chunks = cand?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            if (chunks != null) for (i in 0 until chunks.length()) {
                chunks.optJSONObject(i)?.optJSONObject("web")?.optString("uri", "")?.takeIf { it.startsWith("http") }?.let { urls.add(it) }
            }
            urls.distinct()
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
                remember = o.optString("remember", "").takeIf { it.isNotEmpty() && it != "null" },
                ask = o.optJSONObject("ask")?.let { a ->
                    val q = a.optString("q", "").trim()
                    if (q.isEmpty() || q == "null") null else {
                        val arr = a.optJSONArray("options")
                        val opts = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() && it != "null" }.distinct().take(4)
                        AskSpec(q, opts, a.optString("why", "").trim().takeIf { it != "null" } ?: "")
                    }
                },
                understood = o.optString("understood", "").takeIf { it != "null" } ?: "",
                confidence = o.optDouble("confidence", 1.0)
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

    private fun geminiRun(system: String, contents: JSONArray, json: Boolean, maxOut: Int = 0, think: Boolean = false): String =
        tryModels(if (think) "gemini-flash-latest" else prefs.geminiModel, { discoverGemini() }, geminiFallbacks,
            { if (!think) prefs.geminiModel = it }) { model ->
            fun send(fast: Boolean): String {
                val gen = JSONObject().put("temperature", if (think) 0.3 else 0.7)
                if (maxOut > 0) gen.put("maxOutputTokens", maxOut)
                if (json) gen.put("responseMimeType", "application/json")
                if (think) {
                    // playing games needs real reasoning: a small thinking budget, on the stronger Flash model first
                    gen.put("thinkingConfig",
                        if (model.contains("2.5")) JSONObject().put("thinkingBudget", 1024)
                        else JSONObject().put("thinkingLevel", "low"))
                    if (maxOut == 0) gen.put("maxOutputTokens", 4096)
                } else if (fast) {
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
                    mapOf("x-goog-api-key" to prefs.geminiKey), if (maxOut > 0) 150000 else 30000)
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
    private fun groqRun(system: String, messages: JSONArray, @Suppress("UNUSED_PARAMETER") json: Boolean, maxOut: Int = 0, smart: Boolean = false): String =
        tryModels(if (smart) "openai/gpt-oss-120b" else prefs.groqModel, { discoverGroq() }, groqPreferred, { if (!smart) prefs.groqModel = it }) { model ->
            fun send(tuned: Boolean): String {
                val body = JSONObject().put("model", model).put("messages", messages).put("temperature", if (smart) 0.3 else 0.6)
                if (tuned) {
                    body.put("max_completion_tokens", if (maxOut > 0) maxOut else if (smart) 3000 else 1024)
                    if (model.contains("gpt-oss")) body.put("reasoning_effort", if (smart) "medium" else "low")
                    else if (model.contains("qwen")) body.put("reasoning_effort", "none")
                }
                val resp = request("POST", "https://api.groq.com/openai/v1/chat/completions",
                    body.toString().toByteArray(Charsets.UTF_8), "application/json",
                    mapOf("Authorization" to "Bearer ${prefs.groqKey}"), if (maxOut > 0) 150000 else 30000)
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
