package com.jarvis.assistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One pending alert. [kind] is "wake", "timer" or "reminder"; [rep] is the repeat interval in ms (0 = once). */
data class Due(val id: Long, val at: Long, val text: String, val kind: String, val rep: Long)

/**
 * Jarvis' own alarms, timers and reminders. It never touches the phone's clock or alarm app.
 * They survive restarts, fire on time even when Jarvis is closed, and are delivered discreetly (see [Alerts]).
 */
object Scheduler {
    private const val KEY = "items"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("jarvis_schedule", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): MutableList<Due> {
        val out = ArrayList<Due>()
        try {
            val a = JSONArray(sp(ctx).getString(KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Due(o.getLong("id"), o.getLong("at"), o.getString("text"), o.getString("kind"), o.optLong("rep", 0)))
            }
        } catch (e: Exception) { /* start empty */ }
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Due>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("kind", it.kind).put("rep", it.rep)) }
        sp(ctx).edit().putString(KEY, a.toString()).apply()
    }

    private fun fireIntent(ctx: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (id and 0x7fffffff).toInt(),
            Intent(ctx, AlarmReceiver::class.java).setAction("jarvis.fire").putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun arm(ctx: Context, d: Due) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = fireIntent(ctx, d.id)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d.at, pi)
            } else {
                val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(d.at, show), pi)   // exact without any special permission
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, d.at, pi)
        }
    }

    private fun disarm(ctx: Context, id: Long) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(fireIntent(ctx, id))
    }

    private fun add(ctx: Context, at: Long, text: String, kind: String, rep: Long): Due {
        val d = Due(System.currentTimeMillis(), at, text, kind, rep)
        val list = load(ctx)
        list.add(d)
        save(ctx, list)
        arm(ctx, d)
        return d
    }

    /** Called by the alarm receiver when an alert is due. */
    suspend fun fire(ctx: Context, id: Long) {
        val list = load(ctx)
        val d = list.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        if (d.rep > 0) {
            var n = d.at
            while (n <= now) n += d.rep
            val next = d.copy(at = n)
            list[list.indexOf(d)] = next
            save(ctx, list)
            arm(ctx, next)
        } else {
            list.remove(d)
            save(ctx, list)
        }
        Alerts.deliver(ctx, d, late = now - d.at > 120_000)
    }

    /** After a reboot or app update: re-arm everything, and deliver what was missed meanwhile. */
    fun rearmAll(ctx: Context) {
        val app = ctx.applicationContext
        Engine.scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for (d in load(app)) {
                if (d.at <= now) fire(app, d.id) else arm(app, d)
            }
        }
    }

    fun summary(ctx: Context): String {
        val l = load(ctx).sortedBy { it.at }.take(6)
        return if (l.isEmpty()) "none" else l.joinToString("; ") { "${label(it.at)} ${it.text}${if (it.rep > 0) " (repeats)" else ""}" }
    }

    private fun label(at: Long): String {
        val far = at - System.currentTimeMillis() > 20 * 3600_000L
        return SimpleDateFormat(if (far) "EEE d MMM HH:mm" else "HH:mm", Locale.ENGLISH).format(Date(at))
    }

    private fun repeatOf(s: String): Long = when (s.trim().lowercase()) {
        "daily", "every day", "يوميا", "يومياً" -> 86_400_000L
        "weekly", "every week", "اسبوعيا", "أسبوعياً" -> 604_800_000L
        "hourly", "every hour" -> 3_600_000L
        else -> 0L
    }

    /** "2026-10-12 18:30" or "18:30" (next occurrence). */
    private fun parseWhen(s: String): Long? {
        val t = s.trim()
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})[ T](\d{1,2}):(\d{2})$""").find(t)?.let { m ->
            val c = Calendar.getInstance()
            c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                m.groupValues[4].toInt(), m.groupValues[5].toInt(), 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        Regex("""^(\d{1,2}):(\d{2})$""").find(t)?.let { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h !in 0..23 || mi !in 0..59) return null
            val c = Calendar.getInstance()
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, mi); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis
        }
        return null
    }

    private fun human(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min${if (s % 60 != 0L) " ${s % 60} s" else ""}"
            else -> "$s s"
        }
    }

    /** Executes set_alarm, set_timer, remind, remind_at, list_reminders and cancel_reminder. */
    fun run(ctx: Context, type: String, arg: String): String {
        val now = System.currentTimeMillis()
        when (type) {
            "set_timer" -> {
                val secs = arg.filter { it.isDigit() }.toLongOrNull() ?: return "I need the timer length in seconds."
                add(ctx, now + secs * 1000, "Timer finished", "timer", 0)
                return "Timer set: ${human(secs * 1000)}."
            }
            "set_alarm" -> {
                val p = arg.split("|", limit = 3)
                val at = parseWhen(p[0]) ?: return "I need the alarm time as HH:MM."
                val text = p.getOrElse(1) { "" }.trim().ifBlank { "Wake up" }
                add(ctx, at, text, "wake", repeatOf(p.getOrElse(2) { "" }))
                return "Alarm set for ${label(at)}. Quiet vibration, no ringing."
            }
            "remind" -> {
                val p = arg.split("|", limit = 3)
                val mins = p[0].trim().replace(',', '.').toDoubleOrNull() ?: return "I need the delay in minutes."
                val ms = (mins * 60_000).toLong().coerceAtLeast(5_000L)
                val text = p.getOrElse(1) { "" }.trim().ifBlank { "Reminder" }
                add(ctx, now + ms, text, "reminder", repeatOf(p.getOrElse(2) { "" }))
                return "I will remind you in ${human(ms)}: $text"
            }
            "remind_at" -> {
                val p = arg.split("|", limit = 3)
                val at = parseWhen(p[0]) ?: return "I need the date and time as yyyy-MM-dd HH:mm."
                if (at <= now) return "That time has already passed."
                val text = p.getOrElse(1) { "" }.trim().ifBlank { "Reminder" }
                add(ctx, at, text, "reminder", repeatOf(p.getOrElse(2) { "" }))
                return "I will remind you at ${label(at)}: $text"
            }
            "list_reminders" -> {
                val l = load(ctx).sortedBy { it.at }
                if (l.isEmpty()) return "No pending reminders or alarms."
                return l.joinToString("\n") { "${label(it.at)}  ${it.text}${if (it.rep > 0) " (repeats)" else ""}" }
            }
            "cancel_reminder" -> {
                val q = arg.trim().lowercase()
                val list = load(ctx)
                val hit = if (q.isEmpty() || q == "all" || q == "الكل") list.toList()
                else list.filter { it.text.lowercase().contains(q) || label(it.at).contains(q) }
                hit.forEach { disarm(ctx, it.id) }
                save(ctx, list.filter { it !in hit })
                return if (hit.isEmpty()) "I found no reminder matching \"$arg\"." else "Cancelled ${hit.size}."
            }
        }
        return "Unknown schedule action."
    }
}

/**
 * Discreet delivery. No ringtone, no clock app, nothing that makes noise in public:
 * a gentle vibration and a silent pop-up; Jarvis speaks only when headphones are connected; at night only a silent pop-up
 * (wake-up alarms still vibrate, firmly). The same message also goes to your paired Telegram and the Jarvis chat.
 */
object Alerts {

    private fun headset(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26 /* BLE headset */
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    suspend fun deliver(ctx: Context, d: Due, late: Boolean) {
        val text = if (late) "${d.text} (missed earlier)" else d.text
        val wake = d.kind == "wake"
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = hour >= 23 || hour < 6

        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("jarvis_quiet", "Jarvis quiet alerts", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)
            ch.enableVibration(false)
            nm.createNotificationChannel(ch)
            val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify((d.id and 0x7fffffff).toInt(), Notification.Builder(ctx, "jarvis_quiet")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (wake) "Jarvis: wake up" else "Jarvis")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) { /* notifications may be off */ }

        if (!night || wake) {
            try {
                val pattern: LongArray = if (wake) {
                    val p = ArrayList<Long>()
                    p.add(0L)
                    for (i in 0 until 25) { p.add(300L + i * 60L); p.add(700L) }   // about 45 s of growing pulses
                    p.toLongArray()
                } else longArrayOf(0, 150, 120, 150, 120, 300)
                @Suppress("DEPRECATION")
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } catch (e: Exception) { /* no vibrator */ }
        }

        if (headset(ctx)) Engine.speaker?.invoke(if (wake) "Time to wake up. ${d.text}" else text)

        ChatBus.publish("jarvis", "Reminder: $text")
        withContext(Dispatchers.IO) {
            try { Memory.get(ctx).addChat("jarvis", "Reminder: $text") } catch (e: Exception) { /* ignore */ }
            try { Telegram.sendToOwner(Prefs(ctx), "Reminder: $text") } catch (e: Exception) { /* ignore */ }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext
        Engine.scope.launch {
            try { Scheduler.fire(app, id) } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rearmAll(context)
    }
}
