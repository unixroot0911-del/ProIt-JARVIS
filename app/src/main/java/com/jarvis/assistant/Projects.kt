package com.jarvis.assistant

import android.content.ContentValues
import org.json.JSONArray

/** One workspace per project: goal, requirements, decisions, problems, files, tasks, progress and the next step. */
object Projects {
    private val fields = setOf("goal", "descr", "tools", "files", "decisions", "problems", "next_step", "status", "risks", "resources", "budget", "requirements")

    fun key(name: String) = Workspace.slug(name)

    fun get(name: String): Map<String, String>? = Store.get().one("SELECT * FROM projects WHERE name = ?", key(name))

    fun ensure(name: String, goal: String = "", descr: String = ""): Map<String, String> {
        get(name)?.let { return it }
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply { put("name", key(name)); put("goal", goal); put("descr", descr); put("created", now); put("updated", now) }
        Store.get().insert("projects", cv)
        Workspace.project(name)
        return get(name)!!
    }

    fun list(): List<Map<String, String>> = Store.get().query("SELECT * FROM projects ORDER BY updated DESC")

    fun update(name: String, field: String, value: String): String? {
        if (field !in fields) return "A project has these fields: " + fields.joinToString(", ")
        ensure(name)
        val cv = ContentValues().apply { put(field, value); put("updated", System.currentTimeMillis()) }
        Store.get().update("projects", cv, "name = ?", key(name))
        return null
    }

    fun log(name: String, field: String, text: String) {
        val cur = ensure(name)[field].orEmpty()
        val line = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ": " + text.trim()
        update(name, field, (cur + "\n" + line).trim().takeLast(6000))
    }

    fun switch(prefs: Prefs, name: String): String {
        ensure(name)
        prefs.activeProject = key(name)
        return "Active project: " + key(name)
    }

    fun tasksOf(name: String): List<Map<String, String>> = Store.get().query("SELECT * FROM tasks WHERE project = ? AND parent = '' ORDER BY priority, created", key(name))

    fun progress(name: String): Int {
        val t = tasksOf(name).filter { it["status"] != Tasks.CANCELLED }
        return if (t.isEmpty()) 0 else t.count { it["status"] == Tasks.DONE } * 100 / t.size
    }

    fun nextStep(name: String): String {
        val open = tasksOf(name).filter { it["status"] == Tasks.NEW || it["status"] == Tasks.READY || it["status"] == Tasks.PAUSED }
        return open.firstOrNull()?.get("goal").orEmpty()
    }

    /** Called when a goal finishes: keeps progress and next step true without the model guessing them. */
    fun afterTask(project: String, goal: String, answer: String) {
        try {
            ensure(project)
            val cv = ContentValues().apply { put("progress", progress(project)); put("next_step", nextStep(project)); put("updated", System.currentTimeMillis()) }
            Store.get().update("projects", cv, "name = ?", key(project))
        } catch (e: Exception) { /* ignore */ }
    }

    fun status(name: String): String {
        val p = get(name) ?: return "There is no project called $name."
        val tasks = tasksOf(name)
        val now = System.currentTimeMillis()
        val overdue = tasks.filter { it["due"]!!.toLong() in 1 until now && !Tasks.terminal(it["status"]!!) }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val sb = StringBuilder("PROJECT ").append(p["name"]).append("  —  ").append(progress(name)).append("% done, ").append(p["status"]).append('\n')
        if (p["goal"]!!.isNotEmpty()) sb.append("Goal: ").append(p["goal"]).append('\n')
        for ((label, f) in listOf("Requirements" to "requirements", "Risks" to "risks", "Resources" to "resources", "Budget" to "budget", "Decisions" to "decisions", "Open problems" to "problems"))
            if (p[f]!!.isNotEmpty()) sb.append(label).append(": ").append(Axiom.clip(p[f]!!.trim(), 400)).append('\n')
        sb.append("Tasks: ").append(tasks.count { it["status"] == Tasks.DONE }).append(" done, ").append(tasks.count { it["status"] == Tasks.RUNNING || it["status"] == Tasks.PLANNING }).append(" running, ")
            .append(tasks.count { it["status"] == Tasks.NEW || it["status"] == Tasks.READY }).append(" waiting, ").append(tasks.count { it["status"] == Tasks.FAILED }).append(" failed\n")
        if (overdue.isNotEmpty()) sb.append("OVERDUE: ").append(overdue.joinToString("; ") { Axiom.clip(it["goal"]!!, 40) + " (was due " + fmt.format(java.util.Date(it["due"]!!.toLong())) + ")" }).append('\n')
        val next = nextStep(name)
        if (next.isNotEmpty()) sb.append("Next step: ").append(next).append('\n')
        val files = Workspace.project(name).listFiles()?.filter { it.isFile }?.take(8)?.joinToString { it.name }.orEmpty()
        if (files.isNotEmpty()) sb.append("Files: ").append(files).append('\n')
        return sb.toString().trim()
    }

    /** Phases for a project, created as waiting tasks that can be started one by one. */
    suspend fun planPhases(brain: Brain, name: String, goal: String): String {
        val p = ensure(name, goal)
        val g = goal.ifBlank { p["goal"]!! }
        if (g.isBlank()) return "Tell me the goal of the project first."
        if (p["goal"]!!.isEmpty()) update(name, "goal", g)
        val sys = "You are the project planner of AXIOM. Break the goal into 3 to 8 phases that can each be run as one goal. Answer ONLY JSON: " +
            "{\"requirements\":\"short list\",\"risks\":\"short list\",\"phases\":[{\"title\":\"imperative goal text, complete enough to run alone\",\"priority\":1-5,\"due_days\":number,\"after\":\"title of the phase it depends on or empty\"}]}"
        val o = Router.runJson(brain, Router.T.PLAN, Router.Opts(sys, "PROJECT: ${key(name)}\nGOAL: $g", json = true))
        o.optString("requirements").takeIf { it.isNotBlank() }?.let { update(name, "requirements", it) }
        o.optString("risks").takeIf { it.isNotBlank() }?.let { update(name, "risks", it) }
        val arr = o.optJSONArray("phases") ?: JSONArray()
        val made = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val ph = arr.optJSONObject(i) ?: continue
            val title = ph.optString("title").trim()
            if (title.isEmpty()) continue
            val due = System.currentTimeMillis() + (ph.optDouble("due_days", 7.0).coerceIn(0.0, 365.0) * 86400_000L).toLong()
            Tasks.create(goal = title, project = key(name), descr = ph.optString("after"), role = "coordinator", priority = ph.optInt("priority", 3), status = Tasks.NEW, due = due)
            made.add(title)
        }
        Tasks.recentRoots(1)
        return if (made.isEmpty()) "No phases came back. Try describing the goal in more detail." else "Planned ${made.size} phases for ${key(name)}:\n" + made.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    }

    /**
     * Ranks the waiting tasks. The model only RATES each task on the criteria; the ranking itself is arithmetic done here,
     * so it can be explained, and tasks the user ordered by hand are never moved.
     */
    suspend fun prioritize(brain: Brain, name: String): String {
        val open = tasksOf(name).filter { it["status"] == Tasks.NEW || it["status"] == Tasks.READY }
        if (open.isEmpty()) return "There are no waiting tasks in ${key(name)}."
        val goal = get(name)?.get("goal").orEmpty()
        val list = open.mapIndexed { i, t -> "${i + 1}. " + t["goal"] }.joinToString("\n")
        val sys = "Rate each task from 1 (low) to 5 (high) on: importance, impact, urgency, effort (5 = takes long), cost (5 = expensive), blocked (5 = depends on other unfinished tasks), risk, fit (how close to the main goal). " +
            "Answer ONLY JSON: {\"tasks\":[{\"n\":1,\"importance\":1-5,\"impact\":1-5,\"urgency\":1-5,\"effort\":1-5,\"cost\":1-5,\"blocked\":1-5,\"risk\":1-5,\"fit\":1-5}]}"
        val o = Router.runJson(brain, Router.T.REASON, Router.Opts(sys, "MAIN GOAL: $goal\n\nTASKS:\n$list", json = true))
        val rows = o.optJSONArray("tasks") ?: return "Could not rate the tasks."
        class R(val t: Map<String, String>, val score: Double, val why: String)
        val rated = ArrayList<R>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val t = open.getOrNull(r.optInt("n", 0) - 1) ?: continue
            fun g(k: String) = r.optDouble(k, 3.0).coerceIn(1.0, 5.0)
            val score = 3 * g("importance") + 3 * g("impact") + 2.5 * g("urgency") + 2 * g("fit") - 1.5 * g("effort") - 1 * g("cost") - 2 * g("blocked") - 1 * g("risk")
            rated.add(R(t, score, "importance ${g("importance").toInt()}, impact ${g("impact").toInt()}, urgency ${g("urgency").toInt()}, effort ${g("effort").toInt()}, blocked ${g("blocked").toInt()}"))
        }
        val order = rated.sortedByDescending { it.score }
        val sb = StringBuilder("Suggested order for ${key(name)} (you can change any of them):\n")
        order.forEachIndexed { i, r ->
            val pr = (1 + i * 4 / maxOf(order.size, 1)).coerceIn(1, 5)
            if (r.t["manual"] != "1") Tasks.set(r.t["id"]!!, "priority" to pr)
            sb.append(i + 1).append(". ").append(Axiom.clip(r.t["goal"]!!, 70)).append("  —  ").append(r.why).append(if (r.t["manual"] == "1") "  (your order kept)" else "").append('\n')
        }
        return sb.toString().trim()
    }

    fun setPriority(taskId: String, p: Int): String {
        val id = Tasks.resolve(taskId) ?: return "I could not find that task."
        Tasks.set(id, "priority" to p.coerceIn(1, 5), "manual" to 1)
        return "Priority of $id set to $p. I will not move it."
    }
}
