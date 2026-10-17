package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.asContextElement
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Several conversations at once. Each session has its own visible history and its own model context, and work started in one
 * keeps running (and reporting back to that session) while you look at another. The session of running work travels with the
 * coroutine, so memory, progress and replies always land in the right place.
 */
object Sessions {
    private val local = ThreadLocal<String>()
    class S(val id: String, var title: String)

    fun cur(): String = local.get() ?: "main"
    fun element(id: String) = local.asContextElement(id)

    private fun sp() = Axiom.ctx.getSharedPreferences("axiom_sessions", Context.MODE_PRIVATE)

    @Synchronized fun list(): List<S> {
        val out = ArrayList<S>()
        try {
            val a = JSONArray(sp().getString("list", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { out.add(S(it.getString("id"), it.getString("title"))) }
        } catch (e: Exception) { /* start over below */ }
        if (out.none { it.id == "main" }) { out.add(0, S("main", "Main")); save(out) }
        return out
    }

    private fun save(l: List<S>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title)) }
        sp().edit().putString("list", a.toString()).apply()
    }

    @Synchronized fun create(title: String = "New chat"): S {
        val l = ArrayList(list()); val s = S(Axiom.newId("s"), title); l.add(s); save(l); return s
    }

    @Synchronized fun rename(id: String, title: String) {
        val l = ArrayList(list()); l.firstOrNull { it.id == id }?.title = title.trim().take(28).ifBlank { "Chat" }; save(l)
    }

    @Synchronized fun delete(id: String) {
        if (id == "main") return
        save(list().filter { it.id != id })
        try { Memory.get(Axiom.ctx).clearSession(id) } catch (e: Exception) { Diag.record(e) }
    }

    fun last(): String = sp().getString("last", "main") ?: "main"
    fun setLast(id: String) { sp().edit().putString("last", id).apply() }
}

/** What AXIOM is doing right now, per session, so the screen can show it live without being asked. */
object Progress {
    class Job(val id: Long, val sess: String, val label: String, val start: Long, val taskId: String?, @Volatile var job: kotlinx.coroutines.Job?)

    private val seq = AtomicLong()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lines = ConcurrentHashMap<String, ArrayList<Pair<Long, String>>>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun ping() { for (l in listeners) try { l() } catch (e: Throwable) { /* a view that is gone */ } }

    fun begin(sess: String, label: String, taskId: String? = null, job: kotlinx.coroutines.Job? = null): Long {
        val id = seq.incrementAndGet()
        jobs[id] = Job(id, sess, Axiom.clip(label.replace('\n', ' '), 80), System.currentTimeMillis(), taskId, job)
        ping(); return id
    }

    fun attach(id: Long, job: kotlinx.coroutines.Job) { jobs[id]?.job = job }

    fun end(id: Long) { jobs.remove(id); ping() }

    fun note(sess: String, text: String) {
        val l = lines.getOrPut(sess) { ArrayList() }
        synchronized(l) { l.add(System.currentTimeMillis() to Axiom.clip(text.replace('\n', ' '), 140)); while (l.size > 8) l.removeAt(0) }
        ping()
    }

    fun active(sess: String): List<Job> = jobs.values.filter { it.sess == sess }.sortedBy { it.start }
    fun anyActive(sess: String) = jobs.values.any { it.sess == sess }

    fun recent(sess: String, n: Int = 5): List<String> {
        val l = lines[sess] ?: return emptyList()
        return synchronized(l) { l.takeLast(n).map { it.second } }
    }

    fun lastAgeMs(sess: String): Long {
        val l = lines[sess] ?: return Long.MAX_VALUE
        return synchronized(l) { if (l.isEmpty()) Long.MAX_VALUE else System.currentTimeMillis() - l.last().first }
    }

    /** The Stop button: cancels what is running in that session, background agents included. */
    fun stop(sess: String): Int {
        var n = 0
        for (j in active(sess)) {
            n++
            try { if (j.taskId != null) TaskEngine.stop(j.taskId) else j.job?.cancel() } catch (e: Throwable) { Diag.record(e) }
            jobs.remove(j.id)
        }
        note(sess, if (n > 0) "Stopped." else "Nothing was running."); return n
    }
}
