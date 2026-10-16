package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * The independent researcher: plan the questions, search, read the pages, pull claims WITH verbatim quotes, throw away every claim
 * whose quote is not really on the page, cross-check between independent sites, and write a report where each statement carries its sources.
 * Market mode adds competitors, prices and demand and keeps "found" and "estimated" apart.
 */
object Research {
    class Src(val id: Int, val url: String, val title: String, val date: String, val text: String, val host: String)
    class Claim(val src: Int, val claim: String, val quote: String)

    private fun host(u: String) = try { URL(u).host.removePrefix("www.") } catch (e: Exception) { u }

    suspend fun run(c: ToolCtx, question: String, depth: String, mode: String, format: String, remember: Boolean): ToolResult {
        val nq = when (depth) { "quick" -> 3; "deep" -> 6; else -> 4 }
        val npages = when (depth) { "quick" -> 5; "deep" -> 12; else -> 8 }
        val market = mode == "market" || mode == "compare"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // 1. plan
        c.progress("Planning the research")
        val plan = Router.runJson(c.brain, Router.T.PLAN, Router.Opts(
            "You plan web research. Today is $today. Return ONE JSON object: {\"queries\":[up to $nq short web search queries that together cover the question; at least one in English; add the current year when freshness matters], \"must_verify\":[the 2-4 facts the answer depends on most]}." +
                (if (market) " This is a market study: cover market size and growth, main competitors and their prices/offers, customer demand and reviews, trends, regulation, and risks." else ""),
            question, json = true, maxOut = 1200, task = c.task))
        val queries = (0 until (plan.optJSONArray("queries")?.length() ?: 0)).map { plan.getJSONArray("queries").getString(it) }.filter { it.isNotBlank() }.take(nq).ifEmpty { listOf(question) }

        // 2. search
        c.progress("Searching (${queries.size} queries)")
        val seen = LinkedHashMap<String, Int>(); val titles = HashMap<String, String>()
        for (q in queries) {
            if (c.cancelled()) return fail("Cancelled.")
            for (r in try { Web.results(c, q, 6) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }) { seen[r.url] = (seen[r.url] ?: 0) + 1; titles[r.url] = r.title }
        }
        if (seen.isEmpty()) return fail("The search returned nothing. Either there is no connection or the search services are limiting me right now.", retriable = true)
        val perHost = HashMap<String, Int>()
        val chosen = seen.entries.sortedByDescending { it.value }.map { it.key }.filter { u -> val h = host(u); val n = (perHost[h] ?: 0); if (n >= 2) false else { perHost[h] = n + 1; true } }.take(npages + 4)

        // 3. read
        c.progress("Reading ${chosen.size} pages")
        val gate = Semaphore(3)
        val blocked = ArrayList<String>()
        val fetched = coroutineScope {
            chosen.map { u -> async { gate.withPermit { u to try { Web.fetch(u) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null to (e.message ?: "failed") } } } }.awaitAll()
        }
        val sources = ArrayList<Src>()
        for ((u, r) in fetched) {
            val f = r.first
            if (f == null) { if ((r.second ?: "").contains("block") || (r.second ?: "").contains("robots")) blocked.add(host(u)); continue }
            if (f.text.length < 400) continue
            sources.add(Src(sources.size + 1, f.finalUrl, f.title.ifBlank { titles[u] ?: host(u) }, f.date, f.text, host(f.finalUrl)))
            if (sources.size >= npages) break
        }
        if (sources.isEmpty()) return fail("No page could be read" + (if (blocked.isNotEmpty()) " (blocked or disallowed: ${blocked.distinct().joinToString(", ")})" else "") + ". Try different wording or give me addresses.", retriable = true)

        // 4. claims with quotes, kept only when the quote is on the page
        val claims = ArrayList<Claim>(); var dropped = 0
        val gate2 = Semaphore(2)
        c.progress("Extracting facts from ${sources.size} sources")
        val perSrc = coroutineScope {
            sources.map { s -> async { gate2.withPermit {
                try {
                    val j = Router.runJson(c.brain, Router.T.EXTRACT, Router.Opts(
                        "You read one web page for a research question. Return ONE JSON object {\"claims\":[{\"claim\":\"one self-contained sentence with the fact, figures and unit\",\"quote\":\"text copied VERBATIM from the page that supports it, at most 220 characters\"}]} with at most 7 claims that help answer the question. Only what the page states; no opinions of yours. If the page has nothing relevant return {\"claims\":[]}.\n" + Shield.RULES,
                        "QUESTION: $question\n" + (if (s.date.isNotEmpty()) "PAGE DATE: ${s.date}\n" else "") + Shield.wrap(s.url, s.text, 24000).text, json = true, maxOut = 2500, task = c.task))
                    s to (j.optJSONArray("claims") ?: JSONArray())
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { s to JSONArray() }
            } } }.awaitAll()
        }
        for ((s, arr) in perSrc) {
            val pn = Web.normText(s.text); val pd = Web.digits(s.text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cl = o.optString("claim", "").trim(); val q = o.optString("quote", "").trim()
                if (cl.isEmpty() || q.length < 12) { dropped++; continue }
                if (Web.appears(q, pn, pd)) claims.add(Claim(s.id, cl, q)) else dropped++
            }
        }
        if (claims.isEmpty()) return fail("I read ${sources.size} pages but none contained a checkable statement about this. I will not guess. Rephrase or give me a source.")

        // 5. cross-check
        c.progress("Cross-checking ${claims.size} claims")
        val list = claims.mapIndexed { i, cl -> "C${i + 1} [S${cl.src}] ${cl.claim}" }.joinToString("\n")
        val cross = try {
            Router.runJson(c.brain, Router.T.REASON, Router.Opts(
                "You cross-check claims taken from different web pages. Group claims that say the same thing. Return ONE JSON object {\"findings\":[{\"statement\":\"merged statement, keeping exact figures\",\"claims\":[claim numbers],\"status\":\"confirmed\" or \"single\"}],\"conflicts\":[{\"topic\":\"what differs\",\"versions\":[{\"says\":\"...\",\"claims\":[claim numbers]}]}]}. 'confirmed' only when at least two claims from DIFFERENT sources agree. Never merge claims that differ in figures; report those as conflicts. Use only the claims given.",
                "QUESTION: $question\nTODAY: $today\n$list", json = true, maxOut = 5000, task = c.task))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val findings = ArrayList<String>()
        val findingsData = JSONArray()
        if (cross != null) {
            val fa = cross.optJSONArray("findings") ?: JSONArray()
            for (i in 0 until fa.length()) {
                val f = fa.optJSONObject(i) ?: continue
                val ids = (0 until (f.optJSONArray("claims")?.length() ?: 0)).mapNotNull { f.getJSONArray("claims").optInt(it, -1).takeIf { n -> n in 1..claims.size } }
                if (ids.isEmpty()) continue
                val srcIds = ids.map { claims[it - 1].src }.distinct().sorted()
                val hosts = srcIds.map { id -> sources.first { it.id == id }.host }.distinct()
                val status = if (hosts.size >= 2) "confirmed by ${hosts.size} independent sites" else "single source"
                findings.add("- ${f.optString("statement")} [${srcIds.joinToString(", ") { "S$it" }}] ($status)")
                findingsData.put(JSONObject().put("statement", f.optString("statement")).put("sources", JSONArray(srcIds)).put("status", status))
            }
            val ca = cross.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until ca.length()) {
                val o = ca.optJSONObject(i) ?: continue
                val vs = o.optJSONArray("versions") ?: continue
                val txt = (0 until vs.length()).mapNotNull { vs.optJSONObject(it) }.joinToString(" / ") { v -> "\"" + v.optString("says") + "\" [" + (0 until (v.optJSONArray("claims")?.length() ?: 0)).mapNotNull { k -> v.getJSONArray("claims").optInt(k, -1).takeIf { n -> n in 1..claims.size }?.let { n -> "S" + claims[n - 1].src } }.distinct().joinToString(", ") + "]" }
                findings.add("- CONFLICT on ${o.optString("topic")}: $txt")
            }
        }
        if (findings.isEmpty()) claims.groupBy { it.src }.forEach { (s, l) -> l.forEach { findings.add("- ${it.claim} [S$s] (single source)") } }

        // 6. report
        c.progress("Writing the report")
        val structure = if (market)
            "Sections: ## Summary (5 lines, the answer first); ## Market overview; ## Size and growth; ## Competitors (a table: Name | Offer | Price | Strengths | Weaknesses | Source); ## Customers and demand; ## Trends; ## Risks; ## Opportunities; ## Recommendation; ## What I could not find. Every number is either FOUND (with its [S#]) or an ESTIMATE written as 'Estimate:' with the reasoning and the facts it rests on; an estimate is never given a source."
        else "Sections: ## Answer (the direct answer first, 3-6 lines); ## Findings (grouped, each statement with its [S#] and whether it was confirmed by independent sites or comes from a single source); ## Disagreements and uncertainty; ## What I could not find; ## Next steps (only if useful)."
        val body = Router.run(c.brain, Router.T.WRITE, Router.Opts(
            "You write research reports from VERIFIED FINDINGS only. Language: the language of the question. Today is $today. $structure Rules: use no fact that is not in the findings; keep every [S#] marker next to the statement it supports; say plainly where sources disagree or are a single site; never present an estimate as a sourced fact; do not write a sources list (it is added automatically). Markdown.",
            "QUESTION: $question\n\nFINDINGS:\n" + findings.joinToString("\n") + (if (blocked.isNotEmpty()) "\n\nNOTE: these sites could not be read (blocked or disallowed): ${blocked.distinct().joinToString(", ")}" else ""), maxOut = 7000, task = c.task))
        val usedIds = Regex("S(\\d+)").findAll(body).map { it.groupValues[1].toInt() }.toSet().ifEmpty { sources.map { it.id }.toSet() }
        val srcList = sources.filter { it.id in usedIds }.joinToString("\n") { "- [S${it.id}] ${it.title.take(100)} — ${it.url}" + (if (it.date.isNotEmpty()) " (page date ${it.date})" else " (no date on page)") }
        val unknown = Regex("S(\\d+)").findAll(body).map { it.groupValues[1].toInt() }.filter { n -> sources.none { it.id == n } }.toSet()
        val report = body.trim() + "\n\n## Sources\n" + srcList + "\n\nResearch date: $today. ${sources.size} pages read, ${claims.size} statements verified against the page text" + (if (dropped > 0) ", $dropped dropped because their quote was not on the page" else "") + "." +
            (if (unknown.isNotEmpty()) "\n(Warning: the text mentions sources $unknown that do not exist; ignore those markers.)" else "")

        // 7. save
        val slug = Workspace.slug(question).take(40)
        val dir = Workspace.inside(c.project, "research") ?: return ok(report, untrusted = true)
        val fmt = format.lowercase().ifBlank { "md" }
        val made = withContext(Dispatchers.IO) {
            val md = DocMaker.make(dir, "$slug-$today", "md", "", report)
            if (fmt != "md" && fmt in listOf("pdf", "docx", "html")) DocMaker.make(dir, "$slug-$today", fmt, question.take(80), report) else md
        }
        if (remember) withContext(Dispatchers.IO) { sources.forEach { try { Knowledge.add(c.brain, it.url, c.project, it.title, "web", it.text, "external") } catch (e: Exception) { /* optional */ } } }
        return ok(Axiom.clip(report, 9000) + (if (made.problem == null) "\n\nSaved: ${made.file.path}" else ""), if (made.problem == null) listOf(made.file.absolutePath) else emptyList(), untrusted = true, data = JSONObject().put("findings", findingsData).put("sources", sources.size))
    }

    fun register() {
        Tools.register(Tool("research", "Independent researcher: plans searches, reads many pages, extracts facts with verbatim quotes that are checked against the page, cross-checks sites, and writes a sourced report file. mode = research, market (market study with competitors, prices, demand; found vs estimated kept apart) or compare.",
            listOf(A("question", "the research question or topic"), A("depth", "quick, normal or deep", false), A("mode", "research, market or compare", false), A("format", "md, pdf or docx for the saved report", false), A("remember", "true to also learn the pages into the knowledge base", false, "bool")),
            Risk.READ, 900_000L, group = "research",
            run = { c, a -> run(c, a.getString("question"), a.optString("depth", "normal"), a.optString("mode", "research"), a.optString("format", "md"), a.optBoolean("remember", false)) }))
    }
}
