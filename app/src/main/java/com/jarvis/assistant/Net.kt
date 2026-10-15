package com.jarvis.assistant

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * All outside reading goes through here: only http(s), never the phone's own network, redirects checked hop by hop,
 * robots.txt respected for automated reading, and "I am blocked" reported honestly instead of being worked around.
 */
object Net {
    class Page(val url: String, val finalUrl: String, val status: Int, val contentType: String, val body: String, val blocked: Boolean, val bytes: Int)

    private const val UA = "Mozilla/5.0 (Linux; Android 14; AXIOM) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /** Null when the address is fine to visit, otherwise why not. */
    fun unsafe(u: String): String? {
        val url = try { URL(u) } catch (e: Exception) { return "that is not a valid address" }
        if (url.protocol != "http" && url.protocol != "https") return "only http and https addresses are allowed"
        val host = url.host.lowercase()
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return "that is a local address"
        return try {
            val addrs = InetAddress.getAllByName(host)
            if (addrs.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) "that address points inside a private network" else null
        } catch (e: Exception) { null }   // unresolvable: the request itself will fail with a clear error
    }

    private val blockMarks = listOf("verify you are human", "are you a robot", "captcha", "cf-chl", "just a moment...", "attention required", "access denied", "enable javascript and cookies")

    fun get(url: String, timeoutMs: Int = 20000, maxBytes: Int = 2_000_000, headers: Map<String, String> = emptyMap(), method: String = "GET", body: ByteArray? = null): Page {
        var cur = url
        for (hop in 0..5) {
            unsafe(cur)?.let { throw java.io.IOException("Not fetched: $it ($cur)") }
            val c = URL(cur).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = method
                c.connectTimeout = 10000
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*;q=0.8")
                c.setRequestProperty("Accept-Language", "en,ar;q=0.8,fr;q=0.6")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: break
                    cur = URL(URL(cur), loc).toString()
                    continue
                }
                val ct = c.contentType ?: ""
                val stream = (if (code in 200..299) c.inputStream else c.errorStream)
                val bytes = java.io.ByteArrayOutputStream()
                var total = 0
                stream?.use { s -> val buf = ByteArray(16384); while (total < maxBytes) { val n = s.read(buf); if (n <= 0) break; bytes.write(buf, 0, n); total += n } }
                val cs = Regex("charset=([\\w\\-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)
                val text = try { String(bytes.toByteArray(), if (cs != null) charset(cs) else Charsets.UTF_8) } catch (e: Exception) { String(bytes.toByteArray(), Charsets.UTF_8) }
                val low = text.take(3000).lowercase()
                val blocked = code == 401 || code == 403 || code == 429 || (text.length < 6000 && blockMarks.any { low.contains(it) })
                return Page(url, cur, code, ct, text, blocked, total)
            } finally {
                c.disconnect()
            }
        }
        throw java.io.IOException("Too many redirects from $url")
    }

    /** Status code of a quick HEAD request (falling back to a ranged GET), or -1 when unreachable. */
    fun head(url: String): Int = try {
        val p = get(url, 10000, 2000, method = "HEAD")
        if (p.status == 405 || p.status == 403 || p.status == 501) get(url, 10000, 4000, mapOf("Range" to "bytes=0-2000")).status else p.status
    } catch (e: Exception) { -1 }

    private val strip = listOf(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), Regex("(?is)<!--.*?-->"))

    fun title(html: String): String = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { android.text.Html.fromHtml(it.trim(), 0).toString().trim() } ?: ""

    fun htmlToText(html: String): String {
        var t = html
        for (r in strip) t = r.replace(t, " ")
        t = Regex("(?i)</t[dh]>").replace(t, " | ")
        t = Regex("(?i)<br\\s*/?>|</(p|div|li|tr|h[1-6]|section|article|header|footer|table|ul|ol|blockquote)>").replace(t, "\n")
        t = Regex("(?s)<[^>]+>").replace(t, " ")
        t = try { android.text.Html.fromHtml(t, 0).toString() } catch (e: Exception) { t }
        return t.replace('\u00a0', ' ').lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** (text, absolute link) pairs found in a page. */
    fun links(base: String, html: String, max: Int = 80): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (m in Regex("(?is)<a\\s[^>]*?href\\s*=\\s*[\"']([^\"'#][^\"']*)[\"'][^>]*>(.*?)</a>").findAll(html)) {
            val abs = try { URL(URL(base), m.groupValues[1]).toString() } catch (e: Exception) { continue }
            if (!abs.startsWith("http") || !seen.add(abs)) continue
            out.add(Regex("(?s)<[^>]+>").replace(m.groupValues[2], " ").replace(Regex("\\s+"), " ").trim().take(90) to abs)
            if (out.size >= max) break
        }
        return out
    }

    /** Respects robots.txt for automated reading. Unknown or unreadable robots.txt means allowed. */
    fun robotsAllows(url: String): Boolean {
        return try {
            val u = URL(url)
            val key = "robots:" + u.host
            val txt = Cache.get(key) ?: try {
                val p = get(u.protocol + "://" + u.host + "/robots.txt", 8000, 60000)
                (if (p.status in 200..299) p.body else "").also { Cache.put(key, it, 24 * 3600_000L) }
            } catch (e: Exception) { "" }
            var applies = false
            val disallow = ArrayList<String>(); val allow = ArrayList<String>()
            for (raw in txt.lines()) {
                val line = raw.substringBefore('#').trim()
                val k = line.substringBefore(':').trim().lowercase(); val v = line.substringAfter(':', "").trim()
                if (k == "user-agent") applies = v == "*"
                else if (applies && k == "disallow" && v.isNotEmpty()) disallow.add(v)
                else if (applies && k == "allow" && v.isNotEmpty()) allow.add(v)
            }
            val path = u.path.ifEmpty { "/" } + (if (u.query != null) "?" + u.query else "")
            val d = disallow.filter { path.startsWith(it) }.maxOfOrNull { it.length } ?: -1
            val a = allow.filter { path.startsWith(it) }.maxOfOrNull { it.length } ?: -1
            !(d >= 0 && d > a)
        } catch (e: Exception) { true }
    }
}
