package com.jarvis.assistant

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sectioned memory. Every game, app, person, topic and preference has its own dossier (shared by all modes):
 * how often it is used, the user's standing orders for it ("always skip cutscenes"), notes, lessons.
 */
object Dossier {

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    /** The one name a game or app is filed under: its label on this phone, in lower case. */
    fun canon(ctx: Context, name: String): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        val pkg = try { Actions(ctx).findApp(n) } catch (e: Exception) { null }
        return (if (pkg != null) label(ctx, pkg) else n).lowercase()
    }

    fun isGame(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0 || (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME)
    } catch (e: Exception) { false }

    fun put(ctx: Context, kind: String, name: String, key: String, value: String) {
        val k = kind.trim().lowercase().ifBlank { "topic" }
        val n = if (k == "game" || k == "app") canon(ctx, name) else name.trim().lowercase()
        if (n.isEmpty() || value.isBlank()) return
        Memory.get(ctx).dossierPut(k, n, key.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifBlank { "note" }.take(40), value.trim().take(400))
    }

    fun forget(ctx: Context, name: String, key: String): String {
        val n = canon(ctx, name).ifBlank { name.trim().lowercase() }
        val c = Memory.get(ctx).dossierForget(n, key.trim().lowercase())
        return if (c > 0) "Forgot $c item(s) about $n." else "I had nothing filed about $n."
    }

    /** What is filed about these names, grouped by section. */
    fun text(ctx: Context, names: Collection<String>): String {
        val mem = Memory.get(ctx)
        val sb = StringBuilder()
        for (n in names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()) {
            val rows = mem.dossierRows(n)
            if (rows.isEmpty()) continue
            sb.append('[').append(rows[0][0].uppercase()).append("] ").append(n).append('\n')
            for (r in rows.take(14)) sb.append("  - ").append(r[1]).append(": ").append(r[2]).append('\n')
        }
        return sb.toString().trim()
    }

    fun overview(ctx: Context, arg: String): String {
        val a = arg.trim()
        if (a.isNotEmpty()) {
            val t = text(ctx, listOf(canon(ctx, a), a))
            return t.ifBlank { "Nothing is filed about \"$a\" yet." }
        }
        val rows = Memory.get(ctx).dossierAllRows()
        if (rows.isEmpty()) return "My sectioned memory is still empty. It fills as we use games and apps and as you tell me your rules."
        val sb = StringBuilder()
        for (kind in listOf("game", "app", "person", "topic", "preference")) {
            val mine = rows.filter { it[0] == kind }
            if (mine.isEmpty()) continue
            sb.append(kind.uppercase()).append("S\n")
            for ((name, rs) in mine.groupBy { it[1] }) {
                sb.append("- ").append(name).append(": ").append(rs.take(4).joinToString("; ") { it[2].take(70) }).append('\n')
            }
        }
        return sb.toString().trim().take(2400)
    }

    /** Called when a play or automation run ends: how often and how long, so Jarvis knows what is played a lot. */
    fun recordUse(ctx: Context, kind: String, name: String, minutes: Long) {
        val n = canon(ctx, name)
        if (n.isEmpty()) return
        val mem = Memory.get(ctx)
        val old = mem.dossierRows(n)
        val plays = (old.firstOrNull { it[1] == "plays" }?.get(2)?.toIntOrNull() ?: 0) + 1
        val mins = (old.firstOrNull { it[1] == "minutes" }?.get(2)?.toLongOrNull() ?: 0L) + minutes
        val k = if (kind == "game") "game" else "app"
        mem.dossierPut(k, n, "plays", plays.toString())
        mem.dossierPut(k, n, "minutes", mins.toString())
        val often = if (plays >= 6) "used a lot" else if (plays >= 3) "used regularly" else "used a little"
        mem.dossierPut(k, n, "usage", "$often: $plays runs, $mins min in total, last " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
    }
}
