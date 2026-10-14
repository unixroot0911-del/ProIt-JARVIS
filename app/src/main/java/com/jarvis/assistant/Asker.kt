package com.jarvis.assistant

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A clarifying question from the brain: the question, the likeliest answers, and why it matters. */
data class AskSpec(val q: String, val options: List<String>, val why: String)

/** The user's answer. [text] is empty when they chose "decide for me". */
data class AskAnswer(val text: String, val remember: Boolean)

/**
 * The question screen: the question, tap-to-answer options, a box to write any answer freely,
 * a switch to remember the answer for next time, and "decide for me".
 */
object Asker {

    suspend fun ask(context: Context, spec: AskSpec): AskAnswer? = suspendCancellableCoroutine { cont ->
        val h = Handler(Looper.getMainLooper())
        var dlg: AlertDialog? = null
        h.post {
            try {
                val act = Engine.foreground
                val c: Context = act ?: run {
                    if (!Settings.canDrawOverlays(context)) { if (cont.isActive) cont.resume(null); return@post }
                    ContextThemeWrapper(context.applicationContext, R.style.Theme_Jarvis)
                }
                fun done(a: AskAnswer?) {
                    if (cont.isActive) cont.resume(a)
                    try { dlg?.dismiss() } catch (e: Exception) { /* already gone */ }
                }
                val d = c.resources.displayMetrics.density
                fun px(v: Int) = (v * d).toInt()
                val cyan = Color.parseColor("#2AA8FF")
                val col = LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(20), px(18), px(20), px(14))
                    setBackgroundColor(Color.parseColor("#0B1220"))
                }
                col.addView(TextView(c).apply { text = "AXIOM ASKS"; setTextColor(cyan); textSize = 11f; letterSpacing = 0.25f })
                col.addView(TextView(c).apply { text = spec.q; setTextColor(Color.WHITE); textSize = 18f; setPadding(0, px(8), 0, px(4)) })
                if (spec.why.isNotBlank()) col.addView(TextView(c).apply { text = spec.why; setTextColor(Color.parseColor("#8FA3B8")); textSize = 12f; setPadding(0, 0, 0, px(8)) })

                val remember = Switch(c).apply { text = "Remember my answer for next time"; setTextColor(Color.parseColor("#CFE8FF")); textSize = 13f }
                for (o in spec.options.take(4)) {
                    col.addView(Button(c).apply {
                        text = o
                        isAllCaps = false
                        setTextColor(Color.parseColor("#05080F"))
                        setBackgroundColor(cyan)
                        setOnClickListener { done(AskAnswer(o, remember.isChecked)) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
                }
                val free = EditText(c).apply {
                    hint = "Or write your own answer"
                    setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
                    minLines = 1; maxLines = 4
                }
                col.addView(free, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
                col.addView(Button(c).apply {
                    text = "SEND MY ANSWER"
                    setTextColor(cyan); setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { val t = free.text.toString().trim(); if (t.isNotEmpty()) done(AskAnswer(t, remember.isChecked)) }
                })
                col.addView(remember, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
                col.addView(Button(c).apply {
                    text = "DECIDE FOR ME"
                    setTextColor(Color.parseColor("#8FA3B8")); setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { done(AskAnswer("", false)) }
                })

                val dialog = AlertDialog.Builder(c)
                    .setView(ScrollView(c).apply { addView(col) })
                    .setCancelable(true)
                    .setOnCancelListener { done(null) }
                    .create()
                dlg = dialog
                if (act == null) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.show()
                // the free-text box needs the keyboard, also when the dialog floats over another app
                dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                h.postDelayed({ done(null) }, 180_000)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { h.post { try { dlg?.dismiss() } catch (e: Exception) { /* ignore */ } } }
    }
}
