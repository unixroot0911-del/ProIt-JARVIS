package com.jarvis.assistant

import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Document tools (read, create, convert, summarise, ask, compare, PDF pages) and the knowledge base tools. */
object DocTools {

    private fun resolve(c: ToolCtx, path: String): File? = Workspace.readable(path, c.project)?.takeIf { it.exists() && it.isFile }

    fun parsePages(spec: String, max: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        for (part in spec.split(",", ";", " ").map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = part.split("-", "–")
            val a = r[0].toIntOrNull() ?: continue
            val b = if (r.size > 1) r[1].toIntOrNull() ?: a else a
            for (p in minOf(a, b)..maxOf(a, b)) if (p in 1..max) out.add(p)
        }
        return out.toList()
    }

    /** Full text of a document, reading a PDF in batches. */
    suspend fun fullText(c: ToolCtx, f: File, maxChars: Int = 600_000, maxPages: Int = 400): String {
        if (f.extension.lowercase() != "pdf") return Docs.readText(c, f, maxChars, 0)
        val n = Docs.pdfPages(f).let { if (it == 0) 1 else it }
        val sb = StringBuilder()
        var p = 1
        while (p <= minOf(n, maxPages) && sb.length < maxChars) {
            val t = Docs.readPdf(c, f, p)
            sb.append(t.replace(Regex("\\n?\\[the document has \\d+ pages;[^\\]]*\\]"), "").replace(Regex("\\n?\\[large file:[^\\]]*\\]"), "")).append('\n')
            p += if (f.length() > 14_000_000L) 8 else 40
        }
        return sb.toString()
    }

    /**
     * One question about one document. A PDF that fits is sent whole to Gemini (it reads the layout itself, figures included);
     * long text is handled by reading every part for what matters and then combining.
     */
    suspend fun ask(c: ToolCtx, f: File, system: String, question: String, task: Router.T = Router.T.LONGDOC): String {
        val sys = system + "\n" + Shield.RULES
        if (f.extension.lowercase() == "pdf" && f.length() <= 14_000_000L && c.brain.hasGemini()) {
            try { return Router.run(c.brain, task, Router.Opts(sys, question, fileB64 = withContext(Dispatchers.IO) { Docs.b64(f) }, fileMime = "application/pdf", maxOut = 8000, task = c.task)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* fall through to reading the text */ }
        }
        val text = fullText(c, f)
        if (text.length <= 60_000) return Router.run(c.brain, task, Router.Opts(sys, question + "\n\n" + Shield.wrap(f.name, text, 60_000).text, maxOut = 8000, task = c.task))
        val parts = Knowledge.chunk(text, 38_000, 600)
        val notes = ArrayList<String>()
        for ((i, p) in parts.withIndex().take(14)) {
            c.progress("Reading part ${i + 1} of ${minOf(parts.size, 14)}")
            notes.add("Part ${i + 1}:\n" + Router.run(c.brain, Router.T.EXTRACT, Router.Opts(
                "$sys\nYou read one part of a long document. Write only the notes needed to answer the request below: facts, figures, names, dates, with page or section markers if visible. If nothing here matters, write 'nothing relevant'.",
                "REQUEST: $question\n\n" + Shield.wrap(f.name + " part ${i + 1}", p, 40_000).text, maxOut = 2500, task = c.task)))
        }
        return Router.run(c.brain, Router.T.WRITE, Router.Opts("$sys\nCombine the notes from all parts into one answer to the request. Keep exact figures and page markers. Do not invent anything the notes do not contain.",
            "REQUEST: $question\n\nNOTES:\n" + notes.joinToString("\n\n") + (if (parts.size > 14) "\n\n[only the first 14 of ${parts.size} parts were read]" else ""), maxOut = 8000, task = c.task))
    }

    private fun outFile(c: ToolCtx, name: String): File? = Workspace.inside(c.project, name)

    private fun rasterPdf(out: File, sources: List<Pair<File, List<Int>>>): Int {
        val doc = PdfDocument(); var n = 0
        try {
            for ((f, pages) in sources) for (p in pages) {
                val bmp = Docs.renderPage(f, p, 1240) ?: continue
                val h = (595f * bmp.height / bmp.width).toInt()
                val pg = doc.startPage(PdfDocument.PageInfo.Builder(595, h, ++n).create())
                pg.canvas.drawBitmap(bmp, null, Rect(0, 0, 595, h), null)
                doc.finishPage(pg); bmp.recycle()
            }
            if (n == 0) return 0
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally { doc.close() }
        return n
    }

    fun register() {
        Tools.register(Tool("doc_create", "Create a document file from text. format = md, txt, html, pdf, docx, xlsx, csv, json. Content is markdown-style (# headings, - lists, | tables |, **bold**); for xlsx/csv give a table (formulas start with =). The file is opened again and checked after writing.",
            listOf(A("name", "file name, relative to the project folder"), A("format", "md, txt, html, pdf, docx, xlsx, csv or json"), A("content", "the full content"), A("title", "document title", false)),
            Risk.LOCAL, 120_000L, group = "docs",
            check = { _, _, r -> if (r.files.none { File(it).exists() && File(it).length() > 0 }) "the file is missing or empty after writing" else null },
            run = { c, a ->
                val target = outFile(c, a.getString("name").let { if (it.substringAfterLast('/').contains('.')) it else it + "." + a.getString("format") }) ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                val m = withContext(Dispatchers.IO) { DocMaker.make(target.parentFile!!, target.name, a.getString("format"), a.optString("title", ""), a.getString("content")) }
                if (m.problem != null) fail(m.problem) else { Workspace.note(m.file.path, "created", a.optString("title", "")); ok("Created ${m.file.name} (${Workspace.human(m.file.length())}): ${m.check}. Folder: ${m.file.parent}", listOf(m.file.absolutePath)) }
            }))

        Tools.register(Tool("doc_make", "Write a complete professional document from a template and make the file. types: ${DocMaker.builtin.joinToString(", ") { it.key }}. Uses only the facts given plus saved knowledge; gaps are marked [to fill in], never invented.",
            listOf(A("type", "template name or a free description of the document"), A("topic", "what it is about"), A("details", "all facts the user gave: names, numbers, dates, requirements", false), A("format", "pdf, docx, xlsx, md... default from the template", false), A("language", "ar, en, fr...; default the user's language", false), A("name", "file name", false)),
            Risk.LOCAL, 300_000L, group = "docs",
            run = { c, a ->
                val tpl = DocMaker.template(a.getString("type"))
                val fmt = a.optString("format", "").ifBlank { tpl?.format ?: "docx" }
                val lang = a.optString("language", "").ifBlank { Config.get("language").let { if (it == "auto" || it.isBlank()) "the same language as the topic" else it } }
                val know = Knowledge.contextFor(a.getString("topic") + " " + a.optString("details", ""), c.project)
                c.progress("Writing the ${tpl?.title ?: a.getString("type")}")
                val structure = tpl?.structure ?: "Choose the structure a professional would use for: ${a.getString("type")}."
                val sys = "You write finished, professional documents. Language: $lang. Output ONLY the document in markdown (# title, ## sections, - lists, | tables |, **bold**), no preface and no closing remarks. " +
                    (if (fmt == "xlsx") "The output is a spreadsheet: give markdown tables only (one per sheet, each under a ## heading naming the sheet); formulas start with = and use cell references like =C2-B2 and =SUM(C2:C10). " else "") +
                    "Required structure: $structure\nRULES: use only facts from the user's details or the saved knowledge; where a needed fact is missing write [to fill in] instead of inventing it; never invent statistics, quotes, names or sources; numbers must add up.\n" + Shield.RULES
                val user = "TOPIC: ${a.getString("topic")}\nDETAILS: ${a.optString("details", "(none)")}" + (if (know.isNotBlank()) "\n\nSAVED KNOWLEDGE (use when relevant):\n$know" else "")
                var text = Router.run(c.brain, Router.T.WRITE, Router.Opts(sys, user, maxOut = 12000, task = c.task))
                text = text.trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
                val base = a.optString("name", "").ifBlank { (tpl?.key ?: "document") + "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date()) }
                val target = outFile(c, base.let { if (it.contains('/')) it else "documents/$it" }) ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                val title = Regex("(?m)^#\\s+(.+)$").find(text)?.groupValues?.get(1) ?: a.getString("topic")
                val m = withContext(Dispatchers.IO) { DocMaker.make(target.parentFile!!, target.name, fmt, title, text) }
                if (m.problem != null) fail(m.problem) else {
                    val gaps = Regex("\\[to fill in\\]").findAll(text).count()
                    ok("Created ${m.file.name} (${Workspace.human(m.file.length())}): ${m.check}." + (if (gaps > 0) " $gaps place(s) are marked [to fill in] because the facts were not given." else ""), listOf(m.file.absolutePath), untrusted = know.isNotBlank())
                }
            }))

        Tools.register(Tool("doc_convert", "Convert a document to another format: md, txt, html, pdf, docx, xlsx, csv, json. Tables go to xlsx/csv/json; text keeps headings, lists and tables.",
            listOf(A("path", "source file"), A("to", "target format"), A("name", "output file name", false)), Risk.LOCAL, 240_000L, group = "docs",
            run = { c, a ->
                val src = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val to = a.getString("to").lowercase().removePrefix(".")
                val ext = src.extension.lowercase()
                val tabular = ext in listOf("csv", "xlsx", "json", "tsv")
                val base = a.optString("name", "").ifBlank { src.nameWithoutExtension }
                val target = outFile(c, if (base.contains('/')) base else "converted/$base") ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                val m = withContext(Dispatchers.IO) {
                    if (tabular && ext != "json" || (ext == "json" && to in listOf("csv", "xlsx"))) {
                        val sheets = if (ext == "xlsx") Docs.xlsxSheets(src) else listOf(Docs.Sheet("Sheet1", Docs.rows(src)))
                        if (sheets.isEmpty() || sheets.all { it.rows.isEmpty() }) return@withContext DocMaker.Made(target, "", "the source has no rows")
                        val md = sheets.joinToString("\n\n") { s -> "## " + s.name + "\n\n" + (if (s.rows.isEmpty()) "" else mdTable(s.rows)) }
                        when (to) {
                            "xlsx" -> { val t = File(target.parentFile, target.nameWithoutExtension + ".xlsx"); DocMaker.xlsx(t, sheets); DocMaker.Made(t, "${sheets.size} sheet(s)", null) }
                            "csv" -> DocMaker.make(target.parentFile!!, target.name, "csv", "", mdTable(sheets.first().rows))
                            "json" -> { val rows = sheets.first().rows; val arr = org.json.JSONArray(); for (r in rows.drop(1)) { val o = JSONObject(); rows[0].forEachIndexed { i, h -> o.put(h.ifBlank { "col${i + 1}" }, r.getOrElse(i) { "" }) }; arr.put(o) }; DocMaker.make(target.parentFile!!, target.name, "json", "", arr.toString(2)) }
                            else -> DocMaker.make(target.parentFile!!, target.name, to, src.nameWithoutExtension, md)
                        }
                    } else {
                        val text = try { fullText(c, src) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
                        if (text.isBlank() || text.startsWith("[")) return@withContext DocMaker.Made(target, "", text.ifBlank { "nothing could be read from the source" })
                        if (to == "xlsx" || to == "csv") DocMaker.make(target.parentFile!!, target.name, to, "", text)
                        else DocMaker.make(target.parentFile!!, target.name, to, if (ext in listOf("md", "txt")) "" else src.nameWithoutExtension, text.replace(Regex("(?m)^\\[\\[(page|sheet) (\\d+)[^\\]]*\\]\\]\\s*$"), ""))
                    }
                }
                if (m.problem != null) fail(m.problem) else ok("Converted ${src.name} to ${m.file.name}: ${m.check}.", listOf(m.file.absolutePath))
            }))

        Tools.register(Tool("doc_summarize", "Summarise a document of any length (PDF, Word, Excel, text...) with its key points, figures, decisions and open items. Keeps page references.",
            listOf(A("path", "file"), A("focus", "what matters most to the user", false), A("length", "short, medium or detailed (default medium)", false)), Risk.READ, 300_000L, group = "docs",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val len = when (a.optString("length", "medium")) { "short" -> "at most 6 lines"; "detailed" -> "a detailed structured summary with sections"; else -> "about 12-18 lines" }
                val r = ask(c, f, "You summarise documents faithfully, in the user's language. Never add facts that are not in the document. Keep exact numbers, names, dates and page references like (p. 4).",
                    "Summarise this document in $len. " + (if (a.optString("focus", "").isNotBlank()) "Focus on: ${a.getString("focus")}. " else "") + "End with a line 'Open points:' listing anything unclear or missing.", Router.T.SUMMARIZE)
                ok(r, untrusted = true)
            }))

        Tools.register(Tool("doc_ask", "Answer a question about a document, with the page it came from and the exact figures. Says so when the document does not contain the answer.",
            listOf(A("path", "file"), A("question", "the question")), Risk.READ, 300_000L, group = "docs",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val r = ask(c, f, "Answer ONLY from the document. Quote exact figures and cite the page like (p. 4) or the sheet. If the document does not contain the answer, say exactly that. Answer in the language of the question.", a.getString("question"))
                ok(r, untrusted = true)
            }))

        Tools.register(Tool("doc_compare", "Compare two documents (versions of a contract, two offers, two reports): what changed, what was added or removed, which numbers differ, and what matters.",
            listOf(A("path_a", "first file"), A("path_b", "second file"), A("focus", "what to pay attention to", false)), Risk.READ, 300_000L, group = "docs",
            run = { c, a ->
                val fa = resolve(c, a.getString("path_a")) ?: return@Tool fail("There is no readable file at ${a.getString("path_a")}.")
                val fb = resolve(c, a.getString("path_b")) ?: return@Tool fail("There is no readable file at ${a.getString("path_b")}.")
                val ta = fullText(c, fa, 120_000); val tb = fullText(c, fb, 120_000)
                val sys = "You compare two documents precisely. Output: 1) Summary of the main differences (3-6 lines). 2) A table: Topic | Document A | Document B | Why it matters. 3) Added in B, removed from B. 4) Numbers or dates that differ. Quote exact wording for every change. Do not claim a difference you cannot see in the text. Same language as the documents.\n" + Shield.RULES
                val r = Router.run(c.brain, Router.T.LONGDOC, Router.Opts(sys, (if (a.optString("focus", "").isNotBlank()) "FOCUS: ${a.getString("focus")}\n" else "") + Shield.wrap("A: " + fa.name, ta, 70_000).text + "\n" + Shield.wrap("B: " + fb.name, tb, 70_000).text, maxOut = 8000, task = c.task))
                ok(r, untrusted = true)
            }))

        Tools.register(Tool("pdf_info", "Facts about a PDF: pages, size, title, and a sample of the first page.", listOf(A("path", "PDF file")), Risk.READ, group = "docs",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val n = Docs.pdfPages(f)
                if (n == 0) return@Tool fail("${f.name} is not a readable PDF.")
                val head = String(f.inputStream().use { ins -> val buf = ByteArray(minOf(f.length(), 600_000L).toInt()); val n2 = ins.read(buf); buf.copyOf(maxOf(n2, 0)) }, Charsets.ISO_8859_1)
                val title = Regex("/Title\\s*\\(([^)]{1,120})\\)").find(head)?.groupValues?.get(1)
                ok("${f.name}: $n pages, ${Workspace.human(f.length())}" + (if (title != null) ", title \"$title\"" else "") + "\nFirst page:\n" + Docs.readPdf(c, f, 1).take(900), untrusted = true)
            }))

        Tools.register(Tool("pdf_extract", "Make a new PDF from chosen pages of a PDF (like 1-3,7). Pages are copied as pictures, so the text in the new file is not selectable.",
            listOf(A("path", "PDF file"), A("pages", "pages, like 1-3,7"), A("name", "output name", false)), Risk.LOCAL, 240_000L, group = "docs",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val n = Docs.pdfPages(f); val pages = parsePages(a.getString("pages"), n)
                if (pages.isEmpty()) return@Tool fail("No valid pages in \"${a.getString("pages")}\" (the file has $n).")
                val out = outFile(c, a.optString("name", "").ifBlank { f.nameWithoutExtension + "_p" + a.getString("pages").replace(Regex("[^0-9,-]"), "") + ".pdf" }.let { if (it.endsWith(".pdf")) it else "$it.pdf" }) ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                val made = withContext(Dispatchers.IO) { rasterPdf(out, listOf(f to pages)) }
                if (made != pages.size || Docs.pdfPages(out) != made) fail("Only $made of ${pages.size} pages could be copied.") else ok("Created ${out.name} with $made page(s) (copied as pictures).", listOf(out.absolutePath))
            }))

        Tools.register(Tool("pdf_merge", "Join several PDFs into one, in the order given. Pages are copied as pictures.",
            listOf(A("paths", "PDF files separated by commas"), A("name", "output name", false)), Risk.LOCAL, 300_000L, group = "docs",
            run = { c, a ->
                val files = a.getString("paths").split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { resolve(c, it) ?: return@Tool fail("There is no readable file at $it.") }
                if (files.size < 2) return@Tool fail("Give at least two PDF files.")
                val out = outFile(c, a.optString("name", "merged.pdf").let { if (it.endsWith(".pdf")) it else "$it.pdf" }) ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                val src = files.map { it to (1..Docs.pdfPages(it)).toList() }
                val total = src.sumOf { it.second.size }
                if (total == 0) return@Tool fail("None of those files is a readable PDF.")
                val made = withContext(Dispatchers.IO) { rasterPdf(out, src) }
                if (made != total || Docs.pdfPages(out) != made) fail("Only $made of $total pages could be joined.") else ok("Created ${out.name}: $made pages from ${files.size} files (copied as pictures).", listOf(out.absolutePath))
            }))

        Tools.register(Tool("file_split", "Split a text or CSV file into parts: by number of parts, or by lines per part. A CSV keeps its header in every part.",
            listOf(A("path", "file"), A("parts", "number of parts", false, "int"), A("lines", "lines per part", false, "int")), Risk.LOCAL, group = "docs",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                if (!FileTools.isText(f)) return@Tool fail("Only text-based files can be split this way (txt, md, csv, json lines).")
                val lines = f.readLines()
                val csv = f.extension.lowercase() == "csv"
                val body = if (csv) lines.drop(1) else lines
                val per = if (a.has("lines")) a.getInt("lines").coerceAtLeast(1) else Math.ceil(body.size / a.optInt("parts", 2).coerceAtLeast(2).toDouble()).toInt().coerceAtLeast(1)
                val dir = Workspace.inside(c.project, "split_" + f.nameWithoutExtension) ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                dir.mkdirs()
                val outs = body.chunked(per).mapIndexed { i, ch -> File(dir, f.nameWithoutExtension + "_part" + (i + 1) + "." + f.extension).also { o -> o.writeText((if (csv) lines.first() + "\n" else "") + ch.joinToString("\n") + "\n") } }
                if (outs.sumOf { it.readLines().size - (if (csv) 1 else 0) } != body.size) fail("The parts do not add up to the original.") else ok("Split into ${outs.size} files in ${dir.path}.", outs.map { it.absolutePath })
            }))

        Tools.register(Tool("file_merge", "Join text or CSV files into one (CSV headers are kept once).",
            listOf(A("paths", "files separated by commas"), A("name", "output file name")), Risk.LOCAL, group = "docs",
            run = { c, a ->
                val files = a.getString("paths").split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { resolve(c, it) ?: return@Tool fail("There is no readable file at $it.") }
                if (files.any { !FileTools.isText(it) }) return@Tool fail("Only text-based files can be joined here; use pdf_merge for PDFs.")
                val csv = files.all { it.extension.lowercase() == "csv" }
                val out = outFile(c, a.getString("name")) ?: return@Tool fail("Files can only be created inside the AXIOM folder.")
                val sb = StringBuilder(); var rows = 0
                files.forEachIndexed { i, f -> val l = f.readLines(); val use = if (csv && i > 0) l.drop(1) else l; rows += use.size; sb.append(use.joinToString("\n")).append('\n') }
                out.parentFile?.mkdirs(); out.writeText(sb.toString())
                if (out.readLines().size != rows) fail("The merged file has a different number of lines than expected.") else ok("Joined ${files.size} files into ${out.name} ($rows lines).", listOf(out.absolutePath))
            }))

        // ---------------- knowledge base ----------------

        Tools.register(Tool("kb_add", "Learn a document, web page or text so it can be searched by meaning later. source = a file path, a web address, or plain text with a title.",
            listOf(A("source", "file path, URL, or text"), A("title", "short title (required for plain text)", false), A("project", "project, default the active one", false)), Risk.LOCAL, 600_000L, group = "kb",
            run = { c, a ->
                val src = a.getString("source").trim()
                val project = a.optString("project", c.project)
                val trust = if (c.direct && !c.tainted) "user" else "external"
                val (text, id, kind) = when {
                    src.startsWith("http://") || src.startsWith("https://") -> {
                        val p = withContext(Dispatchers.IO) { Net.get(src) }
                        if (p.blocked || p.status !in 200..299) return@Tool fail("The page did not open (HTTP ${p.status}${if (p.blocked) ", it blocks automated reading" else ""}).")
                        Triple(Net.htmlToText(p.body), p.finalUrl, "web")
                    }
                    src.length < 400 && !src.contains('\n') && Workspace.readable(src, c.project)?.let { it.exists() && it.isFile } == true -> {
                        val f = Workspace.readable(src, c.project)!!
                        Triple(fullText(c, f), f.absolutePath, "file")
                    }
                    else -> { val t = a.optString("title", ""); if (t.isBlank()) return@Tool fail("Give a title for the text to remember."); Triple(src, "note:" + Axiom.sha1(t).take(10), "note") }
                }
                if (text.isBlank() || text.startsWith("[could not") || text.startsWith("[no text")) return@Tool fail(text.ifBlank { "Nothing readable to learn." })
                val title = a.optString("title", "").ifBlank { if (kind == "web") "" else File(id).nameWithoutExtension }.ifBlank { Net.title(text).ifBlank { id.take(60) } }
                val r = withContext(Dispatchers.IO) { Knowledge.add(c.brain, id, project, title, kind, text, trust) }
                ok(r, untrusted = kind != "note" && trust != "user")
            }))

        Tools.register(Tool("kb_search", "Search everything AXIOM has learned, by meaning and by words. Returns passages with their source, page and the date they were learned.",
            listOf(A("query", "what to look for"), A("project", "limit to a project", false), A("source", "limit to a source name or part of its address", false), A("days", "only what was learned in the last N days", false, "int"), A("limit", "how many passages, default 6", false, "int")), Risk.READ, 60_000L, group = "kb",
            run = { c, a ->
                val since = if (a.has("days")) System.currentTimeMillis() - a.getLong("days") * 86400_000L else 0L
                val hits = withContext(Dispatchers.IO) { Knowledge.search(c.brain, a.getString("query"), a.optString("project", "").ifBlank { null }, a.optString("source", "").ifBlank { null }, since, a.optInt("limit", 6).coerceIn(1, 15)) }
                if (hits.isEmpty()) ok("Nothing in the knowledge base matches that.")
                else { val w = Shield.wrap("knowledge base", Knowledge.format(hits), 14000); ok(w.text, untrusted = hits.any { it.trust != "user" }) }
            }))

        Tools.register(Tool("kb_answer", "Answer a question ONLY from the knowledge base, with numbered citations [1], [2] to the sources. Says so when the knowledge base does not contain the answer.",
            listOf(A("question", "the question"), A("project", "limit to a project", false)), Risk.READ, 120_000L, group = "kb",
            run = { c, a ->
                val hits = withContext(Dispatchers.IO) { Knowledge.search(c.brain, a.getString("question"), a.optString("project", "").ifBlank { null }, limit = 8) }
                if (hits.isEmpty()) return@Tool ok("The knowledge base has nothing about that. I do not want to guess; add the source with kb_add or let me search the web.")
                val ctx = hits.mapIndexed { i, h -> "[${i + 1}] (${h.title}${if (h.page > 0) ", p. ${h.page}" else ""})\n" + h.text }.joinToString("\n\n")
                val ans = Router.run(c.brain, Router.T.REASON, Router.Opts(
                    "Answer the question using ONLY the numbered passages. Cite each claim with its number like [2]. If the passages do not contain the answer, say that plainly and stop. Same language as the question. Never use outside knowledge.\n" + Shield.RULES,
                    "QUESTION: ${a.getString("question")}\n\n" + Shield.wrap("knowledge base", ctx, 24000).text, maxOut = 3000, task = c.task))
                val used = Regex("\\[(\\d+)]").findAll(ans).map { it.groupValues[1].toInt() }.toSet()
                val bad = used.filter { it !in 1..hits.size }
                val srcs = hits.mapIndexed { i, h -> "[${i + 1}] ${h.title}${if (h.page > 0) ", p. ${h.page}" else ""} — ${h.source}" }.filter { used.isEmpty() || (it.substringAfter('[').substringBefore(']').toInt() in used) }
                ok(ans.trim() + (if (bad.isNotEmpty()) "\n(note: the answer cited sources $bad that do not exist; treat those claims as unverified)" else "") + "\n\nSources:\n" + srcs.joinToString("\n"), untrusted = hits.any { it.trust != "user" })
            }))

        Tools.register(Tool("kb_sources", "List what AXIOM has learned: title, kind, pieces, date.", listOf(A("project", "limit to a project", false)), Risk.READ, group = "kb",
            run = { _, a ->
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val l = Knowledge.sources(a.optString("project", "").ifBlank { null })
                ok(if (l.isEmpty()) "Nothing learned yet." else l.joinToString("\n") { "- ${it["title"]} [${it["kind"]}] ${it["pieces"]} pieces, ${fmt.format(java.util.Date(it["added"]!!.toLong()))} — ${Axiom.clip(it["source"] ?: "", 80)}" })
            }))

        Tools.register(Tool("kb_remove", "Forget a learned source (by its title or address). Always asks first.", listOf(A("source", "title or address")), Risk.DANGEROUS, group = "kb",
            run = { _, a -> val n = Knowledge.remove(a.getString("source")); if (n == 0) fail("Nothing learned under that name.") else ok("Forgot $n source(s).") }))
    }

    fun mdTable(rows: List<List<String>>): String {
        val cols = rows.maxOfOrNull { it.size } ?: return ""
        fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
        val head = (0 until cols).map { cell(rows[0].getOrElse(it) { "" }) }
        return "| " + head.joinToString(" | ") + " |\n|" + head.joinToString("|") { "---" } + "|\n" +
            rows.drop(1).joinToString("\n") { r -> "| " + (0 until cols).joinToString(" | ") { cell(r.getOrElse(it) { "" }) } + " |" }
    }
}
