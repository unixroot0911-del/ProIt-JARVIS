package com.jarvis.assistant

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Light markdown model shared by the PDF, Word and HTML writers. */
sealed class Blk {
    class H(val level: Int, val text: String) : Blk()
    class P(val text: String) : Blk()
    class Li(val text: String, val ordered: Boolean, val depth: Int) : Blk()
    class Tbl(val rows: List<List<String>>) : Blk()
    class Code(val text: String) : Blk()
    class Quote(val text: String) : Blk()
    object Hr : Blk()
}

class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

object Md {
    private val sep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val li = Regex("^(\\s*)([-*•]|\\d+[.)])\\s+(.*)$")

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.dropLast(1)
        return t.split("|").map { it.trim() }
    }

    fun parse(src: String): List<Blk> {
        val lines = src.replace("\r", "").split("\n")
        val out = ArrayList<Blk>()
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out.add(Blk.P(para.toString().trim())); para.setLength(0) }
        while (i < lines.size) {
            val l = lines[i]
            val t = l.trim()
            when {
                t.startsWith("```") -> {
                    flush(); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                    out.add(Blk.Code(code.toString().trimEnd()))
                }
                t.isEmpty() -> flush()
                Regex("^#{1,6}\\s+.*").matches(t) -> { flush(); val n = t.takeWhile { it == '#' }.length; out.add(Blk.H(n, t.substring(n).trim())) }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out.add(Blk.Hr) }
                t.contains("|") && i + 1 < lines.size && sep.matches(lines[i + 1]) -> {
                    flush()
                    val rows = ArrayList<List<String>>(); rows.add(cells(t)); i += 2
                    while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) { rows.add(cells(lines[i])); i++ }
                    out.add(Blk.Tbl(rows)); continue
                }
                li.matches(l) -> { flush(); val m = li.find(l)!!; out.add(Blk.Li(m.groupValues[3].trim(), m.groupValues[2][0].isDigit(), (m.groupValues[1].replace("\t", "    ").length / 2).coerceAtMost(2))) }
                t.startsWith(">") -> { flush(); out.add(Blk.Quote(t.removePrefix(">").trim())) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    private val inl = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`|\\[(.+?)\\]\\((.+?)\\)")

    fun runs(t: String): List<Run> {
        val out = ArrayList<Run>(); var pos = 0
        for (m in inl.findAll(t)) {
            if (m.range.first > pos) out.add(Run(t.substring(pos, m.range.first)))
            when {
                m.groupValues[1].isNotEmpty() -> out.add(Run(m.groupValues[1], bold = true))
                m.groupValues[2].isNotEmpty() -> out.add(Run(m.groupValues[2], italic = true))
                m.groupValues[3].isNotEmpty() -> out.add(Run(m.groupValues[3], code = true))
                else -> out.add(Run(m.groupValues[4] + " (" + m.groupValues[5] + ")"))
            }
            pos = m.range.last + 1
        }
        if (pos < t.length) out.add(Run(t.substring(pos)))
        return out
    }

    fun plain(t: String): String = runs(t).joinToString("") { it.text }

    fun isRtl(s: String): Boolean {
        var r = 0; var l = 0
        for (ch in s) { val d = Character.getDirectionality(ch); if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) r++ else if (Character.isLetter(ch)) l++ }
        return r > l
    }
}

object DocMaker {
    val formats = listOf("md", "txt", "html", "pdf", "docx", "xlsx", "csv", "json")

    class Made(val file: File, val check: String, val problem: String?)

    /** Writes [content] (markdown-style text) as [format] into [dir] and verifies the result by reading it back. */
    fun make(dir: File, baseName: String, format: String, title: String, content: String): Made {
        val fmt = format.lowercase().removePrefix(".").let { if (it == "doc") "docx" else if (it == "xls") "xlsx" else if (it == "markdown") "md" else if (it == "text") "txt" else it }
        if (fmt !in formats) return Made(File(dir, baseName), "", "Unknown format \"$format\". Available: ${formats.joinToString(", ")}.")
        dir.mkdirs()
        val safe = baseName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().ifBlank { "document" }.take(80)
        val f = File(dir, "$safe.$fmt")
        if (f.exists()) try { f.copyTo(File(dir, "$safe.$fmt.bak"), overwrite = true) } catch (e: Exception) { /* backup best effort */ }
        return try {
            when (fmt) {
                "md", "txt" -> { f.writeText(content); verifyText(f, content) }
                "csv" -> { f.writeText("﻿" + csvOut(rowsFrom(content))); verifyRows(f, rowsFrom(content).size) }
                "json" -> { val t = content.trim(); org.json.JSONTokener(t).nextValue(); f.writeText(t); Made(f, "valid JSON", null) }
                "html" -> { f.writeText(html(Md.parse(content), title)); verifyText(f, Md.plain(content).take(40)) }
                "pdf" -> { pdf(f, withTitle(Md.parse(content), title), title); verifyPdf(f) }
                "docx" -> { docx(f, withTitle(Md.parse(content), title), title); verifyDocx(f, withTitle(Md.parse(content), title)) }
                else -> { val sh = sheetsFrom(content); xlsx(f, sh); verifyXlsx(f, sh) }
            }
        } catch (e: org.json.JSONException) { Made(f, "", "That is not valid JSON: ${e.message}")
        } catch (e: Exception) { Diag.record(e); Made(f, "", "Could not create the $fmt file: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun withTitle(b: List<Blk>, title: String): List<Blk> =
        if (title.isNotBlank() && !(b.firstOrNull() is Blk.H && (b.first() as Blk.H).level == 1)) listOf<Blk>(Blk.H(1, title)) + b else b

    // ---------------- verification ----------------

    private fun verifyText(f: File, expect: String): Made {
        val back = f.readText()
        return if (expect.isNotBlank() && !back.contains(expect.take(40).trim())) Made(f, "", "the saved file does not contain the text that was written") else Made(f, "saved, ${Workspace.human(f.length())}, read back and compared", null)
    }

    private fun verifyRows(f: File, n: Int): Made {
        val back = Docs.csvRows(f.readText()).size
        return if (back != n) Made(f, "", "the CSV has $back rows but $n were written") else Made(f, "$n rows, read back", null)
    }

    private fun verifyPdf(f: File): Made {
        val n = Docs.pdfPages(f)
        if (n < 1) return Made(f, "", "the PDF cannot be opened after writing")
        val bmp = Docs.renderPage(f, 1, 300) ?: return Made(f, "", "the first PDF page cannot be rendered")
        bmp.recycle()
        return Made(f, "$n page(s), opened and rendered back", null)
    }

    private fun verifyDocx(f: File, b: List<Blk>): Made {
        val text = try { Docs.docx(f) } catch (e: Exception) { return Made(f, "", "the Word file cannot be opened after writing: ${e.message}") }
        val first = b.firstOrNull { it is Blk.H || it is Blk.P }?.let { if (it is Blk.H) it.text else (it as Blk.P).text }?.let { Md.plain(it) }?.take(25) ?: ""
        return if (first.isNotBlank() && !text.contains(first.trim())) Made(f, "", "the Word file does not contain its first paragraph when read back") else Made(f, "opened and read back (${text.length} characters)", null)
    }

    private fun verifyXlsx(f: File, sh: List<Docs.Sheet>): Made {
        val back = Docs.xlsxSheets(f)
        if (back.size != sh.size) return Made(f, "", "the workbook has ${back.size} sheets but ${sh.size} were written")
        for (i in sh.indices) if (back[i].rows.size != sh[i].rows.size) return Made(f, "", "sheet ${sh[i].name} has ${back[i].rows.size} rows after writing but ${sh[i].rows.size} were expected")
        return Made(f, "${sh.size} sheet(s), ${sh.sumOf { it.rows.size }} rows, read back", null)
    }

    // ---------------- tables from text ----------------

    private fun csvOut(rows: List<List<String>>): String = rows.joinToString("\n") { r -> r.joinToString(",") { v -> if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v } }

    /** Accepts a markdown table, CSV text or a JSON array of objects. */
    fun rowsFrom(content: String): List<List<String>> {
        val t = content.trim()
        if (t.startsWith("[") || t.startsWith("{")) {
            val tmp = File.createTempFile("tbl", ".json", Axiom.ctx.cacheDir)
            try { tmp.writeText(t); Docs.rows(tmp).takeIf { it.isNotEmpty() }?.let { return it } } finally { tmp.delete() }
        }
        val tb = Md.parse(t).filterIsInstance<Blk.Tbl>().firstOrNull()
        if (tb != null) return tb.rows.map { r -> r.map { Md.plain(it) } }
        return Docs.csvRows(t)
    }

    fun sheetsFrom(content: String): List<Docs.Sheet> {
        val t = content.trim()
        if (!t.startsWith("[") && !t.startsWith("{")) {
            val blocks = Md.parse(t)
            val out = ArrayList<Docs.Sheet>(); var name = ""
            for (b in blocks) {
                if (b is Blk.H) name = Md.plain(b.text)
                if (b is Blk.Tbl) out.add(Docs.Sheet(sheetName(name.ifBlank { "Sheet${out.size + 1}" }, out.map { it.name }), b.rows.map { r -> r.map { Md.plain(it) } }))
            }
            if (out.isNotEmpty()) return out
        }
        val rows = rowsFrom(t)
        if (rows.isEmpty()) throw IllegalArgumentException("no table found in the content (give a markdown table, CSV text or a JSON list)")
        return listOf(Docs.Sheet("Sheet1", rows))
    }

    private fun sheetName(n: String, used: List<String>): String {
        var s = n.replace(Regex("[\\[\\]:*?/\\\\]"), " ").trim().take(31).ifBlank { "Sheet" }
        var k = 2; val base = s
        while (used.any { it.equals(s, true) }) { s = base.take(28) + " " + k; k++ }
        return s
    }

    // ---------------- HTML ----------------

    private fun esc(s: String) = Docs.xmlEscape(s)

    private fun inlineHtml(t: String) = Md.runs(t).joinToString("") { r ->
        var x = esc(r.text); if (r.code) x = "<code>$x</code>"; if (r.bold) x = "<strong>$x</strong>"; if (r.italic) x = "<em>$x</em>"; x
    }

    fun html(b: List<Blk>, title: String): String {
        val sb = StringBuilder("<!doctype html><html dir=\"auto\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>")
        sb.append(esc(title.ifBlank { "Document" })).append("</title><style>body{font-family:system-ui,sans-serif;max-width:820px;margin:2rem auto;padding:0 1rem;line-height:1.65;color:#1b1b1f}")
        sb.append("table{border-collapse:collapse;width:100%;margin:1rem 0}td,th{border:1px solid #c9c9d0;padding:.45rem .6rem;text-align:start}th{background:#eceef4}")
        sb.append("pre{background:#f3f3f6;padding:.8rem;overflow:auto;border-radius:6px}code{background:#f3f3f6;padding:0 .25em;border-radius:4px}blockquote{border-inline-start:4px solid #b9bfd6;margin:1rem 0;padding:.2rem 1rem;color:#444}")
        sb.append("</style></head><body>\n")
        var list: String? = null
        for (x in b) {
            if (x !is Blk.Li && list != null) { sb.append("</$list>\n"); list = null }
            when (x) {
                is Blk.H -> sb.append("<h${x.level}>").append(inlineHtml(x.text)).append("</h${x.level}>\n")
                is Blk.P -> sb.append("<p>").append(inlineHtml(x.text)).append("</p>\n")
                is Blk.Li -> { val tag = if (x.ordered) "ol" else "ul"; if (list != tag) { if (list != null) sb.append("</$list>"); sb.append("<$tag>"); list = tag }; sb.append("<li>").append(inlineHtml(x.text)).append("</li>\n") }
                is Blk.Tbl -> { sb.append("<table>"); x.rows.forEachIndexed { i, r -> sb.append("<tr>"); r.forEach { c -> sb.append(if (i == 0) "<th>" else "<td>").append(inlineHtml(c)).append(if (i == 0) "</th>" else "</td>") }; sb.append("</tr>\n") }; sb.append("</table>\n") }
                is Blk.Code -> sb.append("<pre>").append(esc(x.text)).append("</pre>\n")
                is Blk.Quote -> sb.append("<blockquote>").append(inlineHtml(x.text)).append("</blockquote>\n")
                Blk.Hr -> sb.append("<hr>\n")
            }
        }
        if (list != null) sb.append("</$list>")
        return sb.append("</body></html>").toString()
    }

    // ---------------- PDF ----------------

    private const val PW = 595; private const val PH = 842; private const val MX = 50f; private const val MT = 54f; private const val MB = 62f

    private class PdfCtx(val doc: PdfDocument, val title: String) {
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var n = 0
        var y = MT
        val width = PW - 2 * MX
        fun newPage() {
            finish()
            n++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, n).create())
            canvas = page!!.canvas
            y = MT
        }
        fun finish() {
            val p = page ?: return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.GRAY }
            val label = (if (title.isNotBlank()) title.take(60) + "  ·  " else "") + n
            canvas!!.drawText(label, (PW - paint.measureText(label)) / 2f, PH - 28f, paint)
            doc.finishPage(p); page = null
        }
        fun bottom() = PH - MB
    }

    private fun layout(text: CharSequence, paint: TextPaint, w: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.22f).setIncludePad(false).build()

    private fun span(t: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (r in Md.runs(t)) {
            val s = sb.length; sb.append(r.text)
            if (r.bold || r.italic) sb.setSpan(StyleSpan(if (r.bold && r.italic) Typeface.BOLD_ITALIC else if (r.bold) Typeface.BOLD else Typeface.ITALIC), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.code) sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** Draws a layout line by line so long paragraphs flow onto the next page. */
    private fun drawFlow(c: PdfCtx, l: StaticLayout, x: Float, bg: Int = 0) {
        for (i in 0 until l.lineCount) {
            val top = l.getLineTop(i); val bot = l.getLineBottom(i); val lh = (bot - top).toFloat()
            if (c.page == null || c.y + lh > c.bottom()) c.newPage()
            val cv = c.canvas!!
            if (bg != 0) cv.drawRect(x - 4, c.y, x + l.width + 4, c.y + lh, Paint().apply { color = bg })
            cv.save(); cv.translate(x, c.y - top); cv.clipRect(0f, top.toFloat(), l.width.toFloat(), bot.toFloat()); l.draw(cv); cv.restore()
            c.y += lh
        }
    }

    fun pdf(f: File, blocks: List<Blk>, title: String) {
        val doc = PdfDocument()
        val c = PdfCtx(doc, title)
        c.newPage()
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(27, 27, 31) }
        var orderedN = 0
        try {
            for (b in blocks) {
                if (b !is Blk.Li) orderedN = 0
                when (b) {
                    is Blk.H -> {
                        val p = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD; textSize = when (b.level) { 1 -> 22f; 2 -> 17f; 3 -> 14f; else -> 12f }; color = if (b.level <= 2) Color.rgb(24, 40, 96) else body.color }
                        c.y += if (b.level <= 2) 10f else 6f
                        if (c.y + p.textSize * 2.5f > c.bottom()) c.newPage()
                        drawFlow(c, layout(Md.plain(b.text), p, c.width.toInt()), MX)
                        if (b.level == 1) { c.canvas!!.drawLine(MX, c.y + 2, MX + c.width, c.y + 2, Paint().apply { color = Color.rgb(200, 204, 220); strokeWidth = 1f }); c.y += 6f }
                        c.y += 4f
                    }
                    is Blk.P -> { drawFlow(c, layout(span(b.text), body, c.width.toInt()), MX); c.y += 7f }
                    is Blk.Li -> {
                        if (b.ordered) orderedN++ else orderedN = 0
                        val ind = 16f + 16f * b.depth
                        val mark = if (b.ordered) "$orderedN. " else if (b.depth == 0) "• " else "– "
                        val sp = SpannableStringBuilder(mark).append(span(b.text))
                        drawFlow(c, layout(sp, body, (c.width - ind).toInt()), MX + ind); c.y += 3f
                    }
                    is Blk.Code -> {
                        val p = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 9.5f }
                        c.y += 3f
                        drawFlow(c, layout(b.text, p, (c.width - 8).toInt()), MX + 4, Color.rgb(242, 242, 246)); c.y += 8f
                    }
                    is Blk.Quote -> {
                        val p = TextPaint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC); color = Color.DKGRAY }
                        val startY = c.y; val startPage = c.n
                        drawFlow(c, layout(span(b.text), p, (c.width - 18).toInt()), MX + 18)
                        if (c.n == startPage) c.canvas!!.drawRect(MX + 4, startY, MX + 7, c.y, Paint().apply { color = Color.rgb(185, 191, 214) })
                        c.y += 7f
                    }
                    Blk.Hr -> { c.y += 4f; c.canvas!!.drawLine(MX, c.y, MX + c.width, c.y, Paint().apply { color = Color.LTGRAY }); c.y += 8f }
                    is Blk.Tbl -> pdfTable(c, b, body)
                }
            }
            c.finish()
            FileOutputStream(f).use { doc.writeTo(it) }
        } finally { doc.close() }
    }

    private fun pdfTable(c: PdfCtx, t: Blk.Tbl, body: TextPaint) {
        val cols = t.rows.maxOfOrNull { it.size } ?: return
        val cw = c.width / cols
        val line = Paint().apply { color = Color.rgb(170, 175, 195); style = Paint.Style.STROKE; strokeWidth = 0.7f }
        c.y += 4f
        t.rows.forEachIndexed { ri, row ->
            val p = TextPaint(body).apply { textSize = 9.5f; if (ri == 0) typeface = Typeface.DEFAULT_BOLD }
            val ls = (0 until cols).map { ci -> layout(span(row.getOrElse(ci) { "" }), p, (cw - 8).toInt().coerceAtLeast(20)) }
            val h = (ls.maxOf { it.height } + 8).toFloat()
            if (c.y + minOf(h, 120f) > c.bottom()) c.newPage()
            val cv = c.canvas!!
            if (ri == 0) cv.drawRect(MX, c.y, MX + c.width, c.y + h, Paint().apply { color = Color.rgb(232, 235, 245) })
            for (ci in 0 until cols) {
                val x = MX + ci * cw
                cv.drawRect(x, c.y, x + cw, c.y + h, line)
                cv.save(); cv.translate(x + 4, c.y + 4); cv.clipRect(0f, 0f, cw - 8, h - 6); ls[ci].draw(cv); cv.restore()
            }
            c.y += h
        }
        c.y += 8f
    }

    // ---------------- DOCX ----------------

    private fun zipOut(f: File, parts: List<Pair<String, String>>) {
        ZipOutputStream(FileOutputStream(f)).use { z ->
            for ((n, t) in parts) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
    }

    private fun wRuns(t: String, extra: String = "", force: Boolean = false): String {
        val sb = StringBuilder()
        for (r in Md.runs(t)) {
            if (r.text.isEmpty()) continue
            val rtl = force || Md.isRtl(r.text)
            val pr = (if (r.bold) "<w:b/><w:bCs/>" else "") + (if (r.italic) "<w:i/><w:iCs/>" else "") +
                (if (r.code) "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F6\"/>" else "") + extra + (if (rtl) "<w:rtl/>" else "")
            sb.append("<w:r>").append(if (pr.isEmpty()) "" else "<w:rPr>$pr</w:rPr>").append("<w:t xml:space=\"preserve\">").append(Docs.xmlEscape(r.text)).append("</w:t></w:r>")
        }
        return sb.toString()
    }

    fun docx(f: File, blocks: List<Blk>, title: String) {
        val body = StringBuilder()
        var nums = 3
        val orderedNums = ArrayList<Int>()
        var curOrdered = 0
        for (b in blocks) {
            if (b !is Blk.Li) curOrdered = 0
            when (b) {
                is Blk.H -> body.append("<w:p><w:pPr><w:pStyle w:val=\"").append(if (b.level == 1 && b === blocks.first()) "Title" else "Heading${b.level.coerceIn(1, 3)}").append("\"/>").append(if (Md.isRtl(b.text)) "<w:bidi/>" else "").append("</w:pPr>").append(wRuns(b.text)).append("</w:p>")
                is Blk.P -> body.append("<w:p><w:pPr>").append(if (Md.isRtl(b.text)) "<w:bidi/>" else "").append("<w:spacing w:after=\"120\"/></w:pPr>").append(wRuns(b.text)).append("</w:p>")
                is Blk.Li -> {
                    if (b.ordered && curOrdered == 0) { curOrdered = nums; orderedNums.add(nums); nums++ }
                    val id = if (b.ordered) curOrdered else 1
                    body.append("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"${b.depth}\"/><w:numId w:val=\"$id\"/></w:numPr>").append(if (Md.isRtl(b.text)) "<w:bidi/>" else "").append("<w:spacing w:after=\"60\"/></w:pPr>").append(wRuns(b.text)).append("</w:p>")
                }
                is Blk.Code -> for (ln in b.text.split("\n")) body.append("<w:p><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F6\"/><w:spacing w:after=\"0\"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"19\"/></w:rPr><w:t xml:space=\"preserve\">").append(Docs.xmlEscape(ln)).append("</w:t></w:r></w:p>")
                is Blk.Quote -> body.append("<w:p><w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"B9BFD6\"/></w:pBdr><w:ind w:left=\"360\"/>").append(if (Md.isRtl(b.text)) "<w:bidi/>" else "").append("</w:pPr>").append(wRuns(b.text, "<w:i/><w:color w:val=\"444444\"/>")).append("</w:p>")
                Blk.Hr -> body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"BBBBBB\"/></w:pBdr></w:pPr></w:p>")
                is Blk.Tbl -> {
                    val cols = b.rows.maxOfOrNull { it.size } ?: 1
                    val rtl = b.rows.any { r -> r.any { Md.isRtl(it) } }
                    val w = 9400 / cols
                    body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/>").append(if (rtl) "<w:bidiVisual/>" else "").append("</w:tblPr><w:tblGrid>")
                    repeat(cols) { body.append("<w:gridCol w:w=\"$w\"/>") }
                    body.append("</w:tblGrid>")
                    b.rows.forEachIndexed { ri, r ->
                        body.append("<w:tr>").append(if (ri == 0) "<w:trPr><w:tblHeader/></w:trPr>" else "")
                        for (ci in 0 until cols) {
                            val v = r.getOrElse(ci) { "" }
                            body.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>").append(if (ri == 0) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"E8EBF5\"/>" else "").append("</w:tcPr><w:p><w:pPr>").append(if (Md.isRtl(v)) "<w:bidi/>" else "").append("<w:spacing w:after=\"0\"/></w:pPr>").append(wRuns(v, if (ri == 0) "<w:b/><w:bCs/>" else "")).append("</w:p></w:tc>")
                        }
                        body.append("</w:tr>")
                    }
                    body.append("</w:tbl><w:p/>")
                }
            }
        }
        val ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document $ns><w:body>$body" +
            "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rId3\"/><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        fun lvl(i: Int, fmt: String, txt: String) = "<w:lvl w:ilvl=\"$i\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"$txt\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 + 360 * i}\" w:hanging=\"360\"/></w:pPr></w:lvl>"
        val numbering = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:numbering $ns>" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" + lvl(0, "bullet", "•") + lvl(1, "bullet", "–") + lvl(2, "bullet", "◦") + "</w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" + lvl(0, "decimal", "%1.") + lvl(1, "lowerLetter", "%2.") + lvl(2, "lowerRoman", "%3.") + "</w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            orderedNums.joinToString("") { "<w:num w:numId=\"$it\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>" } + "</w:numbering>"
        fun hs(id: String, name: String, sz: Int, color: String, before: Int) = "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"$before\" w:after=\"120\"/></w:pPr><w:rPr><w:b/><w:bCs/><w:color w:val=\"$color\"/><w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr></w:style>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles $ns><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Arial\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"24\"/><w:lang w:val=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            hs("Title", "Title", 44, "182860", 0) + hs("Heading1", "heading 1", 34, "182860", 360) + hs("Heading2", "heading 2", 28, "182860", 240) + hs("Heading3", "heading 3", 24, "333333", 200) +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:tblPr><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"AAAFC3\"/>" } + "</w:tblBorders><w:tblCellMar><w:top w:w=\"50\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"50\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style></w:styles>"
        val footer = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:ftr $ns><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"17\"/><w:color w:val=\"888888\"/></w:rPr><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:rPr><w:sz w:val=\"17\"/><w:color w:val=\"888888\"/></w:rPr><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r><w:r><w:rPr><w:sz w:val=\"17\"/><w:color w:val=\"888888\"/></w:rPr><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:rPr><w:sz w:val=\"17\"/><w:color w:val=\"888888\"/></w:rPr><w:t>1</w:t></w:r><w:r><w:rPr><w:sz w:val=\"17\"/><w:color w:val=\"888888\"/></w:rPr><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>"
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        zipOut(f, listOf(
            "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/><Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" +
                "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>",
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
            "word/_rels/document.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/></Relationships>",
            "word/document.xml" to document, "word/styles.xml" to styles, "word/numbering.xml" to numbering, "word/footer1.xml" to footer,
            "docProps/core.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${Docs.xmlEscape(title)}</dc:title><dc:creator>AXIOM</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created></cp:coreProperties>"
        ))
    }

    // ---------------- XLSX ----------------

    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }

    private val numRx = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$")

    fun xlsx(f: File, sheets: List<Docs.Sheet>) {
        val parts = ArrayList<Pair<String, String>>()
        sheets.forEachIndexed { si, s ->
            val ncols = s.rows.maxOfOrNull { it.size } ?: 1
            val widths = (0 until ncols).map { ci -> (s.rows.take(200).maxOfOrNull { r -> r.getOrElse(ci) { "" }.lines().maxOfOrNull { it.length } ?: 0 } ?: 8).coerceIn(8, 60) + 2 }
            val rtl = s.rows.take(5).any { r -> r.any { Md.isRtl(it) } }
            val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            sb.append("<sheetViews><sheetView workbookViewId=\"0\"").append(if (rtl) " rightToLeft=\"1\"" else "").append(">")
            if (s.rows.size > 1) sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            sb.append("</sheetView></sheetViews><cols>")
            widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
            sb.append("</cols><sheetData>")
            s.rows.forEachIndexed { ri, r ->
                sb.append("<row r=\"${ri + 1}\">")
                r.forEachIndexed { ci, v ->
                    if (v.isEmpty()) return@forEachIndexed
                    val ref = colName(ci) + (ri + 1)
                    val st = if (ri == 0 && s.rows.size > 1) " s=\"1\"" else ""
                    when {
                        v.startsWith("=") && v.length > 1 -> sb.append("<c r=\"$ref\"$st><f>").append(Docs.xmlEscape(v.substring(1))).append("</f></c>")
                        numRx.matches(v) && v.length <= 15 && !(ri == 0 && s.rows.size > 1) -> sb.append("<c r=\"$ref\"$st><v>$v</v></c>")
                        else -> sb.append("<c r=\"$ref\"$st t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(Docs.xmlEscape(v)).append("</t></is></c>")
                    }
                }
                sb.append("</row>")
            }
            sb.append("</sheetData>")
            if (s.rows.size > 1) sb.append("<autoFilter ref=\"A1:${colName(ncols - 1)}${s.rows.size}\"/>")
            sb.append("</worksheet>")
            parts.add("xl/worksheets/sheet${si + 1}.xml" to sb.toString())
        }
        val wb = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
            sheets.mapIndexed { i, s -> "<sheet name=\"${Docs.xmlEscape(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets><calcPr fullCalcOnLoad=\"1\"/></workbook>"
        val rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8EBF5\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs></styleSheet>"
        val ct = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>"
        zipOut(f, listOf(
            "[Content_Types].xml" to ct,
            "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
            "xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to rels, "xl/styles.xml" to styles
        ) + parts)
    }

    // ---------------- templates ----------------

    class Template(val key: String, val title: String, val format: String, val structure: String)

    val builtin: List<Template> = listOf(
        Template("research_report", "Research report", "docx", "Title; Executive summary (5 lines); Background; Method and sources; Findings (with numbered sections, a table where numbers compare); Analysis; Risks and limits; Conclusions and recommendations; Sources list with links."),
        Template("business_plan", "Business plan", "docx", "Title; Summary; Problem; Solution; Market (size, segments, competitors in a table); Business model and pricing; Operations; Marketing and sales; Team; Financial projection (a table by month or year, stating every assumption); Risks and mitigation; Roadmap with dates; Funding needs."),
        Template("cv", "CV / resume", "docx", "Name and contact line; Professional summary (3 lines); Experience (role, company, dates, 3-4 achievement bullets with numbers); Education; Skills; Languages; Projects. Use only facts the user gave; mark anything missing as [to fill in]."),
        Template("cover_letter", "Cover letter", "docx", "Date; recipient; subject line; opening that names the role; two short paragraphs linking the user's real experience to the need; closing with a clear next step. One page."),
        Template("budget_sheet", "Budget sheet", "xlsx", "A markdown table: Category | Item | Planned | Actual | Difference (as a formula like =D2-C2) with a TOTAL row using SUM formulas. A second table under a '## Summary' heading with totals by category."),
        Template("checklist", "Checklist", "pdf", "Title; short intro line; grouped checklist items written as '- [ ] item'; a final 'Before you finish' group."),
        Template("srs", "Software requirements (SRS)", "docx", "Title; Purpose and scope; Users; Functional requirements (numbered FR-1...); Non-functional requirements (performance, security, offline, privacy); Data model; Interfaces; Constraints; Acceptance criteria per requirement; Out of scope; Open questions."),
        Template("comparison", "Comparison", "docx", "Title; Goal of the comparison; Criteria and why they matter; Comparison table (options as columns); Per-option strengths and weaknesses; Recommendation with the reason and when another option wins."),
        Template("user_guide", "User guide", "pdf", "Title; What it is; Before you start; Step-by-step tasks (numbered, one action per step); Troubleshooting table (symptom | cause | fix); FAQ; Where to get help."),
        Template("meeting_minutes", "Meeting minutes", "docx", "Title; Date, place, attendees; Agenda; Discussion per item; Decisions (numbered); Action items table (task | owner | due date); Next meeting."),
        Template("proposal", "Proposal / offer", "docx", "Title; Context; Objectives; Scope of work; Deliverables and timeline (table); Price and terms; Why us; Next steps."),
        Template("report_weekly", "Periodic report", "pdf", "Title; Period; Key numbers (table); What was done; What is late or at risk; Decisions needed; Plan for next period."),
        Template("study_notes", "Study notes", "pdf", "Title; Key ideas (bullets); Detailed notes by topic; Definitions table; Worked examples; 8 self-test questions with answers at the end.")
    )

    fun template(key: String): Template? {
        val k = key.lowercase().trim().replace(' ', '_')
        Store.get().one("SELECT body FROM templates WHERE name = ?", k)?.get("body")?.let { b ->
            return try { val o = org.json.JSONObject(b); Template(k, o.optString("title", k), o.optString("format", "docx"), o.optString("structure", "")) } catch (e: Exception) { null }
        }
        return builtin.firstOrNull { it.key == k } ?: builtin.firstOrNull { k.contains(it.key) || it.title.lowercase().contains(k) }
    }
}
