package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/** A small chat window that floats over any app. Opened by tapping the floating orb. */
class FloatingChat(
    private val ctx: Context,
    private val assistant: Assistant,
    private val voice: Voice
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var list: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var input: EditText? = null
    private var micChip: TextView? = null
    private var typing: View? = null
    private var busy = false          // microphone only
    private var inflight = 0           // messages being answered right now; typing is never blocked
    private var listening = false

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private val cyan = Color.parseColor("#2AA8FF")

    private val sub: (String, String) -> Unit = { role, text ->
        ui.post {
            if (role == "jarvis" && inflight == 0) hideTyping()
            add(role, text)
        }
    }

    fun isShown() = root != null
    fun toggle() { if (root == null) show() else hide() }

    private fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(cyan)
        textSize = 13f
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    fun show() {
        if (root != null) return
        val dm = ctx.resources.displayMetrics
        val width = minOf(dm.widthPixels - dp(24), dp(340))
        val height = minOf((dm.heightPixels * 0.6f).toInt(), dp(460))

        val panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F2050A12"))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), cyan)
        }
        panel.setPadding(dp(10), dp(6), dp(10), dp(10))
        // a tap outside gives the keyboard and the focus back to the app underneath
        panel.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) releaseKeyboard(); false }

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(12)
        params.y = dp(120)
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        // header: drag handle + actions
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(ctx).apply {
            text = "JARVIS"
            setTextColor(cyan)
            textSize = 13f
            letterSpacing = 0.3f
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val mic = chip("MIC") { micTapped() }
        micChip = mic
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(mic)
        header.addView(chip("APP") {
            ctx.startActivity(Intent(ctx, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        })
        header.addView(chip("X") { hide() })

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    root?.let { wm.updateViewLayout(it, params) }
                    true
                }
                else -> true
            }
        }

        val listView = LinearLayout(ctx)
        listView.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(ctx)
        sv.addView(listView)
        list = listView
        scroll = sv

        val field = EditText(ctx).apply {
            hint = "Message Jarvis"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
            // the window is non-focusable so it never steals the keyboard from the app below; focus it only when typing
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    this@FloatingChat.setWindowFocusable(true)   // not View.setFocusable: this is the overlay window's flag
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    // the window needs a moment to become focused before the keyboard can attach: try a few times
                    for (wait in longArrayOf(80, 250, 600)) {
                        v.postDelayed({
                            if (!v.hasFocus()) v.requestFocus()
                            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        }, wait)
                    }
                }
                false
            }
        }
        input = field
        val inputRow = LinearLayout(ctx)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(chip("SEND") { sendTyped() })

        panel.addView(header, LinearLayout.LayoutParams(-1, -2))
        panel.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(inputRow, LinearLayout.LayoutParams(-1, -2))

        try {
            wm.addView(panel, params)
        } catch (e: Exception) {
            return
        }
        root = panel
        lp = params
        for (t in assistant.recentChat(10)) add(t.role, t.text)
        ChatBus.listeners.add(sub)
    }

    fun hide() {
        ChatBus.listeners.remove(sub)
        root?.let {
            try { wm.removeView(it) } catch (e: Exception) { /* already gone */ }
        }
        root = null; lp = null; list = null; scroll = null; input = null; micChip = null; typing = null
    }

    private fun setWindowFocusable(on: Boolean) {
        val p = lp ?: return
        val r = root ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm.updateViewLayout(r, p) } catch (e: Exception) { /* window closing */ }
    }

    private fun releaseKeyboard() {
        input?.let { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        setWindowFocusable(false)
    }

    private fun add(role: String, text: String) {
        val l = list ?: return
        l.addView(ChatUi.bubble(ctx, role, text, dp(250)), LinearLayout.LayoutParams(-1, -2))
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showTyping() {
        if (typing != null) return
        val l = list ?: return
        val v = ChatUi.typing(ctx)
        typing = v
        l.addView(v, LinearLayout.LayoutParams(-1, -2))
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideTyping() {
        typing?.let { list?.removeView(it) }
        typing = null
    }

    private fun sendTyped() {
        val f = input ?: return
        val t = f.text.toString().trim()
        if (t.isEmpty()) return
        f.setText("")
        releaseKeyboard()
        submit(t, speak = false)
    }

    private fun submit(text: String, speak: Boolean) {
        inflight++
        showTyping()
        Engine.scope.launch(Dispatchers.Main) {
            val r = try {
                assistant.handle(text, null, { Confirmer.ask(ctx, it) }, { _ -> })
            } catch (e: Exception) {
                Reply(e.message ?: "Error", e.message ?: "Error", failed = true)
            }
            inflight--
            if (inflight <= 0) { inflight = 0; hideTyping() }
            if (speak && !r.failed && !Voice.forced(ctx)) voice.speak(r.spoken)
        }
    }

    private fun micTapped() {
        if (listening) { voice.finishRecording(); return }
        if (busy) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            add("system", "Open Jarvis once and allow the microphone.")
            return
        }
        busy = true
        listening = true
        micChip?.text = "DONE"
        Engine.scope.launch(Dispatchers.Main) {
            try {
                val t = assistant.hear(voice)
                listening = false
                busy = false
                micChip?.text = "MIC"
                if (t == null) add("system", "I did not hear anything. If this repeats, open Jarvis once so the mic service restarts.")
                else submit(t, speak = true)
            } catch (e: Exception) {
                listening = false
                busy = false
                micChip?.text = "MIC"
                add("system", e.message ?: "Microphone error.")
            }
        }
    }
}
