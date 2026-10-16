package com.jarvis.assistant

import android.app.Application
import android.content.Context
import java.security.MessageDigest

/** Process-wide handle. Set once when the app starts so every layer can reach the database and settings. */
class AxiomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Axiom.ctx = applicationContext
        Diag.install(applicationContext)
    }
}

object Axiom {
    const val NAME = "AXIOM"
    const val VERSION = "0.15.1"

    @Volatile lateinit var ctx: Context

    fun ready(): Boolean = try { ctx; true } catch (e: UninitializedPropertyAccessException) { false }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun newId(prefix: String): String = prefix + java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000 + java.util.Random().nextInt(9000))

    fun clip(s: String, n: Int): String = if (s.length <= n) s else s.take(n) + "..."
}

/**
 * Every setting that changes how AXIOM works, in one place, checked before it is accepted.
 * A bad or dangerous value is refused with a reason instead of being stored.
 */
object Config {
    data class Spec(val key: String, val label: String, val kind: String, val def: String, val min: Long = 0, val max: Long = 0, val options: List<String> = emptyList())

    val specs: List<Spec> = listOf(
        Spec("approval", "Approval level (strict asks for every outside action, relaxed only for dangerous ones)", "choice", "normal", options = listOf("strict", "normal", "relaxed")),
        Spec("language", "Reply language (auto = the language you wrote in)", "choice", "auto", options = listOf("auto", "ar", "en", "fr", "ar-MA")),
        Spec("detail", "Level of detail", "choice", "normal", options = listOf("brief", "normal", "deep")),
        Spec("max_steps", "Max steps in one plan", "int", "25", 1, 80),
        Spec("max_attempts", "Max attempts per step", "int", "3", 1, 6),
        Spec("tool_timeout_s", "Timeout per tool (seconds)", "int", "120", 5, 900),
        Spec("task_max_min", "Max minutes for one long task", "int", "30", 1, 240),
        Spec("max_parallel", "Max tasks running at the same time", "int", "2", 1, 4),
        Spec("cache_days", "Days to keep temporary memory", "int", "7", 1, 365),
        Spec("log_level", "Log level", "choice", "info", options = listOf("errors", "info", "debug")),
        Spec("gemini_rpm", "Gemini requests per minute allowed", "int", "12", 1, 300),
        Spec("gemini_rpd", "Gemini requests per day allowed", "int", "400", 10, 100000),
        Spec("groq_rpm", "Groq requests per minute allowed", "int", "25", 1, 300),
        Spec("groq_rpd", "Groq requests per day allowed", "int", "1000", 10, 100000),
        Spec("task_budget_calls", "Max model calls one task may use", "int", "60", 1, 1000),
        Spec("gemini_model", "Preferred Gemini model (empty = automatic)", "text", ""),
        Spec("gemini_fallback", "Fallback Gemini model (empty = automatic)", "text", ""),
        Spec("groq_model", "Preferred Groq model (empty = automatic)", "text", ""),
        Spec("groq_fallback", "Fallback Groq model (empty = automatic)", "text", ""),
        Spec("disabled_tools", "Tools switched off (comma separated)", "text", ""),
        Spec("quiet_from", "Quiet hours from HH:MM (empty = none)", "text", ""),
        Spec("quiet_to", "Quiet hours to HH:MM", "text", ""),
        Spec("backup_keep", "Backups to keep", "int", "5", 1, 30)
    )

    private fun sp() = Axiom.ctx.getSharedPreferences("axiom_cfg", Context.MODE_PRIVATE)

    fun get(key: String): String = sp().getString(key, null) ?: specs.firstOrNull { it.key == key }?.def ?: ""
    fun int(key: String): Int = get(key).toIntOrNull() ?: specs.firstOrNull { it.key == key }?.def?.toIntOrNull() ?: 0

    /** Returns null when the value was accepted, otherwise the reason it was refused. */
    fun set(key: String, value: String): String? {
        val s = specs.firstOrNull { it.key == key } ?: return "Unknown setting: $key"
        val v = value.trim()
        when (s.kind) {
            "int" -> {
                val n = v.toLongOrNull() ?: return "${s.label}: a whole number is needed."
                if (n < s.min || n > s.max) return "${s.label}: must be between ${s.min} and ${s.max}."
            }
            "choice" -> if (v !in s.options) return "${s.label}: choose one of " + s.options.joinToString(", ") + "."
            else -> {
                if (v.length > 300) return "${s.label}: too long."
                if (key.endsWith("_model") || key.endsWith("_fallback")) {
                    if (v.isNotEmpty() && !Regex("[A-Za-z0-9._/:\\-]{2,80}").matches(v)) return "${s.label}: that is not a valid model name."
                }
                if (key == "quiet_from" || key == "quiet_to") {
                    if (v.isNotEmpty() && !Regex("([01]?\\d|2[0-3]):[0-5]\\d").matches(v)) return "${s.label}: use HH:MM."
                }
                if (key == "disabled_tools") {
                    val protectedTools = setOf("ask_user", "final_answer")
                    if (v.split(",").map { it.trim() }.any { it in protectedTools }) return "These tools cannot be switched off: " + protectedTools.joinToString(", ")
                }
            }
        }
        if (Secrets.looksLikeSecret(v)) return "That looks like a secret key. Keys belong in the key fields, not here."
        sp().edit().putString(key, v).apply()
        return null
    }

    fun toolDisabled(name: String): Boolean = get("disabled_tools").split(",").map { it.trim() }.filter { it.isNotEmpty() }.contains(name)

    fun inQuietHours(): Boolean {
        val f = get("quiet_from"); val t = get("quiet_to")
        if (f.isEmpty() || t.isEmpty()) return false
        fun m(x: String): Int { val p = x.split(":"); return p[0].toInt() * 60 + p[1].toInt() }
        val c = java.util.Calendar.getInstance()
        val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val a = m(f); val b = m(t)
        return if (a <= b) now in a until b else (now >= a || now < b)
    }

    fun describe(): String = specs.joinToString("\n") { "${it.key} = ${get(it.key).ifEmpty { "(auto)" }}  — ${it.label}" }
}

/** One shared brain for the whole process (model discovery and health state are reused instead of repeated). */
object Shared {
    @Volatile private var b: Brain? = null
    fun brain(): Brain = b ?: synchronized(this) { b ?: Brain(Prefs(Axiom.ctx), Memory.get(Axiom.ctx)).also { b = it } }
}
