package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Web access for agents: search, read, links, tables, extraction that is checked against the page, forms (with approval).
 * Everything read here is untrusted data. Pages that block automated reading are reported, never bypassed.
 */
object Web {
    class Result(val title: String, val url: String, val snippet: String)

    fun normText(s: String): String = Knowledge.norm(s).replace(Regex("\\s+"), " ").trim()

    fun digits(s: String): String = s.filter { it.isDigit() }

    /** True when the value really appears in the page text (numbers are compared without separators). */
    fun appears(value: String, pageNorm: String, pageDigits: String): Boolean {
        val v = normText(value)
        if (v.isEmpty()) return false
        if (pageNorm.contains(v)) return true
        val d = digits(value)
        if (d.length >= 3 && v.count { it.isLetter() } <= 3 && pageDigits.contains(d)) return true
        val words = v.split(' ').filter { it.length > 2 }
        return words.size >= 3 && words.count { pageNorm.contains(it) } >= words.size * 0.85
    }

    private fun ddg(q: String, n: Int): List<Result> {
        val p = Net.get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), 15000, 400_000)
        if (p.status !in 200..299 || p.blocked && !p.body.contains("result__a")) return emptyList()
        val out = ArrayList<Result>()
        val ms = Regex("(?is)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(p.body).toList()
        fun clean(x: String) = Docs.xmlUnescape(Regex("(?s)<[^>]+>").replace(x, "")).trim()
        for ((i, m) in ms.withIndex()) {
            var u = Docs.xmlUnescape(m.groupValues[1])
            if (u.startsWith("//")) u = "https:$u"
            Regex("[?&]uddg=([^&]+)").find(u)?.let { u = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (!u.startsWith("http")) continue
            val block = p.body.substring(m.range.last, if (i + 1 < ms.size) ms[i + 1].range.first else minOf(p.body.length, m.range.last + 3000))
            val snip = Regex("(?is)class=\"result__snippet\"[^>]*>(.*?)</a>").find(block)?.groupValues?.get(1) ?: ""
            out.add(Result(clean(m.groupValues[2]), u, clean(snip)))
            if (out.size >= n) break
        }
        return out.distinctBy { it.url }
    }

    /** Result links for a query: the search engine page first, Gemini's grounded search as the second route. */
    suspend fun results(c: ToolCtx, q: String, n: Int = 8): List<Result> {
        val key = "ws:" + Axiom.sha1(q + n)
        Cache.get(key)?.let { s -> try { val a = JSONArray(s); return (0 until a.length()).map { val o = a.getJSONObject(it); Result(o.getString("t"), o.getString("u"), o.optString("s")) } } catch (e: Exception) { /* refetch */ } }
        var list: List<Result> = try { withContext(Dispatchers.IO) { ddg(q, n) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        if (list.isEmpty() && c.brain.hasGemini()) {
            try {
                val txt = Router.run(c.brain, Router.T.FRESH, Router.Opts("Search the web for the query and list the best pages. Output one line per page: Title | URL | one-line summary. Only real URLs from the results.", q, task = c.task))
                list = Regex("https?://[^\\s)\\]>\"'<,|]+").findAll(txt).map { it.value.trimEnd('.', ';') }.distinct().take(n).map { Result(java.net.URL(it).host, it, "") }.toList()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* nothing */ }
        }
        if (list.isNotEmpty()) Cache.put(key, JSONArray(list.map { JSONObject().put("t", it.title).put("u", it.url).put("s", it.snippet) }).toString(), 3600_000L)
        return list
    }

    class Fetched(val url: String, val finalUrl: String, val title: String, val text: String, val html: String, val date: String, val note: String?)

    /** Reads one page for an agent: robots.txt respected, blocks reported. Returns text or a reason. */
    suspend fun fetch(url: String, robots: Boolean = true, maxBytes: Int = 1_500_000): Pair<Fetched?, String?> = withContext(Dispatchers.IO) {
        if (robots && !Net.robotsAllows(url)) return@withContext null to "That site's robots.txt asks automated tools not to read this page, so I did not. You can open it yourself."
        val p = try { Net.get(url, 20000, maxBytes) } catch (e: java.io.IOException) { return@withContext null to (e.message ?: "could not reach the page") }
        if (p.blocked) return@withContext null to "The page blocks automated reading (HTTP ${p.status}, it asks for a login, a captcha or a human check). I do not bypass that. Open it yourself or give me another source."
        if (p.status !in 200..299) return@withContext null to "The page answered HTTP ${p.status}."
        val ct = p.contentType.lowercase()
        val html = p.body
        val text = if (ct.contains("json") || ct.contains("text/plain") || ct.contains("xml") && !html.contains("<html")) html else Net.htmlToText(html)
        Fetched(url, p.finalUrl, Net.title(html), text, html, pageDate(html), if (p.bytes >= maxBytes) "page was longer than it is read; only the start was used" else null) to null
    }

    private val dateMeta = listOf(
        Regex("(?i)<meta[^>]+(?:property|name)=[\"'](?:article:published_time|og:updated_time|article:modified_time|date|pubdate|publishdate|dc.date)[\"'][^>]+content=[\"']([^\"']+)"),
        Regex("(?i)<time[^>]+datetime=[\"']([^\"']+)"),
        Regex("(?i)\"datePublished\"\\s*:\\s*\"([^\"]+)")
    )

    fun pageDate(html: String): String {
        for (r in dateMeta) r.find(html.take(200_000))?.let { return it.groupValues[1].take(10) }
        return ""
    }

    fun tables(html: String): List<List<List<String>>> {
        val out = ArrayList<List<List<String>>>()
        for (t in Regex("(?is)<table\\b.*?</table>").findAll(html)) {
            val rows = ArrayList<List<String>>()
            for (r in Regex("(?is)<tr\\b.*?</tr>").findAll(t.value)) {
                val cells = Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>").findAll(r.value).map { Docs.xmlUnescape(Regex("(?s)<[^>]+>").replace(it.groupValues[1], " ")).replace(' ', ' ').replace(Regex("\\s+"), " ").trim() }.toList()
                if (cells.any { it.isNotEmpty() }) rows.add(cells)
            }
            if (rows.size >= 2) out.add(rows)
        }
        return out
    }

    fun wrapPage(f: Fetched, max: Int): String {
        val w = Shield.wrap(f.finalUrl, f.text, max)
        return "Title: ${f.title}\nURL: ${f.finalUrl}" + (if (f.date.isNotEmpty()) "\nDate on page: ${f.date}" else "") + (if (f.note != null) "\n[${f.note}]" else "") + "\n" + w.text
    }

    private fun resolveFile(c: ToolCtx, name: String): File? = Workspace.inside(c.project, name)

    fun register() {
        Tools.register(Tool("web_search", "Search the web. Returns result pages (title, address, snippet). Use web_fetch to read one.",
            listOf(A("query", "what to search"), A("count", "how many results, default 8", false, "int")), Risk.READ, 90_000L, group = "web",
            run = { c, a ->
                val l = results(c, a.getString("query"), a.optInt("count", 8).coerceIn(1, 15))
                if (l.isEmpty()) fail("The search returned nothing (no connection, or the search services are limiting me). Try again shortly or rephrase.", retriable = true)
                else ok(Shield.wrap("search results", l.mapIndexed { i, r -> "${i + 1}. ${r.title}\n   ${r.url}" + (if (r.snippet.isNotBlank()) "\n   ${r.snippet}" else "") }.joinToString("\n"), 9000).text, untrusted = true)
            }))

        Tools.register(Tool("web_answer", "Answer a question with live Google-grounded search (fresh facts: prices, news, who holds a role, current rules). Gives sources.",
            listOf(A("question", "the question")), Risk.READ, 90_000L, group = "web",
            run = { c, a ->
                val r = Router.run(c.brain, Router.T.FRESH, Router.Opts("Answer using current web results. Be precise and brief, in the user's language. Name the source site and the date for each important fact. If sources disagree, say so.", a.getString("question"), cacheTtlMs = 20 * 60_000L, task = c.task))
                ok(Shield.wrap("web answer", r, 9000).text, untrusted = true)
            }))

        Tools.register(Tool("web_fetch", "Read a web page (or a PDF/document link: it is downloaded to the project and read). Returns its text, title and date. Respects robots.txt; reports pages that block automated reading.",
            listOf(A("url", "address"), A("max_chars", "default 12000", false, "int")), Risk.READ, 120_000L, group = "web",
            run = { c, a ->
                val url = a.getString("url").trim()
                val max = a.optInt("max_chars", 12000).coerceIn(500, 60000)
                if (Regex("(?i)\\.(pdf|docx|xlsx|pptx|csv)(\\?.*)?$").containsMatchIn(url)) {
                    val name = "downloads/" + (url.substringBefore('?').substringAfterLast('/').ifBlank { "file" })
                    val dest = resolveFile(c, name) ?: return@Tool fail("Cannot save there.")
                    try { withContext(Dispatchers.IO) { Net.download(url, dest) } } catch (e: java.io.IOException) { return@Tool fail("Download failed: ${e.message}", retriable = true) }
                    val t = Docs.readText(c, dest, max, 0)
                    return@Tool ok("Saved to ${dest.path}\n" + Shield.wrap(url, t, max).text, listOf(dest.absolutePath), untrusted = true)
                }
                val (f, why) = fetch(url)
                if (f == null) fail(why ?: "could not read the page") else ok(wrapPage(f, max), untrusted = true)
            }))

        Tools.register(Tool("web_links", "List the links on a page (text and address), optionally only those containing a word.",
            listOf(A("url", "address"), A("filter", "keep links whose text or address contains this", false)), Risk.READ, 90_000L, group = "web",
            run = { _, a ->
                val (f, why) = fetch(a.getString("url"))
                if (f == null) return@Tool fail(why ?: "could not read the page")
                val flt = a.optString("filter", "").lowercase()
                val l = Net.links(f.finalUrl, f.html, 400).filter { flt.isEmpty() || it.first.lowercase().contains(flt) || it.second.lowercase().contains(flt) }.take(80)
                ok(Shield.wrap(f.finalUrl, l.joinToString("\n") { "${it.first.ifBlank { "(no text)" }} -> ${it.second}" }.ifEmpty { "No links found." }, 12000).text, untrusted = true)
            }))

        Tools.register(Tool("web_table", "Extract tables from a page. Returns table N as rows; save_as writes it to a csv/xlsx file in the project.",
            listOf(A("url", "address"), A("index", "which table, 1-based (default: list them)", false, "int"), A("save_as", "file name ending .csv or .xlsx", false)), Risk.LOCAL, 120_000L, group = "web",
            run = { c, a ->
                val (f, why) = fetch(a.getString("url"))
                if (f == null) return@Tool fail(why ?: "could not read the page")
                val ts = tables(f.html)
                if (ts.isEmpty()) return@Tool fail("There is no table on that page (it may be drawn by scripts, which I do not run).")
                val idx = a.optInt("index", 0)
                if (idx !in 1..ts.size) return@Tool ok("The page has ${ts.size} table(s):\n" + ts.mapIndexed { i, t -> "${i + 1}: ${t.size} rows, columns: ${t[0].take(6).joinToString(" | ")}" }.joinToString("\n"), untrusted = true)
                val rows = ts[idx - 1]
                val files = ArrayList<String>()
                a.optString("save_as", "").takeIf { it.isNotBlank() }?.let { n ->
                    val t = resolveFile(c, n) ?: return@Tool fail("Cannot save there.")
                    val m = withContext(Dispatchers.IO) { DocMaker.make(t.parentFile!!, t.name, if (n.lowercase().endsWith("xlsx")) "xlsx" else "csv", "", DocTools.mdTable(rows)) }
                    if (m.problem != null) return@Tool fail(m.problem); files.add(m.file.absolutePath)
                }
                ok(Shield.wrap(f.finalUrl, DocTools.mdTable(rows.take(60)) + (if (rows.size > 60) "\n[${rows.size - 60} more rows" + (if (files.isEmpty()) "; use save_as to keep all]" else " are in the file]") else ""), 14000).text + (if (files.isNotEmpty()) "\nSaved: ${files[0]}" else ""), files, untrusted = true)
            }))

        Tools.register(Tool("page_find", "Find where a word or phrase appears on a page and show the text around it.",
            listOf(A("url", "address"), A("text", "what to find")), Risk.READ, 90_000L, group = "web",
            run = { _, a ->
                val (f, why) = fetch(a.getString("url"))
                if (f == null) return@Tool fail(why ?: "could not read the page")
                val n = normText(a.getString("text"))
                val lines = f.text.lines()
                val hits = lines.indices.filter { normText(lines[it]).contains(n) }.take(8)
                if (hits.isEmpty()) ok("\"${a.getString("text")}\" does not appear on ${f.finalUrl}.")
                else ok(Shield.wrap(f.finalUrl, hits.joinToString("\n---\n") { i -> lines.subList(maxOf(0, i - 1), minOf(lines.size, i + 2)).joinToString("\n") }, 6000).text, untrusted = true)
            }))

        Tools.register(Tool("web_extract", "Pull named fields (like price, title, date, phone) out of a page as data. Every value is checked against the page text; values that do not really appear are dropped and listed as unverified.",
            listOf(A("url", "address"), A("fields", "comma separated field names"), A("what", "what the page is about, to help", false)), Risk.READ, 150_000L, group = "web",
            run = { c, a ->
                val (f, why) = fetch(a.getString("url"))
                if (f == null) return@Tool fail(why ?: "could not read the page")
                val fields = a.getString("fields").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(30)
                val j = Router.runJson(c.brain, Router.T.EXTRACT, Router.Opts(
                    "Extract the requested fields from the page text. Return ONE JSON object whose keys are exactly the field names; each value is the text copied EXACTLY as written on the page, or null when the page does not state it. Never guess, never compute, never use outside knowledge.\n" + Shield.RULES,
                    "FIELDS: ${fields.joinToString(", ")}\n" + (if (a.optString("what", "").isNotBlank()) "PAGE ABOUT: ${a.getString("what")}\n" else "") + Shield.wrap(f.finalUrl, f.text, 30000).text, json = true, maxOut = 2000, task = c.task))
                val pn = normText(f.text); val pd = digits(f.text)
                val good = JSONObject(); val bad = ArrayList<String>(); val missing = ArrayList<String>()
                for (k in fields) {
                    val v = if (j.isNull(k)) null else j.optString(k, "").takeIf { it.isNotBlank() && it != "null" }
                    if (v == null) missing.add(k) else if (appears(v, pn, pd)) good.put(k, v) else bad.add("$k=\"$v\"")
                }
                ok("Verified against the page ${f.finalUrl}:\n" + good.toString(2) + (if (missing.isNotEmpty()) "\nNot stated on the page: ${missing.joinToString(", ")}" else "") + (if (bad.isNotEmpty()) "\nDROPPED, not found in the page text: ${bad.joinToString("; ")}" else ""), untrusted = true, data = good)
            }))

        Tools.register(Tool("web_form", "Fill in and submit a simple web form (search box, contact form, sign-up with no password). Never logs in, never solves captchas, never enters payment details.",
            listOf(A("url", "page with the form"), A("fields", "JSON object name -> value", false, "json"), A("form", "which form, 1-based, default 1", false, "int")), Risk.EXTERNAL, 120_000L, group = "web",
            run = { c, a ->
                val (f, why) = fetch(a.getString("url"), robots = true)
                if (f == null) return@Tool fail(why ?: "could not read the page")
                val forms = Regex("(?is)<form\\b([^>]*)>(.*?)</form>").findAll(f.html).toList()
                if (forms.isEmpty()) return@Tool fail("There is no form on that page.")
                val fm = forms.getOrNull(a.optInt("form", 1) - 1) ?: return@Tool fail("The page has only ${forms.size} form(s).")
                val low = fm.value.lowercase()
                if (low.contains("type=\"password\"") || low.contains("type='password'")) return@Tool fail("That form asks for a password. I do not log in or enter passwords for you; do it yourself.")
                if (Regex("captcha|recaptcha|hcaptcha|turnstile").containsMatchIn(low)) return@Tool fail("That form has a captcha (a check that you are human). I do not bypass it; do it yourself.")
                if (Regex("name=\"[^\"]*(card|cvv|cvc|iban|expiry)[^\"]*\"").containsMatchIn(low)) return@Tool fail("That form takes payment details. Payments are only done by you.")
                val vals = LinkedHashMap<String, String>()
                for (m in Regex("(?is)<input\\b[^>]*>").findAll(fm.groupValues[2])) {
                    val n = Regex("name=[\"']([^\"']+)").find(m.value)?.groupValues?.get(1) ?: continue
                    val type = Regex("type=[\"']([^\"']+)").find(m.value)?.groupValues?.get(1)?.lowercase() ?: "text"
                    if (type in listOf("submit", "button", "image", "reset", "file")) continue
                    if ((type == "checkbox" || type == "radio") && !m.value.contains("checked")) continue
                    vals[n] = Docs.xmlUnescape(Regex("value=[\"']([^\"']*)").find(m.value)?.groupValues?.get(1) ?: "")
                }
                for (m in Regex("(?is)<textarea\\b[^>]*name=[\"']([^\"']+)[\"'][^>]*>(.*?)</textarea>").findAll(fm.groupValues[2])) vals[m.groupValues[1]] = Docs.xmlUnescape(m.groupValues[2])
                for (m in Regex("(?is)<select\\b[^>]*name=[\"']([^\"']+)[\"'][^>]*>(.*?)</select>").findAll(fm.groupValues[2])) vals[m.groupValues[1]] = Regex("value=[\"']([^\"']*)").find(m.groupValues[2])?.groupValues?.get(1) ?: ""
                val given = a.optJSONObject("fields") ?: JSONObject()
                val unknown = given.keys().asSequence().filter { it !in vals }.toList()
                if (unknown.isNotEmpty()) return@Tool fail("This form has no field called ${unknown.joinToString(", ")}. Its fields are: ${vals.keys.joinToString(", ")}.")
                given.keys().forEach { vals[it] = given.get(it).toString() }
                val method = Regex("method=[\"']([^\"']+)").find(fm.groupValues[1])?.groupValues?.get(1)?.uppercase() ?: "GET"
                val action = Regex("action=[\"']([^\"']*)").find(fm.groupValues[1])?.groupValues?.get(1)?.let { Docs.xmlUnescape(it) }?.ifBlank { null }?.let { java.net.URL(java.net.URL(f.finalUrl), it).toString() } ?: f.finalUrl
                val body = vals.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
                val p = withContext(Dispatchers.IO) {
                    if (method == "POST") Net.get(action, 25000, 600_000, mapOf("Content-Type" to "application/x-www-form-urlencoded"), "POST", body.toByteArray())
                    else Net.get(action + (if (action.contains('?')) "&" else "?") + body, 25000, 600_000)
                }
                if (p.blocked) fail("The site blocked the submission (HTTP ${p.status}). Nothing was sent that I can confirm.")
                else ok("Submitted to $action (HTTP ${p.status}). The site answered:\n" + Shield.wrap(p.finalUrl, Net.htmlToText(p.body), 2500).text, untrusted = true)
            }))

        Tools.register(Tool("web_download", "Download a file from a web address into the project's downloads folder (PDF, image, spreadsheet, zip...).",
            listOf(A("url", "direct address of the file"), A("name", "file name, default from the address", false)), Risk.LOCAL, 240_000L, group = "web",
            run = { c, a ->
                val n = a.optString("name", "").ifBlank { a.getString("url").substringBefore('?').substringAfterLast('/').ifBlank { "download" } }
                val dest = resolveFile(c, "downloads/$n") ?: return@Tool fail("Cannot save there.")
                val (ct, size) = try { withContext(Dispatchers.IO) { Net.download(a.getString("url"), dest) } } catch (e: java.io.IOException) { return@Tool fail("Download failed: ${e.message}", retriable = true) }
                if (size == 0L) fail("The server sent an empty file.") else ok("Saved ${dest.name} (${Workspace.human(size)}, $ct) in ${dest.parent}. It came from the web, so it is untrusted until you check it.", listOf(dest.absolutePath), untrusted = true)
            }))
    }
}
