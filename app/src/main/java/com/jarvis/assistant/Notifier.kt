package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * A persistent, silent notification whose tap (or "Listen" button) opens Jarvis straight into listening.
 * This is the trigger you chose instead of an always-on wake word: zero idle battery cost and no always-open mic.
 */
object Notifier {
    private const val CHANNEL = "jarvis_trigger"
    private const val ID = 1

    fun show(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis trigger", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText("Tap to talk")
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Listen", pi).build())
            .build()
        nm.notify(ID, n)
    }
}
