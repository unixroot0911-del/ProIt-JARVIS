package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context

/**
 * Runs once after every update. AXIOM starts the new version from a clean working state: nothing that was learned from the
 * old version's behaviour (cached answers, remembered failures, cooldowns, the old way it described itself) steers the new one.
 * Everything that belongs to the user stays untouched: facts, dossier, projects, files, knowledge base, workflows, watches,
 * tools it built, templates, keys, settings, and the chat history shown on screen.
 */
object Upgrade {
    @Synchronized fun run(ctx: Context) {
        val p = Prefs(ctx)
        val old = p.appVersion
        if (old == Axiom.VERSION) return
        val first = old.isEmpty()
        val db = Store.get()
        try { db.exec("DELETE FROM cache") } catch (e: Throwable) { Diag.record(e) }
        try { db.exec("DELETE FROM procedures WHERE ok = 0") } catch (e: Throwable) { Diag.record(e) }
        try { db.exec("DELETE FROM perf WHERE ts < ?", System.currentTimeMillis() - 30L * 86400_000L) } catch (e: Throwable) { Diag.record(e) }
        try { ctx.getSharedPreferences("axiom_quota", Context.MODE_PRIVATE).edit().clear().apply() } catch (e: Throwable) { Diag.record(e) }
        p.contextSince = System.currentTimeMillis()
        try { Scheduler.rearmAll(ctx) } catch (e: Throwable) { Diag.record(e) }
        try {
            db.insert("changelog", ContentValues().apply {
                put("ts", System.currentTimeMillis()); put("path", "app"); put("action", "update"); put("detail", (if (first) "first run of " else "$old -> ") + Axiom.VERSION)
            })
        } catch (e: Throwable) { Diag.record(e) }
        p.appVersion = Axiom.VERSION
        if (!first) try { Telegram.sendToOwner(p, "AXIOM updated to ${Axiom.VERSION} and refreshed itself. Your memory, files, projects, workflows and settings are untouched.") } catch (e: Throwable) { /* no Telegram yet */ }
    }
}
