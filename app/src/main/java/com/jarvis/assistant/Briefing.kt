package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Briefings {
    fun schedule(context: Context) {
        val prefs = Prefs(context)
        val wm = WorkManager.getInstance(context)
        if (!prefs.briefingsOn) {
            wm.cancelUniqueWork("brief_morning")
            wm.cancelUniqueWork("brief_evening")
            return
        }
        enqueue(context, "morning", prefs.morningTime)
        enqueue(context, "evening", prefs.eveningTime)
    }

    fun enqueue(context: Context, kind: String, hhmm: String) {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(hhmm)
        val h = (m?.groupValues?.get(1)?.toIntOrNull() ?: if (kind == "morning") 7 else 21).coerceIn(0, 23)
        val min = (m?.groupValues?.get(2)?.toIntOrNull() ?: if (kind == "morning") 30 else 0).coerceIn(0, 59)

        val now = Calendar.getInstance()
        val t = Calendar.getInstance()
        t.set(Calendar.HOUR_OF_DAY, h); t.set(Calendar.MINUTE, min); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0)
        if (t.timeInMillis <= now.timeInMillis + 1000) t.add(Calendar.DAY_OF_MONTH, 1)

        val req = OneTimeWorkRequestBuilder<BriefingWorker>()
            .setInitialDelay(t.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("kind" to kind))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("brief_$kind", ExistingWorkPolicy.REPLACE, req)
    }

    fun post(context: Context, kind: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("jarvis_brief", "Jarvis briefings", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(context, "jarvis_brief")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (kind == "morning") "Jarvis: morning briefing" else "Jarvis: evening briefing")
            .setContentText(text.take(100))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(if (kind == "morning") 2 else 3, n)
    }
}

class BriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: "morning"
        val prefs = Prefs(applicationContext)
        val text = try {
            Assistant(applicationContext).briefing(kind)
        } catch (e: Exception) {
            "Briefing failed: ${e.message}"
        }
        Briefings.post(applicationContext, kind, text)
        withContext(Dispatchers.IO) { Telegram.sendToOwner(prefs, text) }
        if (prefs.briefingsOn) {
            Briefings.enqueue(applicationContext, kind, if (kind == "morning") prefs.morningTime else prefs.eveningTime)
        }
        return Result.success()
    }
}
