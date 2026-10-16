package com.jarvis.assistant

import android.content.ContentValues
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Workflows and scheduled automation. A workflow is a short list of tool calls with a trigger (manual, every N minutes, daily/weekly at a time,
 * a new file in a folder, a matching notification). The user reviews the exact steps before it is switched on. Each run is idempotent per trigger
 * (the same file or time slot never runs twice), every step goes through the normal tool door (permissions, approvals, audit), and a failure
 * is reported with the step that failed.
 */
object Workflows {
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    fun cancel(id: String) { cancelled.add(id) }

    // ---------- spec ----------

    class Check(val spec: JSONObject?, val error: String?)

    private val tmpl = Regex("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}")

    /** Validates a spec against the real tool registry. */
    fun validate(raw: JSONObject): Check {
        val steps = raw.optJSONArray("steps") ?: return Check(null, "the workflow has no steps")
        if (steps.length() == 0 || steps.length() > 15) return Check(null, "a workflow needs between 1 and 15 steps")
        val ids = HashSet<String>()
        val out = JSONArray()
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: return Check(null, "step ${i + 1} is not an object")
            val id = s.optString("id", "s${i + 1}").ifBlank { "s${i + 1}" }
            if (!ids.add(id)) return Check(null, "two steps are called $id")
            val tool = s.optString("tool")
            val t = Tools.get(tool) ?: return Check(null, "step $id uses \"$tool\", which is not a tool AXIOM has")
            if (t.group == "core" && tool == "ask_user") return Check(null, "a workflow cannot wait for a question; use approvals instead")
            val args = s.optJSONObject("args") ?: JSONObject()
            val names = t.args.map { it.name }.toSet()
            args.keys().forEach { k -> if (k !in names) return Check(null, "step $id: $tool has no argument \"$k\" (it has: ${names.joinToString(", ")})") }
            for (a in t.args) if (a.required && !args.has(a.name)) return Check(null, "step $id: $tool needs \"${a.name}\" (${a.desc})")
            for (k in args.keys()) {
                val v = args.opt(k)
                if (v is String) for (m in tmpl.findAll(v)) {
                    val p = m.groupValues[1].split('.')
                    val ok = when (p[0]) { "trigger", "date", "prev" -> true; "steps" -> p.size >= 2 && p[1] in ids && (p.size == 2 || p[2] in listOf("output", "file")); else -> false }
                    if (!ok) return Check(null, "step $id uses {{${m.groupValues[1]}}} which refers to nothing before it (use trigger, date, prev or steps.<earlier id>.output)")
                }
            }
            out.put(JSONObject().put("id", id).put("tool", tool).put("args", args).put("on_fail", if (s.optString("on_fail") == "continue") "continue" else "stop"))
        }
        val trg = raw.optJSONObject("trigger") ?: JSONObject().put("type", "manual")
        val type = trg.optString("type", "manual")
        if (type !in listOf("manual", "every", "daily", "weekly", "file", "notification")) return Check(null, "unknown trigger type \"$type\"")
        if (type == "every" && trg.optInt("every_min", 0) < 5) return Check(null, "\"every\" needs every_min of at least 5")
        if ((type == "daily" || type == "weekly") && !Regex("^\\d{1,2}:\\d{2}$").matches(trg.optString("at"))) return Check(null, "$type needs at as HH:MM")
        if (type == "file" && trg.optString("dir").isBlank()) return Check(null, "a file trigger needs dir")
        if (type == "notification" && trg.optString("app").isBlank() && trg.optString("contains").isBlank()) return Check(null, "a notification trigger needs app or contains")
        return Check(JSONObject().put("name", raw.optString("name", "workflow").take(60)).put("trigger", trg).put("steps", out).put("notify", raw.optString("notify", "result").let { if (it in listOf("result", "errors", "never")) it else "result" }), null)
    }

    fun describe(spec: JSONObject): String {
        val t = spec.getJSONObject("trigger")
        val when_ = when (t.optString("type")) {
            "every" -> "every ${t.optInt("every_min")} minutes"; "daily" -> "every day at ${t.optString("at")}"
            "weekly" -> "every ${t.optString("days", "week")} at ${t.optString("at")}"; "file" -> "when a new file${t.optString("pattern").let { if (it.isBlank()) "" else " matching $it" }} appears in ${t.optString("dir")}"
            "notification" -> "when a notification${t.optString("app").let { if (it.isBlank()) "" else " from $it" }}${t.optString("contains").let { if (it.isBlank()) "" else " containing \"$it\"" }} arrives"; else -> "only when you start it"
        }
        val steps = spec.getJSONArray("steps")
        return "\"${spec.optString("name")}\" runs $when_.\n" + (0 until steps.length()).joinToString("\n") { i -> val s = steps.getJSONObject(i); "${i + 1}. ${s.getString("tool")} ${Axiom.clip(s.getJSONObject("args").toString(), 160)}" + (if (s.optString("on_fail") == "continue") " (continues if it fails)" else "") } +
            "\nResult: " + when (spec.optString("notify")) { "errors" -> "you are told only about failures"; "never" -> "silent"; else -> "you get the result of the last step" } + "."
    }

    // ---------- storage ----------

    fun row(id: String): Map<String, String>? = Store.get().one("SELECT * FROM workflows WHERE id = ? OR id LIKE ?", id, "%$id")

    fun list(): List<Map<String, String>> = Store.get().query("SELECT * FROM workflows ORDER BY created DESC")

    fun line(w: Map<String, String>): String {
        val spec = try { JSONObject(w["spec"]!!) } catch (e: Exception) { JSONObject() }
        val last = w["last_run"]!!.toLong()
        return "${w["id"]!!.takeLast(5)} ${if (w["enabled"] == "1") "on " else "off"} ${w["name"]} — trigger ${w["trig"]}, ${spec.optJSONArray("steps")?.length() ?: 0} steps, ${w["runs"]} runs, last " + (if (last == 0L) "never" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(last)))
    }

    fun save(spec: JSONObject, enabled: Boolean): String {
        val id = Axiom.newId("wf")
        val cv = ContentValues().apply {
            put("id", id); put("name", spec.optString("name")); put("spec", spec.toString()); put("trig", spec.getJSONObject("trigger").optString("type"))
            put("enabled", if (enabled) 1 else 0); put("approved", if (enabled) 1 else 0); put("last_run", 0L); put("runs", 0); put("created", System.currentTimeMillis())
        }
        Store.get().insert("workflows", cv)
        Cache.put("wf:mark:$id", System.currentTimeMillis().toString(), 3650L * 86400_000L)
        return id
    }

    // ---------- running ----------

    private fun subst(v: String, outputs: Map<String, String>, prev: String, trigger: String, files: Map<String, String> = emptyMap()): String =
        tmpl.replace(v) { m ->
            val p = m.groupValues[1].split('.')
            when (p[0]) {
                "trigger" -> trigger; "prev" -> prev
                "date" -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                "steps" -> if (p.getOrNull(2) == "file") files[p[1]] ?: "" else outputs[p[1]] ?: ""; else -> ""
            }
        }

    class RunResult(val ok: Boolean, val summary: String)

    suspend fun run(base: ToolCtx, id: String, trigger: String = "", key: String = "", tainted: Boolean = false, manual: Boolean = false): RunResult {
        val w = row(id) ?: return RunResult(false, "No such workflow.")
        val wid = w["id"]!!
        if (w["approved"] != "1") return RunResult(false, "This workflow was not approved yet, so it does not run.")
        if (w["enabled"] != "1" && !manual) return RunResult(false, "It is switched off.")
        if (key.isNotEmpty() && Cache.get("wrun:$wid:$key") != null) return RunResult(true, "Already ran for this trigger.")
        if (!running.add(wid)) return RunResult(false, "It is already running.")
        cancelled.remove(wid)
        val spec = JSONObject(w["spec"]!!)
        val steps = spec.getJSONArray("steps")
        val ctx = ToolCtx(base.app, base.brain, base.prefs, "wf:$wid", base.project, tainted = tainted, direct = false,
            confirm = { Approvals.yesNo(it, "wf:$wid") }, progress = {}, cancelled = { cancelled.contains(wid) })
        val outputs = LinkedHashMap<String, String>(); val files = HashMap<String, String>()
        var prev = ""; var failed: String? = null; var lastOut = ""
        val t0 = System.currentTimeMillis()
        try {
            for (i in 0 until steps.length()) {
                if (cancelled.contains(wid)) { failed = "cancelled before step ${i + 1}"; break }
                val s = steps.getJSONObject(i)
                val args = JSONObject()
                val raw = s.getJSONObject("args")
                raw.keys().forEach { k -> val v = raw.get(k); args.put(k, if (v is String) subst(v, outputs, prev, trigger, files) else v) }
                var r = Tools.execute(ctx, s.getString("tool"), args)
                if (!r.ok && r.retriable && !r.declined) { kotlinx.coroutines.delay(3000); r = Tools.execute(ctx, s.getString("tool"), args) }
                files[s.getString("id")] = r.files.firstOrNull() ?: ""; outputs[s.getString("id")] = Axiom.clip(r.output, 8000); prev = outputs[s.getString("id")]!!; lastOut = r.output
                if (!r.ok && s.optString("on_fail") != "continue") { failed = "step ${i + 1} (${s.getString("tool")}) failed: ${Axiom.clip(r.output, 300)}"; break }
            }
        } catch (e: CancellationException) { running.remove(wid); throw e
        } catch (e: Exception) { Diag.record(e); failed = "unexpected error: ${e.message}" }
        running.remove(wid)
        if (key.isNotEmpty() && failed == null) Cache.put("wrun:$wid:$key", "1", 10L * 86400_000L)
        Store.get().exec("UPDATE workflows SET last_run = ?, runs = runs + 1 WHERE id = ?", System.currentTimeMillis(), wid)
        Audit.log("workflow", "wf:" + w["name"], Axiom.clip(trigger, 200), if (failed == null) "ok in ${(System.currentTimeMillis() - t0) / 1000}s" else "FAIL: $failed", "auto", wid)
        Perf.log("workflow", w["name"]!!, "", System.currentTimeMillis() - t0, 1, 0, failed == null, failed ?: "")
        val mode = spec.optString("notify", "result")
        val name = w["name"]
        if (failed != null) { if (mode != "never") { Notify.post("failed", "wf-fail:$wid", "Workflow failed: $name", failed, 30 * 60_000L); Telegram.sendToOwner(base.prefs, "Workflow \"$name\" failed: $failed") }; return RunResult(false, failed) }
        if (mode == "result" && !manual) { Notify.post("done", "wf-ok:$wid", "Workflow done: $name", Axiom.clip(lastOut, 300), 60_000L); Telegram.sendToOwner(base.prefs, "Workflow \"$name\":\n" + Axiom.clip(lastOut, 900)) }
        return RunResult(true, lastOut)
    }

    // ---------- triggers ----------

    private fun mark(id: String): Long = Cache.get("wf:mark:$id")?.toLongOrNull() ?: System.currentTimeMillis()
    private fun setMark(id: String, ms: Long) = Cache.put("wf:mark:$id", ms.toString(), 3650L * 86400_000L)

    private fun glob(pattern: String, name: String): Boolean = pattern.isBlank() || Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE).matches(name)

    suspend fun tick(c: ToolCtx) {
        val now = System.currentTimeMillis()
        for (w in list()) {
            if (w["enabled"] != "1" || w["approved"] != "1") continue
            val spec = try { JSONObject(w["spec"]!!) } catch (e: Exception) { continue }
            val t = spec.getJSONObject("trigger"); val id = w["id"]!!; val last = w["last_run"]!!.toLong()
            try {
                when (t.optString("type")) {
                    "every" -> if (now - maxOf(last, mark(id)) >= t.optInt("every_min") * 60_000L) { setMark(id, now); run(c, id, "schedule", "") }
                    "daily", "weekly" -> {
                        val (h, m) = t.optString("at").split(":").map { it.toInt() }
                        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                        val dayOk = t.optString("type") == "daily" || t.optString("days").lowercase().let { d -> d.isBlank() || listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")[cal.get(Calendar.DAY_OF_WEEK) - 1].let { n -> d.contains(n) } }
                        if (dayOk && now >= cal.timeInMillis && last < cal.timeInMillis && mark(id) < cal.timeInMillis) run(c, id, "schedule", "slot:" + cal.timeInMillis)
                    }
                    "file" -> {
                        val dir = Workspace.inside(c.project, t.optString("dir")) ?: continue
                        val since = mark(id)
                        val fresh = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.lastModified() > since && now - it.lastModified() > 5000 && glob(t.optString("pattern"), it.name) }.sortedBy { it.lastModified() }.take(3)
                        for (f in fresh) { run(c, id, f.absolutePath, "file:" + f.absolutePath + f.lastModified(), tainted = true); setMark(id, f.lastModified()) }
                    }
                    "notification" -> {
                        val since = mark(id)
                        val hits = Memory.get(Axiom.ctx).recentNotifs(since, 40).filter { it.ts > since }.filter { n ->
                            (t.optString("app").isBlank() || n.app.contains(t.optString("app"), true) || n.pkg.contains(t.optString("app"), true)) &&
                                (t.optString("contains").isBlank() || (n.title + " " + n.text).contains(t.optString("contains"), true))
                        }.sortedBy { it.ts }.take(3)
                        if (hits.isNotEmpty()) setMark(id, hits.maxOf { it.ts })
                        else setMark(id, now)
                        for (n in hits) run(c, id, "[${n.app}] ${n.title}: ${n.text}", "n:" + n.ts, tainted = true)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Diag.record(e) }
        }
    }

    fun init() { Ticker.hook { Workflows.tick(it) } }

    // ---------- tools ----------

    fun register() {
        init()
        Tools.register(Tool("workflow_create", "Turn a described routine into a workflow (trigger + tool steps). Shows the user exactly what will run and switches it on only after approval. Example: 'every morning at 8 fetch this page, summarise it and notify me'.",
            listOf(A("description", "what should happen, and when"), A("name", "short name", false)), Risk.LOCAL, 240_000L, group = "automation",
            run = { c, a ->
                val catalog = Tools.describe(Tools.all().filter { it.name !in listOf("ask_user", "workflow_create") }.map { it.name })
                var spec: JSONObject? = null; var err: String? = null
                for (attempt in 1..2) {
                    val j = Router.runJson(c.brain, Router.T.PLAN, Router.Opts(
                        "You design automation workflows for AXIOM. Return ONE JSON object: {\"name\":\"short name\",\"trigger\":{\"type\":\"manual|every|daily|weekly|file|notification\",\"every_min\":N (for every, at least 5),\"at\":\"HH:MM\",\"days\":\"mon,wed (weekly)\",\"dir\":\"folder inside the project (file)\",\"pattern\":\"*.pdf\",\"app\":\"app name (notification)\",\"contains\":\"text (notification)\"},\"steps\":[{\"id\":\"s1\",\"tool\":\"tool name\",\"args\":{...},\"on_fail\":\"stop|continue\"}],\"notify\":\"result|errors|never\"}. " +
                            "Use ONLY these tools with exactly their argument names. Text arguments may contain {{steps.<earlier id>.output}}, {{prev}} (previous step output), {{trigger}} (the file path or notification text that started it) and {{date}}. " +
                            "Use the 'think' tool to have the model summarise or write from earlier output. Keep it short: fewest steps that do the job. Never invent tools.\nTOOLS:\n$catalog" + (if (err != null) "\nYOUR PREVIOUS ATTEMPT WAS REJECTED: $err. Fix it." else ""),
                        a.getString("description") + (if (a.optString("name", "").isNotBlank()) "\nName: ${a.getString("name")}" else ""), json = true, maxOut = 3000, task = c.task))
                    val chk = validate(j)
                    if (chk.spec != null) { spec = chk.spec; break }
                    err = chk.error
                }
                if (spec == null) return@Tool fail("I could not build a valid workflow from that: $err. Describe it with the tools I have (see the catalogue) or split it in two.")
                val yes = c.confirm("Switch on this workflow?\n\n" + describe(spec))
                val id = save(spec, yes)
                if (yes) ok("Workflow ${id.takeLast(5)} is on.\n" + describe(spec)) else ok("Saved as a draft (id ${id.takeLast(5)}), not running. Ask me to switch it on when you are ready.\n" + describe(spec))
            }))
        Tools.register(Tool("workflow_list", "List workflows with their trigger, runs and last run.", emptyList(), Risk.READ, group = "automation", run = { _, _ -> ok(list().joinToString("\n") { line(it) }.ifEmpty { "No workflows yet." }) }))
        Tools.register(Tool("workflow_show", "Show the exact steps of a workflow.", listOf(A("id", "workflow id (last 5 characters)")), Risk.READ, group = "automation",
            run = { _, a -> val w = row(a.getString("id")) ?: return@Tool fail("No such workflow."); ok(describe(JSONObject(w["spec"]!!)) + "\nStatus: " + (if (w["enabled"] == "1") "on" else "off") + (if (w["approved"] != "1") " (not approved)" else "")) }))
        Tools.register(Tool("workflow_run", "Run a workflow now (a test run). Steps still ask approval for risky actions.", listOf(A("id", "workflow id")), Risk.LOCAL, 900_000L, group = "automation",
            run = { c, a -> val w = row(a.getString("id")) ?: return@Tool fail("No such workflow."); val r = run(c, w["id"]!!, "manual", "", manual = true); if (r.ok) ok(r.summary.ifBlank { "Done." }, untrusted = true) else fail(r.summary) }))
        Tools.register(Tool("workflow_toggle", "Switch a workflow on or off. A draft is approved and switched on by showing it to the user first.", listOf(A("id", "workflow id"), A("on", "true or false", true, "bool")), Risk.LOCAL, group = "automation",
            run = { c, a ->
                val w = row(a.getString("id")) ?: return@Tool fail("No such workflow.")
                if (a.getBoolean("on") && w["approved"] != "1") {
                    if (!c.confirm("Switch on this workflow?\n\n" + describe(JSONObject(w["spec"]!!)))) return@Tool ok("Left off.")
                    Store.get().exec("UPDATE workflows SET approved = 1 WHERE id = ?", w["id"])
                }
                Store.get().exec("UPDATE workflows SET enabled = ? WHERE id = ?", if (a.getBoolean("on")) 1 else 0, w["id"]); ok(if (a.getBoolean("on")) "Switched on." else "Switched off.")
            }))
        Tools.register(Tool("workflow_stop", "Stop a workflow that is running right now.", listOf(A("id", "workflow id")), Risk.LOCAL, group = "automation",
            run = { _, a -> val w = row(a.getString("id")) ?: return@Tool fail("No such workflow."); cancel(w["id"]!!); ok("Stop requested; it ends before its next step.") }))
        Tools.register(Tool("workflow_log", "Recent runs of workflows (what ran, what failed).", listOf(A("id", "workflow id", false)), Risk.READ, group = "automation",
            run = { _, a ->
                val w = a.optString("id", "").takeIf { it.isNotBlank() }?.let { row(it) }
                val rows = Store.get().query("SELECT ts, source, outcome, task FROM audit WHERE actor = 'workflow' " + (if (w != null) "AND task = ? " else "") + "ORDER BY id DESC LIMIT 15", *(if (w != null) arrayOf(w["id"]!!) else emptyArray()))
                ok(rows.joinToString("\n") { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it["ts"]!!.toLong())) + " " + it["source"] + ": " + it["outcome"] }.ifEmpty { "No runs yet." })
            }))
        Tools.register(Tool("workflow_delete", "Delete a workflow. Always asks first.", listOf(A("id", "workflow id")), Risk.DANGEROUS, group = "automation",
            run = { _, a -> val w = row(a.getString("id")) ?: return@Tool fail("No such workflow."); Store.get().delete("workflows", "id = ?", w["id"]!!); ok("Deleted ${w["name"]}.") }))
        Tools.register(Tool("think", "Have the model write, summarise, classify or decide from given material (use inside workflows and plans). role = writer, analyst, researcher, filer.",
            listOf(A("instruction", "what to produce"), A("material", "the text to work from", false), A("role", "writer, analyst, researcher or filer", false)), Risk.READ, 120_000L, group = "automation",
            run = { c, a -> ok(Agents.think(c, a.optString("role", "writer"), a.getString("instruction"), Shield.wrap("material", a.optString("material", ""), 30000).text.takeIf { a.optString("material", "").isNotBlank() } ?: ""), untrusted = c.tainted) }))
    }
}
