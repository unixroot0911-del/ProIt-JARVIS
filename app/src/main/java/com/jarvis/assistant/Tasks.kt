package com.jarvis.assistant

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject

/** The task table: every goal and every step, with its real state. The dashboard reads this, never the model's guesses. */
object Tasks {
    const val NEW = "new"; const val PLANNING = "planning"; const val READY = "ready"; const val RUNNING = "running"
    const val AWAITING = "awaiting"; const val PAUSED = "paused"; const val FAILED = "failed"; const val DONE = "done"; const val CANCELLED = "cancelled"

    fun terminal(s: String) = s == DONE || s == FAILED || s == CANCELLED

    fun label(s: String) = when (s) {
        NEW -> "new"; PLANNING -> "planning"; READY -> "ready"; RUNNING -> "running"; AWAITING -> "waiting for you"
        PAUSED -> "paused"; FAILED -> "failed"; DONE -> "done"; CANCELLED -> "cancelled"; else -> s
    }

    fun create(
        goal: String, project: String, parent: String = "", descr: String = "", tool: String = "", inputs: JSONObject = JSONObject(),
        deps: List<String> = emptyList(), role: String = "", priority: Int = 3, expected: String = "", success: String = "",
        onFail: String = "replan", maxAttempts: Int = Config.int("max_attempts"), direct: Boolean = false, source: String = "",
        status: String = READY, tainted: Boolean = false, id: String = Axiom.newId("t"), due: Long = 0L
    ): String {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", id); put("parent", parent); put("project", project); put("goal", Axiom.clip(goal, 2000)); put("descr", Axiom.clip(descr, 2000))
            put("priority", priority.coerceIn(1, 5)); put("status", status); put("inputs", inputs.toString()); put("expected", Axiom.clip(expected, 500))
            put("deps", deps.joinToString(",")); put("tool", tool); put("role", role); put("success", Axiom.clip(success, 500)); put("on_fail", onFail)
            put("max_attempts", maxAttempts); put("tainted", if (tainted) 1 else 0); put("direct", if (direct) 1 else 0); put("source", source)
            put("created", now); put("updated", now); put("due", due)
        }
        Store.get().insert("tasks", cv)
        return id
    }

    /** Accepts a full id or just its last characters, as shown in lists. */
    fun resolve(idOrTail: String): String? {
        val q = idOrTail.trim().removePrefix("#")
        if (q.length < 3) return null
        get(q)?.let { return q }
        return Store.get().query("SELECT id FROM tasks WHERE id LIKE ? ORDER BY updated DESC LIMIT 2", "%$q").let { if (it.size == 1 || (it.isNotEmpty() && it[0]["id"]!!.endsWith(q))) it[0]["id"] else null }
    }

    fun get(id: String): Map<String, String>? = Store.get().one("SELECT * FROM tasks WHERE id = ?", id)

    fun set(id: String, vararg pairs: Pair<String, Any?>) {
        val cv = ContentValues()
        for ((k, v) in pairs) when (v) {
            null -> cv.putNull(k); is Int -> cv.put(k, v); is Long -> cv.put(k, v); is Boolean -> cv.put(k, if (v) 1 else 0); else -> cv.put(k, v.toString())
        }
        cv.put("updated", System.currentTimeMillis())
        Store.get().update("tasks", cv, "id = ?", id)
    }

    fun children(parent: String): List<Map<String, String>> = Store.get().query("SELECT * FROM tasks WHERE parent = ? ORDER BY created, rowid", parent)

    fun roots(vararg statuses: String): List<Map<String, String>> {
        val marks = statuses.joinToString(",") { "?" }
        return Store.get().query("SELECT * FROM tasks WHERE parent = '' AND status IN ($marks) ORDER BY created DESC", *statuses)
    }

    fun recentRoots(n: Int = 30): List<Map<String, String>> = Store.get().query("SELECT * FROM tasks WHERE parent = '' ORDER BY updated DESC LIMIT ?", n.toString())

    fun backlog(project: String): List<Map<String, String>> =
        Store.get().query("SELECT * FROM tasks WHERE parent = '' AND project = ? AND status IN ('new','ready','paused') ORDER BY priority, created", project)

    fun files(m: Map<String, String>): List<String> = try {
        val a = JSONArray(m["files"].orEmpty().ifEmpty { "[]" }); (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun args(m: Map<String, String>): JSONObject = try { JSONObject(m["inputs"].orEmpty().ifEmpty { "{}" }) } catch (e: Exception) { JSONObject() }

    fun line(m: Map<String, String>): String {
        val kids = children(m["id"]!!)
        val prog = if (kids.isEmpty()) "" else " (" + kids.count { it["status"] == DONE } + "/" + kids.size + " steps)"
        return "#" + m["id"] + " [" + label(m["status"]!!) + "]" + prog + " " + Axiom.clip(m["goal"]!!.replace("\n", " "), 80)
    }

    fun detail(id: String): String {
        val m = get(id) ?: return "There is no task $id."
        val sb = StringBuilder(line(m)).append('\n')
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        if (m["started"]!!.toLong() > 0) sb.append("Started ").append(fmt.format(java.util.Date(m["started"]!!.toLong())))
        if (m["finished"]!!.toLong() > 0) sb.append(", took ").append((m["finished"]!!.toLong() - m["started"]!!.toLong()) / 1000).append(" s")
        sb.append(", model calls: ").append(Quota.taskCalls(id)).append('\n')
        if (m["fail_reason"]!!.isNotEmpty()) sb.append("Reason: ").append(m["fail_reason"]).append('\n')
        for (k in children(id)) {
            sb.append(" - ").append(k["id"]!!.takeLast(5)).append(" [").append(label(k["status"]!!)).append("] ")
                .append(k["tool"]!!.ifEmpty { k["role"]!! }).append(": ").append(Axiom.clip(k["descr"]!!.replace("\n", " "), 70))
            if (k["attempts"]!!.toInt() > 1) sb.append("  (try ").append(k["attempts"]).append(")")
            if (k["status"] == FAILED && k["fail_reason"]!!.isNotEmpty()) sb.append("  ← ").append(Axiom.clip(k["fail_reason"]!!, 80))
            if (k["verify"]!!.isNotEmpty()) sb.append("  [check: ").append(Axiom.clip(k["verify"]!!, 40)).append("]")
            sb.append('\n')
        }
        val f = files(m)
        if (f.isNotEmpty()) sb.append("Files:\n").append(f.joinToString("\n") { "  $it" }).append('\n')
        if (m["result"]!!.isNotEmpty() && terminal(m["status"]!!)) sb.append("\n").append(Axiom.clip(m["result"]!!, 1500))
        return sb.toString().trim()
    }
}
