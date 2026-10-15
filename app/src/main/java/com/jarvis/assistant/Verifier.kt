package com.jarvis.assistant

import org.json.JSONObject
import java.io.File

/**
 * The checking layer. Not "ask the same model again": it compares the result with EVIDENCE (what the tools really returned,
 * whether files exist, whether links open) and uses a different provider for the judging when one is available.
 */
object Verifier {
    class Verdict(val ok: Boolean, val confidence: Double, val issues: List<String>, val notes: String) {
        fun text(): String = (if (ok) "checked" else "NOT fully verified") + " (confidence " + "%.0f".format(confidence * 100) + "%)" +
            (if (issues.isNotEmpty()) ": " + issues.joinToString("; ") else "")
    }

    private val urlRx = Regex("https?://[^\\s)\\]>\"'<,]+")
    private val pathRx = Regex("(/storage/[^\\s\"'<>]+\\.[A-Za-z0-9]{2,5}|/data/user/[^\\s\"'<>]+\\.[A-Za-z0-9]{2,5})")
    private val claimsSent = Regex("(?i)\\b(i (have )?(sent|posted|published|emailed|paid|deleted|installed)|has been (sent|posted|published|deleted)|was (sent|posted|published))\\b|تم (إرسال|الإرسال|النشر|نشر|الدفع|الحذف)")

    /** Cheap, no-model checks for one finished step. Returns the problem or null. */
    fun step(tool: String, role: String, success: String, res: ToolResult): String? {
        if (res.output.isBlank() && res.files.isEmpty()) return "the step produced nothing"
        for (p in res.files) {
            val f = File(p)
            if (!f.exists()) return "the file $p does not exist"
            if (f.length() == 0L) return "the file $p is empty"
        }
        val low = res.output.lowercase()
        if (tool == "think" || tool == "agent") {
            if (low.startsWith("i cannot") || low.startsWith("i can't") || low.startsWith("as an ai")) return "the answer is a refusal, not a result"
            if (role == "researcher" && success.lowercase().let { it.contains("source") || it.contains("link") || it.contains("url") } && !urlRx.containsMatchIn(res.output)) return "no sources were given"
        }
        return null
    }

    private fun evidence(children: List<Map<String, String>>): String = children.joinToString("\n") { k ->
        "- [" + k["status"] + "] " + k["tool"] + ": " + Axiom.clip(k["result"].orEmpty().replace("\n", " "), 260) +
            (Tasks.files(k).takeIf { it.isNotEmpty() }?.let { fl -> "  FILES: " + fl.joinToString { f -> File(f).let { x -> x.name + (if (x.exists()) " (" + Workspace.human(x.length()) + ", exists)" else " (MISSING)") } } } ?: "")
    }

    /** Judges the final answer against the goal and the evidence. */
    suspend fun finalAnswer(brain: Brain, goal: String, draft: String, children: List<Map<String, String>>, writerProvider: String, taskId: String): Verdict {
        val issues = ArrayList<String>()
        // 1. facts a program can check
        for (m in pathRx.findAll(draft)) { if (!File(m.value.trimEnd('.', ',', ')')).exists()) issues.add("the answer mentions a file that does not exist: " + m.value.takeLast(60)) }
        val links = urlRx.findAll(draft).map { it.value.trimEnd('.', ',', ';') }.distinct().take(5).toList()
        for (u in links) { val code = Net.head(u); if (code == -1 || code >= 400) issues.add("a link did not open (HTTP $code): " + u.take(70)) }
        val externalDone = children.any { it["status"] == Tasks.DONE && Tools.get(it["tool"].orEmpty())?.risk.let { r -> r == Risk.EXTERNAL } }
        if (claimsSent.containsMatchIn(draft) && !externalDone) issues.add("the answer claims something was sent, posted, paid or deleted but no tool did it")

        // 2. an independent judge: other provider if possible, with the evidence in hand
        var conf = if (issues.isEmpty()) 0.8 else 0.4
        var notes = ""
        try {
            val sys = "You are the QUALITY checker of AXIOM. Judge a draft against the user's goal and the EVIDENCE of what the tools really did. " +
                "Do not rewrite the draft and do not judge style. Answer ONLY JSON: {\"ok\":true|false,\"confidence\":0-1,\"unmet\":[\"parts of the goal not delivered\"],\"unsupported\":[\"claims in the draft the evidence does not support\"],\"notes\":\"one line\"}"
            val user = "GOAL: $goal\n\nEVIDENCE:\n${evidence(children).ifEmpty { "(no tool evidence)" }}\n\nPROGRAM CHECKS: " + (if (issues.isEmpty()) "no problems found" else issues.joinToString("; ")) + "\n\nDRAFT:\n" + Axiom.clip(draft, 6000)
            val o: JSONObject = Router.runJson(brain, Router.T.VERIFY, Router.Opts(sys, user, json = true, task = taskId, avoidProvider = writerProvider))
            conf = o.optDouble("confidence", conf)
            notes = o.optString("notes")
            o.optJSONArray("unmet")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { issues.add("not delivered: $it") } }
            o.optJSONArray("unsupported")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { issues.add("unsupported: $it") } }
        } catch (e: Exception) {
            notes = "the independent check could not run (" + Secrets.redact(e.message ?: "") + ")"
            conf = minOf(conf, 0.6)
        }
        return Verdict(issues.isEmpty() && conf >= 0.6, conf, issues, notes)
    }
}
