package com.jarvis.assistant

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Jarvis modes. Each mode has its own chat history and conversation memory, and changes how the brain thinks,
 * which tools it reaches for and what it says. Facts about the user are shared by every mode.
 */
object Modes {
    val all = listOf("normal", "gaming", "ai", "study")

    /** Called when the mode changes so open chat windows can swap their history. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun norm(s: String): String {
        val t = s.trim().lowercase()
        return when {
            t.contains("gam") || t.contains("لعب") || t.contains("ألعاب") || t.contains("العاب") -> "gaming"
            t.contains("study") || t.contains("دراس") -> "study"
            Regex("\\bai\\b").containsMatchIn(t) || t.contains("ذكاء") -> "ai"
            else -> "normal"
        }
    }

    fun current(ctx: Context): String = norm(Prefs(ctx).mode)

    fun label(key: String): String = when (key) {
        "gaming" -> "GAMING"
        "ai" -> "AI"
        "study" -> "STUDY"
        else -> "GENERAL"
    }

    fun hint(key: String): String = when (key) {
        "gaming" -> "Gaming mode: tell Jarvis which game to play"
        "ai" -> "AI mode: ask, think, write, code"
        "study" -> "Study mode: learn and get quizzed"
        else -> "Message Jarvis"
    }

    fun set(ctx: Context, arg: String): String {
        val key = norm(arg)
        val app = ctx.applicationContext
        if (current(app) != key) {
            Prefs(app).mode = key
            for (l in listeners) try { l() } catch (e: Exception) { /* ignore */ }
        }
        return when (key) {
            "gaming" -> "Gaming mode on. I play, remember every game and think like a co-pilot. This mode has its own chat."
            "ai" -> "AI mode on. Deep thinking, writing, coding and analysis; I stay out of your phone unless you ask. This mode has its own chat."
            "study" -> "Study mode on. I teach, quiz you and keep your weak points. This mode has its own chat."
            else -> "General mode on. Everything on the phone, as usual. This mode has its own chat."
        }
    }

    fun installedGames(ctx: Context): List<String> {
        return try {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0).mapNotNull { r ->
                val ai = r.activityInfo?.applicationInfo ?: return@mapNotNull null
                val game = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                    (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME)
                if (game) r.loadLabel(pm).toString() else null
            }.distinct().take(40)
        } catch (e: Exception) { emptyList() }
    }

    fun rules(key: String): String = when (key) {
        "study" ->
            "\n\nSTUDY MODE is ON: act as a sharp tutor. Explain simply, then test the user with one question at a time, " +
                "correct them directly, and save weak points with the \"note\" action so they can be reviewed later."
        "gaming" ->
            "\n\nGAMING MODE is ON. The phone is a game console and Jarvis is the player and co-pilot. Read every request in a gaming context. " +
                "Play, keep playing, continue, start, go, again, farm, grind, race, level up, finish the quest: all mean the play action (never run_agent) on the game just mentioned or the one open; " +
                "if none is named, use \"Installed games\" from the phone context and pick the one the user most likely means. " +
                "Use play_memory when asked what Jarvis knows about a game and play_forget to reset it; macro_create for repetitive routines; schedule_task for daily game chores. " +
                "Answer game questions (controls, builds, tier lists, strategies, glitches) like a pro with web_answer for anything recent. " +
                "Short, punchy, confident replies; no tutorials for what Jarvis can do itself: do it."
        "ai" ->
            "\n\nAI MODE is ON. Jarvis is a pure thinking partner: deep reasoning, analysis, writing, translation, coding, maths, research and planning. " +
                "Take the time to think step by step and give complete, well structured, accurate answers with the length the problem needs. " +
                "Do NOT operate the phone, open apps, play games or change settings unless the user explicitly orders that action in this message. " +
                "When the question depends on current facts, use web_answer. Offer concrete next steps, code or drafts, not vague advice."
        else -> ""
    }
}
