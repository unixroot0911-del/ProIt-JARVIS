package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.io.File

/** What a deletion request resolved to, shown to the user in the confirmation BEFORE anything is touched. */
class Target(val kind: String, val label: String, val detail: String, val file: File? = null, val pkg: String? = null)

/**
 * Deletions are always confirmed, and the confirmation names the exact thing: an app or game Jarvis built, a file in
 * Downloads, Documents, Pictures, Movies, Music or DCIM, or an installed app (Android then shows its own uninstall dialog).
 */
object Deleter {

    private fun roots(ctx: Context): List<File?> = listOf(
        Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS, Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_MUSIC
    ).map { Environment.getExternalStoragePublicDirectory(it) } + ctx.getExternalFilesDir(null)

    private fun walk(dir: File?, depth: Int, out: MutableList<File>, budget: IntArray) {
        if (dir == null || !dir.isDirectory || depth > 4) return
        val list = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (f in list) {
            if (budget[0]-- <= 0) return
            if (f.isDirectory) walk(f, depth + 1, out, budget) else out.add(f)
        }
    }

    fun resolve(ctx: Context, raw: String): List<Target> {
        val q = raw.trim().trim('"', '\'').trim()
        if (q.length < 2) return emptyList()
        val out = ArrayList<Target>()
        val actions = Actions(ctx)

        // 1. apps and games Jarvis built
        Creations.find(ctx, q)?.let {
            out.add(Target("creation", "\"${it.nameWithoutExtension}\"", "An app or game that Jarvis built for you. It will be removed from MY APPS AND GAMES.", file = it))
        }

        // 2. an exact path
        if (q.startsWith("/")) {
            val f = File(q)
            if (f.isFile) out.add(Target("file", f.name, f.absolutePath, file = f))
        }

        // 3. files by name
        val files = ArrayList<File>()
        val budget = intArrayOf(6000)
        roots(ctx).forEach { walk(it, 0, files, budget) }
        val lq = q.lowercase()
        val ranked = files
            .map { f -> f to when {
                f.name.equals(q, true) || f.nameWithoutExtension.equals(q, true) -> 0
                f.name.lowercase().contains(lq) -> 1
                actions.similar(q, f.nameWithoutExtension) -> 2
                else -> 9
            } }
            .filter { it.second < 9 }
            .sortedWith(compareBy({ it.second }, { -it.first.lastModified() }))
        ranked.take(4).forEach { (f, _) ->
            out.add(Target("file", f.name, "${f.absolutePath}  (${f.length() / 1024} KB)", file = f))
        }

        // 4. installed apps
        actions.installed().firstOrNull { actions.similar(q, it.first) }?.let {
            out.add(Target("app", "the app \"${it.first}\"", "Android will show its own uninstall confirmation.", pkg = it.second))
        }
        return out
    }

    fun perform(ctx: Context, t: Target): String {
        return when (t.kind) {
            "app" -> {
                ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${t.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Opened Android's uninstall screen for ${t.label}. Confirm it there."
            }
            else -> {
                val f = t.file ?: return "Nothing to delete."
                if (!f.exists()) return "${t.label} no longer exists."
                if (f.delete()) "Deleted ${t.label}."
                else "Android blocked the deletion of ${t.label}. Give Jarvis \"All files access\" (Settings, button 6), then ask again."
            }
        }
    }
}
