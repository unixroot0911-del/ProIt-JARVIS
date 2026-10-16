package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

private val Scheduler_TYPES = setOf("set_alarm", "set_timer", "remind", "remind_at", "list_reminders", "cancel_reminder", "schedule_task")

/** [spoken] is what Jarvis says aloud; [shown] is what appears on screen or in the chat. */
data class Reply(val spoken: String, val shown: String, val failed: Boolean = false)

/** The single pipeline every input goes through: voice, typed text, chat windows, Telegram, briefings. */
class Assistant(context: Context) {
    companion object {
        @Volatile private var inst: Assistant? = null
        /** One assistant for tools, tasks and background work. */
        fun shared(): Assistant = inst ?: synchronized(this) { inst ?: Assistant(Axiom.ctx).also { inst = it } }
    }

    val appContext: Context = context.applicationContext
    init { Diag.install(appContext) }
    private val prefs = Prefs(appContext)
    private val memory = Memory.get(appContext)
    val brain = Shared.brain()
    private val actions = Actions(appContext)
    private val device = Device(appContext)

    private fun startOfDay(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun memoryBlock(text: String, mentions: List<String>): String {
        return try {
            val low = text.lowercase()
            val names = LinkedHashSet<String>()
            mentions.forEach { names.add(Dossier.canon(appContext, it)) }
            for (n in memory.dossierNames()) if (n.length >= 3 && low.contains(n)) names.add(n)
            names.add("user")
            val s = Dossier.text(appContext, names)
            if (s.isBlank()) "" else "SECTIONED MEMORY (what I know about what the user mentions; obey the standing orders):\n" + s + "\n"
        } catch (e: Exception) { "" }
    }

    private fun clip(s: String, n: Int) = if (s.length <= n) s else s.take(n) + "..."

    fun recentChat(n: Int): List<Turn> = memory.recentChat(n)

    fun buildContext(detailed: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("Now: ").append(SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())).append('\n')
        sb.append("Battery: ").append(device.batteryLine()).append('\n')
        sb.append("Pending reminders and alarms: ").append(Scheduler.summary(appContext)).append('\n')
        Creations.names(appContext).take(8).let { if (it.isNotEmpty()) sb.append("Apps and games built so far: ").append(it.joinToString(", ")).append('\n') }
        sb.append("Calendar today: ").append(device.calendarToday()).append('\n')
        sb.append(Inbox.contextLine())

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
        onProgress: (String) -> Unit,
        interactive: Boolean = true
    ): Reply {
        val low = text.trim().lowercase()
        if (low.startsWith("/pair")) {
            return Reply("Pairing codes go to the Telegram bot, not here.", "Pairing codes go to the Telegram bot, not here.")
        }
        if (low == "/start") return Reply("AXIOM online. Say what you need.", "AXIOM online. Say what you need.")
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
            handleInner(text, imageB64, confirm, progress, interactive)
        } catch (e: Exception) {
            val msg = e.message ?: "Something went wrong."
            Reply(msg, msg, failed = true)
        }
        logChat("jarvis", r.shown)
        return r
    }

    /** Lets tools and tasks run one of the phone actions through the same safe pipeline as chat. */
    suspend fun runAction(type: String, arg: String, confirm: suspend (String) -> Boolean, onProgress: (String) -> Unit): String? = execute(type, arg, confirm, onProgress)

    private fun slug(q: String): String = q.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(36).ifBlank { "answer" }

    private fun askText(a: AskSpec): String =
        a.q + (if (a.options.isEmpty()) "" else "\n" + a.options.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString("\n")) + (if (a.q.any { it in '؀'..'ۿ' }) "\nأجب برقم أو بكلماتك." else "\nAnswer with a number or in your own words.")

    private suspend fun handleInner(
        text: String,
        imageB64: String?,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit,
        interactive: Boolean
    ): Reply {
        // 0. Managing AXIOM itself (tasks, projects, usage, settings): rule-based, no model call.
        if (imageB64 == null) {
            val cmd = Commands.handle(text, prefs, brain)
            if (cmd != null) return Reply(cmd, cmd)
        }
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
        val mentions = withContext(Dispatchers.IO) { actions.mentions(text) }
        val ctx = withContext(Dispatchers.IO) {
            buildContext() + "Installed apps: " + actions.installedLabels().joinToString(", ") + "\n" +
                (if (mentions.isNotEmpty()) "Installed apps whose names resemble words in the message (a hint only, not necessarily what they mean): " + mentions.joinToString(", ") + "\n" else "") +
                (if (prefs.smartAsk) "" else "Smart questions are OFF: never use the ask key; decide with your best reading.\n") +
                memoryBlock(text, mentions)
        }
        var d = brain.think(text, ctx, imageB64)
        if (!d.failed && d.actionType == null && refusal.containsMatchIn(d.reply) && d.ask == null) {
            // A bare "I can't" is never the final answer: make the brain find a workable route and act.
            val d2 = brain.think(
                text + "\n\n[SYSTEM NOTE: your draft answer was a refusal or a dead end: \"" + d.reply.take(160) + "\". Do not refuse: find the closest workable route with your actions " +
                    "(download, web_answer, build_app, open_url, remind...) and issue it now, using exactly what the user said. " +
                    "Decline only if it is genuinely harmful or illegal, and then offer the legal alternative and do that instead.]",
                ctx, imageB64
            )
            if (!d2.failed && (d2.actionType != null || !refusal.containsMatchIn(d2.reply))) d = d2
        }
        // the brain wants to understand exactly before acting: ask on the screen (or as a text question where no screen is available)
        val spec = d.ask
        if (!d.failed && spec != null && prefs.smartAsk) {
            val ans: AskAnswer? = if (interactive) Asker.ask(appContext, spec) else null
            if (ans == null) {
                withContext(Dispatchers.IO) { memory.addTurn("user", text); memory.addTurn("model", askText(spec)) }
                val q = askText(spec)
                return Reply(q, q)
            }
            if (ans.remember && ans.text.isNotBlank()) {
                withContext(Dispatchers.IO) { Dossier.put(appContext, "preference", "user", slug(spec.q), "When asked \"" + spec.q.take(120) + "\" the user answered: \"" + ans.text.take(160) + "\"") }
            }
            val note = if (ans.text.isBlank()) "the user said: decide for me. Choose the most sensible reading yourself and act now"
            else "the user answered: \"" + ans.text + "\". Use this answer exactly as given"
            withContext(Dispatchers.IO) { memory.addTurn("user", text); memory.addTurn("model", spec.q); memory.addTurn("user", if (ans.text.isBlank()) "(decide for me)" else ans.text) }
            val d3 = brain.think(text + "\n\n[CLARIFICATION: you asked \"" + spec.q.take(200) + "\"; " + note + ". Carry out the original request now; do not ask again.]", ctx, imageB64)
            d = if (d3.failed) d3 else d3.copy(ask = null)
        } else if (spec != null) {
            d = d.copy(ask = null)
        }
        if (d.failed) return Reply(d.reply, d.reply, failed = true)

        val guarded = d.actionType?.let { Guard.needsConfirmation(it) } == true
        var shown = d.reply
        val type = d.actionType
        var status: String? = null
        if (type == "agent") {
            val id = TaskEngine.start(d.actionArg?.ifBlank { text } ?: text, direct = true, source = if (interactive) "chat" else "telegram")
            status = "Task ${id.takeLast(5)} started. It runs in the background and I will report when it is done. Say \"tasks\" to see progress, or \"stop ${id.takeLast(5)}\"."
        } else if (type != null) status = execute(type, d.actionArg ?: "", confirm, onProgress)
        // the history records what REALLY happened, so a follow-up like "so?" never repeats a finished action
        withContext(Dispatchers.IO) {
            memory.addTurn("user", text)
            val hist = if (type == null) d.reply
            else (if (guarded) "" else d.reply + " ") + "[ACTION DONE: " + type + "(" + clip(d.actionArg ?: "", 80) + ") -> " + clip(status ?: "ok", 160) + ". This task is finished; do not repeat it unless the user asks again.]"
            memory.addTurn("model", hist)
            d.remember?.let { memory.addFact(it) }
        }
        if (type != null) {
            if (guarded && !status.isNullOrBlank()) return Reply(status, status)   // only the real outcome, never "it is done" before it is
            if (!status.isNullOrBlank()) shown = shown + "\n" + status
        }
        return Reply(d.reply, shown)
    }

    /**
     * Payments and deletions: always ask first, and the question names the exact target. After a yes, Jarvis really does it:
     * deletions through [Deleter]; payments by driving the screen itself (each final money tap asks again).
     */
    private suspend fun protectedFlow(
        type: String,
        arg: String,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): String {
        val t = type.lowercase()
        if (t in setOf("delete_file", "delete", "remove_file", "uninstall", "uninstall_app", "delete_app")) {
            val found = withContext(Dispatchers.IO) { Deleter.resolve(appContext, arg) }
            if (found.isEmpty()) {
                return "I found nothing named \"$arg\" to delete (it may already be gone). I checked the apps and games I built, Downloads, Documents, Pictures, Movies, Music, DCIM and your installed apps."
            }
            val targets = if (t.contains("uninstall") || t == "delete_app") found.sortedByDescending { it.kind == "app" } else found
            val x = targets.first()
            val more = if (targets.size > 1) "\n\nOther matches (not touched): " + targets.drop(1).take(3).joinToString { it.label } else ""
            if (!confirm("Delete ${x.label}?\n${x.detail}$more")) return "Cancelled. Nothing was deleted."
            return withContext(Dispatchers.IO) { Deleter.perform(appContext, x) }
        }
        if (!confirm("Confirm: $type\n$arg")) return "Cancelled."
        return "I no longer operate other apps, so I cannot complete \"$type\" for you. Do the final step yourself in the app."
    }

    /** Direct use of any tool from the chat. The user typed the order, so it counts as direct (no extra approval for ordinary outside actions). */
    private suspend fun runTool(name: String, argJson: String, confirm: suspend (String) -> Boolean, onProgress: (String) -> Unit): String {
        val tool = Tools.get(name) ?: return "There is no tool called \"$name\"."
        val args = try { JSONObject(argJson.trim().ifEmpty { "{}" }) } catch (e: Exception) {
            // a single plain value goes to the tool's first argument
            JSONObject().apply { tool.args.firstOrNull()?.let { put(it.name, argJson.trim()) } }
        }
        val c = ToolCtx(appContext, brain, prefs, "", prefs.activeProject, false, true, confirm, onProgress)
        val r = Tools.execute(c, name, args)
        return (if (r.ok) "" else "Failed: ") + r.output + (if (r.files.isNotEmpty()) "\nFiles:\n" + r.files.joinToString("\n") else "")
    }

    private val refusal = Regex(
        "(i can'?t|i cannot|i can not|i'm unable|i am unable|unable to|can'?t help|can'?t assist|can'?t fetch|can'?t do that|won'?t be able|not able to)" +
            "|لا أستطيع|لا استطيع|لا يمكنني|ما نقدرش|ما كنقدرش|ماقدرش",
        RegexOption.IGNORE_CASE
    )

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
            else -> "I can send WhatsApp messages and SMS. I no longer operate other apps like $app, so send that one yourself."
        }
    }

    private suspend fun execute(
        type: String,
        arg: String,
        confirm: suspend (String) -> Boolean,
        onProgress: (String) -> Unit
    ): String? {
        if (Guard.needsConfirmation(type)) return protectedFlow(type, arg, confirm, onProgress)
        return try {
            val t = type.lowercase()
            when {
                t == "send_message" -> sendMessage(arg, confirm, onProgress)
                t == "send_whatsapp" || t == "whatsapp_message" || t == "send_whatsapp_message" ->
                    sendMessage(if (arg.count { it == '|' } >= 2) arg else "whatsapp|$arg", confirm, onProgress)
                t == "send_sms" -> withContext(Dispatchers.IO) { device.sendSms(arg) }
                t in Device.TYPES -> withContext(Dispatchers.IO) { device.run(t, arg) }
                t == "remember" -> {
                    val p = arg.split("|", limit = 4).map { it.trim() }
                    if (p.size < 4 || p[1].isEmpty() || p[3].isEmpty()) "To file that I need kind|name|key|value."
                    else { Dossier.put(appContext, p[0], p[1], p[2], p[3]); "Filed under " + p[1] + "." }
                }
                t == "forget_about" -> {
                    val p = arg.split("|", limit = 2).map { it.trim() }
                    val r = Dossier.forget(appContext, p.getOrElse(0) { "" }, p.getOrElse(1) { "" })
                    // "forget / remove X" about something that is a built app, file or installed app means delete it (with confirmation)
                    if (r.startsWith("I had nothing") && withContext(Dispatchers.IO) { Deleter.resolve(appContext, p.getOrElse(0) { "" }) }.isNotEmpty())
                        protectedFlow("delete_file", p.getOrElse(0) { "" }, confirm, onProgress)
                    else r
                }
                t == "memory_show" -> Dossier.overview(appContext, arg)
                t == "last_error" -> Diag.report(appContext)
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
                t == "download" -> Downloader.fetch(appContext, brain, arg, onProgress)
                t == "update_self" -> Updater.check(appContext, true)
                t == "check_update" -> Updater.check(appContext, false)
                t == "self_check" -> Health.report(appContext)
                t == "alert_on" || t == "alert_off" || t == "alert_list" -> Monitor.run(appContext, t, arg)
                t == "web_answer" -> brain.search(arg)
                t in Scheduler_TYPES -> Scheduler.run(appContext, t, arg)
                t == "tool" || Tools.get(t) != null -> runTool(if (t == "tool") arg.substringBefore('|').trim() else t, if (t == "tool") arg.substringAfter('|', "{}") else arg, confirm, onProgress)
                t == "build_app" -> Creations.build(appContext, brain, arg, onProgress)
                t == "open_creation" -> Creations.openByName(appContext, arg)
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
