package com.jarvis.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** An honest answer to "what works and what is missing?", shown in Settings and available by voice or text. */
object Health {
    fun report(ctx: Context): String {
        val p = Prefs(ctx)
        val sb = StringBuilder()
        fun line(ok: Boolean, name: String, fix: String) {
            sb.append(if (ok) "OK       " else "MISSING  ").append(name)
            if (!ok) sb.append("  ->  ").append(fix)
            sb.append('\n')
        }
        fun perm(x: String) = ContextCompat.checkSelfPermission(ctx, x) == PackageManager.PERMISSION_GRANTED

        line(p.geminiKey.isNotEmpty(), "Gemini key (brain, vision, web search)", "add it under BRAIN 1")
        line(p.groqKey.isNotEmpty(), "Groq key (fast brain, voice)", "add it under BRAIN 2")
        line(perm(Manifest.permission.RECORD_AUDIO), "Microphone", "allow it when asked")
        line(NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName), "Notification access", "button 1 below")
        line(JarvisAccessibilityService.instance != null, "Screen agent and screenshots (Accessibility)", "button 2 below; turn Jarvis off and on again")
        line(Settings.canDrawOverlays(ctx), "Floating orb and chat over other apps", "press 'Allow display over other apps'")
        line(perm(Manifest.permission.READ_CONTACTS) && perm(Manifest.permission.SEND_SMS), "Contacts and SMS", "button 3 below")
        line(perm(Manifest.permission.READ_CALENDAR), "Calendar", "button 3 below")
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        line(Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(), "Exact alarm timing", "button 5 below")
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        line(pm.isIgnoringBatteryOptimizations(ctx.packageName), "Unrestricted battery (keeps Telegram, alarms and watch alive)", "button 4 below")
        line(p.telegramToken.isNotEmpty() && p.telegramChatId.isNotEmpty(), "Telegram remote control paired", "paste the bot token, SAVE, then send /pair CODE")
        line(Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls(), "Install updates by itself", "allowed on the update screen when asked")
        sb.append("Version ").append(try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" })
        return sb.toString()
    }
}

/**
 * Self-update: the build server publishes the newest APK and its version number on a public branch.
 * Jarvis compares versions, downloads the file and hands it to Android's installer (you confirm once).
 */
object Updater {
    private const val BASE = "https://raw.githubusercontent.com/unixroot0911-del/proit-jarvis/apk/"

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun parts(s: String) = s.trim().split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    private fun newer(a: String, b: String): Boolean {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    suspend fun check(ctx: Context, install: Boolean): String = withContext(Dispatchers.IO) {
        val current = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }
        val latest = try { String(get(BASE + "version.txt")).trim() } catch (e: Exception) {
            return@withContext "I could not reach the update server (${e.message}). You have $current."
        }
        if (!newer(latest, current)) return@withContext "You are up to date: $current."
        if (!install) return@withContext "Update $latest is available (you have $current). Say \"update yourself\" to install it."

        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { /* settings unavailable */ }
            return@withContext "Update $latest found. Allow \"Install unknown apps\" for Jarvis on the screen I just opened, then ask me to update again."
        }
        val bytes = try { get(BASE + "jarvis.apk") } catch (e: Exception) {
            return@withContext "The download of $latest failed (${e.message}). Try again in a minute."
        }
        if (bytes.size < 1_000_000 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return@withContext "The downloaded file is not a valid app. Try again in a minute."
        }
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "jarvis-$latest.apk")
        f.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        "Update $latest downloaded (${bytes.size / 1_048_576} MB). Confirm the install on the screen that opened."
    }
}

/** Notification rules: "alert me when a notification mentions X". Delivered through the same discreet alerts as reminders. */
object Monitor {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("jarvis_monitor", Context.MODE_PRIVATE)
    private val last = HashMap<String, Long>()

    fun rules(ctx: Context): List<String> =
        (sp(ctx).getString("rules", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun save(ctx: Context, l: List<String>) = sp(ctx).edit().putString("rules", l.joinToString("\n")).apply()

    fun run(ctx: Context, type: String, arg: String): String {
        val a = arg.trim()
        return when (type) {
            "alert_on" -> {
                if (a.isEmpty()) return "Tell me which words to watch for."
                val l = rules(ctx).toMutableList()
                if (l.none { it.equals(a, true) }) l.add(a)
                save(ctx, l)
                "I will alert you quietly whenever a notification mentions: $a"
            }
            "alert_off" -> {
                val l = rules(ctx)
                val keep = if (a.isEmpty() || a.equals("all", true) || a == "الكل") emptyList() else l.filter { !it.contains(a, true) }
                save(ctx, keep)
                "Removed ${l.size - keep.size} alert rule(s)."
            }
            else -> {
                val l = rules(ctx)
                if (l.isEmpty()) "No notification alerts are set." else "Alerting on:\n" + l.joinToString("\n") { "- $it" }
            }
        }
    }

    /** A rule like "bank+code" needs every part; plain words need to appear anywhere in app, title or text. */
    fun onNotification(ctx: Context, app: String, title: String, text: String) {
        val rules = rules(ctx)
        if (rules.isEmpty()) return
        val hay = "$app $title $text".lowercase()
        val now = System.currentTimeMillis()
        for (r in rules) {
            if (!r.lowercase().split("+").map { it.trim() }.filter { it.isNotEmpty() }.all { hay.contains(it) }) continue
            if (now - (last[r] ?: 0L) < 20_000L) continue
            last[r] = now
            val msg = "[$app] ${title.take(40)}: ${text.take(100)}"
            Engine.scope.launch(Dispatchers.IO) { Alerts.deliver(ctx, Due(System.nanoTime(), now, msg, "reminder", 0), false) }
        }
    }
}

/** Quick Settings tile: pull down the status bar, tap Jarvis, and it starts listening. */
class JarvisTile : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.label = "Jarvis"; it.updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
