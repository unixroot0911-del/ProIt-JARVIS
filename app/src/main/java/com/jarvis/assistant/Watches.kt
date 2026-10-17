package com.jarvis.assistant

import android.content.ContentValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Change monitoring: a page is read on a schedule, trivial churn (clocks, counters, "x minutes ago") is ignored, and when something
 * real changed AXIOM says what changed, in one short message, and only if it matches the user's rule.
 */
object Watches {
    private val volatile = listOf(
        Regex("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b"), Regex("(?i)\\b\\d+\\s*(seconds?|minutes?|hours?|days?|mins?|hrs?)\\s*ago\\b"), Regex("(?i)\\b(views?|visitors?|online now|users online|مشاهدة)\\b"),
        Regex("(?i)(©|copyright|\\ball rights reserved)"), Regex("(?i)\\b(csrf|nonce|token|session)\\b"), Regex("^\\W*$"), Regex("(?i)^(sponsored|advertisement|ad)\\b")
    )

    private fun stable(text: String): List<String> = text.lines().map { it.trim() }.filter { it.length > 2 && volatile.none { r -> r.containsMatchIn(it) } }

    fun hashOf(lines: List<String>) = Axiom.sha1(lines.joinToString("\n") { Web.normText(it) })

    class Outcome(val changed: Boolean, val notified: Boolean, val message: String)

    fun add(url: String, what: String, everyMin: Int, rule: String, project: String): String {
        val id = Axiom.newId("w")
        val cv = ContentValues().apply {
            put("id", id); put("url", url); put("what", what); put("every_min", everyMin.coerceIn(15, 7 * 1440)); put("last_hash", ""); put("last_text", ""); put("last_check", 0L)
            put("rule", rule); put("enabled", 1); put("project", project); put("created", System.currentTimeMillis()); put("changes", 0)
        }
        Store.get().insert("watches", cv)
        return id
    }

    fun list(): List<Map<String, String>> = Store.get().query("SELECT * FROM watches ORDER BY created DESC")

    fun line(w: Map<String, String>): String {
        val last = w["last_check"]!!.toLong()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        return "${w["id"]!!.takeLast(5)} ${if (w["enabled"] == "1") "on " else "off"} every ${w["every_min"]} min — ${w["what"]!!.ifBlank { w["url"] }} (${Axiom.clip(w["url"]!!, 50)})" +
            (if (w["rule"]!!.isNotBlank()) " — only if: ${w["rule"]}" else "") + " — checked " + (if (last == 0L) "never" else fmt.format(java.util.Date(last))) + ", ${w["changes"]} change(s)"
    }

    fun resolve(idOrTail: String): String? = Store.get().one("SELECT id FROM watches WHERE id = ? OR id LIKE ?", idOrTail, "%$idOrTail")?.get("id")

    /** Reads one watch once. Never throws; returns what happened. */
    suspend fun check(c: ToolCtx, id: String, force: Boolean = false): Outcome {
        val w = Store.get().one("SELECT * FROM watches WHERE id = ?", id) ?: return Outcome(false, false, "No such watch.")
        val now = System.currentTimeMillis()
        val db = Store.get()
        val (page, why) = Web.fetch(w["url"]!!)
        val cv = ContentValues().apply { put("last_check", now) }
        if (page == null) {
            val fails = (Cache.get("wfail:$id")?.toIntOrNull() ?: 0) + 1
            Cache.put("wfail:$id", fails.toString(), 7 * 86400_000L)
            db.update("watches", cv, "id = ?", id)
            if (fails == 3 || (fails % 24 == 0)) { val m = "I could not read ${w["url"]} $fails times in a row: $why"; alert(c, "watch-fail:$id", "Watch problem", m); return Outcome(false, true, m) }
            return Outcome(false, false, "Could not read it ($why)")
        }
        Cache.put("wfail:$id", "0", 86400_000L)
        val newLines = stable(page.text)
        val h = hashOf(newLines)
        val oldText = w["last_text"]!!
        if (w["last_hash"]!!.isEmpty()) {
            cv.put("last_hash", h); cv.put("last_text", page.text.take(80_000)); db.update("watches", cv, "id = ?", id)
            return Outcome(false, false, "Baseline saved (${newLines.size} lines). I will tell you when something changes.")
        }
        if (h == w["last_hash"] && !force) { db.update("watches", cv, "id = ?", id); return Outcome(false, false, "No change.") }
        val oldLines = stable(oldText)
        val oldSet = oldLines.map { Web.normText(it) }.toSet(); val newSet = newLines.map { Web.normText(it) }.toSet()
        val added = newLines.filter { Web.normText(it) !in oldSet }; val removed = oldLines.filter { Web.normText(it) !in newSet }
        cv.put("last_hash", h); cv.put("last_text", page.text.take(80_000))
        if (added.size + removed.size == 0) { db.update("watches", cv, "id = ?", id); return Outcome(false, false, "No real change.") }
        if (added.size + removed.size <= 1 && (added + removed).sumOf { it.length } < 25 && !force) { db.update("watches", cv, "id = ?", id); return Outcome(false, false, "Only a trivial change; ignored.") }

        val verdict = try {
            Router.runJson(c.brain, Router.T.CLASSIFY, Router.Opts(
                "A web page being watched changed. Decide if the user should be told. Return ONE JSON object {\"notify\":true/false,\"summary\":\"what changed in one or two short sentences, with exact old and new values\"}. " +
                    "WATCHING FOR: ${w["what"]!!.ifBlank { "any meaningful change" }}. " + (if (w["rule"]!!.isNotBlank()) "NOTIFY ONLY IF: ${w["rule"]}. " else "Ignore ads, counters, timestamps, layout and cookie banners. ") + "Use only the text shown; do not guess.\n" + Shield.RULES,
                "ADDED LINES:\n" + Shield.wrap("added", added.take(40).joinToString("\n"), 3500).text + "\nREMOVED LINES:\n" + Shield.wrap("removed", removed.take(40).joinToString("\n"), 3500).text, json = true, maxOut = 500, task = c.task))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { JSONObject().put("notify", true).put("summary", "The page changed (${added.size} lines added, ${removed.size} removed), but I could not summarise it right now.") }
        cv.put("changes", (w["changes"]!!.toIntOrNull() ?: 0) + 1)
        db.update("watches", cv, "id = ?", id)
        if (!verdict.optBoolean("notify", false) && !force) return Outcome(true, false, "Changed, but not what you asked about: " + verdict.optString("summary"))
        val msg = (w["what"]!!.ifBlank { page.title.ifBlank { w["url"]!! } }) + ": " + verdict.optString("summary", "changed") + "\n" + page.finalUrl
        alert(c, "watch:$id", "Change detected", msg)
        return Outcome(true, true, msg)
    }

    fun alert(c: ToolCtx, key: String, title: String, text: String) {
        Notify.post("info", key, title, text, 10 * 60_000L)
        Telegram.sendToOwner(c.prefs, "$title\n$text")
    }

    /** Called every minute by the ticker. */
    suspend fun tick(c: ToolCtx) {
        val now = System.currentTimeMillis()
        for (w in Store.get().query("SELECT * FROM watches WHERE enabled = 1")) {
            val due = w["last_check"]!!.toLong() + w["every_min"]!!.toLong() * 60_000L
            if (due > now) continue
            try { check(ToolCtx(c.app, c.brain, c.prefs, "watch:${w["id"]}", w["project"]!!), w["id"]!!) } catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.record(e) }
            delay(1500)
        }
    }

    fun register() {
        Tools.register(Tool("watch_add", "Watch a web page and tell the user when it really changes (price, availability, new post, status). rule = optional condition like 'price below 200'.",
            listOf(A("url", "page address"), A("what", "what to watch for, in words"), A("every_min", "check interval in minutes, 15 or more, default 60", false, "int"), A("rule", "only notify if...", false)),
            Risk.LOCAL, 120_000L, group = "watch",
            run = { c, a ->
                val (p, why) = Web.fetch(a.getString("url"))
                if (p == null) return@Tool fail("I cannot watch that page: $why")
                val id = add(a.getString("url"), a.getString("what"), a.optInt("every_min", 60), a.optString("rule", ""), c.project)
                check(c, id)
                ok("Watching ${a.getString("url")} every ${a.optInt("every_min", 60).coerceAtLeast(15)} minutes (id ${id.takeLast(5)}). The first read is saved as the baseline.")
            }))
        Tools.register(Tool("watch_list", "List the watched pages.", emptyList(), Risk.READ, group = "watch", run = { _, _ -> ok(list().joinToString("\n") { line(it) }.ifEmpty { "Nothing is being watched." }) }))
        Tools.register(Tool("watch_check", "Check a watched page now.", listOf(A("id", "watch id (last 5 characters are enough)")), Risk.READ, 120_000L, group = "watch",
            run = { c, a -> val id = resolve(a.getString("id")) ?: return@Tool fail("No such watch."); ok(check(c, id, force = true).message, untrusted = true) }))
        Tools.register(Tool("watch_remove", "Stop watching a page (pause = true keeps it but switches it off).", listOf(A("id", "watch id"), A("pause", "true to pause only", false, "bool")), Risk.LOCAL, group = "watch",
            run = { _, a ->
                val id = resolve(a.getString("id")) ?: return@Tool fail("No such watch.")
                if (a.optBoolean("pause", false)) { Store.get().update("watches", ContentValues().apply { put("enabled", 0) }, "id = ?", id); ok("Paused.") } else { Store.get().delete("watches", "id = ?", id); ok("Stopped watching.") }
            }))
    }
}

/** One quiet loop that runs everything periodic: page watches, workflows, reports, housekeeping. */
object Ticker {
    private val hooks = ArrayList<suspend (ToolCtx) -> Unit>()
    @Volatile private var lastHouse = 0L

    fun hook(h: suspend (ToolCtx) -> Unit) { synchronized(hooks) { hooks.add(h) } }

    suspend fun loop(prefs: Prefs) {
        Tools.ensure()
        delay(20_000L)
        while (currentCoroutineContext().isActive) {
            try {
                val c = ToolCtx(Axiom.ctx, Shared.brain(), prefs, "ticker", prefs.activeProject)
                Watches.tick(c)
                try { Mcp.keepAlive() } catch (e: CancellationException) { throw e } catch (e: Throwable) { Diag.record(e) }
                for (h in synchronized(hooks) { hooks.toList() }) try { h(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.record(e) }
                val now = System.currentTimeMillis()
                if (now - lastHouse > 3600_000L) {
                    lastHouse = now
                    withContext(Dispatchers.IO) { Cache.purge(); Knowledge.purgeMissing(); Knowledge.maybeSummarize(Shared.brain(), prefs) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.record(e) }
            delay(60_000L)
        }
    }
}
