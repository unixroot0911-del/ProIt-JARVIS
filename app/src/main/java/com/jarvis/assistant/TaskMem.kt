package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Working memory for long orders. A long command is split once into ordered steps, saved on the phone, and carried out
 * step by step; progress survives interruptions, so "continue" resumes exactly where it stopped and nothing is forgotten or skipped.
 */
object TaskMem {

    class Task(val goal: String, val steps: List<String>, val done: BooleanArray, private val app: Context) {
        fun next(): Int = done.indexOfFirst { !it }
        fun markDone(i: Int) { if (i in done.indices) { done[i] = true; save() } }

        fun block(): String {
            val cur = next()
            val sb = StringBuilder("LONG TASK (saved working memory). Overall order: ").append(goal.take(500)).append('\n')
            for (i in steps.indices) {
                sb.append(i + 1).append(if (done[i]) " [done] " else if (i == cur) " [NOW] " else " [ ] ").append(steps[i]).append('\n')
            }
            sb.append("Work ONLY on the step marked NOW, exactly as ordered. Never skip, merge, repeat or forget a step. When the NOW step is finished, say so (step_done).")
            return sb.toString()
        }

        fun save() {
            try {
                val o = JSONObject().put("goal", goal).put("steps", JSONArray(steps)).put("done", JSONArray(done.toList()))
                app.getSharedPreferences("jarvis_task", Context.MODE_PRIVATE).edit().putString("cur", o.toString()).apply()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private const val SPLIT = """You split a long order for a phone assistant into ordered, atomic steps.
Keep the user's own words and every detail (names, numbers, texts to send, amounts). Do not add steps the user did not ask for and do not drop any.
Each step is one thing to do on the phone, in order. At most 25 steps.
Respond ONLY with JSON: {"steps":["step 1","step 2"]}"""

    private val connectors = Regex("(?i)\\b(then|after that|afterwards|next|and then|finally|also)\\b|ثم|بعدها|بعد ذلك|بعد ما|وبعد|وبعدين|وفي الأخير|أخيرا|اخيرا|;|\\n|\\d\\s*[.)]\\s")

    private fun load(ctx: Context): Task? = try {
        val s = ctx.getSharedPreferences("jarvis_task", Context.MODE_PRIVATE).getString("cur", null) ?: return null
        val o = JSONObject(s)
        val st = o.getJSONArray("steps"); val dn = o.getJSONArray("done")
        Task(o.getString("goal"), (0 until st.length()).map { st.getString(it) }, BooleanArray(st.length()) { dn.optBoolean(it, false) }, ctx.applicationContext)
    } catch (e: Exception) { null }

    fun clear(ctx: Context) {
        try { ctx.getSharedPreferences("jarvis_task", Context.MODE_PRIVATE).edit().remove("cur").apply() } catch (e: Exception) { /* ignore */ }
    }

    /** The unfinished long task, if any (for "continue"). */
    fun unfinished(ctx: Context): Task? = load(ctx)?.takeIf { it.steps.size > 1 && it.next() >= 0 }

    private fun looksLong(g: String): Boolean = g.length > 110 || connectors.findAll(g).count() >= 1 && g.length > 60 || connectors.findAll(g).count() >= 2

    /** Resumes the saved task for the same order, or splits a new long order into steps (one brain call). */
    suspend fun begin(ctx: Context, brain: Brain, goal: String): Task {
        val app = ctx.applicationContext
        load(app)?.let { if (it.goal == goal && it.next() >= 0) return it }
        val single = Task(goal, listOf(goal), BooleanArray(1), app)
        if (!looksLong(goal)) { clear(app); return single }
        return try {
            val o = JSONObject(Brain.extractJson(brain.complete(SPLIT, goal, emptyList(), null, json = true)))
            val a = o.optJSONArray("steps") ?: return single
            val list = (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.take(25)
            if (list.size < 2) { clear(app); single } else Task(goal, list, BooleanArray(list.size), app).also { it.save() }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { single }
    }
}
