package com.jarvis.assistant

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Data analysis done by code, never by the language model: every number comes from a calculation over the real rows.
 * The model only chooses what to calculate (data_ask) and then explains a result table it is not allowed to change.
 */
object DataTools {
    class Table(val header: List<String>, val rows: List<List<String>>) {
        fun col(name: String): Int {
            val n = name.trim().trim('"', '\'', '`').lowercase()
            header.indexOfFirst { it.trim().lowercase() == n }.let { if (it >= 0) return it }
            header.indexOfFirst { Knowledge.norm(it).trim() == Knowledge.norm(n) }.let { if (it >= 0) return it }
            header.indexOfFirst { it.lowercase().contains(n) }.let { if (it >= 0) return it }
            n.removePrefix("col").toIntOrNull()?.let { if (it in 1..header.size) return it - 1 }
            return -1
        }
        fun cell(r: List<String>, i: Int) = r.getOrElse(i) { "" }.trim()
    }

    fun load(f: File, sheet: Int = 1): Table? {
        val rows = Docs.rows(f, sheet).filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return null
        val width = rows.take(50).maxOf { it.size }
        val head = (0 until width).map { i -> rows[0].getOrElse(i) { "" }.trim().ifBlank { "col${i + 1}" } }
        val dup = HashMap<String, Int>()
        val header = head.map { h -> val k = (dup[h] ?: 0) + 1; dup[h] = k; if (k > 1) "$h $k" else h }
        return Table(header, rows.drop(1).map { r -> (0 until width).map { r.getOrElse(it) { "" } } })
    }

    // ---------- values ----------

    private val arabicDigits = mapOf('٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4', '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9', '٫' to '.', '٬' to ',')

    fun num(raw: String): Double? {
        var t = raw.trim()
        if (t.isEmpty() || t.length > 40) return null
        t = t.map { arabicDigits[it] ?: it }.joinToString("")
        var neg = false
        if (t.startsWith("(") && t.endsWith(")")) { neg = true; t = t.substring(1, t.length - 1) }
        t = t.replace(Regex("(?i)(mad|dh|dhs|usd|eur|€|\\$|£|%|د\\.م\\.?|درهم)"), "").replace(" ", "").replace(" ", "")
        if (t.isEmpty() || !t.any { it.isDigit() }) return null
        if (t.startsWith("-")) { neg = !neg; t = t.substring(1) } else if (t.startsWith("+")) t = t.substring(1)
        val lastC = t.lastIndexOf(','); val lastD = t.lastIndexOf('.')
        t = when {
            lastC >= 0 && lastD >= 0 -> if (lastC > lastD) t.replace(".", "").replace(',', '.') else t.replace(",", "")
            lastC >= 0 -> if (Regex("^\\d{1,3}(,\\d{3})+$").matches(t)) t.replace(",", "") else t.replace(',', '.')
            else -> t
        }
        val v = t.toDoubleOrNull() ?: return null
        return if (neg) -v else v
    }

    private val dateRx = Regex("^(\\d{1,4})[-/.](\\d{1,2})[-/.](\\d{1,4})(?:[ T].*)?$")
    private val ymRx = Regex("^(\\d{4})-(\\d{2})$")

    fun date(raw: String, dmy: Boolean = true): LocalDate? {
        val t = raw.trim().map { arabicDigits[it] ?: it }.joinToString("")
        ymRx.find(t)?.let { return try { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), 1) } catch (e: Exception) { null } }
        val m = dateRx.find(t) ?: return null
        val a = m.groupValues[1]; val b = m.groupValues[2].toInt(); val c = m.groupValues[3]
        return try {
            if (a.length == 4) LocalDate.of(a.toInt(), b, c.toInt())
            else if (c.length == 4) { if (dmy) LocalDate.of(c.toInt(), b, a.toInt()) else LocalDate.of(c.toInt(), a.toInt(), b) }
            else null
        } catch (e: Exception) { null }
    }

    enum class Kind { NUM, DATE, TEXT }

    class ColInfo(val name: String, val kind: Kind, val dmy: Boolean, val filled: Int, val missing: Int)

    fun infer(t: Table): List<ColInfo> = t.header.indices.map { i ->
        val vals = t.rows.map { t.cell(it, i) }.filter { it.isNotEmpty() }
        val sample = vals.take(500)
        val missing = t.rows.size - vals.size
        if (sample.isEmpty()) return@map ColInfo(t.header[i], Kind.TEXT, true, 0, missing)
        val dates = sample.mapNotNull { dateRx.find(it.map { ch -> arabicDigits[ch] ?: ch }.joinToString("")) }
        val dmy = !(dates.any { it.groupValues[1].length < 4 && it.groupValues[2].toInt() > 12 || false }) || dates.any { it.groupValues[1].length < 4 && it.groupValues[1].toInt() > 12 }
        val dateShare = sample.count { date(it, dmy) != null }.toDouble() / sample.size
        val numShare = sample.count { num(it) != null }.toDouble() / sample.size
        val kind = when { dateShare >= 0.8 -> Kind.DATE; numShare >= 0.8 -> Kind.NUM; else -> Kind.TEXT }
        ColInfo(t.header[i], kind, dmy, vals.size, missing)
    }

    fun fmt(d: Double): String = if (d == floor(d) && abs(d) < 1e12) d.toLong().toString() else "%.4f".format(d).trimEnd('0').trimEnd('.')

    private fun median(v: List<Double>): Double { val s = v.sorted(); return if (s.isEmpty()) 0.0 else if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    // ---------- filters ----------

    private class Cond(val col: Int, val op: String, val value: String)

    private fun parseWhere(t: Table, where: String): Pair<List<List<Cond>>?, String?> {
        if (where.isBlank()) return emptyList<List<Cond>>() to null
        val ors = Regex("\\s+(?:or|أو)\\s+", RegexOption.IGNORE_CASE).split(where.trim())
        val out = ArrayList<List<Cond>>()
        for (o in ors) {
            val ands = Regex("\\s+(?:and|و)\\s+", RegexOption.IGNORE_CASE).split(o).map { it.trim() }.filter { it.isNotEmpty() }
            val cs = ArrayList<Cond>()
            for (p in ands) {
                val m = Regex("^(.+?)\\s*(>=|<=|!=|==|=|>|<|\\bcontains\\b|\\bstartswith\\b|\\bin\\b)\\s*(.+)$", RegexOption.IGNORE_CASE).find(p) ?: return null to "I cannot read the condition \"$p\". Use: column > value, column = value, column contains word (joined with 'and' / 'or')."
                val ci = t.col(m.groupValues[1])
                if (ci < 0) return null to "There is no column \"${m.groupValues[1].trim()}\". Columns: ${t.header.joinToString(", ")}."
                cs.add(Cond(ci, m.groupValues[2].lowercase(), m.groupValues[3].trim().trim('"', '\'')))
            }
            out.add(cs)
        }
        return out to null
    }

    private fun matches(t: Table, info: List<ColInfo>, r: List<String>, ors: List<List<Cond>>): Boolean {
        if (ors.isEmpty()) return true
        return ors.any { ands -> ands.all { c ->
            val cell = t.cell(r, c.col); val ci = info[c.col]
            when (c.op) {
                "contains" -> Knowledge.norm(cell).contains(Knowledge.norm(c.value))
                "startswith" -> Knowledge.norm(cell).startsWith(Knowledge.norm(c.value))
                "in" -> c.value.trim('[', ']', '(', ')').split(",", "|").map { Knowledge.norm(it.trim().trim('"', '\'')) }.contains(Knowledge.norm(cell))
                else -> {
                    val cmp: Int? = when {
                        ci.kind == Kind.DATE && date(c.value) != null -> date(cell, ci.dmy)?.compareTo(date(c.value, ci.dmy))
                        num(cell) != null && num(c.value) != null -> num(cell)!!.compareTo(num(c.value)!!)
                        else -> Knowledge.norm(cell).compareTo(Knowledge.norm(c.value))
                    }
                    if (cmp == null) false else when (c.op) { ">" -> cmp > 0; "<" -> cmp < 0; ">=" -> cmp >= 0; "<=" -> cmp <= 0; "!=" -> cmp != 0; else -> cmp == 0 }
                }
            }
        } }
    }

    // ---------- query ----------

    class QueryResult(val header: List<String>, val rows: List<List<String>>, val matched: Int, val total: Int)

    /** select / where / group_by / agg ("sum:amount,avg:price,count") / order ("col desc") / limit. Returns the result or an error text. */
    fun query(t: Table, select: String, where: String, groupBy: String, agg: String, order: String, limit: Int): Pair<QueryResult?, String?> {
        val info = infer(t)
        val (ors, err) = parseWhere(t, where)
        if (ors == null) return null to err
        val rows = t.rows.filter { matches(t, info, it, ors) }
        val gcols = groupBy.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { g -> t.col(g).also { if (it < 0) return null to "There is no column \"$g\". Columns: ${t.header.joinToString(", ")}." } }
        val aggs = agg.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        var header: List<String>; var out: List<List<String>>
        if (gcols.isNotEmpty() || aggs.isNotEmpty()) {
            val specs = aggs.ifEmpty { listOf("count") }.map { a ->
                val fn = a.substringBefore(':').trim().lowercase().let { when (it) { "average", "mean" -> "avg"; "total" -> "sum"; else -> it } }
                val cn = a.substringAfter(':', "").trim()
                val ci = if (cn.isEmpty()) -1 else t.col(cn)
                if (cn.isNotEmpty() && ci < 0) return null to "There is no column \"$cn\"."
                if (fn !in listOf("sum", "avg", "min", "max", "count", "median", "distinct")) return null to "Unknown calculation \"$fn\". Use sum, avg, min, max, median, count, distinct."
                if (fn in listOf("sum", "avg", "min", "max", "median") && (ci < 0 || info[ci].kind != Kind.NUM)) return null to "$fn needs a numeric column; \"$cn\" is not numeric."
                Triple(fn, ci, if (cn.isEmpty()) "count" else "$fn of ${t.header[ci]}")
            }
            val groups = LinkedHashMap<List<String>, MutableList<List<String>>>()
            for (r in rows) groups.getOrPut(gcols.map { t.cell(r, it) }) { ArrayList() }.add(r)
            header = gcols.map { t.header[it] } + specs.map { it.third }
            out = groups.map { (k, rs) -> k + specs.map { (fn, ci, _) ->
                val vals = if (ci >= 0) rs.mapNotNull { num(t.cell(it, ci)) } else emptyList()
                when (fn) {
                    "sum" -> fmt(vals.sum()); "avg" -> if (vals.isEmpty()) "" else fmt(vals.average()); "min" -> vals.minOrNull()?.let { fmt(it) } ?: ""; "max" -> vals.maxOrNull()?.let { fmt(it) } ?: ""
                    "median" -> if (vals.isEmpty()) "" else fmt(median(vals)); "distinct" -> rs.map { t.cell(it, ci) }.filter { it.isNotEmpty() }.toSet().size.toString(); else -> rs.size.toString()
                }
            } }
        } else {
            val sel = select.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "*" }.map { s -> t.col(s).also { if (it < 0) return null to "There is no column \"$s\"." } }.ifEmpty { t.header.indices.toList() }
            header = sel.map { t.header[it] }; out = rows.map { r -> sel.map { t.cell(r, it) } }
        }
        if (order.isNotBlank()) {
            val parts = order.trim().split(Regex("\\s+")); val desc = parts.last().lowercase().let { it == "desc" || it == "descending" }
            val name = (if (parts.last().lowercase() in listOf("asc", "desc", "ascending", "descending")) parts.dropLast(1) else parts).joinToString(" ")
            val oi = header.indexOfFirst { it.equals(name, true) }.let { if (it >= 0) it else header.indexOfFirst { h -> h.lowercase().contains(name.lowercase()) } }
            if (oi < 0) return null to "Cannot order by \"$name\"; the result has: ${header.joinToString(", ")}."
            out = out.sortedWith { a, b -> val x = num(a[oi]); val y = num(b[oi]); val c = if (x != null && y != null) x.compareTo(y) else a[oi].compareTo(b[oi]); if (desc) -c else c }
        }
        return QueryResult(header, if (limit > 0) out.take(limit) else out, rows.size, t.rows.size) to null
    }

    fun resultTable(q: QueryResult, show: Int = 40): String = DocTools.mdTable(listOf(q.header) + q.rows.take(show)) + (if (q.rows.size > show) "\n[${q.rows.size - show} more rows]" else "")

    // ---------- profile ----------

    fun profile(f: File, t: Table): String {
        val info = infer(t)
        val sb = StringBuilder("${f.name}: ${t.rows.size} rows x ${t.header.size} columns\n")
        val dupRows = t.rows.size - t.rows.map { it.joinToString("\u0001") { c -> c.trim().lowercase() } }.toSet().size
        val emptyRows = t.rows.count { r -> r.all { it.isBlank() } }
        sb.append("Duplicate rows: $dupRows. Empty rows: $emptyRows.\n\n")
        info.forEachIndexed { i, ci ->
            val vals = t.rows.map { t.cell(it, i) }.filter { it.isNotEmpty() }
            sb.append("- ${ci.name} [${ci.kind.name.lowercase()}] filled ${ci.filled}, missing ${ci.missing}, distinct ${vals.toSet().size}")
            when (ci.kind) {
                Kind.NUM -> {
                    val v = vals.mapNotNull { num(it) }
                    if (v.isNotEmpty()) {
                        val s = v.sorted(); val q1 = s[s.size / 4]; val q3 = s[(s.size * 3) / 4]; val iqr = q3 - q1
                        val out = v.count { it < q1 - 1.5 * iqr || it > q3 + 1.5 * iqr }
                        sb.append(": min ${fmt(s.first())}, max ${fmt(s.last())}, mean ${fmt(v.average())}, median ${fmt(median(v))}, sum ${fmt(v.sum())}" + (if (out > 0) ", outliers $out" else "") + (if (v.size < vals.size) ", ${vals.size - v.size} cells are not numbers" else ""))
                    }
                }
                Kind.DATE -> { val d = vals.mapNotNull { date(it, ci.dmy) }.sorted(); if (d.isNotEmpty()) sb.append(": ${d.first()} to ${d.last()}" + (if (ci.dmy) "" else " (month/day order assumed)") + (if (d.size < vals.size) ", ${vals.size - d.size} cells are not dates" else "")) }
                Kind.TEXT -> sb.append(": top " + vals.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "\"${Axiom.clip(it.key, 30)}\" x${it.value}" })
            }
            sb.append('\n')
        }
        val bad = ArrayList<String>()
        info.forEachIndexed { i, ci -> if (ci.kind == Kind.NUM) { val raw = t.rows.map { t.cell(it, i) }.filter { it.isNotEmpty() && num(it) == null }.take(3); if (raw.isNotEmpty()) bad.add("${ci.name}: e.g. ${raw.joinToString(", ") { "\"$it\"" }}") } }
        if (bad.isNotEmpty()) sb.append("\nText inside numeric columns (will be ignored in sums): ${bad.joinToString("; ")}\n")
        return sb.toString().trim()
    }

    // ---------- trend ----------

    class Point(val label: String, val value: Double)

    fun series(t: Table, dateCol: Int, valCol: Int, freq: String, agg: String, where: String): Pair<List<Point>?, String?> {
        val info = infer(t)
        if (info[dateCol].kind != Kind.DATE) return null to "\"${t.header[dateCol]}\" does not contain dates I can read."
        if (info[valCol].kind != Kind.NUM && agg != "count") return null to "\"${t.header[valCol]}\" is not numeric."
        val (ors, err) = parseWhere(t, where); if (ors == null) return null to err
        val map = java.util.TreeMap<String, MutableList<Double>>()
        for (r in t.rows) {
            if (!matches(t, info, r, ors)) continue
            val d = date(t.cell(r, dateCol), info[dateCol].dmy) ?: continue
            val key = when (freq) {
                "day" -> d.toString()
                "week" -> { val w = d.with(java.time.DayOfWeek.MONDAY); w.toString() }
                "year" -> d.year.toString()
                "quarter" -> "${d.year}-Q${(d.monthValue - 1) / 3 + 1}"
                else -> "%04d-%02d".format(d.year, d.monthValue)
            }
            map.getOrPut(key) { ArrayList() }.add(if (agg == "count") 1.0 else num(t.cell(r, valCol)) ?: continue)
        }
        if (map.isEmpty()) return null to "No rows with a readable date and value."
        return map.map { (k, v) -> Point(k, when (agg) { "avg" -> v.average(); "count" -> v.size.toDouble(); "max" -> v.max(); "min" -> v.min(); else -> v.sum() }) } to null
    }

    fun trendReport(p: List<Point>, name: String, agg: String): String {
        val n = p.size
        val xs = p.indices.map { it.toDouble() }; val ys = p.map { it.value }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx).pow(2) }; val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val slope = if (sxx == 0.0) 0.0 else sxy / sxx; val icpt = my - slope * mx
        val ss = ys.sumOf { (it - my).pow(2) }; val sr = ys.indices.sumOf { (ys[it] - (icpt + slope * xs[it])).pow(2) }
        val r2 = if (ss == 0.0) 0.0 else 1 - sr / ss
        val sd = sqrt(ys.sumOf { (it - my).pow(2) } / max(1, n - 1))
        val sb = StringBuilder("$agg of $name by period, $n periods (${p.first().label} to ${p.last().label})\n")
        sb.append(p.takeLast(24).joinToString("\n") { "${it.label}: ${fmt(it.value)}" }).append('\n')
        val first = ys.first(); val last = ys.last()
        sb.append("\nFirst to last: ${fmt(first)} -> ${fmt(last)}" + (if (first != 0.0) " (${"%+.1f".format((last - first) / abs(first) * 100)}%)" else "") + "\n")
        if (n >= 2) sb.append("Direction: " + (if (abs(slope) < 0.02 * max(abs(my), 1e-9)) "flat" else if (slope > 0) "rising" else "falling") + " by about ${fmt(slope)} per period on average (fit quality R2 = ${"%.2f".format(r2)}).\n")
        if (n >= 4) {
            val best = p.maxByOrNull { it.value }!!; val worst = p.minByOrNull { it.value }!!
            sb.append("Highest: ${best.label} (${fmt(best.value)}). Lowest: ${worst.label} (${fmt(worst.value)}).\n")
            if (sd > 0) { val odd = p.filter { abs(it.value - my) / sd > 2.0 }; if (odd.isNotEmpty()) sb.append("Unusual periods (more than 2 standard deviations from average): ${odd.joinToString(", ") { it.label + " " + fmt(it.value) }}\n") }
            val ma = ys.windowed(3) { it.average() }; sb.append("3-period moving average, latest: ${fmt(ma.last())}\n")
        }
        if (n >= 6 && r2 >= 0.3) sb.append("Straight-line projection for the next 3 periods (a projection from past data, not a promise): " + (1..3).joinToString(", ") { fmt(icpt + slope * (n - 1 + it)) } + "\n")
        else if (n >= 2) sb.append("Too little or too irregular data for a reliable projection; I am not giving one.\n")
        return sb.toString().trim()
    }

    // ---------- charts ----------

    private val palette = intArrayOf(Color.rgb(0, 114, 178), Color.rgb(230, 159, 0), Color.rgb(0, 158, 115), Color.rgb(204, 121, 167), Color.rgb(86, 180, 233), Color.rgb(213, 94, 0), Color.rgb(120, 120, 120), Color.rgb(240, 228, 66))

    private fun niceStep(range: Double, ticks: Int): Double {
        if (range <= 0) return 1.0
        val raw = range / ticks; val mag = 10.0.pow(floor(Math.log10(raw))); val f = raw / mag
        return mag * (if (f < 1.5) 1.0 else if (f < 3) 2.0 else if (f < 7) 5.0 else 10.0)
    }

    class Series(val name: String, val values: List<Double>)

    fun chart(out: File, kind: String, title: String, labels: List<String>, series: List<Series>, xTitle: String = "", yTitle: String = ""): String? {
        if (labels.isEmpty() || series.isEmpty()) return "there is nothing to draw"
        val W = 1100; val H = 680
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
        val ink = Color.rgb(40, 42, 54); val grid = Color.rgb(225, 228, 238)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
        cv.drawText(title.take(70), 40f, 52f, tp)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 94, 110); textSize = 20f }
        val left = 110f; val right = W - 40f; val top = 100f; val bottom = H - 130f
        if (kind == "pie") {
            val vals = series[0].values.map { max(0.0, it) }; val total = vals.sum()
            if (total <= 0) return "all values are zero or negative"
            val order = vals.indices.sortedByDescending { vals[it] }
            val shown = order.take(7); val rest = order.drop(7).sumOf { vals[it] }
            val items = shown.map { labels[it] to vals[it] } + (if (rest > 0) listOf("Other" to rest) else emptyList())
            val box = RectF(60f, 110f, 60f + 460f, 110f + 460f); var a = -90f
            items.forEachIndexed { i, (l, v) -> val sw = (v / total * 360.0).toFloat(); cv.drawArc(box, a, sw, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[i % palette.size] }); a += sw
                val y = 140f + i * 52f
                cv.drawRect(580f, y - 22f, 606f, y + 4f, Paint().apply { color = palette[i % palette.size] })
                cv.drawText(Axiom.clip(l, 26) + "  " + "%.1f".format(v / total * 100) + "%", 618f, y, Paint(lp).apply { color = ink }) }
        } else {
            val all = series.flatMap { it.values }
            var lo = min(0.0, all.minOrNull() ?: 0.0); var hi = max(0.0, all.maxOrNull() ?: 1.0); if (kind == "line" || kind == "scatter") lo = all.minOrNull() ?: 0.0
            if (hi == lo) hi = lo + 1
            val step = niceStep(hi - lo, 6); lo = floor(lo / step) * step; hi = ceil(hi / step) * step
            fun yOf(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
            var g = lo
            while (g <= hi + step / 1000) { val y = yOf(g); cv.drawLine(left, y, right, y, Paint().apply { color = grid; strokeWidth = 1.5f }); cv.drawText(fmt(g), 12f, y + 7f, lp); g += step }
            cv.drawLine(left, bottom, right, bottom, Paint().apply { color = ink; strokeWidth = 2f })
            val n = labels.size; val slot = (right - left) / n
            fun xOf(i: Int) = left + slot * (i + 0.5f)
            val every = max(1, ceil(n / 14.0).toInt())
            for (i in 0 until n step every) { cv.save(); cv.rotate(-30f, xOf(i), bottom + 26f); cv.drawText(Axiom.clip(labels[i], 14), xOf(i) - 20f, bottom + 30f, lp); cv.restore() }
            if (kind == "bar") {
                val bw = slot * 0.75f / series.size
                series.forEachIndexed { si, s -> s.values.forEachIndexed { i, v ->
                    val x0 = xOf(i) - slot * 0.375f + bw * si
                    cv.drawRect(x0, min(yOf(v), yOf(0.0)), x0 + bw, max(yOf(v), yOf(0.0)), Paint().apply { color = palette[si % palette.size] })
                    if (n <= 16 && series.size == 1) cv.drawText(fmt(v), x0 - 2f, yOf(v) - 8f, Paint(lp).apply { textSize = 17f; color = ink })
                } }
            } else {
                series.forEachIndexed { si, s ->
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[si % palette.size]; strokeWidth = 4f; style = Paint.Style.STROKE }
                    if (kind == "line") { val path = Path(); s.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(xOf(i), yOf(v)) else path.lineTo(xOf(i), yOf(v)) }; cv.drawPath(path, p) }
                    s.values.forEachIndexed { i, v -> cv.drawCircle(xOf(i), yOf(v), if (n > 60) 3f else 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[si % palette.size] }) }
                }
            }
            if (series.size > 1) series.forEachIndexed { i, s -> cv.drawRect(left + i * 230f, 70f, left + i * 230f + 20f, 90f, Paint().apply { color = palette[i % palette.size] }); cv.drawText(Axiom.clip(s.name, 20), left + i * 230f + 28f, 88f, lp) }
            if (yTitle.isNotBlank()) cv.drawText(yTitle.take(40), 12f, 90f, Paint(lp).apply { textSize = 18f })
            if (xTitle.isNotBlank()) cv.drawText(xTitle.take(50), left, H - 24f, lp)
        }
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        val check = android.graphics.BitmapFactory.decodeFile(out.path)
        val okImg = check != null && check.width == W
        check?.recycle()
        return if (okImg) null else "the chart image could not be read back after saving"
    }

    private fun resolve(c: ToolCtx, p: String): File? = Workspace.readable(p, c.project)?.takeIf { it.isFile }

    fun register() {
        Tools.register(Tool("data_profile", "Understand a table file (csv, xlsx, json): rows, column types, missing values, duplicates, min/max/mean/median, outliers, date range. Calculated by code.",
            listOf(A("path", "file"), A("sheet", "sheet number for Excel", false, "int")), Risk.READ, 120_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table with a header row and data.")
                ok(Shield.wrap(f.name, profile(f, t), 12000).text, untrusted = true)
            }))

        Tools.register(Tool("data_query", "Filter, group and calculate on a table file. where: 'amount > 100 and city = Rabat'; group_by: 'city'; agg: 'sum:amount,avg:price,count'; order: 'sum of amount desc'. Exact calculation by code. save_as writes the result to csv/xlsx.",
            listOf(A("path", "file"), A("where", "filter", false), A("select", "columns to show (no grouping)", false), A("group_by", "columns to group by", false), A("agg", "calculations", false), A("order", "sort", false), A("limit", "max rows", false, "int"), A("sheet", "Excel sheet", false, "int"), A("save_as", "output file .csv or .xlsx", false)),
            Risk.LOCAL, 120_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table with a header row and data.")
                val (q, err) = query(t, a.optString("select", ""), a.optString("where", ""), a.optString("group_by", ""), a.optString("agg", ""), a.optString("order", ""), a.optInt("limit", 0))
                if (q == null) return@Tool fail(err ?: "the query failed")
                val files = ArrayList<String>()
                a.optString("save_as", "").takeIf { it.isNotBlank() }?.let { n ->
                    val o = Workspace.inside(c.project, n) ?: return@Tool fail("Cannot save there.")
                    val m = withContext(Dispatchers.IO) { DocMaker.make(o.parentFile!!, o.name, if (n.lowercase().endsWith("xlsx")) "xlsx" else "csv", "", DocTools.mdTable(listOf(q.header) + q.rows)) }
                    if (m.problem != null) return@Tool fail(m.problem); files.add(m.file.absolutePath)
                }
                ok("${q.matched} of ${q.total} rows matched.\n" + Shield.wrap(f.name, resultTable(q), 12000).text + (if (files.isNotEmpty()) "\nSaved: ${files[0]}" else ""), files, untrusted = true)
            }))

        Tools.register(Tool("data_pivot", "Pivot table: rows by columns with a calculation (sum, avg, count, min, max) of a value column.",
            listOf(A("path", "file"), A("rows", "column for the rows"), A("cols", "column for the columns", false), A("values", "numeric column (not needed for count)", false), A("agg", "sum, avg, count, min, max (default sum)", false), A("sheet", "Excel sheet", false, "int")),
            Risk.READ, 120_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table.")
                val ri = t.col(a.getString("rows")); val ci = if (a.optString("cols", "").isBlank()) -1 else t.col(a.getString("cols"))
                val agg = a.optString("agg", "sum").lowercase(); val vi = if (a.optString("values", "").isBlank()) -1 else t.col(a.getString("values"))
                if (ri < 0) return@Tool fail("There is no column \"${a.getString("rows")}\". Columns: ${t.header.joinToString(", ")}.")
                if (a.optString("cols", "").isNotBlank() && ci < 0) return@Tool fail("There is no column \"${a.getString("cols")}\".")
                if (agg != "count" && (vi < 0 || infer(t)[vi].kind != Kind.NUM)) return@Tool fail("$agg needs a numeric values column.")
                val cells = HashMap<Pair<String, String>, MutableList<Double>>()
                val rk = LinkedHashSet<String>(); val ck = LinkedHashSet<String>()
                for (r in t.rows) { val rr = t.cell(r, ri); val cc = if (ci >= 0) t.cell(r, ci) else "Total"; rk.add(rr); ck.add(cc); cells.getOrPut(rr to cc) { ArrayList() }.add(if (agg == "count") 1.0 else num(t.cell(r, vi)) ?: continue) }
                fun calc(v: List<Double>?) = if (v == null || v.isEmpty()) "" else fmt(when (agg) { "avg" -> v.average(); "count" -> v.size.toDouble(); "min" -> v.min(); "max" -> v.max(); else -> v.sum() })
                val head = listOf(t.header[ri] + (if (ci >= 0) " \\ " + t.header[ci] else "")) + ck.toList() + (if (ci >= 0 && agg in listOf("sum", "count")) listOf("Total") else emptyList())
                val body = rk.map { rr -> listOf(rr) + ck.map { cc -> calc(cells[rr to cc]) } + (if (ci >= 0 && agg in listOf("sum", "count")) listOf(calc(ck.flatMap { cc -> cells[rr to cc] ?: emptyList() })) else emptyList()) }
                ok(Shield.wrap(f.name, DocTools.mdTable(listOf(head) + body.take(60)), 12000).text, untrusted = true)
            }))

        Tools.register(Tool("data_compare", "Compare two periods in a table (this month vs last, Q1 vs Q2): totals, change in value and in percent, and which groups drove the change.",
            listOf(A("path", "file"), A("date_col", "date column"), A("value_col", "numeric column"), A("period_a", "like 2026-01..2026-03 or 2026-01"), A("period_b", "like 2026-04..2026-06"), A("group_by", "column to explain the change by", false), A("agg", "sum or avg, default sum", false), A("sheet", "Excel sheet", false, "int")),
            Risk.READ, 120_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table.")
                val info = infer(t); val di = t.col(a.getString("date_col")); val vi = t.col(a.getString("value_col"))
                if (di < 0 || info[di].kind != Kind.DATE) return@Tool fail("\"${a.getString("date_col")}\" is not a date column. Columns: ${t.header.joinToString(", ")}.")
                if (vi < 0 || info[vi].kind != Kind.NUM) return@Tool fail("\"${a.getString("value_col")}\" is not a numeric column.")
                fun range(s: String): Pair<LocalDate, LocalDate>? { val p = s.split("..", "to", "→").map { it.trim() }; val x = date(p[0]) ?: return null; val y = if (p.size > 1) date(p[1]) ?: return null else x
                    val endExclusive = if (p.getOrElse(1) { p[0] }.matches(Regex("\\d{4}-\\d{2}"))) y.plusMonths(1) else y.plusDays(1); return x to endExclusive }
                val ra = range(a.getString("period_a")) ?: return@Tool fail("I cannot read period_a. Write it like 2026-01..2026-03 or 2026-01-01..2026-01-31.")
                val rb = range(a.getString("period_b")) ?: return@Tool fail("I cannot read period_b.")
                val gi = if (a.optString("group_by", "").isBlank()) -1 else t.col(a.getString("group_by"))
                val avg = a.optString("agg", "sum") == "avg"
                fun pick(r: Pair<LocalDate, LocalDate>) = t.rows.filter { row -> date(t.cell(row, di), info[di].dmy)?.let { !it.isBefore(r.first) && it.isBefore(r.second) } == true }
                val ra1 = pick(ra); val rb1 = pick(rb)
                fun tot(rs: List<List<String>>) = rs.mapNotNull { num(t.cell(it, vi)) }.let { if (avg) (if (it.isEmpty()) 0.0 else it.average()) else it.sum() }
                val x = tot(ra1); val y = tot(rb1)
                val sb = StringBuilder("${a.getString("value_col")} (${if (avg) "average" else "total"}):\nA ${a.getString("period_a")}: ${fmt(x)} (${ra1.size} rows)\nB ${a.getString("period_b")}: ${fmt(y)} (${rb1.size} rows)\nChange: ${fmt(y - x)}" + (if (x != 0.0) " (${"%+.1f".format((y - x) / abs(x) * 100)}%)" else " (A is zero, no percentage)") + "\n")
                if (ra1.isEmpty() || rb1.isEmpty()) sb.append("Warning: one period has no rows, so the comparison is not meaningful.\n")
                if (gi >= 0) {
                    val keys = (ra1 + rb1).map { t.cell(it, gi) }.distinct()
                    val rowsOut = keys.map { k -> val ga = tot(ra1.filter { t.cell(it, gi) == k }); val gb = tot(rb1.filter { t.cell(it, gi) == k }); Triple(k, ga, gb) }.sortedByDescending { abs(it.third - it.second) }.take(15)
                    sb.append("\nBy ${t.header[gi]} (largest changes first):\n").append(DocTools.mdTable(listOf(listOf(t.header[gi], "A", "B", "Change", "%")) + rowsOut.map { (k, ga, gb) -> listOf(k, fmt(ga), fmt(gb), fmt(gb - ga), if (ga != 0.0) "%+.1f".format((gb - ga) / abs(ga) * 100) else "n/a") }))
                }
                ok(Shield.wrap(f.name, sb.toString(), 9000).text, untrusted = true)
            }))

        Tools.register(Tool("data_trend", "Trend of a value over time: period totals, direction and size of the change, highest and lowest periods, unusual periods, moving average, and a labelled straight-line projection when the data supports one.",
            listOf(A("path", "file"), A("date_col", "date column"), A("value_col", "numeric column (any column when agg=count)"), A("freq", "day, week, month, quarter or year (default month)", false), A("agg", "sum, avg, count, min, max (default sum)", false), A("where", "filter", false), A("sheet", "Excel sheet", false, "int")),
            Risk.READ, 120_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table.")
                val di = t.col(a.getString("date_col")); val vi = t.col(a.getString("value_col"))
                if (di < 0 || vi < 0) return@Tool fail("Column not found. Columns: ${t.header.joinToString(", ")}.")
                val agg = a.optString("agg", "sum")
                val (p, err) = series(t, di, vi, a.optString("freq", "month"), agg, a.optString("where", ""))
                if (p == null) return@Tool fail(err ?: "no series")
                ok(trendReport(p, t.header[vi], agg), untrusted = true)
            }))

        Tools.register(Tool("chart_make", "Draw a chart (bar, line, pie, scatter) from a table file or from given values and save it as a PNG. For files give x and y columns (y may list up to 3, comma separated) and optionally group/agg to summarise first.",
            listOf(A("kind", "bar, line, pie or scatter"), A("title", "chart title"), A("path", "table file", false), A("x", "x column", false), A("y", "y column(s)", false), A("agg", "sum, avg, count, min, max when x repeats", false), A("labels", "inline: comma separated labels", false), A("values", "inline: comma separated numbers", false), A("name", "output file name", false), A("sheet", "Excel sheet", false, "int")),
            Risk.LOCAL, 120_000L, group = "data",
            check = { _, _, r -> if (r.files.none { File(it).length() > 2000 }) "the image file is missing or empty" else null },
            run = { c, a ->
                val kind = a.getString("kind").lowercase().let { if (it in listOf("bar", "line", "pie", "scatter")) it else return@Tool fail("kind must be bar, line, pie or scatter.") }
                var labels: List<String>; var series: List<Series>
                if (a.optString("path", "").isNotBlank()) {
                    val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                    val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table.")
                    val xi = t.col(a.optString("x", "").ifBlank { t.header[0] }); val ys = a.optString("y", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3)
                    if (xi < 0) return@Tool fail("x column not found. Columns: ${t.header.joinToString(", ")}.")
                    val yi = (if (ys.isEmpty()) listOf(t.header.indices.firstOrNull { it != xi && infer(t)[it].kind == Kind.NUM } ?: -1) else ys.map { t.col(it) })
                    if (yi.any { it < 0 || infer(t)[it].kind != Kind.NUM }) return@Tool fail("y must be numeric column(s). Columns: ${t.header.joinToString(", ")}.")
                    val agg = a.optString("agg", "sum").lowercase()
                    val keys = LinkedHashSet<String>(); t.rows.forEach { keys.add(t.cell(it, xi)) }
                    val keyList = (if (kind == "scatter" || kind == "line" && infer(t)[xi].kind == Kind.DATE) keys.toList().sortedBy { k -> date(k)?.toEpochDay()?.toDouble() ?: num(k) ?: 0.0 } else keys.toList()).filter { it.isNotEmpty() }
                    series = yi.map { y -> Series(t.header[y], keyList.map { k -> val v = t.rows.filter { r -> t.cell(r, xi) == k }.mapNotNull { r -> num(t.cell(r, y)) }; when (agg) { "avg" -> if (v.isEmpty()) 0.0 else v.average(); "count" -> v.size.toDouble(); "min" -> v.minOrNull() ?: 0.0; "max" -> v.maxOrNull() ?: 0.0; else -> v.sum() } }) }
                    labels = keyList
                    if (kind == "bar" && labels.size > 20) { val top = labels.indices.sortedByDescending { series[0].values[it] }.take(20).sorted(); labels = top.map { labels[it] }; series = series.map { s -> Series(s.name, top.map { s.values[it] }) } }
                } else {
                    labels = a.optString("labels", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val vals = a.optString("values", "").split(",").map { num(it) ?: return@Tool fail("\"$it\" is not a number.") }
                    if (labels.isEmpty() || labels.size != vals.size) return@Tool fail("Give the same number of labels and values (got ${labels.size} labels and ${vals.size} values).")
                    series = listOf(Series(a.getString("title"), vals))
                }
                val out = Workspace.inside(c.project, "charts/" + a.optString("name", "").ifBlank { Workspace.slug(a.getString("title")) + "_" + kind }.let { if (it.endsWith(".png")) it else "$it.png" }) ?: return@Tool fail("Cannot save there.")
                val problem = withContext(Dispatchers.IO) { chart(out, kind, a.getString("title"), labels, series, a.optString("x", ""), if (series.size == 1) series[0].name else "") }
                if (problem != null) fail("The chart could not be made: $problem") else ok("Chart saved: ${out.name} (${labels.size} points, ${series.size} series). Folder: ${out.parent}", listOf(out.absolutePath))
            }))

        Tools.register(Tool("data_clean", "Clean a table file without touching the original; writes a new file and reports every change. ops (comma separated): trim, dedupe, drop_empty, numbers (normalise 1.234,5 / 12% / currency to plain numbers), dates (to yyyy-MM-dd), fill:column=value|mean|median, drop_col:name, lower_headers.",
            listOf(A("path", "file"), A("ops", "operations"), A("name", "output file name", false), A("sheet", "Excel sheet", false, "int")), Risk.LOCAL, 120_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t0 = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table.")
                var header = t0.header; var rows = t0.rows.map { it.toMutableList() }
                val info = infer(t0); val log = ArrayList<String>()
                for (op in a.getString("ops").split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                    val name = op.substringBefore(':').lowercase(); val arg = op.substringAfter(':', "")
                    when (name) {
                        "trim" -> { var n = 0; rows.forEach { r -> for (i in r.indices) { val s = r[i].trim().replace(Regex("\\s+"), " "); if (s != r[i]) { r[i] = s; n++ } } }; log.add("trim: $n cells") }
                        "dedupe" -> { val before = rows.size; val seen = HashSet<String>(); rows = rows.filter { seen.add(it.joinToString("\u0001") { x -> x.trim().lowercase() }) }.toMutableList().map { it.toMutableList() }; log.add("dedupe: removed ${before - rows.size} duplicate rows") }
                        "drop_empty" -> { val before = rows.size; rows = rows.filter { r -> r.any { it.isNotBlank() } }; log.add("drop_empty: removed ${before - rows.size} empty rows") }
                        "numbers" -> { var n = 0; info.forEachIndexed { i, ci -> if (ci.kind == Kind.NUM) rows.forEach { r -> val v = num(r[i]); if (v != null && r[i] != fmt(v)) { r[i] = fmt(v); n++ } } }; log.add("numbers: normalised $n cells") }
                        "dates" -> { var n = 0; info.forEachIndexed { i, ci -> if (ci.kind == Kind.DATE) rows.forEach { r -> val d = date(r[i], ci.dmy); if (d != null && r[i].trim() != d.toString()) { r[i] = d.toString(); n++ } } }; log.add("dates: converted $n cells" + (if (info.any { it.kind == Kind.DATE && !it.dmy }) " (month/day order assumed where the column showed it)" else " (day/month order assumed for dd/mm/yyyy)")) }
                        "lower_headers" -> { header = header.map { it.trim().lowercase() }; log.add("lower_headers") }
                        "drop_col" -> { val i = header.indexOfFirst { it.equals(arg.trim(), true) }; if (i < 0) return@Tool fail("There is no column \"$arg\" to drop."); header = header.filterIndexed { k, _ -> k != i }; rows.forEach { it.removeAt(i) }; log.add("drop_col: removed $arg") }
                        "fill" -> {
                            val col = arg.substringBefore('=').trim(); val how = arg.substringAfter('=', "").trim(); val i = header.indexOfFirst { it.equals(col, true) }
                            if (i < 0) return@Tool fail("There is no column \"$col\" to fill.")
                            val nums = rows.mapNotNull { num(it[i]) }
                            val v = when (how.lowercase()) { "mean", "avg" -> if (nums.isEmpty()) return@Tool fail("$col has no numbers to average.") else fmt(nums.average()); "median" -> if (nums.isEmpty()) return@Tool fail("$col has no numbers.") else fmt(median(nums)); else -> how }
                            var n = 0; rows.forEach { r -> if (r[i].isBlank()) { r[i] = v; n++ } }; log.add("fill: $n empty cells in $col set to $v")
                        }
                        else -> return@Tool fail("Unknown operation \"$name\". Available: trim, dedupe, drop_empty, numbers, dates, fill:col=value, drop_col:name, lower_headers.")
                    }
                }
                val ext = if (f.extension.lowercase() == "xlsx") "xlsx" else "csv"
                val out = Workspace.inside(c.project, "clean/" + a.optString("name", "").ifBlank { f.nameWithoutExtension + "_clean" }.let { if (it.contains('.')) it else "$it.$ext" }) ?: return@Tool fail("Cannot save there.")
                val m = withContext(Dispatchers.IO) { DocMaker.make(out.parentFile!!, out.name, out.extension, "", DocTools.mdTable(listOf(header) + rows)) }
                if (m.problem != null) fail(m.problem) else ok("Cleaned copy saved: ${m.file.path} (${rows.size} rows, original ${t0.rows.size}). Original untouched.\n" + log.joinToString("\n"), listOf(m.file.absolutePath))
            }))

        Tools.register(Tool("data_ask", "Answer a question about a table file with real calculations. The model only decides which calculation to run; the numbers come from code, and the answer may not contain numbers that are not in the calculated result.",
            listOf(A("path", "file"), A("question", "the question about the data"), A("sheet", "Excel sheet", false, "int")), Risk.READ, 240_000L, group = "data",
            run = { c, a ->
                val f = resolve(c, a.getString("path")) ?: return@Tool fail("There is no readable file at ${a.getString("path")}.")
                val t = withContext(Dispatchers.IO) { load(f, a.optInt("sheet", 1)) } ?: return@Tool fail("${f.name} has no table.")
                val info = infer(t)
                val schema = info.joinToString("\n") { "- ${it.name} [${it.kind.name.lowercase()}]" + (if (it.kind == Kind.TEXT) " e.g. " + t.rows.map { r -> t.cell(r, t.header.indexOf(it.name)) }.filter { v -> v.isNotEmpty() }.distinct().take(5).joinToString(", ") { v -> "\"" + Axiom.clip(v, 25) + "\"" } else "") }
                val spec = Router.runJson(c.brain, Router.T.PLAN, Router.Opts(
                    "You turn a question about a table into ONE calculation. Return a JSON object: {\"where\":\"\" or conditions like 'amount > 100 and city = Rabat' (joined by 'and'/'or'), \"group_by\":\"column or empty\", \"agg\":\"calculations like 'sum:amount,count,avg:price' or empty\", \"select\":\"columns to list when no grouping\", \"order\":\"'column desc' or empty\", \"limit\":number or 0, \"explain\":\"one line: what this calculates\"}. Use ONLY the column names given. If the question cannot be answered from these columns return {\"impossible\":\"why\"}.",
                    "COLUMNS:\n$schema\nROWS: ${t.rows.size}\nQUESTION: ${a.getString("question")}", json = true, maxOut = 800, task = c.task))
                if (spec.has("impossible")) return@Tool ok("This table cannot answer that: ${spec.optString("impossible")}\nColumns: ${t.header.joinToString(", ")}")
                val (q, err) = query(t, spec.optString("select", ""), spec.optString("where", ""), spec.optString("group_by", ""), spec.optString("agg", ""), spec.optString("order", ""), spec.optInt("limit", 0))
                if (q == null) return@Tool fail("The calculation I chose did not work: $err", retriable = true)
                val table = resultTable(q, 30)
                val ans = Router.run(c.brain, Router.T.REASON, Router.Opts(
                    "Answer the question using ONLY the calculated result table. Quote its numbers exactly; do not calculate new numbers, do not add outside facts. If the table does not answer the question, say so. Same language as the question; short.\n" + Shield.RULES,
                    "QUESTION: ${a.getString("question")}\nCALCULATION: ${spec.optString("explain")} (${q.matched} of ${q.total} rows matched)\nRESULT:\n" + Shield.wrap(f.name, table, 6000).text, maxOut = 1500, task = c.task))
                val inTable = Regex("[0-9][0-9.,]*").findAll(table).map { it.value.trim('.', ',') }.toSet()
                val foreign = Regex("[0-9][0-9.,]*").findAll(ans).map { it.value.trim('.', ',') }.filter { it.length >= 3 && it !in inTable && it !in setOf(q.matched.toString(), q.total.toString()) }.distinct().toList()
                ok(ans.trim() + (if (foreign.isNotEmpty()) "\n(Check: the explanation mentions ${foreign.joinToString(", ")} which is not in the calculated table.)" else "") + "\n\nCalculation: ${spec.optString("explain")} — ${q.matched} of ${q.total} rows.\n$table", untrusted = true)
            }))
    }
}
