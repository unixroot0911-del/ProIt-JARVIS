package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Turn(val role: String, val text: String)
data class Notif(val app: String, val pkg: String, val title: String, val text: String, val ts: Long)

/** Long-term memory, all on-device: facts, conversation, notifications, money, habits, study notes. */
class Memory private constructor(context: Context) : SQLiteOpenHelper(context, "jarvis_memory.db", null, 5) {

    private val app: Context = context.applicationContext

    companion object {
        @Volatile private var inst: Memory? = null
        fun get(context: Context): Memory =
            inst ?: synchronized(this) { inst ?: Memory(context.applicationContext).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT UNIQUE, ts INTEGER)")
        db.execSQL("CREATE TABLE turns (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
        createV2(db)
        addMode(db)
        createDossier(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) createV2(db)
        if (oldVersion < 4) addMode(db)
        if (oldVersion < 5) createDossier(db)
    }

    private fun createDossier(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS dossier (kind TEXT NOT NULL, name TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, ts INTEGER, PRIMARY KEY (name, k))")
    }

    fun dossierPut(kind: String, name: String, k: String, v: String) {
        val cv = ContentValues().apply { put("kind", kind); put("name", name); put("k", k); put("v", v); put("ts", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("dossier", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Rows of one dossier as [kind, key, value]. */
    fun dossierRows(name: String): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        readableDatabase.rawQuery("SELECT kind, k, v FROM dossier WHERE name = ? ORDER BY ts DESC", arrayOf(name)).use {
            while (it.moveToNext()) out.add(arrayOf(it.getString(0), it.getString(1), it.getString(2)))
        }
        return out
    }

    fun dossierAllRows(): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        readableDatabase.rawQuery("SELECT kind, name, k, v FROM dossier WHERE k != 'plays' AND k != 'minutes' ORDER BY kind, name, ts DESC LIMIT 400", null).use {
            while (it.moveToNext()) out.add(arrayOf(it.getString(0), it.getString(1), it.getString(3)))
        }
        return out
    }

    fun dossierNames(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT DISTINCT name FROM dossier", null).use { while (it.moveToNext()) out.add(it.getString(0)) }
        return out
    }

    fun dossierForget(name: String, k: String): Int {
        val db = writableDatabase
        return if (k.isBlank()) db.delete("dossier", "name = ?", arrayOf(name)) else db.delete("dossier", "name = ? AND k = ?", arrayOf(name, k))
    }

    fun topPlayed(n: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT name FROM dossier WHERE k = 'plays' AND kind = 'game' ORDER BY CAST(v AS INTEGER) DESC LIMIT ?", arrayOf(n.toString())).use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }


    private fun addMode(db: SQLiteDatabase) {
        for (t in listOf("chat", "turns")) {
            try { db.execSQL("ALTER TABLE " + t + " ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal'") } catch (e: Exception) { /* already there */ }
        }
    }

    private fun mode(): String = "normal"

    private fun createV2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS notifs (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, app TEXT, title TEXT, text TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL, category TEXT, note TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS habits (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT, text TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chat (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    }

    // ---- facts and conversation ----

    fun addFact(fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        val cv = ContentValues().apply { put("fact", clean); put("ts", System.currentTimeMillis()) }
        writableDatabase.insertWithOnConflict("facts", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun facts(limit: Int = 60): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT fact FROM facts ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) out.add(it.getString(0))
        }
        return out
    }

    fun addTurn(role: String, text: String) {
        val cv = ContentValues().apply { put("role", role); put("text", text); put("ts", System.currentTimeMillis()); put("mode", mode()) }
        writableDatabase.insert("turns", null, cv)
    }

    fun recentTurns(limit: Int = 12): List<Turn> {
        val out = ArrayList<Turn>()
        readableDatabase.rawQuery("SELECT role, text FROM turns WHERE mode = ? ORDER BY id DESC LIMIT ?", arrayOf(mode(), limit.toString())).use {
            while (it.moveToNext()) out.add(Turn(it.getString(0), it.getString(1)))
        }
        return out.reversed()
    }

    // ---- visible chat history ----

    fun addChat(role: String, text: String) {
        val cv = ContentValues().apply { put("role", role); put("text", text); put("ts", System.currentTimeMillis()); put("mode", mode()) }
        val db = writableDatabase
        db.insert("chat", null, cv)
        db.execSQL("DELETE FROM chat WHERE id < (SELECT MAX(id) FROM chat) - 1500")
    }

    fun recentChat(limit: Int): List<Turn> {
        val out = ArrayList<Turn>()
        readableDatabase.rawQuery("SELECT role, text FROM chat WHERE mode = ? ORDER BY id DESC LIMIT ?", arrayOf(mode(), limit.toString())).use {
            while (it.moveToNext()) out.add(Turn(it.getString(0), it.getString(1)))
        }
        return out.reversed()
    }

    // ---- notifications ----

    fun addNotif(pkg: String, app: String, title: String, text: String) {
        readableDatabase.rawQuery("SELECT pkg, title, text FROM notifs ORDER BY id DESC LIMIT 1", null).use {
            if (it.moveToFirst() && it.getString(0) == pkg && it.getString(1) == title && it.getString(2) == text) return
        }
        val cv = ContentValues().apply {
            put("pkg", pkg); put("app", app); put("title", title); put("text", text)
            put("ts", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.insert("notifs", null, cv)
        db.execSQL("DELETE FROM notifs WHERE id < (SELECT MAX(id) FROM notifs) - 1500")
    }

    fun recentNotifs(sinceMs: Long, limit: Int): List<Notif> {
        val out = ArrayList<Notif>()
        readableDatabase.rawQuery(
            "SELECT app, pkg, title, text, ts FROM notifs WHERE ts >= ? ORDER BY id DESC LIMIT ?",
            arrayOf(sinceMs.toString(), limit.toString())
        ).use {
            while (it.moveToNext()) out.add(Notif(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4)))
        }
        return out
    }

    // ---- money, habits, notes ----

    fun addExpense(amount: Double, category: String, note: String) {
        val cv = ContentValues().apply {
            put("amount", amount); put("category", category.ifBlank { "other" }); put("note", note)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("expenses", null, cv)
    }

    fun expenseSummary(sinceMs: Long): String {
        var total = 0.0
        val parts = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT category, SUM(amount) FROM expenses WHERE ts >= ? GROUP BY category ORDER BY 2 DESC",
            arrayOf(sinceMs.toString())
        ).use {
            while (it.moveToNext()) {
                val v = it.getDouble(1)
                total += v
                parts.add("${it.getString(0)} ${"%.2f".format(v)}")
            }
        }
        return if (parts.isEmpty()) "none" else "total ${"%.2f".format(total)} (" + parts.joinToString(", ") + ")"
    }

    fun logHabit(name: String) {
        val cv = ContentValues().apply { put("name", name.trim().lowercase()); put("ts", System.currentTimeMillis()) }
        writableDatabase.insert("habits", null, cv)
    }

    fun habitSummary(sinceMs: Long): String {
        val parts = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT name, COUNT(*) FROM habits WHERE ts >= ? GROUP BY name ORDER BY 2 DESC",
            arrayOf(sinceMs.toString())
        ).use { while (it.moveToNext()) parts.add("${it.getString(0)} x${it.getInt(1)}") }
        return if (parts.isEmpty()) "none" else parts.joinToString(", ")
    }

    fun addNote(topic: String, text: String) {
        val cv = ContentValues().apply {
            put("topic", topic.trim()); put("text", text.trim()); put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("notes", null, cv)
    }

    fun notes(limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT topic, text FROM notes ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToNext()) out.add("${it.getString(0)}: ${it.getString(1)}")
        }
        return out
    }

    fun forgetAll() {
        val db = writableDatabase
        for (t in listOf("facts", "turns", "notifs", "expenses", "habits", "notes", "chat")) db.execSQL("DELETE FROM $t")
    }
}
