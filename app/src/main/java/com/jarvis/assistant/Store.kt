package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * AXIOM's operating database: tasks, projects, the audit trail, performance records, the semantic knowledge base,
 * temporary cache, procedures, templates, workflows, page watches and user-made tools. All on the phone.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "axiom.db", null, 1) {

    companion object {
        @Volatile private var inst: Store? = null
        fun get(): Store = inst ?: synchronized(this) { inst ?: Store(Axiom.ctx).also { inst = it } }
        fun close() { synchronized(this) { inst?.close(); inst = null } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tasks (
            id TEXT PRIMARY KEY, parent TEXT NOT NULL DEFAULT '', project TEXT NOT NULL DEFAULT 'general', goal TEXT NOT NULL DEFAULT '',
            descr TEXT NOT NULL DEFAULT '', priority INTEGER NOT NULL DEFAULT 3, status TEXT NOT NULL DEFAULT 'new',
            inputs TEXT NOT NULL DEFAULT '{}', expected TEXT NOT NULL DEFAULT '', deps TEXT NOT NULL DEFAULT '', tool TEXT NOT NULL DEFAULT '',
            role TEXT NOT NULL DEFAULT '', success TEXT NOT NULL DEFAULT '', on_fail TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0,
            max_attempts INTEGER NOT NULL DEFAULT 3, last_try INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0,
            fail_reason TEXT NOT NULL DEFAULT '', verify TEXT NOT NULL DEFAULT '', result TEXT NOT NULL DEFAULT '', files TEXT NOT NULL DEFAULT '',
            tainted INTEGER NOT NULL DEFAULT 0, direct INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '',
            created INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0, started INTEGER NOT NULL DEFAULT 0, finished INTEGER NOT NULL DEFAULT 0,
            calls INTEGER NOT NULL DEFAULT 0, ms INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX tasks_status ON tasks(status)")
        db.execSQL("CREATE INDEX tasks_parent ON tasks(parent)")
        db.execSQL("""CREATE TABLE projects (
            name TEXT PRIMARY KEY, goal TEXT NOT NULL DEFAULT '', descr TEXT NOT NULL DEFAULT '', tools TEXT NOT NULL DEFAULT '',
            files TEXT NOT NULL DEFAULT '', decisions TEXT NOT NULL DEFAULT '', problems TEXT NOT NULL DEFAULT '', next_step TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'active', progress INTEGER NOT NULL DEFAULT 0, risks TEXT NOT NULL DEFAULT '', resources TEXT NOT NULL DEFAULT '',
            budget TEXT NOT NULL DEFAULT '', requirements TEXT NOT NULL DEFAULT '', due INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, actor TEXT, tool TEXT, args TEXT, outcome TEXT,
            approved TEXT, source TEXT, task TEXT)""")
        db.execSQL("CREATE INDEX audit_ts ON audit(ts)")
        db.execSQL("""CREATE TABLE perf (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, name TEXT, model TEXT, ms INTEGER,
            attempts INTEGER, tokens INTEGER, ok INTEGER, rating INTEGER, err TEXT)""")
        db.execSQL("CREATE INDEX perf_name ON perf(kind, name)")
        db.execSQL("CREATE TABLE cache (k TEXT PRIMARY KEY, v TEXT, expires INTEGER)")
        db.execSQL("""CREATE TABLE procedures (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, kind TEXT, tool TEXT, sig TEXT, ok INTEGER,
            reason TEXT, fix TEXT)""")
        db.execSQL("""CREATE TABLE kb_docs (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT, project TEXT, title TEXT, kind TEXT,
            hash TEXT, added INTEGER, trust TEXT)""")
        db.execSQL("CREATE INDEX kb_docs_source ON kb_docs(source)")
        db.execSQL("CREATE TABLE kb_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, doc INTEGER, idx INTEGER, page INTEGER, text TEXT, vec BLOB, ts INTEGER)")
        db.execSQL("CREATE INDEX kb_chunks_doc ON kb_chunks(doc)")
        db.execSQL("CREATE TABLE templates (name TEXT PRIMARY KEY, kind TEXT, body TEXT, version INTEGER, ts INTEGER)")
        db.execSQL("CREATE TABLE template_versions (name TEXT, version INTEGER, body TEXT, ts INTEGER, PRIMARY KEY (name, version))")
        db.execSQL("""CREATE TABLE workflows (id TEXT PRIMARY KEY, name TEXT, spec TEXT, trig TEXT, enabled INTEGER, approved INTEGER,
            last_run INTEGER, runs INTEGER, created INTEGER)""")
        db.execSQL("""CREATE TABLE watches (id TEXT PRIMARY KEY, url TEXT, what TEXT, every_min INTEGER, last_hash TEXT, last_text TEXT,
            last_check INTEGER, rule TEXT, enabled INTEGER, project TEXT, created INTEGER, changes INTEGER)""")
        db.execSQL("""CREATE TABLE user_tools (name TEXT PRIMARY KEY, descr TEXT, code TEXT, version INTEGER, enabled INTEGER, tests TEXT,
            approved INTEGER, created INTEGER, uses INTEGER)""")
        db.execSQL("CREATE TABLE user_tool_versions (name TEXT, version INTEGER, code TEXT, ts INTEGER, PRIMARY KEY (name, version))")
        db.execSQL("CREATE TABLE services (name TEXT PRIMARY KEY, body TEXT, verified INTEGER)")
        db.execSQL("CREATE TABLE usage (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, provider TEXT, model TEXT, status INTEGER, tin INTEGER, tout INTEGER)")
        db.execSQL("CREATE INDEX usage_ts ON usage(ts)")
        db.execSQL("CREATE TABLE social (id INTEGER PRIMARY KEY AUTOINCREMENT, project TEXT, platform TEXT, body TEXT, due INTEGER, status TEXT)")
        db.execSQL("CREATE TABLE learning (id INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT, q TEXT, a TEXT, box INTEGER, due INTEGER, seen INTEGER)")
        db.execSQL("CREATE TABLE changelog (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, path TEXT, action TEXT, detail TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* version 1 so far */ }

    // ---- small helpers: every row comes back as strings ("" for null) ----

    fun query(sql: String, vararg args: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        readableDatabase.rawQuery(sql, args as Array<String>).use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val m = HashMap<String, String>(names.size)
                for (i in names.indices) {
                    m[names[i]] = if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) "" else (c.getString(i) ?: "")
                }
                out.add(m)
            }
        }
        return out
    }

    fun one(sql: String, vararg args: String): Map<String, String>? = query(sql, *args).firstOrNull()

    fun scalar(sql: String, vararg args: String): Long =
        readableDatabase.rawQuery(sql, args as Array<String>).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun exec(sql: String, vararg args: Any?) { writableDatabase.execSQL(sql, args as Array<Any?>) }

    fun insert(table: String, cv: ContentValues): Long = writableDatabase.insert(table, null, cv)

    fun replace(table: String, cv: ContentValues) { writableDatabase.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE) }

    fun update(table: String, cv: ContentValues, where: String, vararg args: String): Int = writableDatabase.update(table, cv, where, args as Array<String>)

    fun delete(table: String, where: String, vararg args: String): Int = writableDatabase.delete(table, where, args as Array<String>)

    fun integrityOk(): Boolean = try {
        readableDatabase.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
    } catch (e: Exception) { false }
}

/** The trail of everything important AXIOM did, so a mistake can be understood afterwards. */
object Audit {
    fun log(actor: String, tool: String, args: String, outcome: String, approved: String, source: String = "", task: String = "") {
        if (Config.get("log_level") == "errors" && !outcome.startsWith("FAIL") && !outcome.startsWith("DECLINED")) return
        try {
            val cv = ContentValues().apply {
                put("ts", System.currentTimeMillis()); put("actor", actor); put("tool", tool)
                put("args", Axiom.clip(Secrets.redact(args), 500)); put("outcome", Axiom.clip(Secrets.redact(outcome), 500))
                put("approved", approved); put("source", source); put("task", task)
            }
            Store.get().insert("audit", cv)
        } catch (e: Exception) { /* the audit must never break the work */ }
    }

    fun recent(n: Int = 40): List<Map<String, String>> = Store.get().query("SELECT * FROM audit ORDER BY id DESC LIMIT ?", n.toString())

    fun format(n: Int = 25): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        val rows = recent(n)
        if (rows.isEmpty()) return "The audit trail is empty."
        return rows.joinToString("\n") { r ->
            fmt.format(java.util.Date(r["ts"]!!.toLong())) + "  " + r["tool"] + "  [" + r["approved"] + "]  " + r["outcome"]
        }
    }
}

/** Measured results: how long things take, how often they fail, how the user rated them. Real numbers, never guesses. */
object Perf {
    fun log(kind: String, name: String, model: String, ms: Long, attempts: Int, tokens: Int, ok: Boolean, err: String = "") {
        try {
            val cv = ContentValues().apply {
                put("ts", System.currentTimeMillis()); put("kind", kind); put("name", name); put("model", model); put("ms", ms)
                put("attempts", attempts); put("tokens", tokens); put("ok", if (ok) 1 else 0); put("rating", 0); put("err", Axiom.clip(Secrets.redact(err), 300))
            }
            Store.get().insert("perf", cv)
        } catch (e: Exception) { /* ignore */ }
    }

    fun rate(taskName: String, rating: Int) {
        try { Store.get().exec("UPDATE perf SET rating = ? WHERE id = (SELECT MAX(id) FROM perf WHERE kind = 'task')", rating) } catch (e: Exception) { /* ignore */ }
    }

    fun summary(kind: String, limit: Int = 12): List<Map<String, String>> =
        Store.get().query(
            "SELECT name, COUNT(*) AS n, SUM(ok) AS good, CAST(AVG(ms) AS INTEGER) AS avg_ms, CAST(AVG(attempts) AS REAL) AS avg_try, SUM(tokens) AS tokens " +
                "FROM perf WHERE kind = ? GROUP BY name ORDER BY n DESC LIMIT ?", kind, limit.toString()
        )
}

/** Short-lived memory: results that can be reused for a while instead of being asked for again. */
object Cache {
    fun get(k: String): String? {
        val r = Store.get().one("SELECT v, expires FROM cache WHERE k = ?", k) ?: return null
        if ((r["expires"] ?: "0").toLong() < System.currentTimeMillis()) { Store.get().delete("cache", "k = ?", k); return null }
        return r["v"]
    }

    fun put(k: String, v: String, ttlMs: Long) {
        val cv = ContentValues().apply { put("k", k); put("v", v); put("expires", System.currentTimeMillis() + ttlMs) }
        Store.get().replace("cache", cv)
    }

    fun purge() { Store.get().delete("cache", "expires < ?", System.currentTimeMillis().toString()) }

    fun clear() { Store.get().delete("cache", "1 = 1") }
}

/** Remembers which actions worked, which failed and why, so the same mistake is not repeated. */
object Procedures {
    fun note(kind: String, tool: String, sig: String, ok: Boolean, reason: String, fix: String = "") {
        try {
            val cv = ContentValues().apply {
                put("ts", System.currentTimeMillis()); put("kind", kind); put("tool", tool); put("sig", Axiom.clip(Secrets.redact(sig), 200))
                put("ok", if (ok) 1 else 0); put("reason", Axiom.clip(Secrets.redact(reason), 300)); put("fix", Axiom.clip(fix, 300))
            }
            Store.get().insert("procedures", cv)
        } catch (e: Exception) { /* ignore */ }
    }

    /** Lessons worth showing the planner: recent failures of the tools it is about to use. */
    fun lessons(tools: Collection<String>, n: Int = 6): String {
        if (tools.isEmpty()) return ""
        val marks = tools.joinToString(",") { "?" }
        val rows = Store.get().query(
            "SELECT tool, reason, fix FROM procedures WHERE ok = 0 AND tool IN ($marks) ORDER BY id DESC LIMIT $n", *tools.toTypedArray()
        )
        return rows.joinToString("\n") { "- " + it["tool"] + " failed before: " + it["reason"] + (if (it["fix"]!!.isNotEmpty()) " (worked: " + it["fix"] + ")" else "") }
    }
}
