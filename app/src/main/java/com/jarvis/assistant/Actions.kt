package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/** Actions that need only standard Android intents. Direct phone control lives in Device.kt. */
class Actions(private val context: Context) {

    /** Returns a short status string, or null if nothing needs to be added to the reply. */
    fun run(type: String, arg: String): String? {
        return try {
            when (type.lowercase()) {
                "open_app" -> openApp(arg)
                "set_alarm" -> setAlarm(arg)
                "set_timer" -> setTimer(arg)
                "web_search" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(arg))))
                "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(if (arg.startsWith("http")) arg else "https://$arg")))
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

    /** Package name of the launchable app whose label contains [name], or null. */
    fun findApp(name: String): String? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(name.trim(), ignoreCase = true) }
            ?.activityInfo?.packageName
    }

    /** Android settings screens people ask for by name: bluetooth, wifi, location... in English, Arabic and Darija. */
    fun settingFor(name: String): String? {
        val n = name.trim().lowercase()
        val map = listOf(
            listOf("bluetooth", "blue tooth", "بلوتوث", "بلوتوت", "بلوتووث") to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
            listOf("wifi", "wi-fi", "wi fi", "واي فاي", "وايفاي", "الواي فاي", "wlan") to android.provider.Settings.ACTION_WIFI_SETTINGS,
            listOf("airplane", "flight mode", "وضع الطيران", "طيران") to android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            listOf("location", "gps", "الموقع", "لوكيشن") to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            listOf("mobile data", "data", "network", "الشبكة", "البيانات", "الداتا", "hotspot", "هوتسبوت") to android.provider.Settings.ACTION_WIRELESS_SETTINGS,
            listOf("display", "brightness", "الشاشة", "السطوع") to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
            listOf("sound", "الصوت", "ringtone") to android.provider.Settings.ACTION_SOUND_SETTINGS,
            listOf("battery saver", "battery", "توفير البطارية") to android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS,
            listOf("nfc") to android.provider.Settings.ACTION_NFC_SETTINGS,
            listOf("storage", "التخزين") to android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            listOf("accessibility", "إمكانية الوصول", "امكانية الوصول") to android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
            listOf("language", "اللغة") to android.provider.Settings.ACTION_LOCALE_SETTINGS,
            listOf("date", "time settings", "التاريخ") to android.provider.Settings.ACTION_DATE_SETTINGS,
            listOf("settings", "setting", "الإعدادات", "الاعدادات", "إعدادات", "اعدادات") to android.provider.Settings.ACTION_SETTINGS
        )
        return map.firstOrNull { (keys, _) -> keys.any { n == it || n == "$it settings" || n == "${it} setting" } }?.second
    }

    fun openApp(name: String): String? {
        val pkg = findApp(name)
        if (pkg == null) {
            settingFor(name)?.let { return launch(Intent(it)) }
            return try {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(name))))
                "\"$name\" is not installed on this phone. I opened a Play Store search for it."
            } catch (e: Exception) {
                "I could not find an app called $name."
            }
        }
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "Cannot launch $name."
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
