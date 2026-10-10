package com.jarvis.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** The real chat: persistent conversation with Jarvis, typed or spoken. Same brain and actions as everywhere else. */
class ChatActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var memory: Memory
    private lateinit var assistant: Assistant
    private lateinit var voice: Voice

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mic: Button

    private var busy = false
    private var listening = false
    private var typing: View? = null
    private val maxBubble by lazy { (resources.displayMetrics.widthPixels * 0.78f).toInt() }

    private val sub: (String, String) -> Unit = { role, text ->
        runOnUiThread {
            if (role == "jarvis") hideTyping()
            add(role, text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        memory = Memory.get(this)
        assistant = Assistant(this)
        voice = Voice(this, prefs)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        list.removeAllViews()
        typing = null
        for (t in memory.recentChat(80)) add(t.role, t.text)
        ChatBus.listeners.add(sub)
    }

    override fun onStop() {
        ChatBus.listeners.remove(sub)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Engine.foreground = this
    }

    override fun onPause() {
        if (Engine.foreground === this) Engine.foreground = null
        super.onPause()
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05080F"))
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
            text = "JARVIS"
            setTextColor(Color.parseColor("#2AA8FF"))
            textSize = 16f
            letterSpacing = 0.3f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        scroll = ScrollView(this).apply { addView(list) }

        input = EditText(this).apply {
            hint = "Message Jarvis"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        mic = Button(this).apply {
            text = "MIC"
            setOnClickListener { micTapped() }
        }
        val send = Button(this).apply {
            text = "SEND"
            setOnClickListener { sendTyped() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 8, 16, 24)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(mic, LinearLayout.LayoutParams(-2, -2))
        inputRow.addView(send, LinearLayout.LayoutParams(-2, -2))

        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(inputRow, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun add(role: String, text: String) {
        list.addView(ChatUi.bubble(this, role, text, maxBubble), LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showTyping() {
        if (typing != null) return
        val v = ChatUi.typing(this)
        typing = v
        list.addView(v, LinearLayout.LayoutParams(-1, -2))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideTyping() {
        typing?.let { list.removeView(it) }
        typing = null
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        submit(t, speak = false)
    }

    private fun submit(text: String, speak: Boolean) {
        if (busy) return
        busy = true
        showTyping()
        lifecycleScope.launch {
            val r = try {
                assistant.handle(text, null, { Confirmer.ask(this@ChatActivity, it) }, { _ -> })
            } catch (e: Exception) {
                Reply(e.message ?: "Error", e.message ?: "Error", failed = true)
            }
            hideTyping()
            busy = false
            if (speak && !r.failed && !Voice.forced(this@ChatActivity)) voice.speak(r.spoken)
        }
    }

    private fun micTapped() {
        if (listening) { voice.finishRecording(); return }
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            add("system", "Allow the microphone, then tap MIC again.")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 3)
            return
        }
        busy = true
        listening = true
        mic.text = "DONE"
        add("system", "Listening... tap DONE when finished")
        lifecycleScope.launch {
            try {
                val t = assistant.hear(voice)
                listening = false
                busy = false
                mic.text = "MIC"
                if (t == null) add("system", "I did not hear anything.") else submit(t, speak = true)
            } catch (e: Exception) {
                listening = false
                busy = false
                mic.text = "MIC"
                add("system", e.message ?: "Microphone error.")
            }
        }
    }
}
