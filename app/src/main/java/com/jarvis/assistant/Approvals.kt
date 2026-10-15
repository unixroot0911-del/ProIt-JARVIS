package com.jarvis.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Notifications that respect the user: one channel for results, one for "I need you", duplicates collapsed,
 * quiet hours honoured (except real questions), and no flood when many things finish at once.
 */
object Notify {
    private const val CH_TASKS = "axiom_tasks"
    private const val CH_ASK = "axiom_ask"
    private val lastKey = ConcurrentHashMap<String, Long>()
    private val burst = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    private fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun channels(c: Context) {
        nm(c).createNotificationChannel(NotificationChannel(CH_TASKS, "AXIOM results", NotificationManager.IMPORTANCE_DEFAULT))
        nm(c).createNotificationChannel(NotificationChannel(CH_ASK, "AXIOM needs you", NotificationManager.IMPORTANCE_HIGH))
    }

    /** kind: done, failed, quota, problem, info. Same key within [coolMs] is dropped. */
    fun post(kind: String, key: String, title: String, text: String, coolMs: Long = 60_000L) {
        val c = Axiom.ctx
        val now = System.currentTimeMillis()
        if ((lastKey[key] ?: 0L) + coolMs > now) return
        lastKey[key] = now
        val important = kind == "failed" || kind == "quota" || kind == "problem"
        if (Config.inQuietHours() && !important) return
        while (true) { val h = burst.peek() ?: break; if (now - h > 120_000L) burst.poll() else break }
        if (burst.size >= 4 && !important) return          // more than four in two minutes: the chat already has them
        burst.add(now)
        channels(c)
        val open = PendingIntent.getActivity(c, key.hashCode(), Intent(c, ControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(c, CH_TASKS)
            .setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(Axiom.clip(Secrets.redact(text), 160))
            .setStyle(android.app.Notification.BigTextStyle().bigText(Axiom.clip(Secrets.redact(text), 800)))
            .setContentIntent(open).setAutoCancel(true).build()
        try { nm(c).notify(key.hashCode(), n) } catch (e: SecurityException) { /* notifications not allowed */ }
    }

    fun askChannel(c: Context): String { channels(c); return CH_ASK }
}

/**
 * Questions that must wait for the user even when the app is closed: approvals for risky steps and clarifications.
 * The answer can come from a dialog, a notification button, the control center or Telegram, whichever is first.
 */
object Approvals {
    class Pending(val id: String, val text: String, val options: List<String>, val task: String, val deferred: CompletableDeferred<String>, val ts: Long)

    val pending = ConcurrentHashMap<String, Pending>()

    fun list(): List<Pending> = pending.values.sortedBy { it.ts }

    suspend fun yesNo(text: String, task: String = ""): Boolean = ask(text, listOf("yes", "no"), task) == "yes"

    /** Returns the chosen option (or the typed answer), or "" if nobody answered in time. */
    suspend fun ask(text: String, options: List<String>, task: String = "", timeoutMs: Long = 20 * 60_000L): String {
        val c = Axiom.ctx
        val id = Axiom.newId("a").takeLast(6)
        val p = Pending(id, text, options, task, CompletableDeferred(), System.currentTimeMillis())
        pending[id] = p
        if (task.isNotEmpty()) Tasks.set(task, "status" to Tasks.AWAITING, "note" to Axiom.clip(text, 200))
        Notify.channels(c)
        val yn = options == listOf("yes", "no")
        val b = android.app.Notification.Builder(c, Notify.askChannel(c))
            .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("AXIOM needs your answer")
            .setContentText(Axiom.clip(text, 120)).setStyle(android.app.Notification.BigTextStyle().bigText(Axiom.clip(text, 800)))
            .setContentIntent(PendingIntent.getActivity(c, 7, Intent(c, ControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (yn) {
            fun act(label: String, v: String) = android.app.Notification.Action.Builder(null, label,
                PendingIntent.getBroadcast(c, (id + v).hashCode(), Intent(c, ApprovalReceiver::class.java).setAction("axiom.answer").putExtra("id", id).putExtra("v", v),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
            b.addAction(act("Approve", "yes")).addAction(act("Deny", "no"))
        }
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id.hashCode(), b.build()) } catch (e: SecurityException) { /* ignore */ }

        val prefs = Prefs(c)
        if (prefs.telegramToken.isNotEmpty() && prefs.telegramChatId.isNotEmpty()) {
            val tg = StringBuilder("AXIOM needs you:\n").append(text).append("\n\n")
            if (yn) tg.append("Approve: /yes_").append(id).append("\nDeny: /no_").append(id)
            else { options.forEachIndexed { i, o -> tg.append("/pick_").append(id).append("_").append(i + 1).append("  ").append(o).append('\n') }; tg.append("or reply: /say_").append(id).append(" your answer") }
            Engine.scope.launch(kotlinx.coroutines.Dispatchers.IO) { Telegram.sendToOwner(prefs, tg.toString()) }
        }
        ChatBus.publish("system", "Waiting for you: " + Axiom.clip(text, 200))
        if (Engine.foreground != null) {
            Engine.scope.launch {
                if (yn) {
                    val r = Confirmer.ask(c, text)
                    answer(id, if (r) "yes" else "no")
                } else {
                    val r = Asker.ask(c, AskSpec(text.take(300), options.take(4), ""))
                    if (r != null) answer(id, r.text.ifBlank { options.firstOrNull() ?: "" })
                }
            }
        }
        val got = withTimeoutOrNull(timeoutMs) { p.deferred.await() }
        pending.remove(id)
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.hashCode()) } catch (e: Exception) { /* ignore */ }
        if (task.isNotEmpty()) Tasks.set(task, "status" to Tasks.RUNNING, "note" to "")
        return got ?: ""
    }

    fun answer(id: String, value: String): Boolean {
        val p = pending[id] ?: return false
        Audit.log("user", "approval", p.text, "answered: " + Axiom.clip(value, 100), "user", "", p.task)
        return p.deferred.complete(value)
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Approvals.answer(id, intent.getStringExtra("v") ?: "no")
    }
}
