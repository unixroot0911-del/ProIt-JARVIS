package com.jarvis.assistant

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Finds free, legal downloads on the web (author sites, mod hosts, GitHub, Drive links posted by the author) and downloads
 * them to the phone's Downloads folder. The brain searches; Jarvis then checks every link itself, follows download pages
 * to the real file, and hands the file to Android's download manager (visible progress, survives closing Jarvis).
 */
object Downloader {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private const val EXT = "zip|rar|7z|scs|apk|pdf|exe|msi|mp3|mp4|mkv|iso|tar|gz|zst|epub|docx|xlsx|pptx|jar|obb|apks|xapk"
    private val fileExt = Regex("""\.($EXT)(\?|$)""", RegexOption.IGNORE_CASE)

    private class Probe(val url: String, val direct: Boolean, val name: String, val size: Long, val html: String?)

    private fun normalize(u: String): String {
        Regex("""drive\.google\.com/file/d/([^/?&]+)""").find(u)?.let {
            return "https://drive.usercontent.google.com/download?id=${it.groupValues[1]}&export=download&confirm=t"
        }
        Regex("""drive\.google\.com/(?:open|uc)\?(?:[^ ]*&)?id=([^&]+)""").find(u)?.let {
            return "https://drive.usercontent.google.com/download?id=${it.groupValues[1]}&export=download&confirm=t"
        }
        return u
    }

    private fun fileName(finalUrl: String, disp: String): String {
        Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE).find(disp)?.let {
            return try { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (e: Exception) { it.groupValues[1].trim() }
        }
        val last = finalUrl.substringBefore('?').substringAfterLast('/')
        return last.ifBlank { "download.bin" }
    }

    private fun probe(url: String): Probe? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = true
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", UA)
                if (c.responseCode !in 200..299) return null
                val type = (c.contentType ?: "").lowercase()
                val disp = c.getHeaderField("Content-Disposition") ?: ""
                val finalUrl = c.url.toString()
                val html = type.startsWith("text/html") || type.contains("xhtml")
                val direct = disp.contains("attachment", true) || (!html && !type.startsWith("text/") && type.isNotEmpty())
                val name = fileName(finalUrl, disp).replace(Regex("""[\\/:*?"<>|]"""), "_")
                if (direct) return Probe(finalUrl, true, name, c.contentLengthLong, null)
                if (!html) return null
                val sb = StringBuilder()
                c.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (sb.length < 800_000) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                }
                Probe(finalUrl, false, name, -1, sb.toString())
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Download links on a page, best match for [what] first. */
    private fun links(base: String, html: String, what: String): List<String> {
        val words = what.lowercase().split(Regex("""[^a-z0-9؀-ۿ.]+""")).filter { it.length >= 3 }
        val out = ArrayList<Pair<Int, String>>()
        for (m in Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE).findAll(html)) {
            val raw = m.groupValues[1].replace("&amp;", "&")
            val abs = try { URL(URL(base), raw).toString() } catch (e: Exception) { continue }
            val low = abs.lowercase()
            val file = fileExt.containsMatchIn(abs)
            val drive = low.contains("drive.google.com/file/d/")
            val named = low.contains("download") && words.any { low.contains(it) }
            if (!file && !drive && !named) continue
            out.add(((if (file) 2 else 0) + (if (drive) 1 else 0) + words.count { low.contains(it) }) to abs)
        }
        return out.sortedByDescending { it.first }.map { it.second }.distinct().take(5)
    }

    private fun enqueue(ctx: Context, p: Probe): String {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(p.url))
            .setTitle(p.name)
            .setDescription("Downloaded by Jarvis")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", UA)
        if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, p.name)
        else req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, p.name)
        dm.enqueue(req)
        val size = if (p.size > 0) " (${p.size / 1_048_576} MB)" else ""
        return "Downloading ${p.name}$size to your Downloads folder. Progress and the finished file show in your notifications."
    }

    /** [arg] = "what to download|optional direct link". */
    suspend fun fetch(ctx: Context, brain: Brain, arg: String, onProgress: (String) -> Unit): String {
        val p = arg.split("|", limit = 2)
        val what = p[0].trim()
        val given = p.getOrElse(1) { "" }.trim().takeIf { it.startsWith("http") }
        val start: List<String> = if (given != null) listOf(given) else {
            onProgress("Searching the web for \"$what\"...")
            brain.findLinks(what)
        }
        if (start.isEmpty()) return "I searched but found no download source for \"$what\". Give me a more exact name and version, or paste a link."

        return withContext(Dispatchers.IO) {
            var firstPage: String? = null
            for (u in start.take(6)) {
                val pr = probe(normalize(u)) ?: continue
                if (pr.direct) return@withContext enqueue(ctx, pr)
                if (firstPage == null) firstPage = pr.url
                onProgress("Checking ${pr.url.substringAfter("://").substringBefore('/')}...")
                for (l in links(pr.url, pr.html ?: "", what).take(4)) {
                    val pr2 = probe(normalize(l)) ?: continue
                    if (pr2.direct) return@withContext enqueue(ctx, pr2)
                }
            }
            val page = firstPage ?: start.first()
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { /* no browser */ }
            "I found the source but it hides the file link behind a click (or a captcha, or a login). I opened it for you: $page"
        }
    }
}
