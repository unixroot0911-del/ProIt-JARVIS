package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** [spoken] is what Jarvis says aloud; [shown] adds any action result for the screen or chat. */
data class Reply(val spoken: String, val shown: String)

/** The single pipeline every input goes through: voice, typed text, Telegram, briefings. */
class Assistant(context: Context) {
    val appContext: Context = context.applicationContext
    private val prefs = Prefs(appContext)
    private val memory = Memory.get(appContext)
    val brain = Brain(prefs, memory)
    private val actions = Actions(appContext)

    private fun startOfDay(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n) + "..."

    fun buildContext(detailed: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("Now: ").append(SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())).append('\n')

        val hours = if (detailed) 14 else 8
        val notifs = memory.recentNotifs(System.currentTimeMillis() - hours * 3600_000L, if (detailed) 30 else 12)
        if (notifs.isEmpty()) {
            sb.append("Notifications: none captured (or notification access is off)\n")
        } else {
            sb.append("Recent notifications (newest first):\n")
            val fmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
            for (n in notifs) {
                sb.append("- ").append(fmt.format(Date(n.ts))).append(" [").append(n.app).append("] ")
                    .append(clip(n.title, 40)).append(": ").append(clip(n.text, 90)).append('\n')
            }
        }
        sb.append("Spending today: ").append(memory.expenseSummary(startOfDay())).append('\n')
        sb.append("Habits today: ").append(memory.habitSummary(startOfDay())).append('\n')
        if (detailed) {
            val week = System.currentTimeMillis() - 7 * 86400_000L
            sb.append("Spending last 7 days: ").append(memory.expenseSummary(week)).append('\n')
            sb.append("Habits last 7 days: ").append(memory.habitSummary(week)).append('\n')
        }
        if (prefs.mode == "study" || detailed) {
            val notes = memory.notes(15)
            if (notes.isNotEmpty()) sb.append("Study notes to review:\n").append(notes.joinToString("\n") { "- $it" }).append('\n')
        }
        return sb.toString()
    }

    suspend fun handle(
        text: String,
        imageB64: String?,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): Reply {
        val ctx = withContext(Dispatchers.IO) { buildContext() }
        val d = brain.think(text, ctx, imageB64)
        withContext(Dispatchers.IO) {
            memory.addTurn("user", text)
            memory.addTurn("model", d.reply)
            d.remember?.let { memory.addFact(it) }
        }

        var shown = d.reply
        val type = d.actionType
        val arg = d.actionArg
        if (type != null && arg != null) {
            val status = execute(type, arg, confirm, onProgress)
            if (!status.isNullOrBlank()) shown = shown + "\n" + status
        }
        return Reply(d.reply, shown)
    }

    private suspend fun execute(
        type: String,
        arg: String,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): String? {
        if (Guard.needsConfirmation(type)) {
            return if (confirm("Confirm action: $type\n$arg"))
                "Confirmed, but '$type' is not wired up in this version, so I did not do it. Do it yourself for now."
            else "Cancelled."
        }
        return try {
            when (type.lowercase()) {
                "reply_notification" -> {
                    val p = arg.split("|", limit = 3)
                    withContext(Dispatchers.Default) { Replier.reply(appContext, p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }) }
                }
                "log_expense" -> {
                    val p = arg.split("|", limit = 3)
                    val amount = p.getOrElse(0) { "" }.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').toDoubleOrNull()
                    if (amount == null) "I could not read the amount."
                    else {
                        withContext(Dispatchers.IO) { memory.addExpense(amount, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }) }
                        "Logged ${"%.2f".format(amount)}."
                    }
                }
                "log_habit" -> { withContext(Dispatchers.IO) { memory.logHabit(arg) }; "Logged habit: $arg." }
                "note" -> {
                    val p = arg.split("|", limit = 2)
                    withContext(Dispatchers.IO) { memory.addNote(p.getOrElse(0) { "note" }, p.getOrElse(1) { p[0] }) }
                    "Saved to your study notes."
                }
                "set_mode" -> {
                    prefs.mode = if (arg.trim().lowercase() == "study") "study" else "normal"
                    "Mode: ${prefs.mode}."
                }
                "run_agent" -> {
                    if (Engine.agentJob?.isActive == true) "An agent task is already running. Stop it first."
                    else {
                        val agent = ScreenAgent(appContext, brain)
                        Engine.agentJob = Engine.scope.launch {
                            val r = agent.run(arg, onProgress, confirm)
                            onProgress("AGENT: $r")
                        }
                        "Agent started."
                    }
                }
                else -> actions.run(type, arg)
            }
        } catch (e: Exception) {
            "Action '$type' failed: ${e.message}"
        }
    }

    /** Free-form briefing text built from everything Jarvis knows. */
    suspend fun briefing(kind: String): String {
        val data = withContext(Dispatchers.IO) { buildContext(detailed = true) }
        val ask = if (kind == "morning")
            "Write the user's MORNING briefing: a short greeting with no titles, today's date, what matters from the notifications, " +
                "a snapshot of habits and spending, and the 3 priorities you suggest for today. Maximum 120 words."
        else
            "Write the user's EVENING briefing: what happened today, anything unanswered in the notifications, " +
                "how habits and spending went, and one thing to prepare for tomorrow. Maximum 120 words."
        return brain.complete(
            brain.plainSystem() + "\nWrite in Arabic (Modern Standard with light Moroccan Darija). Plain text, no markdown.",
            ask + "\n\nDATA:\n" + data,
            emptyList(), null, json = false
        )
    }
}
