package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/** Executes phone actions. Phase 1 covers what standard Android intents allow without special access. */
class Actions(private val context: Context) {

    /** Returns a short status string, or null if nothing needs to be added to the reply. */
    fun run(type: String, arg: String): String? {
        return try {
            when (type.lowercase()) {
                "open_app" -> openApp(arg)
                "set_alarm" -> setAlarm(arg)
                "set_timer" -> setTimer(arg)
                "web_search" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(arg))))
                "call" -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(arg))))
                else -> "Action '$type' is not supported yet."
            }
        } catch (e: Exception) {
            "Action failed: ${e.message}"
        }
    }

    private fun launch(i: Intent): String? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return null
    }

    fun openApp(name: String): String? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val target = pm.queryIntentActivities(launcher, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name, ignoreCase = true) }
            ?: return "I could not find an app called $name."
        val i = pm.getLaunchIntentForPackage(target.activityInfo.packageName) ?: return "Cannot launch $name."
        return launch(i)
    }

    private fun setAlarm(hhmm: String): String? {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return "Invalid alarm time."
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }

    private fun setTimer(seconds: String): String? {
        val s = seconds.trim().toIntOrNull() ?: return "Invalid timer length."
        return launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
    }
}
