package com.jarvis.assistant

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The control center: what AXIOM is doing right now, what waits for you, what finished or failed, with stop / pause / retry,
 * plus projects, tools, usage, the audit trail and every setting. Everything shown is read from the real task database.
 */
class ControlActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var lastSig = ""
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 2000) }
    }

    private val cyan = Color.parseColor("#2AA8FF")
    private val panel = Color.parseColor("#0E1B2B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Tools.ensure()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#05080F")) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(24, 72, 24, 8) }
        bar.addView(btn("<", false) { finish() }, LinearLayout.LayoutParams(130, -2))
        bar.addView(TextView(this).apply { text = "AXIOM  CONTROL"; setTextColor(cyan); textSize = 16f; letterSpacing = 0.25f }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)

        val goalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 0, 16, 8) }
        val goal = EditText(this).apply { hint = "Give AXIOM a goal (it plans and works in the background)"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 14f }
        goalRow.addView(goal, LinearLayout.LayoutParams(0, -2, 1f))
        goalRow.addView(btn("GO", true) {
            val g = goal.text.toString().trim()
            if (g.isNotEmpty()) { goal.setText(""); TaskEngine.start(g, prefs.activeProject, direct = true, source = "control"); render(force = true) }
        })
        root.addView(goalRow)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 0, 8, 8) }
        for ((label, act) in listOf<Pair<String, () -> Unit>>(
            "PROJECTS" to { showText("Projects", projectsText()) },
            "TOOLS" to { showText("Tools", toolsText()) },
            "USAGE" to { showText("Usage and limits", Quota.report() + "\n\n" + Evaluate.report()) },
            "AUDIT" to { showText("Audit trail", Audit.format(40)) },
            "SETTINGS" to { showSettings() }
        )) tabs.addView(btn(label, false) { act() }.apply { textSize = 10f; setPadding(2, 0, 2, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 32) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); Engine.foreground = this; handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); if (Engine.foreground === this) Engine.foreground = null; super.onPause() }

    private fun btn(t: String, filled: Boolean, onClick: () -> Unit = {}) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 12f
        setTextColor(if (filled) Color.parseColor("#05080F") else cyan)
        setBackgroundColor(if (filled) cyan else Color.TRANSPARENT)
        setOnClickListener { onClick() }
    }

    private fun card(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(28, 20, 28, 20)
        background = GradientDrawable().apply { setColor(panel); cornerRadius = 28f; setStroke(2, Color.parseColor("#1F4B6E")) }
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
    }

    private fun txt(t: String, size: Float = 14f, color: String = "#CFE8FF") = TextView(this).apply { text = t; textSize = size; setTextColor(Color.parseColor(color)); setTextIsSelectable(false) }

    private fun head(t: String) = TextView(this).apply { text = t; textSize = 11f; letterSpacing = 0.2f; setTextColor(cyan); setPadding(0, 16, 0, 8) }

    private fun render(force: Boolean = false) {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val waiting = Approvals.list()
                val active = Tasks.roots(Tasks.PLANNING, Tasks.RUNNING, Tasks.AWAITING, Tasks.PAUSED)
                val ids = active.map { it["id"]!! }
                val recent = Tasks.recentRoots(14).filter { it["id"] !in ids }
                Triple(waiting, active.map { it to Tasks.children(it["id"]!!) }, recent)
            }
            val (waiting, active, recent) = data
            val sig = waiting.joinToString { it.id } + "|" + active.joinToString { it.first["id"] + it.first["status"] + it.second.count { k -> k["status"] == Tasks.DONE } + it.first["note"] } + "|" + recent.joinToString { it["id"] + it["status"] }
            if (!force && sig == lastSig) return@launch
            lastSig = sig
            body.removeAllViews()
            if (waiting.isNotEmpty()) {
                body.addView(head("WAITING FOR YOU"))
                for (w in waiting) {
                    val row = LinearLayout(this@ControlActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    val opts = if (w.options == listOf("yes", "no")) listOf("Approve" to "yes", "Deny" to "no") else w.options.map { it to it }
                    for ((label, v) in opts) row.addView(btn(label, v == "yes" || opts.size > 2) { Approvals.answer(w.id, v); render(force = true) }, LinearLayout.LayoutParams(0, -2, 1f))
                    body.addView(card(txt(w.text, 14f), row))
                }
            }
            body.addView(head("RUNNING"))
            if (active.isEmpty()) body.addView(txt("Nothing is running. Type a goal above, or ask in the chat.", 13f, "#6F8CA8"))
            for ((r, kids) in active) {
                val id = r["id"]!!
                val done = kids.count { it["status"] == Tasks.DONE }
                val cur = kids.filter { it["status"] == Tasks.RUNNING || it["status"] == Tasks.AWAITING }.joinToString("; ") { Axiom.clip(it["descr"]!!, 50) }
                val row = LinearLayout(this@ControlActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(btn("Details", false) { showText("Task ${id.takeLast(5)}", Tasks.detail(id)) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (r["status"] == Tasks.PAUSED) row.addView(btn("Resume", true) { TaskEngine.resume(id); render(force = true) }, LinearLayout.LayoutParams(0, -2, 1f))
                else row.addView(btn("Pause", false) { TaskEngine.pause(id); render(force = true) }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(btn("Stop", true) { TaskEngine.stop(id); render(force = true) }, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(card(
                    txt(Axiom.clip(r["goal"]!!, 120), 14f),
                    txt(Tasks.label(r["status"]!!).uppercase() + (if (kids.isEmpty()) "" else "  ·  $done/${kids.size} steps") + (if (r["note"]!!.isNotEmpty()) "  ·  " + Axiom.clip(r["note"]!!, 40) else ""), 11f, "#6F8CA8"),
                    if (cur.isNotEmpty()) txt("Now: $cur", 12f, "#9CC8E8") else txt("", 1f),
                    row
                ))
            }
            body.addView(head("RECENT"))
            if (recent.isEmpty()) body.addView(txt("No finished tasks yet.", 13f, "#6F8CA8"))
            for (r in recent) {
                val id = r["id"]!!
                val st = r["status"]!!
                val row = LinearLayout(this@ControlActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(btn("Details", false) { showText("Task ${id.takeLast(5)}", Tasks.detail(id)) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (st == Tasks.NEW || st == Tasks.READY) row.addView(btn("Run", true) { TaskEngine.resume(id); Tasks.set(id, "status" to Tasks.RUNNING); render(force = true) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (st == Tasks.FAILED || st == Tasks.CANCELLED) row.addView(btn("Retry", true) { TaskEngine.retry(id); render(force = true) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (st == Tasks.DONE) {
                    row.addView(btn("👍", false) { Perf.rate(id, 1) }, LinearLayout.LayoutParams(0, -2, 0.5f))
                    row.addView(btn("👎", false) { Perf.rate(id, -1) }, LinearLayout.LayoutParams(0, -2, 0.5f))
                }
                body.addView(card(
                    txt(Axiom.clip(r["goal"]!!, 120), 14f),
                    txt(Tasks.label(st).uppercase() + (if (r["fail_reason"]!!.isNotEmpty()) "  ·  " + Axiom.clip(r["fail_reason"]!!, 70) else "") + (if (r["verify"]!!.isNotEmpty()) "  ·  " + Axiom.clip(r["verify"]!!, 50) else ""), 11f, if (st == Tasks.FAILED) "#FF8A80" else "#6F8CA8"),
                    row
                ))
            }
        }
    }

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply { this.text = text; setTextColor(Color.parseColor("#CFE8FF")); textSize = 13f; setPadding(40, 20, 40, 20); setTextIsSelectable(true) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("Close", null).show()
    }

    private fun projectsText(): String {
        val l = Projects.list()
        if (l.isEmpty()) return "No projects yet. In the chat say: new project NAME: its goal."
        return l.joinToString("\n\n") { Projects.status(it["name"]!!) } + "\n\nActive: " + prefs.activeProject
    }

    private fun toolsText(): String {
        val all = Tools.all()
        return "${all.size} tools. A tool marked ⚠ asks you first when AXIOM chooses it by itself.\n\n" +
            all.groupBy { it.group }.entries.joinToString("\n\n") { (g, ts) ->
                g.uppercase() + "\n" + ts.joinToString("\n") { (if (Config.toolDisabled(it.name)) "✗ " else "• ") + it.name + (if (it.risk >= Risk.EXTERNAL) " ⚠" else "") + " — " + it.description }
            }
    }

    private fun showSettings() {
        val names = Config.specs.map { it.label.substringBefore(" (") + ":  " + Config.get(it.key).ifEmpty { "auto" } }
        AlertDialog.Builder(this).setTitle("Settings").setItems(names.toTypedArray()) { _, i -> editSetting(Config.specs[i]) }.setPositiveButton("Close", null).show()
    }

    private fun editSetting(s: Config.Spec) {
        if (s.kind == "choice") {
            AlertDialog.Builder(this).setTitle(s.label).setItems(s.options.toTypedArray()) { _, i -> apply(s.key, s.options[i]) }.show()
            return
        }
        val input = EditText(this).apply { setText(Config.get(s.key)); if (s.kind == "int") inputType = InputType.TYPE_CLASS_NUMBER; setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this).setTitle(s.label).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> apply(s.key, input.text.toString()) }.show()
    }

    private fun apply(key: String, value: String) {
        val err = Config.set(key, value)
        AlertDialog.Builder(this).setMessage(err ?: "Saved.").setPositiveButton("OK") { _, _ -> showSettings() }.show()
    }
}
