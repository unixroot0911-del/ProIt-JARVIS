package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The persistent, silent notification. Tapping it (or "Listen") opens Jarvis straight into listening.
 * It also keeps the core service (Telegram bot, floating orb) alive.
 */
object Notifier {
    const val ID = 1
    private const val CHANNEL = "jarvis_trigger"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis trigger", NotificationManager.IMPORTANCE_LOW))
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText("Tap to talk")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
