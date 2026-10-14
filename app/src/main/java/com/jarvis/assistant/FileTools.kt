package com.jarvis.assistant

import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Files and folders: read, write, find, move, de-duplicate, back up. Writing stays inside the AXIOM workspace. */
object FileTools {

    private fun sha1(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        FileInputStream(f).use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val textExt = setOf("txt", "md", "json", "csv", "tsv", "xml", "html", "htm", "log", "kt", "java", "js", "ts", "py", "css", "yml", "yaml", "ini", "cfg", "sql", "sh", "properties", "gradle", "toml", "svg")

    fun isText(f: File) = f.extension.lowercase() in textExt || f.extension.isEmpty()

    private fun listing(dir: File, depth: Int, max: Int, sb: StringBuilder, indent: String, count: IntArray) {
        val kids = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
        for (k in kids) {
            if (count[0]++ >= max) { sb.append(indent).append("...\n"); return }
            sb.append(indent).append(if (k.isDirectory) k.name + "/" else k.name + "  (" + Workspace.human(k.length()) + ")").append('\n')
            if (k.isDirectory && depth > 1) listing(k, depth - 1, max, sb, "$indent  ", count)
        }
    }

    fun register() {
        Tools.register(Tool("file_read", "Read a file (text, code, csv, json, PDF, Word, Excel, image text). Returns its text.",
            listOf(A("path", "file path, relative to the project folder or absolute"), A("max_chars", "how much to return, default 20000", false, "int"), A("page", "page or sheet number for PDF/Excel", false, "int")),
            Risk.READ, group = "files",
            run = { c, a ->
                val f = Workspace.readable(a.getString("path"), c.project) ?: return@Tool fail("I may not read that location: ${a.getString("path")}")
                if (!f.exists() || !f.isFile) return@Tool fail("There is no file at ${a.getString("path")}.")
                val max = a.optInt("max_chars", 20000).coerceIn(500, 120000)
                val text = Docs.readText(c, f, max, a.optInt("page", 0))
                val w = Shield.wrap(f.name, text, max)
                ok(w.text, untrusted = true, data = JSONObject().put("flagged", w.flagged).put("path", f.absolutePath))
            }))

        Tools.register(Tool("file_write", "Create or change a text file in the project folder. mode = write (default) or append.",
            listOf(A("path", "relative path inside the project folder"), A("content", "the full text"), A("mode", "write or append", false)),
            Risk.LOCAL, group = "files",
            check = { c, a, _ ->
                val f = Workspace.inside(c.project, a.getString("path"))
                if (f == null || !f.exists()) "the file does not exist after writing"
                else if (f.length() == 0L && a.getString("content").isNotEmpty()) "the file is empty after writing" else null
            },
            run = { c, a ->
                val f = Workspace.inside(c.project, a.getString("path")) ?: return@Tool fail("Files can only be written inside the AXIOM folder (${Workspace.root().path}).")
                f.parentFile?.mkdirs()
                val existed = f.exists()
                if (existed && a.optString("mode", "write") != "append") {                      // keep the previous version
                    try { f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true) } catch (e: Exception) { /* backup is best effort */ }
                }
                if (a.optString("mode", "write") == "append") f.appendText(a.getString("content")) else f.writeText(a.getString("content"))
                Workspace.note(f.path, if (existed) "changed" else "created", a.getString("content").take(80))
                ok("Saved ${f.name} (${Workspace.human(f.length())}) in ${f.parent}.", listOf(f.absolutePath))
            }))

        Tools.register(Tool("file_list", "List the files and folders in a directory (default: the project folder).",
            listOf(A("path", "directory", false), A("depth", "how deep, default 1", false, "int")), Risk.READ, group = "files",
            run = { c, a ->
                val p = a.optString("path", "")
                val d = if (p.isEmpty()) Workspace.project(c.project) else (Workspace.readable(p, c.project) ?: return@Tool fail("I may not look there."))
                if (!d.isDirectory) return@Tool fail("${d.path} is not a folder.")
                val sb = StringBuilder(d.path).append('\n')
                listing(d, a.optInt("depth", 1).coerceIn(1, 4), 150, sb, "  ", intArrayOf(0))
                ok(sb.toString().trim())
            }))

        Tools.register(Tool("file_search", "Find files by name or by words inside them (text files).",
            listOf(A("query", "name part or words"), A("path", "where to look, default the project folder", false), A("inside", "true to search inside files", false, "bool")),
            Risk.READ, group = "files",
            run = { c, a ->
                val p = a.optString("path", "")
                val d = if (p.isEmpty()) Workspace.project(c.project) else (Workspace.readable(p, c.project) ?: return@Tool fail("I may not look there."))
                val q = a.getString("query").lowercase()
                val inside = a.optBoolean("inside", false)
                val hits = ArrayList<String>()
                d.walkTopDown().maxDepth(8).filter { it.isFile }.forEach { f ->
                    if (hits.size >= 40) return@forEach
                    if (f.name.lowercase().contains(q)) hits.add(f.path)
                    else if (inside && isText(f) && f.length() < 2_000_000 && f.readText().lowercase().contains(q)) hits.add(f.path + "  (inside)")
                }
                if (hits.isEmpty()) ok("Nothing found for \"$q\".") else ok(hits.joinToString("\n"))
            }))

        Tools.register(Tool("file_move", "Move or rename a file inside the project folders.",
            listOf(A("from", "current path"), A("to", "new path")), Risk.LOCAL, group = "files",
            check = { c, a, _ -> if (Workspace.inside(c.project, a.getString("to"))?.exists() == true) null else "the target does not exist after the move" },
            run = { c, a ->
                val f = Workspace.inside(c.project, a.getString("from")) ?: return@Tool fail("Source is outside the AXIOM folder.")
                val t = Workspace.inside(c.project, a.getString("to")) ?: return@Tool fail("Target is outside the AXIOM folder.")
                if (!f.exists()) return@Tool fail("Nothing at ${a.getString("from")}.")
                if (t.exists()) return@Tool fail("${a.getString("to")} already exists. Choose another name.")
                t.parentFile?.mkdirs()
                if (!f.renameTo(t)) { f.copyRecursively(t); f.deleteRecursively() }
                Workspace.note(t.path, "moved", f.path)
                ok("Moved to ${t.path}.", listOf(t.absolutePath))
            }))

        Tools.register(Tool("file_copy", "Copy a file into the project folders.",
            listOf(A("from", "source (any readable path)"), A("to", "target path inside the project folder")), Risk.LOCAL, group = "files",
            run = { c, a ->
                val f = Workspace.readable(a.getString("from"), c.project) ?: return@Tool fail("I may not read that source.")
                val t = Workspace.inside(c.project, a.getString("to")) ?: return@Tool fail("Target is outside the AXIOM folder.")
                if (!f.exists()) return@Tool fail("Nothing at ${a.getString("from")}.")
                t.parentFile?.mkdirs()
                f.copyRecursively(t, overwrite = false)
                Workspace.note(t.path, "copied", f.path)
                ok("Copied to ${t.path}.", listOf(t.absolutePath))
            }))

        Tools.register(Tool("file_dedupe", "Find duplicate files (same content) in a folder. Only reports; deleting needs file_delete.",
            listOf(A("path", "folder to scan", false)), Risk.READ, group = "files",
            run = { c, a ->
                val p = a.optString("path", "")
                val d = if (p.isEmpty()) Workspace.project(c.project) else (Workspace.readable(p, c.project) ?: return@Tool fail("I may not look there."))
                val bySize = d.walkTopDown().maxDepth(8).filter { it.isFile && it.length() > 0 }.groupBy { it.length() }.filter { it.value.size > 1 }
                val groups = ArrayList<List<File>>()
                for ((_, fs) in bySize) fs.groupBy { sha1(it) }.values.filter { it.size > 1 }.forEach { groups.add(it) }
                if (groups.isEmpty()) ok("No duplicates found.")
                else ok(groups.take(30).joinToString("\n\n") { g -> "Same content (" + Workspace.human(g[0].length()) + "):\n" + g.joinToString("\n") { "  " + it.path } })
            }))

        Tools.register(Tool("file_backup", "Zip a folder or file into the backups folder and check that the zip opens.",
            listOf(A("path", "what to back up, default the whole project folder", false)), Risk.LOCAL, group = "files",
            run = { c, a ->
                val p = a.optString("path", "")
                val src = if (p.isEmpty()) Workspace.project(c.project) else (Workspace.readable(p, c.project) ?: return@Tool fail("I may not read that."))
                val out = File(Workspace.root(), "backups").apply { mkdirs() }.let { File(it, Workspace.slug(src.name) + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".zip") }
                var n = 0
                ZipOutputStream(out.outputStream().buffered()).use { z ->
                    val base = if (src.isDirectory) src else src.parentFile!!
                    src.walkTopDown().filter { it.isFile && !it.path.startsWith(File(Workspace.root(), "backups").path) }.forEach { f ->
                        z.putNextEntry(ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry(); n++
                    }
                }
                var back = 0
                ZipInputStream(out.inputStream().buffered()).use { z -> while (z.nextEntry != null) back++ }
                if (back != n) return@Tool fail("The backup was written but is damaged ($back of $n files readable).", retriable = true)
                Workspace.note(out.path, "backup", "$n files")
                ok("Backed up $n files to ${out.path} (${Workspace.human(out.length())}), checked.", listOf(out.absolutePath))
            }))

        Tools.register(Tool("file_delete", "Delete a file or folder from the project folders. Always asks the user; it goes to the trash folder first.",
            listOf(A("path", "what to delete")), Risk.DANGEROUS, group = "files",
            run = { c, a ->
                val f = Workspace.inside(c.project, a.getString("path")) ?: return@Tool fail("Only things inside the AXIOM folder can be deleted this way.")
                if (!f.exists()) return@Tool fail("Nothing at ${a.getString("path")}.")
                val trash = File(Workspace.root(), ".trash").apply { mkdirs() }
                val t = File(trash, System.currentTimeMillis().toString() + "_" + f.name)
                if (!f.renameTo(t)) { f.copyRecursively(t); f.deleteRecursively() }
                Workspace.note(f.path, "deleted", "moved to trash")
                ok("Deleted ${f.name} (kept in the trash folder in case you change your mind).")
            }))

        Tools.register(Tool("file_open", "Open a file on the phone with the app that handles it.",
            listOf(A("path", "file to open")), Risk.LOCAL, group = "files",
            run = { c, a ->
                val f = Workspace.readable(a.getString("path"), c.project) ?: return@Tool fail("I may not open that.")
                if (!f.exists()) return@Tool fail("Nothing at ${a.getString("path")}.")
                val uri = FileProvider.getUriForFile(c.app, c.app.packageName + ".files", f)
                c.app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, Workspace.mime(f)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                ok("Opened ${f.name}.")
            }))

        Tools.register(Tool("send_file", "Send a file to the user's Telegram chat so they have it on any device.",
            listOf(A("path", "file to send"), A("caption", "short caption", false)), Risk.EXTERNAL, group = "files",
            run = { c, a ->
                val f = Workspace.readable(a.getString("path"), c.project) ?: return@Tool fail("I may not send that.")
                if (!f.exists()) return@Tool fail("Nothing at ${a.getString("path")}.")
                if (f.length() > 49_000_000L) return@Tool fail("Telegram accepts files up to 50 MB; this one is ${Workspace.human(f.length())}.")
                if (c.prefs.telegramToken.isEmpty() || c.prefs.telegramChatId.isEmpty()) return@Tool fail("Telegram is not paired, so I cannot send files. The file is saved at ${f.path}.")
                if (!Telegram.sendFileToOwner(c.prefs, f, a.optString("caption", f.name))) return@Tool fail("Telegram did not accept the file.", retriable = true)
                ok("Sent ${f.name} to your Telegram chat.")
            }))
    }
}
