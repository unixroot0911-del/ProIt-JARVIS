package com.jarvis.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * Reads documents of many kinds into plain text so tools and agents can work with them:
 * PDF (read by Gemini natively, page markers kept; offline fallback), Word, Excel, PowerPoint, CSV, JSON, HTML, images (OCR / description).
 * Every extraction is cached by file content so the same document is never paid for twice.
 */
object Docs {
    val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    fun xmlUnescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }.replace("&amp;", "&")

    fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

    private fun clip(t: String, max: Int): String =
        if (t.length <= max) t else t.take(max) + "\n[cut: ${t.length - max} more characters. Ask for a later page/sheet, or use kb_add and kb_search to search the whole document.]"

    suspend fun readText(c: ToolCtx, f: File, maxChars: Int, page: Int): String {
        val ext = f.extension.lowercase()
        return try {
            when {
                ext == "pdf" -> clip(readPdf(c, f, page), maxChars)
                ext == "docx" -> clip(docx(f), maxChars)
                ext == "xlsx" -> clip(xlsxText(f, page), maxChars)
                ext == "pptx" -> clip(pptx(f), maxChars)
                ext == "html" || ext == "htm" -> clip(Net.htmlToText(f.readText()), maxChars)
                ext in imageExt -> clip(readImage(c, f), maxChars)
                FileTools.isText(f) -> {
                    if (f.length() > 3_000_000L) f.inputStream().bufferedReader().use { r -> val b = CharArray(maxChars); val n = r.read(b); String(b, 0, maxOf(n, 0)) + "\n[file is ${Workspace.human(f.length())}; only the start is shown]" }
                    else clip(f.readText(), maxChars)
                }
                else -> "[${f.name}: this kind of file (.$ext) is not a readable document. Supported: PDF, Word, Excel, PowerPoint, text, CSV, JSON, HTML and images.]"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { "[could not read ${f.name}: ${e.message ?: e.javaClass.simpleName}]" }
    }

    fun b64(f: File): String = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)

    // ---------------- PDF ----------------

    fun pdfPages(f: File): Int = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    } catch (e: Exception) { 0 }

    private const val READER_SYS = "You are a precise document reader. Transcribe the text of the requested pages exactly as written, in reading order, in the original language, " +
        "keeping every number, name and date unchanged. Put a line [[page N]] (N = the real page number in the document) before each page. " +
        "Render tables as rows with cells separated by ' | '. For a figure or chart write one line [figure: what it shows, with its values]. " +
        "If a part is unreadable write [unreadable]. Do not summarise, translate, comment or add anything."

    suspend fun readPdf(c: ToolCtx, f: File, page: Int): String {
        val pages = pdfPages(f)
        val from = if (page > 0) page else 1
        val perCall = 40
        val to = if (pages == 0) 0 else minOf(pages, from + perCall - 1)
        val key = "doc:" + Axiom.sha1(f.absolutePath + f.length() + f.lastModified()) + ":$from-$to"
        Cache.get(key)?.let { return it }
        val brain = c.brain
        var text = ""
        var note = ""
        if (brain.hasGemini()) {
            try {
                val o = if (f.length() <= 14_000_000L)
                    Router.Opts(READER_SYS, "Pages $from to ${if (to == 0) "end" else to.toString()} of ${if (pages == 0) "?" else pages.toString()}.", fileB64 = b64(f), fileMime = "application/pdf", maxOut = 30000, task = c.task)
                else null
                text = if (o != null) Router.run(brain, Router.T.LONGDOC, o)
                else ocrPages(c, f, from, minOf(to, from + 7))
                if (f.length() > 14_000_000L) note = "\n[large file: only pages $from to ${minOf(to, from + 7)} were read; ask for page=${minOf(to, from + 7) + 1} to continue]"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { text = "" }
        }
        if (text.isBlank()) {
            val raw = rawPdfText(f)
            if (raw.isBlank()) return "[${f.name}: no text could be read. It is probably a scan and no vision model is available right now (keys / limits). Try again later.]"
            return "[offline extraction without the vision model: it may be incomplete or have broken Arabic]\n$raw"
        }
        if (pages > to) note += "\n[the document has $pages pages; this is pages $from to $to. Ask for page=${to + 1} to continue.]"
        val out = text.trim() + note
        Cache.put(key, out, 30L * 24 * 3600_000L)
        return out
    }

    /** Renders pages to pictures and reads them one by one (used when a PDF is too big to send whole). */
    private suspend fun ocrPages(c: ToolCtx, f: File, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (p in from..to) {
            val bmp = renderPage(f, p, 1500) ?: continue
            val jpg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            bmp.recycle()
            val t = Router.run(c.brain, Router.T.EXTRACT, Router.Opts(READER_SYS, "This is page $p.", image = Base64.encodeToString(jpg, Base64.NO_WRAP), maxOut = 8000, task = c.task))
            sb.append("[[page $p]]\n").append(t.replace(Regex("(?m)^\\[\\[page \\d+\\]\\]\\s*"), "")).append("\n\n")
        }
        return sb.toString()
    }

    fun renderPage(f: File, page: Int, widthPx: Int): Bitmap? = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (page < 1 || page > r.pageCount) null else r.openPage(page - 1).use { pg ->
                    val w = widthPx
                    val h = (w.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    } catch (e: Exception) { null }

    private fun inflate(raw: ByteArray): ByteArray? = try {
        val inf = Inflater(); inf.setInput(raw)
        val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n); if (out.size() > 40_000_000) break }
        inf.end(); out.toByteArray()
    } catch (e: Exception) { null }

    private val tj = Regex("\\[(.*?)\\]\\s*TJ|\\(((?:\\\\.|[^\\\\)])*)\\)\\s*(?:Tj|'|\")|T\\*|T[dD]|ET", RegexOption.DOT_MATCHES_ALL)
    private val str = Regex("\\(((?:\\\\.|[^\\\\)])*)\\)")

    private fun pdfUnescape(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> { sb.append('\n'); i += 2 }; 'r' -> { i += 2 }; 't' -> { sb.append(' '); i += 2 }
                    '(', ')', '\\' -> { sb.append(n); i += 2 }
                    in '0'..'7' -> { var j = i + 1; var v = 0; while (j < s.length && j < i + 4 && s[j] in '0'..'7') { v = v * 8 + (s[j] - '0'); j++ }; sb.append(v.toChar()); i = j }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(ch); i++ }
        }
        return sb.toString()
    }

    /** Last-resort text pull for simple PDFs when no vision model is reachable. */
    fun rawPdfText(f: File): String {
        if (f.length() > 30_000_000L) return ""
        val bytes = f.readBytes()
        val s = String(bytes, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var pos = 0
        while (out.length < 400_000) {
            val st = s.indexOf("stream", pos); if (st < 0) break
            var a = st + 6
            if (a < s.length && s[a] == '\r') a++
            if (a < s.length && s[a] == '\n') a++
            val en = s.indexOf("endstream", a); if (en < 0) break
            val data = inflate(bytes.copyOfRange(a, en)) ?: bytes.copyOfRange(a, en)
            val t = String(data, Charsets.ISO_8859_1)
            if (t.contains("BT") && t.contains("ET")) {
                for (m in tj.findAll(t)) {
                    val v = m.value
                    when {
                        v.endsWith("TJ") && v.startsWith("[") -> out.append(str.findAll(m.groupValues[1]).joinToString("") { pdfUnescape(it.groupValues[1]) })
                        m.groupValues[2].isNotEmpty() || v.startsWith("(") -> out.append(pdfUnescape(m.groupValues[2]))
                        else -> out.append('\n')
                    }
                }
                out.append('\n')
            }
            pos = en + 9
        }
        val txt = out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val printable = txt.count { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?-()%$/" }
        return if (txt.length < 20 || printable < txt.length * 0.7) "" else txt
    }

    // ---------------- Word / PowerPoint ----------------

    private fun zipText(z: ZipFile, name: String): String? = z.getEntry(name)?.let { e -> z.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() } }

    private val wT = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>|<w:tab\\s*/>|<w:br\\s*/>|<w:cr\\s*/>", RegexOption.DOT_MATCHES_ALL)

    private fun wText(x: String): String {
        val sb = StringBuilder()
        for (m in wT.findAll(x)) sb.append(if (m.groupValues[1].isNotEmpty() || m.value.startsWith("<w:t>") || m.value.startsWith("<w:t ")) xmlUnescape(m.groupValues[1]) else if (m.value.startsWith("<w:tab")) "\t" else "\n")
        return sb.toString()
    }

    fun docx(f: File): String {
        ZipFile(f).use { z ->
            val xml = zipText(z, "word/document.xml") ?: return "[this is not a valid Word (.docx) file]"
            val sb = StringBuilder()
            val rx = Regex("<w:tbl>.*?</w:tbl>|<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
            for (m in rx.findAll(xml)) {
                val b = m.value
                if (b.startsWith("<w:tbl>")) {
                    for (r in Regex("<w:tr[ >].*?</w:tr>", RegexOption.DOT_MATCHES_ALL).findAll(b)) {
                        val cells = Regex("<w:tc>.*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(r.value).map { wText(it.value).replace("\n", " ").trim() }.toList()
                        sb.append(cells.joinToString(" | ")).append('\n')
                    }
                    sb.append('\n')
                } else {
                    val t = wText(b).trim()
                    if (t.isEmpty()) { sb.append('\n'); continue }
                    val h = Regex("<w:pStyle w:val=\"(?:Heading|heading|Titre)\\s?(\\d)\"").find(b)?.groupValues?.get(1)?.toIntOrNull()
                    val isTitle = b.contains("<w:pStyle w:val=\"Title\"")
                    when {
                        isTitle -> sb.append("# ").append(t)
                        h != null -> sb.append("#".repeat(h.coerceIn(1, 6) + 0)).append(' ').append(t)
                        b.contains("<w:numPr>") -> sb.append("- ").append(t)
                        else -> sb.append(t)
                    }
                    sb.append('\n')
                }
            }
            zipText(z, "word/comments.xml")?.let { cx ->
                val cm = Regex("<w:comment [^>]*>.*?</w:comment>", RegexOption.DOT_MATCHES_ALL).findAll(cx).map { wText(it.value).trim() }.filter { it.isNotEmpty() }.toList()
                if (cm.isNotEmpty()) sb.append("\n[comments]\n").append(cm.joinToString("\n") { "- $it" })
            }
            return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }

    fun pptx(f: File): String {
        ZipFile(f).use { z ->
            val slides = z.entries().asSequence().map { it.name }.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                .sortedBy { Regex("(\\d+)").find(it.substringAfterLast('/'))!!.value.toInt() }.toList()
            if (slides.isEmpty()) return "[no slides found]"
            val sb = StringBuilder()
            slides.forEachIndexed { i, n ->
                sb.append("[[page ${i + 1}]]\n")
                val x = zipText(z, n) ?: ""
                for (p in Regex("<a:p>.*?</a:p>", RegexOption.DOT_MATCHES_ALL).findAll(x)) {
                    val t = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(p.value).joinToString("") { xmlUnescape(it.groupValues[1]) }.trim()
                    if (t.isNotEmpty()) sb.append(t).append('\n')
                }
                sb.append('\n')
            }
            return sb.toString().trim()
        }
    }

    // ---------------- Excel / CSV ----------------

    class Sheet(val name: String, val rows: List<List<String>>)

    private fun attr(tag: String, name: String): String? = Regex("(?:^|\\s)$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else if (ch in 'a'..'z') n = n * 26 + (ch - 'a' + 1) else break }
        return n - 1
    }

    private fun serialToDate(v: String): String {
        val d = v.toDoubleOrNull() ?: return v
        if (d < 1 || d > 80000) return v
        val ms = ((d - 25569.0) * 86400_000.0).toLong()
        val hasTime = d != Math.floor(d)
        return java.text.SimpleDateFormat(if (hasTime) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
    }

    fun xlsxSheets(f: File, maxRows: Int = 20000): List<Sheet> {
        ZipFile(f).use { z ->
            val shared = ArrayList<String>()
            zipText(z, "xl/sharedStrings.xml")?.let { sx ->
                for (si in Regex("<si>.*?</si>|<si/>", RegexOption.DOT_MATCHES_ALL).findAll(sx))
                    shared.add(Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(si.value).joinToString("") { xmlUnescape(it.groupValues[1]) })
            }
            // which cell styles are dates
            val dateStyles = HashSet<Int>()
            zipText(z, "xl/styles.xml")?.let { st ->
                val custom = HashMap<Int, String>()
                for (m in Regex("<numFmt [^>]*/>").findAll(st)) { val id = attr(m.value, "numFmtId")?.toIntOrNull(); val code = attr(m.value, "formatCode"); if (id != null && code != null) custom[id] = code }
                val xfs = Regex("<cellXfs[^>]*>(.*?)</cellXfs>", RegexOption.DOT_MATCHES_ALL).find(st)?.groupValues?.get(1) ?: ""
                Regex("<xf [^>]*?/?>").findAll(xfs).forEachIndexed { i, m ->
                    val id = attr(m.value, "numFmtId")?.toIntOrNull() ?: 0
                    val code = custom[id]?.lowercase()?.replace(Regex("\\[[^\\]]*\\]|\"[^\"]*\""), "")
                    if (id in 14..22 || id in 45..47 || (code != null && Regex("[ymd]").containsMatchIn(code) && !code.contains("0.") && !code.contains("#"))) dateStyles.add(i)
                }
            }
            val wb = zipText(z, "xl/workbook.xml") ?: return emptyList()
            val rels = HashMap<String, String>()
            zipText(z, "xl/_rels/workbook.xml.rels")?.let { rx -> for (m in Regex("<Relationship [^>]*/?>").findAll(rx)) { val id = attr(m.value, "Id"); val t = attr(m.value, "Target"); if (id != null && t != null) rels[id] = t } }
            val sheets = ArrayList<Sheet>()
            var idx = 0
            for (m in Regex("<sheet [^>]*/?>").findAll(wb)) {
                idx++
                val name = xmlUnescape(attr(m.value, "name") ?: "Sheet$idx")
                val rid = Regex("r:id=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                val target = (rid?.let { rels[it] } ?: "worksheets/sheet$idx.xml").let { if (it.startsWith("/")) it.removePrefix("/") else "xl/$it" }
                val sx = zipText(z, target) ?: continue
                val rows = ArrayList<List<String>>()
                for (r in Regex("<row[ >].*?</row>|<row [^>]*/>", RegexOption.DOT_MATCHES_ALL).findAll(sx)) {
                    if (rows.size >= maxRows) break
                    val row = ArrayList<String>()
                    for (cm in Regex("<c [^>]*?/>|<c [^>]*?>.*?</c>", RegexOption.DOT_MATCHES_ALL).findAll(r.value)) {
                        val tag = cm.value.substringBefore('>')
                        val ci = colIndex(attr(tag, "r") ?: "")
                        if (ci < 0) continue
                        val t = attr(tag, "t") ?: ""
                        val s = attr(tag, "s")?.toIntOrNull() ?: -1
                        val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(cm.value)?.groupValues?.get(1)?.let { xmlUnescape(it) } ?: ""
                        val value = when (t) {
                            "s" -> shared.getOrElse(v.toIntOrNull() ?: -1) { "" }
                            "inlineStr" -> Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).findAll(cm.value).joinToString("") { xmlUnescape(it.groupValues[1]) }
                            "b" -> if (v == "1") "TRUE" else "FALSE"
                            "str", "e" -> v
                            else -> if (s in dateStyles) serialToDate(v) else v.let { n -> if (n.contains('E') || n.length > 14) n.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: n else n }
                        }
                        while (row.size <= ci) row.add("")
                        row[ci] = value
                    }
                    while (row.isNotEmpty() && row.last().isEmpty()) row.removeAt(row.size - 1)
                    val rn = attr(r.value.substringBefore('>'), "r")?.toIntOrNull()
                    if (rn != null) while (rows.size < rn - 1) rows.add(emptyList())
                    rows.add(row)
                }
                sheets.add(Sheet(name, rows))
            }
            return sheets
        }
    }

    fun xlsxText(f: File, sheetNo: Int): String {
        val sheets = xlsxSheets(f)
        if (sheets.isEmpty()) return "[no sheets found, or this is not a valid Excel (.xlsx) file]"
        val pick = if (sheetNo in 1..sheets.size) listOf(sheetNo - 1) else sheets.indices.toList()
        val sb = StringBuilder()
        for (i in pick) {
            val s = sheets[i]
            sb.append("[[sheet ${i + 1}: ${s.name}]] (${s.rows.size} rows)\n")
            for (r in s.rows.take(3000)) sb.append(r.joinToString(" | ")).append('\n')
            if (s.rows.size > 3000) sb.append("[${s.rows.size - 3000} more rows not shown]\n")
            sb.append('\n')
        }
        if (sheetNo !in 1..sheets.size && sheets.size > 1) sb.append("[sheets: ").append(sheets.mapIndexed { i, s -> "${i + 1}=${s.name}" }.joinToString(", ")).append("]")
        return sb.toString().trim()
    }

    /** Splits one CSV line honouring quotes. */
    fun csvLine(line: String, d: Char): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (q) { if (ch == '"') { if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(ch) }
            else if (ch == '"') q = true else if (ch == d) { out.add(sb.toString()); sb.setLength(0) } else sb.append(ch)
            i++
        }
        out.add(sb.toString())
        return out
    }

    fun csvRows(text: String, max: Int = 200000): List<List<String>> {
        val lines = ArrayList<String>(); val cur = StringBuilder(); var q = false
        for (ch in text.removePrefix("\uFEFF")) {      // a quoted cell may contain line breaks
            if (ch == '"') q = !q
            if ((ch == '\n') && !q) { lines.add(cur.toString().trimEnd('\r')); cur.setLength(0) } else cur.append(ch)
            if (lines.size >= max) break
        }
        if (cur.isNotEmpty()) lines.add(cur.toString().trimEnd('\r'))
        val first = lines.firstOrNull { it.isNotBlank() } ?: return emptyList()
        val d = listOf(',', ';', '\t', '|').maxByOrNull { c -> first.count { it == c } }!!.takeIf { c -> first.contains(c) } ?: ','
        return lines.filter { it.isNotBlank() }.map { csvLine(it, d) }
    }

    /** Rows of a table file: CSV/TSV, Excel (sheet 1-based), JSON array of objects. */
    fun rows(f: File, sheet: Int = 1): List<List<String>> = when (f.extension.lowercase()) {
        "xlsx" -> xlsxSheets(f).let { it.getOrNull(sheet - 1)?.rows ?: it.firstOrNull()?.rows ?: emptyList() }
        "json" -> try {
            val t = f.readText().trim()
            val arr = if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t).let { o -> o.keys().asSequence().map { o.opt(it) }.firstOrNull { it is org.json.JSONArray } as? org.json.JSONArray ?: org.json.JSONArray().put(o) }
            val keys = LinkedHashSet<String>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.keys()?.forEach { keys.add(it) }
            listOf(keys.toList()) + (0 until arr.length()).map { i -> val o = arr.optJSONObject(i); keys.map { k -> o?.opt(k)?.toString()?.takeIf { v -> v != "null" } ?: "" } }
        } catch (e: Exception) { emptyList() }
        else -> csvRows(f.readText())
    }

    // ---------------- images ----------------

    fun imageJpegB64(f: File, maxSide: Int = 1600): String? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / s > maxSide * 2 || o.outHeight / s > maxSide * 2) s *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun readImage(c: ToolCtx, f: File): String {
        val key = "doc:" + Axiom.sha1(f.absolutePath + f.length() + f.lastModified()) + ":img"
        Cache.get(key)?.let { return it }
        val b = imageJpegB64(f) ?: return "[${f.name}: this image could not be decoded]"
        val t = Router.run(c.brain, Router.T.EXTRACT, Router.Opts(
            "You read images. First transcribe ALL visible text exactly (OCR), in its language, tables as rows with ' | '. Then, after a line '---', describe what the image shows in 2-5 sentences, including any numbers, charts or labels. If it is a screenshot, say which app or site it looks like.",
            "Read this image.", image = b, maxOut = 4000, task = c.task))
        Cache.put(key, t, 30L * 24 * 3600_000L)
        return t
    }
}
