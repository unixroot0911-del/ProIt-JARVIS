package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** [spoken] is what Jarvis says aloud; [shown] is what appears on screen or in the chat. */
data class Reply(val spoken: String, val shown: String, val failed: Boolean = false)

/** The single pipeline every input goes through: voice, typed text, chat windows, Telegram, briefings. */
class Assistant(context: Context) {
    val appContext: Context = context.applicationContext
    private val prefs = Prefs(appContext)
    private val memory = Memory.get(appContext)
    val brain = Brain(prefs, memory)
    private val actions = Actions(appContext)
    private val device = Device(appContext)

    private fun startOfDay(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n) + "..."

    fun recentChat(n: Int): List<Turn> = memory.recentChat(n)

    /** Records one utterance and returns its text, or null if nobody spoke. Throws a readable error on failure. */
    suspend fun hear(voice: Voice): String? {
        val wav = voice.record() ?: return null
        val t = brain.transcribe(wav).trim()
        return t.ifEmpty { null }
    }

    fun buildContext(detailed: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("Now: ").append(SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())).append('\n')
        sb.append("Battery: ").append(device.batteryLine()).append('\n')
        if (Watcher.active) sb.append("Watch mode: ON, watching the screen for: ").append(Watcher.topic).append('\n')
        Creations.names(appContext).take(8).let { if (it.isNotEmpty()) sb.append("Apps and games built so far: ").append(it.joinToString(", ")).append('\n') }
        sb.append("Calendar today: ").append(device.calendarToday()).append('\n')

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

    private suspend fun logChat(role: String, text: String) {
        withContext(Dispatchers.IO) { memory.addChat(role, text) }
        ChatBus.publish(role, text)
    }

    /** Handles one user message end to end and records it in the visible chat. */
    suspend fun handle(
        text: String,
        imageB64: String?,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): Reply {
        logChat("user", if (imageB64 != null) "[photo] $text" else text)

        val progress: (String) -> Unit = { m ->
            onProgress(m)
            if (m.startsWith("AGENT:")) {
                Engine.scope.launch(Dispatchers.IO) { memory.addChat("jarvis", m); ChatBus.publish("jarvis", m) }
            } else {
                ChatBus.publish("system", m)
            }
        }

        val r = try {
            handleInner(text, imageB64, confirm, progress)
        } catch (e: Exception) {
            val msg = e.message ?: "Something went wrong."
            Reply(msg, msg, failed = true)
        }
        logChat("jarvis", r.shown)
        return r
    }

    private suspend fun handleInner(
        text: String,
        imageB64: String?,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): Reply {
        // 1. Instant commands: no internet, no AI quota.
        if (imageB64 == null) {
            val off = Offline.parse(text, actions)
            if (off != null) {
                val type = off.actionType
                val status = if (type != null) execute(type, off.actionArg ?: "", confirm, onProgress) else null
                val out = status?.takeIf { it.isNotBlank() } ?: off.reply
                return Reply(out, out)
            }
        }

        // 2. Everything else goes to the brain.
        val ctx = withContext(Dispatchers.IO) { buildContext() }
        val d = brain.think(text, ctx, imageB64)
        if (d.failed) return Reply(d.reply, d.reply, failed = true)

        withContext(Dispatchers.IO) {
            memory.addTurn("user", text)
            memory.addTurn("model", d.reply)
            d.remember?.let { memory.addFact(it) }
        }

        var shown = d.reply
        val type = d.actionType
        if (type != null) {
            val status = execute(type, d.actionArg ?: "", confirm, onProgress)
            if (!status.isNullOrBlank()) shown = shown + "\n" + status
        }
        return Reply(d.reply, shown)
    }

    private fun startAgent(goal: String, confirm: suspend (String) -> Boolean, onProgress: (String) -> Unit): String {
        if (Engine.agentJob?.isActive == true) return "An agent task is already running. Say 'stop agent' first."
        val agent = ScreenAgent(appContext, brain)
        Engine.agentJob = Engine.scope.launch {
            val r = agent.run(goal, onProgress, confirm)
            onProgress("AGENT: $r")
        }
        return "Agent started."
    }

    /** Sends a message in any app: WhatsApp and SMS directly, every other app through the screen agent. */
    private suspend fun sendMessage(
        arg: String,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): String {
        val p = arg.split("|", limit = 3)
        val app = p.getOrElse(0) { "" }.trim()
        val who: String
        val msg: String
        if (p.size >= 3) { who = p[1].trim(); msg = p[2].trim() }
        else { who = p.getOrElse(0) { "" }.trim(); msg = p.getOrElse(1) { "" }.trim() }   // the brain forgot the app: plain SMS
        if (who.isEmpty() || msg.isEmpty()) return "I need to know who to message and what to say."

        val a = if (p.size >= 3) app.lowercase() else "sms"
        return when {
            a.contains("whatsapp") || a == "wa" || a == "واتساب" || a == "واتس" ->
                withContext(Dispatchers.IO) { device.whatsapp(who, msg) }
            a.isEmpty() || a.contains("sms") || a == "message" || a == "messages" || a == "text" || a == "رسالة" ->
                withContext(Dispatchers.IO) { device.sendSms("$who|$msg") }
            else -> startAgent(
                "Open the $app app, find the chat or conversation with \"$who\", type this exact message: \"$msg\" and send it. " +
                    "Then report that it was sent.", confirm, onProgress
            )
        }
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
            val t = type.lowercase()
            when {
                t == "send_message" -> sendMessage(arg, confirm, onProgress)
                t == "send_whatsapp" || t == "whatsapp_message" || t == "send_whatsapp_message" ->
                    sendMessage(if (arg.count { it == '|' } >= 2) arg else "whatsapp|$arg", confirm, onProgress)
                t == "send_sms" -> withContext(Dispatchers.IO) { device.sendSms(arg) }
                t in Device.TYPES -> withContext(Dispatchers.IO) { device.run(t, arg) }
                t == "stop_agent" -> {
                    val job = Engine.agentJob
                    if (job?.isActive == true) { job.cancel(); "Agent stopped." } else "No agent is running."
                }
                t == "reply_notification" -> {
                    val p = arg.split("|", limit = 3)
                    withContext(Dispatchers.Default) { Replier.reply(appContext, p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }) }
                }
                t == "log_expense" -> {
                    val p = arg.split("|", limit = 3)
                    val amount = p.getOrElse(0) { "" }.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.').toDoubleOrNull()
                    if (amount == null) "I could not read the amount."
                    else {
                        withContext(Dispatchers.IO) { memory.addExpense(amount, p.getOrElse(1) { "" }, p.getOrElse(2) { "" }) }
                        "Logged ${"%.2f".format(amount)}."
                    }
                }
                t == "log_habit" -> { withContext(Dispatchers.IO) { memory.logHabit(arg) }; "Logged habit: $arg." }
                t == "note" -> {
                    val p = arg.split("|", limit = 2)
                    withContext(Dispatchers.IO) { memory.addNote(p.getOrElse(0) { "note" }, p.getOrElse(1) { p[0] }) }
                    "Saved to your study notes."
                }
                t == "set_mode" -> {
                    prefs.mode = if (arg.trim().lowercase() == "study") "study" else "normal"
                    "Mode: ${prefs.mode}."
                }
                t == "run_agent" -> startAgent(arg, confirm, onProgress)
                t == "watch_start" -> Watcher.start(appContext, brain, arg)
                t == "watch_stop" -> Watcher.stop()
                t == "look_screen" -> Watcher.see(appContext, brain, arg)
                t == "web_answer" -> brain.search(arg)
                t == "remind" -> Reminders.set(appContext, arg)
                t == "build_app" -> Creations.build(appContext, brain, arg, onProgress)
                t == "open_creation" -> Creations.openByName(appContext, arg)
                t == "converse" -> {
                    Engine.converse = arg.trim().lowercase() !in listOf("off", "false", "no", "stop")
                    if (Engine.converse) "Conversation mode on. Just keep talking; say 'stop' to end it." else "Conversation mode off."
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
            "Write the user's MORNING briefing: a short greeting with no titles, today's date, today's calendar, what matters from the notifications, " +
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
