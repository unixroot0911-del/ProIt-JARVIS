package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.provider.AlarmClock
import android.provider.MediaStore

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
                "navigate" -> navigate(arg)
                "play_music" -> playMusic(arg)
                "share" -> share(arg)
                else -> "Action '$type' is not supported yet."
            }
        } catch (e: Exception) {
            "Action failed: ${e.message}"
        }
    }

    companion object {
        @Volatile private var cache: List<Pair<String, String>> = emptyList()
        @Volatile private var cacheAt = 0L
    }

    /** (label, package) of every launchable app, cached for a minute. */
    fun installed(): List<Pair<String, String>> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - cacheAt < 60_000L) return cache
        val pm = context.packageManager
        val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.first }
            .sortedBy { it.first.lowercase() }
        cache = l
        cacheAt = System.currentTimeMillis()
        return l
    }

    fun installedLabels(max: Int = 120): List<String> = installed().map { it.first }.take(max)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun edit(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Typos, dropped letters and partial names count as the same word: "monopost" is "Monoposto". */
    fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.length < 3 || y.length < 3) return x == y
        if (x == y) return true
        val s = if (x.length <= y.length) x else y
        val l = if (x.length <= y.length) y else x
        if (s.length >= 4 && l.contains(s) && s.length * 10 >= l.length * 5) return true
        return x.length >= 4 && y.length >= 4 && edit(x, y) <= maxOf(1, minOf(x.length, y.length) / 5)
    }

    /** Installed apps whose names resemble words or word pairs in [text]. */
    fun mentions(text: String): List<String> {
        val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val grams = (words + words.zipWithNext { a, b -> "$a $b" }).filter { norm(it).length >= 4 }
        val apps = installed()
        val out = LinkedHashSet<String>()
        for (g in grams) for ((label, _) in apps) if (similar(g, label)) out.add(label)
        return out.take(5).toList()
    }

    /** A message that is just an app's name (maybe misspelled) opens that app. */
    fun findAppLoose(text: String): String? =
        installed().firstOrNull { similar(text, it.first) }?.second

    private fun playMusic(arg: String): String? {
        val p = arg.split("|", limit = 2)
        val q = p[0].trim()
        val app = p.getOrElse(1) { "" }.trim()
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
        if (app.isNotEmpty()) findApp(app)?.let { i.setPackage(it) }
        return try {
            launch(i)
        } catch (e: Exception) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q))))
        }
    }

    private fun navigate(place: String): String? {
        return try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps"))
        } catch (e: Exception) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        }
    }

    /** Opens an app's share screen with the text ready ("post this to Telegram"); blank app opens the system share menu. */
    private fun share(arg: String): String? {
        val p = arg.split("|", limit = 2)
        val app = if (p.size > 1) p[0].trim() else ""
        val text = if (p.size > 1) p[1] else p[0]
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (app.isEmpty()) return launch(Intent.createChooser(i, "Share"))
        val pkg = findApp(app) ?: return "I could not find an app called $app."
        i.setPackage(pkg)
        launch(i)
        return "Opened the share screen of $app with your text ready. Pick the chat and tap send."
    }

    private fun launch(i: Intent): String? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return null
    }

    /** Package name of the launchable app whose label contains [name], or null. */
    fun findApp(name: String): String? {
        val pm = context.packageManager
        val n = name.trim()
        if (n.isEmpty()) return null
        if (n.contains('.') && !n.contains(' ') && pm.getLaunchIntentForPackage(n) != null) return n   // a package name
        fun norm(s: String) = s.lowercase().replace(Regex("[\\s._-]"), "")
        val nn = norm(n)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { norm(it.loadLabel(pm).toString()) to it.activityInfo.packageName }
        return apps.firstOrNull { it.first == nn }?.second
            ?: apps.firstOrNull { it.first.contains(nn) }?.second
            ?: apps.firstOrNull { it.first.length >= 3 && nn.contains(it.first) }?.second
            ?: installed().firstOrNull { similar(n, it.first) }?.second
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

    fun openApp(name: String, store: Boolean = true): String? {
        val pkg = findApp(name)
        if (pkg == null) {
            settingFor(name)?.let { return launch(Intent(it)) }
            if (!store) return "I could not find an app called $name."
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
