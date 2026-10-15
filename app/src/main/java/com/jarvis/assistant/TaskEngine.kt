package com.jarvis.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs goals to the end without the chat window: plans, executes the steps (in parallel where they are independent),
 * retries with growing delays, re-plans when a route fails, keeps every state change in the database so it survives a restart,
 * and can be paused, resumed or stopped at any moment.
 */
object TaskEngine {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val paused = ConcurrentHashMap.newKeySet<String>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val replans = ConcurrentHashMap<String, Int>()

    private fun brain() = Shared.brain()
    private fun app() = Axiom.ctx

    /** Starts a new goal. Returns its id at once; work continues in the background. */
    fun start(goal: String, project: String = Prefs(app()).activeProject, direct: Boolean = true, source: String = "chat", priority: Int = 3): String {
        val id = Tasks.create(goal = goal, project = project, descr = goal, role = "coordinator", status = Tasks.PLANNING, direct = direct, source = source, priority = priority)
        Tasks.set(id, "started" to System.currentTimeMillis())
        launch(id)
        return id
    }

    private fun launch(id: String) {
        jobs[id]?.cancel()
        jobs[id] = Engine.scope.launch(Dispatchers.IO) { runRoot(id) }
    }

    /** Called when the app starts: anything that was running when the phone or the app stopped goes on from its last good step. */
    fun resumeAll() {
        try {
            for (r in Tasks.roots(Tasks.PLANNING, Tasks.RUNNING, Tasks.READY, Tasks.AWAITING)) {
                val id = r["id"]!!
                if (jobs[id]?.isActive == true) continue
                for (k in Tasks.children(id)) if (k["status"] == Tasks.RUNNING || k["status"] == Tasks.AWAITING) Tasks.set(k["id"]!!, "status" to Tasks.READY, "note" to "resumed")
                ChatBus.publish("system", "Resuming: " + Axiom.clip(r["goal"]!!, 70))
                launch(id)
            }
        } catch (e: Exception) { Diag.record(e) }
    }

    fun stop(id: String): String {
        val r = Tasks.get(id) ?: return "There is no task $id."
        if (Tasks.terminal(r["status"]!!)) return "Task $id is already ${Tasks.label(r["status"]!!)}."
        cancelled.add(id)
        Approvals.list().filter { it.task == id || Tasks.get(it.task)?.get("parent") == id }.forEach { Approvals.answer(it.id, "no") }
        jobs[id]?.cancel()
        for (k in Tasks.children(id)) if (!Tasks.terminal(k["status"]!!)) Tasks.set(k["id"]!!, "status" to Tasks.CANCELLED, "fail_reason" to "stopped by you")
        Tasks.set(id, "status" to Tasks.CANCELLED, "finished" to System.currentTimeMillis(), "fail_reason" to "stopped by you")
        Quota.forgetTask(id)
        return "Stopped task $id. What was already finished is kept."
    }

    fun pause(id: String): String { paused.add(id); Tasks.set(id, "status" to Tasks.PAUSED); return "Paused $id. Steps already running will finish; nothing new starts." }

    fun resume(id: String): String {
        val r = Tasks.get(id) ?: return "There is no task $id."
        paused.remove(id)
        if (r["status"] == Tasks.PAUSED || r["status"] == Tasks.NEW) { Tasks.set(id, "status" to Tasks.RUNNING); if (jobs[id]?.isActive != true) launch(id) }
        return "Resumed $id."
    }

    /** Retry what failed in a finished or stopped task, keeping everything that succeeded. */
    fun retry(id: String): String {
        val r = Tasks.get(id) ?: return "There is no task $id."
        cancelled.remove(id); paused.remove(id)
        var n = 0
        for (k in Tasks.children(id)) if (k["status"] == Tasks.FAILED || k["status"] == Tasks.CANCELLED) {
            Tasks.set(k["id"]!!, "status" to Tasks.READY, "attempts" to 0, "fail_reason" to "", "retry_at" to 0L); n++
        }
        Tasks.set(id, "status" to Tasks.RUNNING, "fail_reason" to "", "finished" to 0L, "started" to System.currentTimeMillis())
        replans.remove(id)
        launch(id)
        return if (n == 0 && Tasks.children(id).isEmpty()) "Starting $id again from the plan." else "Retrying $n step(s) of $id."
    }

    // ---- the run ----

    private fun say(root: Map<String, String>, text: String, telegram: Boolean = false) {
        ChatBus.publish("system", text)
        if (telegram || root["source"] == "telegram") {
            val p = Prefs(app())
            if (telegram) Engine.scope.launch(Dispatchers.IO) { Telegram.sendToOwner(p, text) }
        }
    }

    private suspend fun runRoot(id: String) {
        var root = Tasks.get(id) ?: return
        try {
            if (Tasks.children(id).isEmpty()) {
                Tasks.set(id, "status" to Tasks.PLANNING)
                say(root, "Planning: " + Axiom.clip(root["goal"]!!, 80), telegram = root["source"] == "telegram")
                val memory = try { Knowledge.contextFor(root["goal"]!!, root["project"]!!) } catch (e: Exception) { "" }
                var plan = Planner.plan(brain(), root["goal"]!!, root["project"]!!, memory, id)
                if (plan.ask != null) {
                    val ans = Approvals.ask(plan.ask!!.q, plan.ask!!.options, id)
                    if (ans.isEmpty()) { fin(id, Tasks.FAILED, "I needed an answer to: ${plan.ask!!.q} and did not get one."); return }
                    plan = Planner.plan(brain(), root["goal"]!!, root["project"]!!, memory, id, ans)
                }
                if (plan.steps.isEmpty()) { fin(id, Tasks.FAILED, "The plan came back empty."); return }
                addSteps(root, plan.steps, emptyMap())
                Tasks.set(id, "note" to plan.summary)
            }
            Tasks.set(id, "status" to Tasks.RUNNING)
            root = Tasks.get(id)!!
            loop(id)
        } catch (e: CancellationException) {
            if (!cancelled.contains(id)) Tasks.set(id, "note" to "interrupted; will continue when the app is running")
            throw e
        } catch (e: Exception) {
            Diag.record(e)
            fin(id, Tasks.FAILED, Secrets.redact(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun addSteps(root: Map<String, String>, steps: List<Planner.Step>, existing: Map<String, String>): Map<String, String> {
        val map = HashMap<String, String>(existing)
        for (s in steps) map[s.id] = Axiom.newId("t")
        for (s in steps) {
            val inputs = JSONObject().put("args", s.args)
            val refs = JSONObject(); map.forEach { (k, v) -> refs.put(k, v) }
            inputs.put("refs", refs)
            Tasks.create(
                goal = root["goal"]!!, project = root["project"]!!, parent = root["id"]!!, descr = s.desc, tool = s.tool, inputs = inputs,
                deps = s.depends.mapNotNull { map[it] }, role = s.role, priority = s.priority, expected = s.expected, success = s.success,
                onFail = s.onFail, direct = root["direct"] == "1", source = root["source"]!!, status = Tasks.READY, id = map[s.id]!!
            )
        }
        return map
    }

    private suspend fun loop(id: String) {
        val deadline = (Tasks.get(id)!!["started"]!!.toLong().takeIf { it > 0 } ?: System.currentTimeMillis()) + Config.int("task_max_min") * 60_000L
        var lastReport = 0
        while (true) {
            if (cancelled.contains(id)) return
            val kids = Tasks.children(id)
            if (kids.all { Tasks.terminal(it["status"]!!) }) break
            if (System.currentTimeMillis() > deadline) {
                for (k in kids) if (!Tasks.terminal(k["status"]!!)) Tasks.set(k["id"]!!, "status" to Tasks.CANCELLED, "fail_reason" to "time limit")
                break
            }
            if (paused.contains(id)) { delay(1000); continue }
            val byId = kids.associateBy { it["id"]!! }
            // steps whose prerequisite failed for good cannot run
            for (k in kids) if (k["status"] == Tasks.READY && !inflight.contains(k["id"])) {
                val bad = k["deps"]!!.split(",").filter { it.isNotEmpty() }.mapNotNull { byId[it] }.firstOrNull { (it["status"] == Tasks.FAILED && it["on_fail"] != "skip") || it["status"] == Tasks.CANCELLED }
                if (bad != null) Tasks.set(k["id"]!!, "status" to Tasks.CANCELLED, "fail_reason" to "a step it needed did not finish")
            }
            val running = kids.count { it["status"] == Tasks.RUNNING || inflight.contains(it["id"]) }
            val now = System.currentTimeMillis()
            val ready = kids.filter { k ->
                k["status"] == Tasks.READY && !inflight.contains(k["id"]) && k["retry_at"]!!.toLong() <= now &&
                    k["deps"]!!.split(",").filter { it.isNotEmpty() }.all { d -> byId[d]?.get("status").let { it == Tasks.DONE || (it == Tasks.FAILED && byId[d]?.get("on_fail") == "skip") } }
            }.sortedBy { it["priority"]!!.toInt() }
            var slots = Config.int("max_parallel") - running
            for (k in ready) {
                if (slots <= 0) break
                val tid = k["id"]!!
                inflight.add(tid)
                slots--
                Engine.scope.launch(Dispatchers.IO) {
                    try { runStep(id, tid) } catch (e: CancellationException) { /* stopped */ } catch (e: Exception) {
                        Diag.record(e); Tasks.set(tid, "status" to Tasks.FAILED, "fail_reason" to Secrets.redact(e.message ?: "error"))
                    } finally { inflight.remove(tid) }
                }
            }
            val done = kids.count { it["status"] == Tasks.DONE }
            if (done > lastReport) {
                lastReport = done
                ChatBus.publish("system", "Task ${id.takeLast(5)}: $done/${kids.size} steps done")
                Tasks.set(id, "calls" to Quota.taskCalls(id))
            }
            delay(400)
        }
        if (cancelled.contains(id)) return
        finish(id)
    }

    private fun resolve(v: Any?, refs: JSONObject): Any? {
        if (v is String) {
            return Regex("\\{\\{\\s*(\\w+)(?:\\.(output|files))?\\s*\\}\\}").replace(v) { m ->
                val real = refs.optString(m.groupValues[1])
                val t = if (real.isEmpty()) null else Tasks.get(real)
                if (t == null) m.value else if (m.groupValues[2] == "files") Tasks.files(t).joinToString("\n") else Axiom.clip(t["result"].orEmpty(), 14000)
            }
        }
        if (v is JSONObject) { val o = JSONObject(); v.keys().forEach { k -> o.put(k, resolve(v.get(k), refs)) }; return o }
        if (v is JSONArray) { val a = JSONArray(); for (i in 0 until v.length()) a.put(resolve(v.get(i), refs)); return a }
        return v
    }

    private suspend fun runStep(rootId: String, tid: String) {
        val t = Tasks.get(tid) ?: return
        val root = Tasks.get(rootId) ?: return
        val attempts = t["attempts"]!!.toInt() + 1
        Tasks.set(tid, "status" to Tasks.RUNNING, "attempts" to attempts, "last_try" to System.currentTimeMillis(), "started" to (t["started"]!!.toLong().takeIf { it > 0 } ?: System.currentTimeMillis()))
        val inputs = Tasks.args(t)
        val refs = inputs.optJSONObject("refs") ?: JSONObject()
        val deps = t["deps"]!!.split(",").filter { it.isNotEmpty() }.mapNotNull { Tasks.get(it) }
        val tainted = t["tainted"] == "1" || root["tainted"] == "1" || deps.any { it["tainted"] == "1" }
        val ctx = ToolCtx(app(), brain(), Prefs(app()), tid, t["project"]!!, tainted, t["direct"] == "1",
            confirm = { text -> Approvals.yesNo(text, tid) },
            progress = { m -> ChatBus.publish("system", m) },
            cancelled = { cancelled.contains(rootId) })
        val args = (resolve(inputs.optJSONObject("args") ?: JSONObject(), refs) as JSONObject)
        val t0 = System.currentTimeMillis()
        val tool = t["tool"]!!
        val material = deps.joinToString("\n\n") { "[" + it["descr"] + "]\n" + Axiom.clip(it["result"].orEmpty(), 9000) }
        val res: ToolResult = when (tool) {
            "think" -> {
                val instruction = args.optString("instruction", t["descr"]!!).ifBlank { t["descr"]!! } + (if (t["expected"]!!.isNotEmpty()) "\nExpected result: " + t["expected"] else "")
                val extra = args.optString("material", "")
                val provider = deps.mapNotNull { Router.lastProvider[it["id"]] }.lastOrNull().orEmpty()
                ToolResult(true, Agents.think(ctx, t["role"]!!, instruction, (if (extra.isNotBlank()) extra + "\n\n" else "") + material, provider), untrusted = deps.any { it["tainted"] == "1" } || tainted)
            }
            "agent" -> Agents.work(ctx, t["role"]!!, args.optString("goal", t["descr"]!!).ifBlank { t["descr"]!! }, args.optString("context", "") + (if (material.isNotBlank()) "\n\n$material" else ""), args.optInt("max_steps", 8))
            else -> Tools.execute(ctx, tool, args)
        }
        val problem = if (res.ok) Verifier.step(tool, t["role"]!!, t["success"]!!, res) else null
        val ms = System.currentTimeMillis() - t0

        if (res.ok && problem == null) {
            Tasks.set(tid, "status" to Tasks.DONE, "result" to Axiom.clip(res.output, 60000), "files" to JSONArray(res.files).toString(), "finished" to System.currentTimeMillis(),
                "tainted" to (if (res.untrusted || tainted) 1 else 0), "ms" to ms, "verify" to "ok", "fail_reason" to "", "calls" to Quota.taskCalls(tid))
            Perf.log("task", (if (tool == "think" || tool == "agent") t["role"]!! + ":" + tool else tool), "", ms, attempts, 0, true)
            Procedures.note("step", tool, t["descr"]!!, true, "")
            return
        }
        val reason = problem?.let { "check failed: $it" } ?: res.output
        Perf.log("task", (if (tool == "think" || tool == "agent") t["role"]!! + ":" + tool else tool), "", ms, attempts, 0, false, reason)
        if (res.declined) { Tasks.set(tid, "status" to Tasks.FAILED, "fail_reason" to "you declined it", "finished" to System.currentTimeMillis(), "ms" to ms); return }
        if (cancelled.contains(rootId)) return

        val transient = res.retriable || problem != null
        if (transient && attempts < t["max_attempts"]!!.toInt()) {
            val wait = (2000L shl (attempts - 1)).coerceAtMost(60_000L)          // 2 s, 4 s, 8 s ... up to a minute
            Tasks.set(tid, "status" to Tasks.READY, "retry_at" to System.currentTimeMillis() + wait, "fail_reason" to Axiom.clip(reason, 300), "note" to "retrying in ${wait / 1000} s")
            return
        }
        Tasks.set(tid, "status" to Tasks.FAILED, "fail_reason" to Axiom.clip(reason, 500), "finished" to System.currentTimeMillis(), "ms" to ms, "result" to Axiom.clip(res.output, 4000))
        Procedures.note("step", tool, t["descr"]!!, false, reason)
        when (t["on_fail"]) {
            "abort" -> abortRoot(rootId, "A required step failed: " + Axiom.clip(reason, 200))
            "skip" -> ChatBus.publish("system", "Skipped a step that failed: " + Axiom.clip(t["descr"]!!, 60))
            else -> tryReplan(rootId, tid)
        }
    }

    private fun abortRoot(rootId: String, why: String) {
        for (k in Tasks.children(rootId)) if (!Tasks.terminal(k["status"]!!)) Tasks.set(k["id"]!!, "status" to Tasks.CANCELLED, "fail_reason" to "aborted")
        Tasks.set(rootId, "fail_reason" to why)
    }

    private suspend fun tryReplan(rootId: String, failedId: String) {
        val n = replans.merge(rootId, 1, Int::plus) ?: 1
        val root = Tasks.get(rootId) ?: return
        val failed = Tasks.get(failedId) ?: return
        if (n > 2) { abortRoot(rootId, "Gave up after two new plans. Last problem: " + Axiom.clip(failed["fail_reason"]!!, 200)); return }
        ChatBus.publish("system", "A step failed (${Axiom.clip(failed["fail_reason"]!!, 60)}). Planning another route (attempt $n of 2).")
        try {
            val kids = Tasks.children(rootId)
            val done = kids.filter { it["status"] == Tasks.DONE }
            val plan = Planner.replan(brain(), root["goal"]!!, root["project"]!!, done, failed, rootId)
            // unstarted steps that depended on the failed one are replaced by the new plan
            for (k in kids) if (k["status"] == Tasks.READY && !inflight.contains(k["id"])) Tasks.set(k["id"]!!, "status" to Tasks.CANCELLED, "fail_reason" to "replaced by a new plan")
            val existing = HashMap<String, String>(); for (k in done) existing[k["id"]!!] = k["id"]!!
            addSteps(root, plan.steps, existing)
        } catch (e: Exception) {
            abortRoot(rootId, "No other route could be planned: " + Axiom.clip(Secrets.redact(e.message ?: ""), 200))
        }
    }

    private suspend fun finish(id: String) {
        val root = Tasks.get(id) ?: return
        val kids = Tasks.children(id)
        val done = kids.filter { it["status"] == Tasks.DONE }
        val failed = kids.filter { it["status"] == Tasks.FAILED }
        val files = LinkedHashSet<String>(); done.forEach { files.addAll(Tasks.files(it)) }
        // the answer is the output of the last finished step that writes for the user
        val last = done.lastOrNull { it["tool"] == "think" || it["tool"] == "agent" } ?: done.lastOrNull()
        var answer = last?.get("result").orEmpty()
        if (done.isEmpty()) {
            fin(id, Tasks.FAILED, root["fail_reason"]!!.ifEmpty { "Nothing could be completed. " + (failed.firstOrNull()?.get("fail_reason") ?: "") }.trim())
            return
        }
        val tainted = kids.any { it["tainted"] == "1" }
        if (tainted) Tasks.set(id, "tainted" to 1)
        Tasks.set(id, "note" to "checking the result")
        val v = try { Verifier.finalAnswer(brain(), root["goal"]!!, answer, kids, Router.lastProvider[last?.get("id").orEmpty()].orEmpty(), id) } catch (e: Exception) { Verifier.Verdict(true, 0.5, emptyList(), "the check could not run") }
        if (!v.ok && v.issues.isNotEmpty() && (replans[id + ":rev"] ?: 0) < 1) {
            replans[id + ":rev"] = 1
            ChatBus.publish("system", "The check found problems (" + Axiom.clip(v.issues.joinToString("; "), 120) + "). Fixing them once.")
            try {
                val c = ToolCtx(app(), brain(), Prefs(app()), id, root["project"]!!, tainted, root["direct"] == "1", { Approvals.yesNo(it, id) }, {}, { cancelled.contains(id) })
                val fixed = Agents.think(c, "writer", "Revise the draft so that it fixes these problems, using ONLY the material. Remove claims that cannot be supported and say what could not be verified.\nPROBLEMS: " + v.issues.joinToString("; "),
                    "DRAFT:\n$answer\n\nMATERIAL:\n" + done.joinToString("\n\n") { "[" + it["descr"] + "]\n" + Axiom.clip(it["result"].orEmpty(), 5000) })
                if (fixed.isNotBlank()) answer = fixed
            } catch (e: Exception) { /* keep the draft, with the warning below */ }
        }
        val sb = StringBuilder(answer.trim())
        if (!v.ok) sb.append("\n\n⚠ Not fully verified: ").append(v.issues.joinToString("; ").ifEmpty { v.notes })
        if (failed.isNotEmpty()) sb.append("\n\nSteps that failed: ").append(failed.joinToString("; ") { Axiom.clip(it["descr"]!!, 50) + " (" + Axiom.clip(it["fail_reason"]!!, 60) + ")" })
        if (files.isNotEmpty()) sb.append("\n\nFiles:\n").append(files.joinToString("\n"))
        Tasks.set(id, "files" to JSONArray(files.toList()).toString(), "verify" to v.text())
        fin(id, Tasks.DONE, sb.toString())
        Projects.afterTask(root["project"]!!, root["goal"]!!, answer)
    }

    private fun fin(id: String, status: String, text: String) {
        val root = Tasks.get(id) ?: return
        Tasks.set(id, "status" to status, "result" to Axiom.clip(text, 60000), "fail_reason" to (if (status == Tasks.FAILED) text else ""), "finished" to System.currentTimeMillis(),
            "ms" to (System.currentTimeMillis() - root["started"]!!.toLong().coerceAtLeast(1)), "calls" to Quota.taskCalls(id))
        Perf.log("task", "goal", "", System.currentTimeMillis() - root["started"]!!.toLong(), Tasks.children(id).size, 0, status == Tasks.DONE, if (status == Tasks.FAILED) text else "")
        Quota.forgetTask(id)
        val shortGoal = Axiom.clip(root["goal"]!!, 60)
        val msg = if (status == Tasks.DONE) text else "Task ${id.takeLast(5)} did not finish: $text"
        ChatBus.publish("jarvis", msg)
        val p = Prefs(app())
        if (p.telegramToken.isNotEmpty() && p.telegramChatId.isNotEmpty()) {
            Engine.scope.launch(Dispatchers.IO) {
                Telegram.sendToOwner(p, (if (status == Tasks.DONE) "Done: " else "Failed: ") + shortGoal + "\n\n" + Axiom.clip(msg, 3500))
                if (status == Tasks.DONE) for (f in Tasks.files(Tasks.get(id)!!).take(4)) { val file = java.io.File(f); if (file.exists() && file.length() < 49_000_000L) Telegram.sendFileToOwner(p, file, file.name) }
            }
        }
        try { Memory.get(app()).addChat("jarvis", msg) } catch (e: Exception) { /* ignore */ }
        Notify.post(if (status == Tasks.DONE) "done" else "failed", "task:$id", if (status == Tasks.DONE) "AXIOM finished" else "AXIOM could not finish", shortGoal + ": " + Axiom.clip(text, 120))
    }
}
