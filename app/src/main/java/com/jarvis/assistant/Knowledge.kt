package com.jarvis.assistant

import android.content.ContentValues
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns text into vectors by meaning using whatever embedding model the Gemini key offers. Nothing is assumed: the model list is asked for. */
object Embed {
    @Volatile private var model: String? = null

    private fun pick(brain: Brain): String? {
        model?.let { if (!Quota.cooling(it)) return it }
        val names = brain.geminiInfo().filter { it.methods.contains("embedContent") || it.methods.contains("batchEmbedContents") }.map { it.name }
        val best = names.sortedBy { when { it.contains("gemini-embedding") -> 0; it.contains("text-embedding-00") -> 1; it.contains("gecko") -> 9; else -> 5 } }.firstOrNull { !Quota.cooling(it) }
        model = best
        return best
    }

    fun available(brain: Brain): Boolean = brain.hasGemini() && Quota.allow("Gemini") && pick(brain) != null

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val d = Math.sqrt(n).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / d }
    }

    /** One vector per text, or null when embeddings are not available right now (search then falls back to words). */
    fun embed(brain: Brain, texts: List<String>, query: Boolean): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val m = pick(brain) ?: return null
        val out = ArrayList<FloatArray>()
        try {
            for (batch in texts.chunked(40)) {
                var dim = 256
                var resp: String? = null
                for (attempt in 0..1) {
                    val reqs = JSONArray()
                    for (t in batch) {
                        val r = JSONObject().put("model", "models/$m")
                            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t.take(7000)))))
                            .put("taskType", if (query) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT")
                        if (attempt == 0) r.put("outputDimensionality", dim)
                        reqs.put(r)
                    }
                    try {
                        resp = brain.request("POST", "https://generativelanguage.googleapis.com/v1beta/models/$m:batchEmbedContents",
                            JSONObject().put("requests", reqs).toString().toByteArray(Charsets.UTF_8), "application/json",
                            mapOf("x-goog-api-key" to brain.appPrefs().geminiKey), 40000)
                        break
                    } catch (e: HttpError) {
                        if (e.code == 400 && attempt == 0) continue        // this model may not accept a chosen size
                        if (e.code == 429) Quota.cool(m, if (e.retryAfterMs > 0) e.retryAfterMs else 60_000L)
                        if (e.code == 404) { Quota.cool(m, 6 * 3600_000L); model = null }
                        throw e
                    }
                }
                val arr = JSONObject(resp ?: return null).optJSONArray("embeddings") ?: return null
                if (arr.length() != batch.size) return null
                for (i in 0 until arr.length()) {
                    val vals = arr.getJSONObject(i).getJSONArray("values")
                    out.add(normalize(FloatArray(vals.length()) { vals.getDouble(it).toFloat() }))
                }
            }
            return out
        } catch (e: Exception) {
            return null
        }
    }

    fun toBlob(v: FloatArray): ByteArray { val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN); v.forEach { b.putFloat(it) }; return b.array() }

    fun fromBlob(b: ByteArray): FloatArray { val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.size / 4) { bb.getFloat() } }

    fun cos(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

/**
 * Memory by meaning. Documents are split into pieces, each piece gets a vector, and a question finds the pieces closest in meaning,
 * with their source, page and the date they were learned. Changed files are re-indexed, deleted files lose their pieces.
 * Without embeddings it still works, by words.
 */
object Knowledge {
    class Hit(val docId: Long, val source: String, val title: String, val page: Int, val text: String, val score: Double, val added: Long, val trust: String)

    fun norm(s: String): String = s.lowercase().replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')

    private fun tokens(s: String): List<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(norm(s)).map { it.value }.toList()

    /** Pieces of about [size] characters that break at paragraph or sentence ends and overlap a little. */
    fun chunk(text: String, size: Int = 900, overlap: Int = 120): List<String> {
        val clean = text.replace("\r", "").trim()
        if (clean.length <= size) return if (clean.isEmpty()) emptyList() else listOf(clean)
        val out = ArrayList<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + size, clean.length)
            if (end < clean.length) {
                val window = clean.substring(start + size / 2, end)
                val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf("\n"), window.lastIndexOf("。"), window.lastIndexOf("؟ "), window.lastIndexOf("؛ "))
                if (cut > 0) end = start + size / 2 + cut + 1
            }
            clean.substring(start, end).trim().takeIf { it.length > 20 }?.let { out.add(it) }
            if (end >= clean.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }

    private val pageRx = Regex("\\[\\[page (\\d+)\\]\\]")

    /** Adds (or updates) one source. Same source and same content: nothing to do. Same source, new content: old pieces are replaced. */
    fun add(brain: Brain, source: String, project: String, title: String, kind: String, text: String, trust: String): String {
        val h = Axiom.sha1(text)
        val db = Store.get()
        val old = db.one("SELECT id, hash FROM kb_docs WHERE source = ? AND project = ?", source, Projects.key(project))
        if (old != null && old["hash"] == h) return "Already known and unchanged: $title"
        if (old != null) { db.delete("kb_chunks", "doc = ?", old["id"]!!); db.delete("kb_docs", "id = ?", old["id"]!!) }
        val pieces = ArrayList<Pair<Int, String>>()
        val marks = pageRx.findAll(text).toList()
        if (marks.isEmpty()) chunk(text).forEach { pieces.add(0 to it) }
        else for (i in marks.indices) {
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            chunk(text.substring(marks[i].range.last + 1, end)).forEach { pieces.add(marks[i].groupValues[1].toInt() to it) }
        }
        if (pieces.isEmpty()) return "Nothing readable to remember in $title."
        val cvDoc = ContentValues().apply { put("source", source); put("project", Projects.key(project)); put("title", title); put("kind", kind); put("hash", h); put("added", System.currentTimeMillis()); put("trust", trust) }
        val docId = db.insert("kb_docs", cvDoc)
        val vecs = Embed.embed(brain, pieces.map { it.second }, false)
        val now = System.currentTimeMillis()
        db.writableDatabase.beginTransaction()
        try {
            pieces.forEachIndexed { i, p ->
                val cv = ContentValues().apply { put("doc", docId); put("idx", i); put("page", p.first); put("text", p.second); put("ts", now); if (vecs != null) put("vec", Embed.toBlob(vecs[i])) }
                db.writableDatabase.insert("kb_chunks", null, cv)
            }
            db.writableDatabase.setTransactionSuccessful()
        } finally { db.writableDatabase.endTransaction() }
        return (if (old != null) "Updated" else "Learned") + " \"$title\": ${pieces.size} pieces" + (if (vecs == null) " (indexed by words; meaning-search was unavailable right now)" else " (indexed by meaning)")
    }

    private class Row(val docId: Long, val source: String, val title: String, val page: Int, val text: String, val vec: FloatArray?, val added: Long, val trust: String)

    fun search(brain: Brain, query: String, project: String? = null, source: String? = null, sinceMs: Long = 0L, limit: Int = 6): List<Hit> {
        val where = StringBuilder("1 = 1"); val args = ArrayList<String>()
        if (!project.isNullOrBlank()) { where.append(" AND d.project = ?"); args.add(Projects.key(project)) }
        if (!source.isNullOrBlank()) { where.append(" AND d.source LIKE ?"); args.add("%$source%") }
        if (sinceMs > 0) { where.append(" AND d.added >= ?"); args.add(sinceMs.toString()) }
        val rows = ArrayList<Row>()
        Store.get().readableDatabase.rawQuery(
            "SELECT c.doc, d.source, d.title, c.page, c.text, c.vec, d.added, d.trust FROM kb_chunks c JOIN kb_docs d ON d.id = c.doc WHERE $where ORDER BY c.id DESC LIMIT 6000", args.toTypedArray()
        ).use { c -> while (c.moveToNext()) rows.add(Row(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), if (c.isNull(5)) null else Embed.fromBlob(c.getBlob(5)), c.getLong(6), c.getString(7))) }
        if (rows.isEmpty()) return emptyList()
        val qv = if (rows.any { it.vec != null }) Embed.embed(brain, listOf(query), true)?.firstOrNull() else null
        val qt = tokens(query).toSet()
        val qn = norm(query)
        val scored = rows.map { r ->
            val lex = if (qt.isEmpty()) 0.0 else { val t = tokens(r.text).toSet(); qt.count { it in t }.toDouble() / qt.size + (if (qn.length > 6 && norm(r.text).contains(qn)) 0.3 else 0.0) }
            val sem = if (qv != null && r.vec != null) Embed.cos(qv, r.vec) else -1.0
            val score = if (sem >= 0) 0.75 * sem + 0.25 * minOf(lex, 1.0) else minOf(lex, 1.2) * 0.8
            Hit(r.docId, r.source, r.title, r.page, r.text, score, r.added, r.trust)
        }.filter { it.score > (if (qv != null) 0.30 else 0.15) }.sortedByDescending { it.score }
        val seen = HashSet<String>()
        return scored.filter { seen.add(it.docId.toString() + ":" + it.text.take(60)) }.take(limit)
    }

    fun format(hits: List<Hit>, withText: Boolean = true): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return hits.mapIndexed { i, h ->
            "[" + (i + 1) + "] " + h.title + (if (h.page > 0) ", page ${h.page}" else "") + " — source: " + h.source + " — learned " + fmt.format(java.util.Date(h.added)) + " — match " + "%.0f".format(h.score * 100) + "%" +
                (if (withText) "\n" + Axiom.clip(h.text, 700) else "")
        }.joinToString("\n\n")
    }

    fun remove(source: String): Int {
        val db = Store.get()
        val ids = db.query("SELECT id FROM kb_docs WHERE source = ? OR title = ?", source, source).map { it["id"]!! }
        for (id in ids) { db.delete("kb_chunks", "doc = ?", id); db.delete("kb_docs", "id = ?", id) }
        return ids.size
    }

    fun sources(project: String? = null): List<Map<String, String>> =
        if (project.isNullOrBlank()) Store.get().query("SELECT d.*, (SELECT COUNT(*) FROM kb_chunks c WHERE c.doc = d.id) AS pieces FROM kb_docs d ORDER BY added DESC LIMIT 100")
        else Store.get().query("SELECT d.*, (SELECT COUNT(*) FROM kb_chunks c WHERE c.doc = d.id) AS pieces FROM kb_docs d WHERE project = ? ORDER BY added DESC LIMIT 100", Projects.key(project))

    /** Pieces that belong to files that no longer exist are removed. */
    fun purgeMissing(): Int {
        var n = 0
        for (d in Store.get().query("SELECT id, source FROM kb_docs WHERE source LIKE '/%'")) {
            if (!File(d["source"]!!).exists()) { Store.get().delete("kb_chunks", "doc = ?", d["id"]!!); Store.get().delete("kb_docs", "id = ?", d["id"]!!); n++ }
        }
        return n
    }

    /** What AXIOM already knows that is relevant to a goal, with sources, for the planner. Untrusted pieces stay wrapped. */
    fun contextFor(goal: String, project: String): String {
        if (Store.get().scalar("SELECT COUNT(*) FROM kb_docs") == 0L) return ""
        val hits = search(Shared.brain(), goal, project, limit = 4).ifEmpty { search(Shared.brain(), goal, null, limit = 3) }
        if (hits.isEmpty()) return ""
        val trusted = hits.filter { it.trust == "user" }
        val outside = hits.filter { it.trust != "user" }
        return (if (trusted.isNotEmpty()) format(trusted) else "") + (if (outside.isNotEmpty()) "\n" + Shield.wrap("saved knowledge", format(outside), 4000).text else "")
    }

    /**
     * Past conversations are kept as short summaries instead of being replayed in full: every 30 new turns the oldest unsummarised
     * ones are condensed into one knowledge entry.
     */
    fun maybeSummarize(brain: Brain, prefs: Prefs) {
        try {
            val sp = Axiom.ctx.getSharedPreferences("axiom_conv", android.content.Context.MODE_PRIVATE)
            val last = sp.getLong("last_ts", 0L)
            val turns = Store.get().let { Memory.get(Axiom.ctx).readableDatabase.rawQuery("SELECT role, text, ts FROM turns WHERE ts > ? ORDER BY id LIMIT 60", arrayOf(last.toString())).use { c ->
                val l = ArrayList<Triple<String, String, Long>>(); while (c.moveToNext()) l.add(Triple(c.getString(0), c.getString(1), c.getLong(2))); l } }
            if (turns.size < 30) return
            val text = turns.joinToString("\n") { (if (it.first == "user") "User: " else "AXIOM: ") + Axiom.clip(it.second, 300) }
            val sum = kotlinx.coroutines.runBlocking { Router.run(brain, Router.T.SUMMARIZE, Router.Opts("Summarize this conversation in at most 8 short lines: decisions made, facts about the user, open requests, projects mentioned. Keep names and numbers exactly. Plain text.", text, task = "")) }
            val day = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(turns.first().third))
            add(brain, "conversation:$day", prefs.activeProject, "Conversation $day", "conversation", sum, "user")
            sp.edit().putLong("last_ts", turns.last().third).apply()
        } catch (e: Exception) { /* summarising is best effort */ }
    }
}
