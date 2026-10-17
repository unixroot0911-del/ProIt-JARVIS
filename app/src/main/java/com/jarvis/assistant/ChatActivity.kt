package com.jarvis.assistant

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.widget.HorizontalScrollView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** The real chat: persistent text conversation with Jarvis. Same brain and actions as everywhere else. */
class ChatActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var memory: Memory
    private lateinit var assistant: Assistant

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText

    private val picker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { Inbox.copyFrom(this@ChatActivity, it, prefs.activeProject) } }
            if (files.isEmpty()) { add("jarvis", "I could not read that file."); return@launch }
            val note = input.text.toString().trim().ifEmpty { "I attached a file. Read it and tell me what it is, then wait for what I want." }
            input.setText("")
            submit(note + "\n[attached file: " + files.joinToString(", ") { it.absolutePath } + "]")
        }
    }

    private var cur = "main"
    private val unread = HashMap<String, Int>()
    private lateinit var tabs: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelLines: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refreshPanel(); ui.postDelayed(this, 1000) } }
    private val progressSub: () -> Unit = { ui.post { refreshPanel() } }
    private val maxBubble by lazy { (resources.displayMetrics.widthPixels * 0.78f).toInt() }

    private val sub: (String, String, String) -> Unit = { sess, role, text ->
        runOnUiThread {
            if (sess == cur) { if (role != "system") add(role, text) }
            else if (role == "jarvis") { unread[sess] = (unread[sess] ?: 0) + 1; renderTabs() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        memory = Memory.get(this)
        assistant = Assistant(this)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        cur = Sessions.last().let { id -> if (Sessions.list().any { it.id == id }) id else "main" }
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        loadSession()
        ChatBus.sessListeners.add(sub)
        Progress.listeners.add(progressSub)
        ui.post(tick)
    }

    override fun onStop() {
        ChatBus.sessListeners.remove(sub)
        Progress.listeners.remove(progressSub)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    private fun loadSession() {
        list.removeAllViews()
        for (t in memory.recentChat(80, cur)) add(t.role, t.text)
        unread.remove(cur)
        Sessions.setLast(cur)
        renderTabs(); refreshPanel()
    }

    private fun switchTo(id: String) { if (id == cur) return; cur = id; loadSession() }

    private fun pill(text: String, fill: String, stroke: String) = TextView(this).apply {
        this.text = text; setTextColor(Color.parseColor("#CFE8FF")); textSize = 13f; setPadding(30, 14, 30, 14)
        background = GradientDrawable().apply { setColor(Color.parseColor(fill)); cornerRadius = 40f; setStroke(2, Color.parseColor(stroke)) }
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        for (s in Sessions.list()) {
            val working = Progress.anyActive(s.id)
            val n = unread[s.id] ?: 0
            val label = (if (working) "\u25CF " else "") + s.title + (if (n > 0) "  ($n)" else "")
            val sel = s.id == cur
            val v = pill(label, if (sel) "#1B4F8A" else "#0E1B2B", if (sel) "#2AA8FF" else "#1F4B6E")
            v.setOnClickListener { switchTo(s.id) }
            v.setOnLongClickListener { sessionMenu(s); true }
            tabs.addView(v, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 14, 0) })
        }
        val plus = pill("+", "#0E1B2B", "#2AA8FF")
        plus.setOnClickListener { val s = Sessions.create(); switchTo(s.id) }
        tabs.addView(plus)
    }

    private fun sessionMenu(s: Sessions.S) {
        val items = if (s.id == "main") arrayOf("Rename", "Stop work in this chat") else arrayOf("Rename", "Stop work in this chat", "Delete this chat")
        AlertDialog.Builder(this).setTitle(s.title).setItems(items) { _, i ->
            when (items[i]) {
                "Rename" -> {
                    val e = EditText(this).apply { setText(s.title) }
                    AlertDialog.Builder(this).setTitle("Name").setView(e).setPositiveButton("OK") { _, _ -> Sessions.rename(s.id, e.text.toString()); renderTabs() }.show()
                }
                "Stop work in this chat" -> { Progress.stop(s.id); renderTabs() }
                else -> {
                    Progress.stop(s.id); Sessions.delete(s.id)
                    if (cur == s.id) { cur = "main"; loadSession() } else renderTabs()
                }
            }
        }.show()
    }

    private fun refreshPanel() {
        if (!::panel.isInitialized) return
        val jobs = Progress.active(cur)
        val fresh = Progress.lastAgeMs(cur) < 8000
        if (jobs.isEmpty() && !fresh) { panel.visibility = View.GONE } else {
            panel.visibility = View.VISIBLE
            val secs = if (jobs.isNotEmpty()) (System.currentTimeMillis() - jobs.first().start) / 1000 else 0
            panelTitle.text = if (jobs.isEmpty()) "AXIOM" else "Working  " + String.format("%d:%02d", secs / 60, secs % 60) + (if (jobs.size > 1) "  -  ${jobs.size} jobs" else "") + "  -  " + jobs.first().label
            val rec = Progress.recent(cur, 5)
            panelLines.text = if (rec.isEmpty()) "Thinking..." else rec.joinToString("\n")
        }
        renderTabsIfChanged()
    }

    private var lastTabState = ""
    private fun renderTabsIfChanged() {
        val st = Sessions.list().joinToString("|") { it.id + it.title + Progress.anyActive(it.id) + (unread[it.id] ?: 0) } + cur
        if (st != lastTabState) { lastTabState = st; renderTabs() }
    }

    override fun onResume() {
        super.onResume()
        Engine.foreground = this
    }

    override fun onPause() {
        if (Engine.foreground === this) Engine.foreground = null
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.background)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 72, 24, 16)
        }
        bar.addView(Button(this).apply {
            text = "<"
            setTextColor(Color.parseColor("#2AA8FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(120, -2))
        bar.addView(TextView(this).apply {
            text = "AXIOM"
            setTextColor(Color.parseColor("#2AA8FF"))
            textSize = 16f
            letterSpacing = 0.3f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 0, 24, 12) }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }

        panelTitle = TextView(this).apply { setTextColor(Color.parseColor("#2AA8FF")); textSize = 13f }
        panelLines = TextView(this).apply { setTextColor(Color.parseColor("#9FB8D0")); textSize = 12f }
        val stop = Button(this).apply { text = "STOP"; textSize = 11f; setOnClickListener { Progress.stop(cur); refreshPanel() } }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(30, 18, 30, 18)
            background = GradientDrawable().apply { setColor(Color.parseColor("#0A1624")); cornerRadius = 28f; setStroke(2, Color.parseColor("#1F4B6E")) }
            val head = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(stop, LinearLayout.LayoutParams(-2, 90))
            addView(head); addView(panelLines)
        }

        bar.addView(Button(this).apply {
            text = "MODS"; textSize = 11f
            setOnClickListener {
                val panels = Store.get().query("SELECT name, descr FROM mods WHERE enabled = 1 AND kind = 'panel' ORDER BY name")
                if (panels.isEmpty()) AlertDialog.Builder(this@ChatActivity).setTitle("Mods").setMessage("No panel mods yet. Ask AXIOM: make a mod that ...").setPositiveButton("OK", null).show()
                else AlertDialog.Builder(this@ChatActivity).setTitle("Mods").setItems(panels.map { it["name"] + " - " + (it["descr"] ?: "").take(40) }.toTypedArray()) { _, i -> Mods.openPanel(applicationContext, panels[i]["name"]!!) }.show()
            }
        }, LinearLayout.LayoutParams(-2, -2))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        scroll = ScrollView(this).apply { addView(list) }

        input = EditText(this).apply {
            hint = "Message AXIOM"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        val send = Button(this).apply {
            text = "SEND"
            setOnClickListener { sendTyped() }
        }
        val attach = Button(this).apply {
            text = "+"
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 24)
        }
        inputRow.addView(attach, LinearLayout.LayoutParams(-2, -2))
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(-2, -2))

        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { setMargins(16, 6, 16, 6) })
        root.addView(inputRow, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun add(role: String, text: String) {
        list.addView(ChatUi.bubble(this, role, text, maxBubble), LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        submit(t)
    }

    private fun submit(text: String) {
        val sess = cur
        val app = applicationContext
        // the work belongs to the session, not to this screen: it goes on if you switch chats or leave
        val pid = Progress.begin(sess, text)
        val job = Engine.scope.launch(Dispatchers.Default + Sessions.element(sess)) {
            try {
                autoTitle(sess, text)
                assistant.handle(text, null, { Confirmer.ask(app, it) }, { _ -> })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) { Diag.record(e) }
            finally { Progress.end(pid) }
        }
        Progress.attach(pid, job)
    }

    private fun autoTitle(sess: String, text: String) {
        val s = Sessions.list().firstOrNull { it.id == sess } ?: return
        if (s.title == "New chat") { Sessions.rename(sess, text.replace(Regex("\\[attached file:[^\\]]*]"), "").trim().take(22).ifBlank { "Chat" }); ui.post { renderTabs() } }
    }
}
