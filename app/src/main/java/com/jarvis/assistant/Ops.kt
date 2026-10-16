package com.jarvis.assistant

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Operations: backup and restore, performance and quota reports, the free-services registry, templates, the audit trail and the coder loop. */
object Ops {
    private fun sha(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun shaBytes(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun dbFile() = Axiom.ctx.getDatabasePath("axiom.db")

    // ---------------- backup ----------------

    /** Zips the database and the workspace (never keys or tokens), writes a manifest of hashes, and reads the zip back to prove it. */
    fun backup(): Pair<File?, String> {
        val root = Workspace.root()
        val dir = File(root, "backups").apply { mkdirs() }
        val out = File(dir, "axiom_backup_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".zip")
        try { Store.get().writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } } catch (e: Exception) { /* fine without WAL */ }
        val manifest = JSONObject(); var n = 0
        try {
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                fun add(name: String, f: File) {
                    z.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                    manifest.put(name, sha(f)); n++
                }
                val db = dbFile()
                if (db.exists()) add("db/axiom.db", db)
                root.walkTopDown().filter { it.isFile && !it.path.startsWith(dir.path) && it.length() < 80_000_000L && !it.name.startsWith(".") }
                    .forEach { add("ws/" + it.relativeTo(root).path, it) }
                z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString().toByteArray()); z.closeEntry()
            }
        } catch (e: Exception) { out.delete(); return null to "The backup failed: ${e.message}" }
        val problem = verify(out)
        if (problem != null) { out.delete(); return null to "The backup was written but did not verify, so I deleted it: $problem" }
        val keep = Config.get("backup_keep").toIntOrNull() ?: 5
        dir.listFiles { f -> f.name.startsWith("axiom_backup_") }?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.delete() }
        return out to "$n files, ${Workspace.human(out.length())}, hashes verified."
    }

    /** Null when every file in the backup matches its recorded hash and the database copy passes its own integrity check. */
    fun verify(zip: File): String? {
        try {
            ZipFile(zip).use { z ->
                val m = z.getEntry("manifest.json") ?: return "no manifest"
                val man = JSONObject(z.getInputStream(m).readBytes().toString(Charsets.UTF_8))
                var bad = 0
                for (k in man.keys()) {
                    val e = z.getEntry(k) ?: return "missing $k"
                    if (shaBytes(z.getInputStream(e).readBytes()) != man.getString(k)) bad++
                }
                if (bad > 0) return "$bad files are damaged"
                val d = z.getEntry("db/axiom.db")
                if (d != null) {
                    val tmp = File(Axiom.ctx.cacheDir, "bk_check.db")
                    z.getInputStream(d).use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val good = try {
                        android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                            db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                        }
                    } catch (e: Exception) { false }
                    tmp.delete()
                    if (!good) return "the database copy fails its integrity check"
                }
            }
        } catch (e: Exception) { return "cannot be opened: ${e.message}" }
        return null
    }

    /** Restores a verified backup. Files already there are kept as .before if they differ. */
    fun restore(zip: File): String {
        verify(zip)?.let { return "Not restoring, the backup is not sound: $it" }
        val root = Workspace.root(); var files = 0; var dbDone = false
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory || e.name == "manifest.json") continue
                if (e.name.startsWith("ws/")) {
                    val rel = e.name.removePrefix("ws/")
                    val dest = File(root, rel).canonicalFile
                    if (!dest.path.startsWith(root.canonicalPath)) continue
                    dest.parentFile?.mkdirs()
                    val data = z.getInputStream(e).readBytes()
                    if (dest.exists() && shaBytes(data) != sha(dest)) try { dest.copyTo(File(dest.path + ".before"), true) } catch (x: Exception) { /* best effort */ }
                    dest.writeBytes(data); files++
                } else if (e.name == "db/axiom.db") {
                    val db = dbFile()
                    val data = z.getInputStream(e).readBytes()
                    Store.close()
                    try { db.copyTo(File(db.path + ".before"), true) } catch (x: Exception) { /* none yet */ }
                    File(db.path + "-wal").delete(); File(db.path + "-shm").delete()
                    db.writeBytes(data); dbDone = true
                }
            }
        }
        return "Restored $files files" + (if (dbDone) " and the database (the old one is kept as axiom.db.before)" else "") + ". Close and reopen AXIOM so everything reloads."
    }

    // ---------------- services registry ----------------

    private val seed = listOf(
        Triple("gemini", "Google Gemini API: free tier with rate limits; text, vision, PDF reading, embeddings, image generation on some models.", "https://generativelanguage.googleapis.com"),
        Triple("groq", "Groq API: free tier with rate limits; fast open models and Whisper transcription.", "https://api.groq.com"),
        Triple("duckduckgo", "DuckDuckGo HTML search: no key, light use only.", "https://html.duckduckgo.com/html/"),
        Triple("pollinations", "Pollinations: free image generation, no key.", "https://image.pollinations.ai"),
        Triple("github", "GitHub REST API: free with a token, 5000 requests per hour.", "https://api.github.com"),
        Triple("open-meteo", "Open-Meteo: free weather data, no key.", "https://api.open-meteo.com")
    )

    private fun ensureSeed() {
        if (Store.get().scalar("SELECT COUNT(*) FROM services") > 0) return
        for ((n, d, u) in seed) Store.get().replace("services", ContentValues().apply {
            put("name", n); put("body", JSONObject().put("desc", d).put("url", u).put("checked", 0).put("status", "not checked").toString()); put("verified", 0)
        })
    }

    // ---------------- registration ----------------

    fun register() {
        Tools.register(Tool("backup_make", "Make a backup now: the database and every project file (never keys or tokens), hash-verified. Old backups beyond the keep limit are removed.",
            emptyList(), Risk.LOCAL, 180_000L, group = "ops", run = { _, _ ->
                val (f, msg) = backup()
                if (f == null) fail(msg, retriable = true) else { Workspace.note(f.path, "backup", msg); ok("Backup ready: ${f.name}. $msg", listOf(f.path)) }
            }))
        Tools.register(Tool("backup_list", "List existing backups and whether each one still verifies.", emptyList(), Risk.READ, 120_000L, group = "ops", run = { _, _ ->
            val fs = File(Workspace.root(), "backups").listFiles { f -> f.name.startsWith("axiom_backup_") }?.sortedByDescending { it.name } ?: emptyList()
            if (fs.isEmpty()) ok("No backups yet.") else ok(fs.joinToString("\n") { it.name + "  " + Workspace.human(it.length()) + "  " + (verify(it)?.let { p -> "DAMAGED: $p" } ?: "ok") })
        }))
        Tools.register(Tool("backup_restore", "Restore a backup by file name (from backup_list). Verifies it first. Overwrites current data, so it always needs the user's approval.",
            listOf(A("name", "backup file name")), Risk.DANGEROUS, 180_000L, group = "ops", run = { c, a ->
                val f = File(File(Workspace.root(), "backups"), File(a.getString("name")).name)
                if (!f.exists()) return@Tool fail("No such backup.")
                if (!c.confirm("Restore ${f.name}? Current data is replaced (changed files are kept as .before).")) return@Tool ToolResult(false, "Declined by the user.", declined = true)
                ok(restore(f))
            }))
        Tools.register(Tool("perf_report", "Measured performance: tools and models by number of uses, success rate, average time, plus suggestions where something keeps failing or is slow.",
            emptyList(), Risk.READ, group = "ops", run = { _, _ ->
                val sb = StringBuilder()
                val flagged = ArrayList<String>()
                for (kind in listOf("tool", "model", "task")) {
                    val rows = Perf.summary(kind, 12)
                    if (rows.isEmpty()) continue
                    sb.append(kind.uppercase()).append('\n')
                    for (r in rows) {
                        val n = r["n"]!!.toIntOrNull() ?: 0; val good = r["good"]!!.toIntOrNull() ?: 0; val ms = r["avg_ms"]!!.toIntOrNull() ?: 0
                        sb.append("  ").append(r["name"]).append(": ").append(n).append(" runs, ").append(if (n > 0) good * 100 / n else 0).append("% ok, ").append(ms).append(" ms avg\n")
                        if (n >= 5 && good * 100 / n < 70) flagged.add("${r["name"]} fails ${100 - good * 100 / n}% of the time over $n runs: check its inputs or try a different approach.")
                        if (n >= 5 && ms > 60_000) flagged.add("${r["name"]} averages ${ms / 1000} s: consider splitting the job or lowering the depth.")
                    }
                }
                if (sb.isEmpty()) return@Tool ok("Nothing measured yet. Use AXIOM for a while and ask again.")
                if (flagged.isNotEmpty()) sb.append("SUGGESTIONS\n").append(flagged.joinToString("\n") { "  - $it" })
                ok(sb.toString().trim())
            }))
        Tools.register(Tool("quota_status", "Free-tier usage of each model provider: requests this minute, limits, cooldowns, errors, and the last 7 days.", emptyList(), Risk.READ, group = "ops",
            run = { _, _ -> ok(Quota.report()) }))
        Tools.register(Tool("services_list", "The registry of free services AXIOM can use, with the last check result.", emptyList(), Risk.READ, group = "ops", run = { _, _ ->
            ensureSeed()
            ok(Store.get().query("SELECT * FROM services ORDER BY name").joinToString("\n") { r ->
                val o = try { JSONObject(r["body"]!!) } catch (e: Exception) { JSONObject() }
                "${r["name"]}: ${o.optString("desc")}  [${o.optString("status")}]  ${o.optString("url")}"
            })
        }))
        Tools.register(Tool("services_check", "Check that every registered free service answers right now.", emptyList(), Risk.READ, 120_000L, group = "ops", run = { _, _ ->
            ensureSeed()
            val sb = StringBuilder()
            for (r in Store.get().query("SELECT * FROM services")) {
                val o = try { JSONObject(r["body"]!!) } catch (e: Exception) { continue }
                val u = o.optString("url"); val code = if (u.isNotEmpty() && Net.unsafe(u) == null) Net.head(u) else -1
                val st = if (code in 200..499) "reachable ($code)" else "NOT reachable"
                o.put("status", st).put("checked", System.currentTimeMillis())
                Store.get().replace("services", ContentValues().apply { put("name", r["name"]); put("body", o.toString()); put("verified", if (code in 200..499) 1 else 0) })
                sb.append(r["name"]).append(": ").append(st).append('\n')
            }
            ok(sb.toString().trim())
        }))
        Tools.register(Tool("services_add", "Add a free service to the registry (name, what it gives, its URL).",
            listOf(A("name", "short name"), A("desc", "what it offers and its limits"), A("url", "base URL")), Risk.LOCAL, group = "ops", run = { _, a ->
                ensureSeed()
                Store.get().replace("services", ContentValues().apply {
                    put("name", a.getString("name").lowercase().take(40)); put("verified", 0)
                    put("body", JSONObject().put("desc", a.getString("desc")).put("url", a.getString("url")).put("checked", 0).put("status", "not checked").toString())
                })
                ok("Added ${a.getString("name")}. Run services_check to test it.")
            }))

        // templates
        Tools.register(Tool("template_list", "List document templates (built in and your own).", emptyList(), Risk.READ, group = "ops", run = { _, _ ->
            val mine = Store.get().query("SELECT name, version FROM templates").associate { it["name"]!! to it["version"]!! }
            ok((DocMaker.builtin.map { it.key + " (" + it.format + ")" + (mine[it.key]?.let { v -> " customised v$v" } ?: "") } +
                mine.keys.filter { k -> DocMaker.builtin.none { it.key == k } }.map { "$it (yours, v${mine[it]})" }).joinToString("\n"))
        }))
        Tools.register(Tool("template_show", "Show a template's structure.", listOf(A("name", "template key")), Risk.READ, group = "ops", run = { _, a ->
            val t = DocMaker.template(a.getString("name")) ?: return@Tool fail("No such template.")
            ok("${t.key}: ${t.title}, format ${t.format}\n${t.structure}")
        }))
        Tools.register(Tool("template_save", "Create or change a template. The old version is kept and can be rolled back.",
            listOf(A("name", "template key"), A("title", "display title"), A("format", "docx, pdf, xlsx, md, html"), A("structure", "what sections the document must have, in order")), Risk.LOCAL, group = "ops", run = { _, a ->
                val k = Workspace.slug(a.getString("name"))
                val body = JSONObject().put("title", a.getString("title")).put("format", a.getString("format")).put("structure", a.getString("structure")).toString()
                val v = (Store.get().scalar("SELECT COALESCE(MAX(version),0) FROM template_versions WHERE name = ?", k) + 1).toInt()
                val now = System.currentTimeMillis()
                Store.get().replace("template_versions", ContentValues().apply { put("name", k); put("version", v); put("body", body); put("ts", now) })
                Store.get().replace("templates", ContentValues().apply { put("name", k); put("kind", "doc"); put("body", body); put("version", v); put("ts", now) })
                ok("Template $k saved as version $v.")
            }))
        Tools.register(Tool("template_rollback", "Go back to an earlier version of a template (or drop the customisation with version 0).",
            listOf(A("name", "template key"), A("version", "version number, 0 = back to the built-in", true, "integer")), Risk.LOCAL, group = "ops", run = { _, a ->
                val k = a.getString("name"); val v = a.optInt("version", 0)
                if (v == 0) { Store.get().delete("templates", "name = ?", k); return@Tool ok("Template $k is back to its built-in form.") }
                val r = Store.get().one("SELECT body FROM template_versions WHERE name = ? AND version = ?", k, v.toString()) ?: return@Tool fail("No version $v of $k.")
                Store.get().replace("templates", ContentValues().apply { put("name", k); put("kind", "doc"); put("body", r["body"]); put("version", v); put("ts", System.currentTimeMillis()) })
                ok("Template $k is now version $v.")
            }))

        Tools.register(Tool("audit_show", "Show the latest entries of the audit trail (what AXIOM did, who approved it, and the outcome).",
            listOf(A("count", "how many entries, default 25", false, "integer")), Risk.READ, group = "ops", run = { _, a -> ok(Audit.format(a.optInt("count", 25).coerceIn(1, 100))) }))

        // coder loop
        Tools.register(Tool("code_build", "Write a program (JavaScript, runs in the offline sandbox), test it against cases it writes itself, repair it until the tests pass, and save the code and tests into the project. Use it for algorithms, parsers, calculators, converters; for fixing a bug pass the broken code in 'existing'.",
            listOf(A("goal", "what the program must do, with input and output format"), A("name", "file base name"), A("existing", "current code to fix or extend", false)), Risk.LOCAL, 400_000L, group = "code", run = { c, a ->
                val goal = a.getString("goal"); val existing = a.optString("existing", "")
                var feedback = ""; var lastCode = existing; val log = StringBuilder()
                for (round in 1..5) {
                    if (c.cancelled()) return@Tool fail("Cancelled.")
                    c.progress("Round $round of 5")
                    val j = try {
                        Router.runJson(c.brain, Router.T.CODE, Router.Opts(
                            "You write JavaScript that runs in a sandbox with NO network and NO files. The code is a function BODY reading the string DATA and returning a string or JSON-serialisable value. " +
                                "Return ONE JSON object: {\"code\":\"...\",\"tests\":[{\"input\":\"...\",\"expect_equals\":\"exact output\" or \"expect_contains\":\"text\"}],\"notes\":\"how to use it\"}. " +
                                "At least 4 tests including malformed input and an edge case; expected values must be right by hand calculation." +
                                (if (existing.isNotBlank()) "\nExisting code to fix or extend:\n$existing" else "") + feedback,
                            goal, json = true, maxOut = 6000, task = c.task))
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return@Tool fail("The model could not write it: ${e.message}", retriable = true) }
                    val code = j.optString("code"); val tests = j.optJSONArray("tests") ?: JSONArray()
                    if (code.isBlank() || tests.length() < 3) { feedback = "\nYour last answer had no code or fewer than 3 tests. Fix that."; continue }
                    lastCode = code
                    val r = UserTools.runTests(code, tests)
                    log.append("round $round: ${r.passed}/${r.total} tests passed\n")
                    if (r.passed == r.total) {
                        val dir = File(Workspace.project(c.project), "code").apply { mkdirs() }
                        val base = Workspace.slug(a.getString("name"))
                        val f = File(dir, "$base.js"); val t = File(dir, "$base.tests.json")
                        if (f.exists()) try { f.copyTo(File(dir, "$base.js.bak"), true) } catch (e: Exception) { /* best effort */ }
                        f.writeText("// function body: reads DATA, returns the result\n$code\n"); t.writeText(tests.toString(2))
                        Workspace.note(f.path, "code_build", "${r.total} tests pass")
                        return@Tool ok("Done after $round round(s); all ${r.total} tests pass.\n${j.optString("notes")}\n\n```js\n${Axiom.clip(code, 3000)}\n```", listOf(f.path, t.path))
                    }
                    feedback = "\nYOUR PREVIOUS CODE:\n$code\nFAILED TESTS:\n" + r.failures.take(4).joinToString("\n") + "\nFix the code (or a wrong expected value) and return the full JSON again."
                }
                fail(log.toString() + "It still fails its own tests after 5 rounds, so nothing was saved. Last attempt:\n" + Axiom.clip(lastCode, 1500))
            }))
    }
}
