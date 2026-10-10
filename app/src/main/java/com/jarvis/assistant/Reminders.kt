package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** "Remind me in 20 minutes to ...": survives restarts. Android may delay it by a few minutes in battery saver. */
object Reminders {
    fun set(ctx: Context, arg: String): String {
        val p = arg.split("|", limit = 2)
        val mins = p[0].trim().replace(',', '.').toDoubleOrNull() ?: return "I need the delay in minutes."
        val text = p.getOrElse(1) { "" }.trim().ifBlank { "Reminder" }
        val ms = (mins * 60_000).toLong().coerceAtLeast(5_000L)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(ms, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("text" to text))
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
        val m = ms / 60_000
        val when_ = if (m >= 60) "${m / 60} h ${m % 60} min" else if (m >= 1) "$m min" else "${ms / 1000} s"
        return "Reminder set for $when_ from now: $text"
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString("text") ?: "Reminder"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("jarvis_remind", "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(applicationContext, "jarvis_remind")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jarvis reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
        Engine.speaker?.invoke("Reminder: $text")
        ChatBus.publish("jarvis", "Reminder: $text")
        withContext(Dispatchers.IO) {
            Memory.get(applicationContext).addChat("jarvis", "Reminder: $text")
            Telegram.sendToOwner(Prefs(applicationContext), "Reminder: $text")
        }
        return Result.success()
    }
}
