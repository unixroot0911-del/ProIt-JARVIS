package com.jarvis.assistant

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where AXIOM keeps what it makes. A folder per project, on shared storage when the phone allows it (so files can be opened
 * from any app), otherwise private. Writing is only ever allowed inside this root; reading may also look at the user's own folders.
 */
object Workspace {
    private var cached: File? = null

    fun root(ctx: Context = Axiom.ctx): File {
        cached?.let { if (it.exists() && it.canWrite()) return it }
        val shared = File(Environment.getExternalStorageDirectory(), "Documents/AXIOM")
        val f = try {
            if ((shared.exists() || shared.mkdirs()) && File(shared, ".w").let { it.writeText("1"); it.delete() }) shared
            else File(ctx.filesDir, "workspace").apply { mkdirs() }
        } catch (e: Exception) { File(ctx.filesDir, "workspace").apply { mkdirs() } }
        cached = f
        return f
    }

    fun slug(name: String): String = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40).ifBlank { "general" }

    fun project(name: String): File = File(root(), slug(name)).apply { mkdirs() }

    /** A path inside the workspace (relative to the project folder), or null when it tries to escape. */
    fun inside(project: String, rel: String): File? {
        val base = project(project).canonicalFile
        val f = if (rel.startsWith("/")) File(rel) else File(base, rel)
        val c = try { f.canonicalFile } catch (e: Exception) { return null }
        return if (c.path.startsWith(root().canonicalPath)) c else null
    }

    /** Where reading is allowed: the workspace and the usual user folders. */
    fun readable(path: String, project: String): File? {
        inside(project, path)?.let { return it }
        val f = try { File(path).canonicalFile } catch (e: Exception) { return null }
        val ext = Environment.getExternalStorageDirectory().canonicalPath
        return if (path.startsWith("/") && f.path.startsWith(ext) && !f.path.contains("/Android/data") && !f.path.contains("/Android/obb")) f else null
    }

    fun mime(f: File): String = when (f.extension.lowercase()) {
        "pdf" -> "application/pdf"; "txt", "md", "log" -> "text/plain"; "csv" -> "text/csv"; "json" -> "application/json"
        "html", "htm" -> "text/html"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "zip" -> "application/zip"; "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    fun human(n: Long): String = when {
        n < 1024 -> "$n B"; n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0); else -> "%.1f MB".format(n / 1048576.0)
    }

    fun note(path: String, action: String, detail: String = "") {
        try {
            val cv = android.content.ContentValues().apply { put("ts", System.currentTimeMillis()); put("path", path); put("action", action); put("detail", Axiom.clip(detail, 200)) }
            Store.get().insert("changelog", cv)
        } catch (e: Exception) { /* ignore */ }
    }
}
