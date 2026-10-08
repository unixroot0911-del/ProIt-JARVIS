package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Turn(val role: String, val text: String)

/** Long-term memory: durable facts about the user plus recent conversation history. All on-device. */
class Memory(context: Context) : SQLiteOpenHelper(context, "jarvis_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT UNIQUE, ts INTEGER)")
        db.execSQL("CREATE TABLE turns (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun addFact(fact: String) {
        val clean = fact.trim()
        if (clean.isEmpty()) return
        val cv = ContentValues().apply {
            put("fact", clean)
            put("ts", System.currentTimeMillis())
        }
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
        val cv = ContentValues().apply {
            put("role", role)
            put("text", text)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("turns", null, cv)
    }

    fun recentTurns(limit: Int = 12): List<Turn> {
        val out = ArrayList<Turn>()
        readableDatabase.rawQuery(
            "SELECT role, text FROM turns ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
        ).use {
            while (it.moveToNext()) out.add(Turn(it.getString(0), it.getString(1)))
        }
        return out.reversed()
    }

    fun forgetAll() {
        writableDatabase.execSQL("DELETE FROM facts")
        writableDatabase.execSQL("DELETE FROM turns")
    }
}
