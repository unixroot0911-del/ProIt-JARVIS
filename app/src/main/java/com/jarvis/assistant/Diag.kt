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
            val main = android.os.Looper.getMainLooper().thread
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                if (t !== main && !fatal(e)) {
                    // a stray background thread failed: note it and keep AXIOM alive
                    try { sp(a).edit().putString("last", stamp(e, "ERROR")).apply() } catch (x: Throwable) { /* ignore */ }
                } else {
                    try { sp(a).edit().putString("last", stamp(e, "CRASH")).putBoolean("pending", true).commit() } catch (x: Throwable) { /* ignore */ }
                    prev?.uncaughtException(t, e)
                }
            }
            // the main thread keeps running after a failed message or callback instead of closing the app
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                var recent = 0; var windowStart = System.currentTimeMillis()
                while (true) {
                    try { android.os.Looper.loop() }
                    catch (e: Throwable) {
                        val now = System.currentTimeMillis()
                        if (now - windowStart > 4000) { windowStart = now; recent = 0 }
                        recent++
                        try { sp(a).edit().putString("last", stamp(e, "ERROR")).apply() } catch (x: Throwable) { /* ignore */ }
                        if (recent > 6 || fatal(e)) {   // a failure that keeps repeating cannot be survived: let Android restart us cleanly
                            try { sp(a).edit().putBoolean("pending", true).commit() } catch (x: Throwable) { /* ignore */ }
                            prev?.uncaughtException(Thread.currentThread(), e); return@post
                        }
                    }
                }
            }
            val s = sp(a)
            if (s.getBoolean("pending", false)) {
                val txt = s.getString("last", "") ?: ""
                s.edit().putBoolean("pending", false).apply()
                Engine.scope.launch(Dispatchers.IO) {
                    try { Telegram.sendToOwner(Prefs(a), "AXIOM crashed last time:\n" + txt.take(1500)) } catch (x: Exception) { /* ignore */ }
                }
            }
        } catch (x: Throwable) { /* ignore */ }
    }

    private fun fatal(e: Throwable) = e is OutOfMemoryError

    fun record(e: Throwable) {
        val a = app ?: return
        try { sp(a).edit().putString("last", stamp(e, "ERROR")).apply() } catch (x: Throwable) { /* ignore */ }
    }

    fun report(ctx: Context): String {
        val t = sp(ctx.applicationContext).getString("last", "")
        return if (t.isNullOrBlank()) "No crash or internal error has been recorded." else "Last recorded problem:\n$t"
    }
}
