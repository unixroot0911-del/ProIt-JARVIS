package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches every request: how many were made, how many tokens, which model is cooling down after a rate limit,
 * which key was rejected. Nothing here assumes the free tier is unlimited.
 */
object Quota {
    private val minute = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedQueue<Long>>()
    private val cooldown = ConcurrentHashMap<String, Long>()
    private val auth = ConcurrentHashMap<String, Long>()
    private val callsByTask = ConcurrentHashMap<String, Int>()

    private fun sp() = Axiom.ctx.getSharedPreferences("axiom_quota", Context.MODE_PRIVATE)

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun dayCount(provider: String): Int {
        val s = sp()
        return if (s.getString("day", "") == today()) s.getInt("n_$provider", 0) else 0
    }

    private fun bumpDay(provider: String) {
        val s = sp(); val t = today()
        val e = s.edit()
        var n = 0
        if (s.getString("day", "") == t) n = s.getInt("n_$provider", 0) else e.clear().putString("day", t)
        e.putInt("n_$provider", n + 1).apply()
    }

    /** Called for every POST to a model provider. */
    fun request(provider: String, model: String, status: Int) {
        val q = minute.getOrPut(provider) { java.util.concurrent.ConcurrentLinkedQueue() }
        q.add(System.currentTimeMillis())
        bumpDay(provider)
        if (status == 401 || status == 403) auth[provider] = System.currentTimeMillis()
        if (status in 200..299) auth.remove(provider)
        try {
            val cv = android.content.ContentValues().apply {
                put("ts", System.currentTimeMillis()); put("provider", provider); put("model", model); put("status", status); put("tin", 0); put("tout", 0)
            }
            Store.get().insert("usage", cv)
        } catch (e: Exception) { /* ignore */ }
    }

    fun tokens(provider: String, tin: Int, tout: Int) {
        try {
            Store.get().exec("UPDATE usage SET tin = ?, tout = ? WHERE id = (SELECT MAX(id) FROM usage WHERE provider = ?)", tin, tout, provider)
        } catch (e: Exception) { /* ignore */ }
    }

    fun authFailed(provider: String): Boolean {
        val t = auth[provider] ?: return false
        if (System.currentTimeMillis() - t > 10 * 60_000L) { auth.remove(provider); return false }   // look again after ten minutes
        return true
    }

    fun cool(model: String, ms: Long) { cooldown[model] = System.currentTimeMillis() + ms.coerceIn(5_000L, 6 * 3600_000L) }
    fun cooling(model: String): Boolean = (cooldown[model] ?: 0L) > System.currentTimeMillis()

    private fun rpm(provider: String) = Config.int(if (provider == "Gemini") "gemini_rpm" else "groq_rpm")
    private fun rpd(provider: String) = Config.int(if (provider == "Gemini") "gemini_rpd" else "groq_rpd")

    fun usedLastMinute(provider: String): Int {
        val q = minute[provider] ?: return 0
        val cut = System.currentTimeMillis() - 60_000L
        while (true) { val h = q.peek() ?: break; if (h < cut) q.poll() else break }
        return q.size
    }

    /** True when one more request would stay inside the limits the user set. */
    fun allow(provider: String): Boolean = usedLastMinute(provider) < rpm(provider) && dayCount(provider) < rpd(provider)

    fun nearLimit(provider: String): Boolean = dayCount(provider) >= rpd(provider) * 8 / 10 || usedLastMinute(provider) >= rpm(provider) * 8 / 10

    /** Per-task call budget: a task that used up its calls is stopped, not allowed to run on. */
    fun taskCall(task: String): Boolean {
        if (task.isEmpty()) return true
        val n = (callsByTask[task] ?: 0) + 1
        callsByTask[task] = n
        return n <= Config.int("task_budget_calls")
    }

    fun taskCalls(task: String): Int = callsByTask[task] ?: 0
    fun forgetTask(task: String) { callsByTask.remove(task) }

    fun report(): String {
        val sb = StringBuilder()
        for (p in listOf("Gemini", "Groq")) {
            sb.append(p).append(": ").append(dayCount(p)).append("/").append(rpd(p)).append(" today, ")
                .append(usedLastMinute(p)).append("/").append(rpm(p)).append(" this minute")
            if (authFailed(p)) sb.append("  KEY REJECTED")
            if (nearLimit(p)) sb.append("  near the limit")
            sb.append('\n')
        }
        val cooling = cooldown.filter { it.value > System.currentTimeMillis() }
        if (cooling.isNotEmpty()) sb.append("Cooling down: ").append(cooling.keys.joinToString(", ")).append('\n')
        val rows = Store.get().query(
            "SELECT provider, COUNT(*) AS n, SUM(tin) AS ti, SUM(tout) AS tout, SUM(CASE WHEN status BETWEEN 200 AND 299 THEN 0 ELSE 1 END) AS bad " +
                "FROM usage WHERE ts > ? GROUP BY provider", (System.currentTimeMillis() - 7 * 86400_000L).toString()
        )
        for (r in rows) sb.append("Last 7 days ").append(r["provider"]).append(": ").append(r["n"]).append(" requests, ").append(r["bad"]).append(" failed, ")
            .append(r["ti"]).append(" tokens in, ").append(r["tout"]).append(" out\n")
        return sb.toString().trim()
    }
}

/**
 * Chooses the right model for each kind of job and keeps going when one fails.
 * Model names are never hard-wired: each provider is asked what it offers, and the best fit is picked from that list.
 */
object Router {
    val lastProvider = ConcurrentHashMap<String, String>()

    enum class T { CHAT, CLASSIFY, SUMMARIZE, EXTRACT, REASON, PLAN, VERIFY, CODE, WRITE, LONGDOC, FRESH }

    class Opts(
        val system: String,
        val user: String,
        val history: List<Turn> = emptyList(),
        val image: String? = null,
        val json: Boolean = false,
        val maxOut: Int = 0,
        val cacheTtlMs: Long = 0L,
        val task: String = "",
        val avoidProvider: String = ""
    )

    /** How each kind of job should be run: which provider leads, how hard to think, how warm to be. */
    private class Profile(val order: List<String>, val strong: Boolean, val thinking: Int, val effort: String, val temp: Double, val hedgeMs: Long)

    private fun profile(t: T): Profile = when (t) {
        T.CHAT -> Profile(listOf("Groq", "Gemini"), false, 0, "low", 0.7, 6000)
        T.CLASSIFY -> Profile(listOf("Groq", "Gemini"), false, 0, "low", 0.1, 6000)
        T.SUMMARIZE -> Profile(listOf("Gemini", "Groq"), false, 0, "low", 0.3, 15000)
        T.EXTRACT -> Profile(listOf("Gemini", "Groq"), false, 1, "low", 0.1, 15000)
        T.REASON -> Profile(listOf("Groq", "Gemini"), true, 1, "medium", 0.3, 9000)
        T.PLAN -> Profile(listOf("Gemini", "Groq"), true, 2, "medium", 0.3, 20000)
        T.VERIFY -> Profile(listOf("Gemini", "Groq"), true, 2, "medium", 0.1, 20000)
        T.CODE -> Profile(listOf("Groq", "Gemini"), true, 2, "medium", 0.2, 25000)
        T.WRITE -> Profile(listOf("Gemini", "Groq"), true, 1, "low", 0.8, 20000)
        T.LONGDOC -> Profile(listOf("Gemini"), true, 1, "low", 0.2, 60000)
        T.FRESH -> Profile(listOf("Gemini"), false, 0, "low", 0.2, 30000)
    }

    private fun geminiCandidates(brain: Brain, strong: Boolean): List<String> {
        val info = brain.geminiInfo()
        val usable = info.filter { it.methods.contains("generateContent") }.map { it.name }
        val banned = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "native", "learnlm", "gemma", "customtools", "veo", "imagen")
        val flash = brain.discoverGeminiRanked(usable).ifEmpty { brain.geminiFallbacks }
        val pro = usable.filter { it.contains("pro") && banned.none { b -> it.contains(b) } && !it.contains("preview") }
        val list = ArrayList<String>()
        Config.get("gemini_model").takeIf { it.isNotEmpty() }?.let { list.add(it) }
        if (strong) {
            list.addAll(flash.filter { !it.contains("lite") })
            list.addAll(flash.filter { it.contains("lite") })
            list.addAll(pro.take(1))
        } else {
            list.addAll(flash.filter { it.contains("lite") })
            list.addAll(flash.filter { !it.contains("lite") })
        }
        Config.get("gemini_fallback").takeIf { it.isNotEmpty() }?.let { list.add(it) }
        return list.distinct()
    }

    private fun groqCandidates(brain: Brain, strong: Boolean): List<String> {
        val all = brain.groqInfoList().map { it.name }
        val banned = listOf("whisper", "orpheus", "guard", "tts", "embed", "compound", "safeguard")
        val ok = all.filter { id -> banned.none { id.contains(it) } }.ifEmpty { brain.groqPreferred }
        val ranked = ok.sortedBy { id -> brain.groqPreferred.indexOf(id).let { if (it >= 0) it else 100 + (if (id.contains("llama")) 1 else 0) } }
        val list = ArrayList<String>()
        Config.get("groq_model").takeIf { it.isNotEmpty() }?.let { list.add(it) }
        if (strong) list.addAll(ranked) else {
            list.addAll(ranked.filter { it.contains("20b") || it.contains("8b") || it.contains("instant") })
            list.addAll(ranked)
        }
        Config.get("groq_fallback").takeIf { it.isNotEmpty() }?.let { list.add(it) }
        return list.distinct()
    }

    private fun friendly(e: Exception): String = Secrets.redact(e.message ?: e.javaClass.simpleName)

    /** Runs one job on the best available model. Throws a readable reason when nothing worked. */
    suspend fun run(brain: Brain, type: T, o: Opts): String {
        val pr = profile(type)
        val key = if (o.cacheTtlMs > 0) "r:" + Axiom.sha1(type.name + "|" + o.system + "|" + o.user + "|" + o.history.takeLast(2).joinToString { it.text }) else ""
        if (key.isNotEmpty()) Cache.get(key)?.let { return it }
        if (!Quota.taskCall(o.task)) throw IllegalStateException("This task used all ${Config.int("task_budget_calls")} model calls it was allowed. Raise the budget in settings if it should continue.")

        val t0 = System.currentTimeMillis()
        val runners = ArrayList<Pair<String, () -> String>>()
        val skipped = ArrayList<String>()
        for (p in pr.order) {
            if (p == o.avoidProvider && pr.order.size > 1) continue
            if (p == "Gemini" && !brain.hasGemini()) { skipped.add("Gemini: no working key"); continue }
            if (p == "Groq" && (!brain.hasGroq() || o.image != null)) { skipped.add("Groq: " + if (o.image != null) "cannot see images" else "no working key"); continue }
            if (!Quota.allow(p)) { skipped.add("$p: at the limit you set (" + Quota.report().lineSequence().firstOrNull { it.startsWith(p) } + ")"); continue }
            val models = (if (p == "Gemini") geminiCandidates(brain, pr.strong) else groqCandidates(brain, pr.strong)).filter { !Quota.cooling(it) }
            if (models.isEmpty()) { skipped.add("$p: every model is cooling down after a rate limit"); continue }
            runners.add(p to { generate(brain, p, models.take(4), pr, o, type) })
        }
        if (runners.isEmpty()) throw IllegalStateException("No brain available. " + skipped.joinToString("; "))
        runners.sortBy { if (Brain.isBad(it.first)) 1 else 0 }

        val won = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val tracked = runners.map { r -> r.first to { val x = r.second(); won.compareAndSet(null, r.first); x } }
        val text = try {
            brain.race(tracked, pr.hedgeMs)
        } catch (e: Exception) {
            Perf.log("model", type.name, "", System.currentTimeMillis() - t0, 1, 0, false, friendly(e))
            throw e
        }
        if (o.task.isNotEmpty()) won.get()?.let { lastProvider[o.task] = it }
        Perf.log("model", type.name, "", System.currentTimeMillis() - t0, 1, (o.system.length + o.user.length + text.length) / 4, true)
        if (key.isNotEmpty() && text.isNotBlank()) Cache.put(key, text, o.cacheTtlMs)
        return text
    }

    /** Like [run] but the answer must be a JSON object; one repair attempt on the other provider if it is not. */
    suspend fun runJson(brain: Brain, type: T, o: Opts): JSONObject {
        val first = run(brain, type, o)
        try { return JSONObject(Brain.extractJson(first)) } catch (e: Exception) { /* repair below */ }
        val again = run(brain, type, Opts(
            o.system + "\n\nYour last answer was not valid JSON. Return ONLY one valid JSON object, nothing else.", o.user, o.history, o.image,
            json = true, maxOut = o.maxOut, task = o.task, avoidProvider = ""
        ))
        return JSONObject(Brain.extractJson(again))
    }

    private fun generate(brain: Brain, provider: String, models: List<String>, pr: Profile, o: Opts, type: T): String {
        var last: Exception = IllegalStateException("$provider produced nothing")
        for (m in models) {
            if (Quota.cooling(m)) continue
            try {
                val t0 = System.currentTimeMillis()
                val g = if (provider == "Gemini") {
                    val grounded = type == T.FRESH
                    brain.gemGen(m, o.system, brain.geminiContents(o.history, o.user, o.image), pr.temp, o.maxOut, pr.thinking, o.json, grounded, if (o.maxOut > 8000) 150000 else 45000)
                } else {
                    brain.groqGen(m, brain.groqMessages(o.system, o.history, o.user), pr.temp, o.maxOut, pr.effort, if (o.maxOut > 8000) 150000 else 45000)
                }
                if (g.text.isBlank()) throw IllegalStateException("$m returned an empty answer")
                Perf.log("model", m, provider, System.currentTimeMillis() - t0, 1, g.inTok + g.outTok, true)
                return if (g.sources.isNotEmpty()) g.text + "\n\nSOURCES:\n" + g.sources.take(8).joinToString("\n") else g.text
            } catch (e: HttpError) {
                last = e
                Perf.log("model", m, provider, 0, 1, 0, false, "HTTP ${e.code}")
                when {
                    e.code == 429 -> Quota.cool(m, if (e.retryAfterMs > 0) e.retryAfterMs else 60_000L)
                    e.code == 404 || (e.code == 400 && e.body.contains("model", true)) -> Quota.cool(m, 6 * 3600_000L)
                    e.code == 401 || e.code == 403 -> throw IllegalStateException("$provider rejected the key (HTTP ${e.code}). Replace it in settings.")
                    e.retriable -> Quota.cool(m, 20_000L)
                    else -> throw e
                }
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw last
    }
}
