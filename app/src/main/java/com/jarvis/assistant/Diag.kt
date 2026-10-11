package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers the last crash or internal error so it can be shown ("last error") and, after a crash, sent to Telegram
 * on the next start. Nothing here can itself fail loudly.
 */
object Diag {
    @Volatile private var app: Context? = null

    private fun sp(c: Context) = c.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)

    private fun stamp(e: Throwable, kind: String): String =
        kind + " " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n" + e.toString() + "\n" +
            e.stackTrace.take(8).joinToString("\n") { "  at $it" }

    fun install(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try { sp(a).edit().putString("last", stamp(e, "CRASH")).putBoolean("pending", true).commit() } catch (x: Throwable) { /* ignore */ }
                prev?.uncaughtException(t, e)
            }
            val s = sp(a)
            if (s.getBoolean("pending", false)) {
                val txt = s.getString("last", "") ?: ""
                s.edit().putBoolean("pending", false).apply()
                Engine.scope.launch(Dispatchers.IO) {
                    try { Telegram.sendToOwner(Prefs(a), "Jarvis crashed last time:\n" + txt.take(1500)) } catch (x: Exception) { /* ignore */ }
                }
            }
        } catch (x: Throwable) { /* ignore */ }
    }

    fun record(e: Throwable) {
        val a = app ?: return
        try { sp(a).edit().putString("last", stamp(e, "ERROR")).apply() } catch (x: Throwable) { /* ignore */ }
    }

    fun report(ctx: Context): String {
        val t = sp(ctx.applicationContext).getString("last", "")
        return if (t.isNullOrBlank()) "No crash or internal error has been recorded." else "Last recorded problem:\n$t"
    }
}
