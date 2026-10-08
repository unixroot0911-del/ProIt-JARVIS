package com.jarvis.assistant

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var pairInfo: TextView
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val memory = Memory.get(this)

        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#2AA8FF")); setPadding(0, 40, 0, 8)
        }
        fun field(value: String, hint: String, secret: Boolean = false) = EditText(this).apply {
            setText(value); this.hint = hint
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun switch(t: String, on: Boolean) = Switch(this).apply {
            text = t; isChecked = on; setTextColor(Color.WHITE)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
        }

        val gemini = field(prefs.geminiKey, "Gemini API key (free: aistudio.google.com)", true)
        val groq = field(prefs.groqKey, "Groq API key (free: console.groq.com)", true)
        val locale = field(prefs.speechLocale, "Speech language, e.g. ar-MA, ar-SA, en-US")
        val speak = switch("Speak replies", prefs.speakReplies)
        val study = switch("Study mode (tutor + quizzes)", prefs.mode == "study")
        val briefings = switch("Morning and evening briefings", prefs.briefingsOn)
        val morning = field(prefs.morningTime, "Morning time HH:MM")
        val evening = field(prefs.eveningTime, "Evening time HH:MM")
        val overlay = switch("Floating orb over other apps", prefs.overlayOn)
        val tgToken = field(prefs.telegramToken, "Telegram bot token (from @BotFather)", true)
        pairInfo = TextView(this).apply { setTextColor(Color.WHITE) }
        refreshPairInfo()

        val save = button("SAVE") {
            prefs.geminiKey = gemini.text.toString()
            prefs.groqKey = groq.text.toString()
            prefs.speechLocale = locale.text.toString().ifBlank { "ar-MA" }
            prefs.speakReplies = speak.isChecked
            prefs.mode = if (study.isChecked) "study" else "normal"
            prefs.briefingsOn = briefings.isChecked
            prefs.morningTime = morning.text.toString().ifBlank { "07:30" }
            prefs.eveningTime = evening.text.toString().ifBlank { "21:00" }
            prefs.overlayOn = overlay.isChecked
            prefs.telegramToken = tgToken.text.toString()
            Briefings.schedule(this)
            ContextCompat.startForegroundService(this, Intent(this, CoreService::class.java))
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refreshPairInfo()
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 96)

            addView(label("BRAIN 1: GEMINI")); addView(gemini)
            addView(label("BRAIN 2: GROQ (automatic fallback)")); addView(groq)
            addView(label("VOICE")); addView(locale); addView(speak)
            addView(label("MODE")); addView(study)
            addView(label("BRIEFINGS")); addView(briefings); addView(morning); addView(evening)
            addView(label("FLOATING ORB")); addView(overlay)
            addView(button("Allow display over other apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(label("TELEGRAM REMOTE CONTROL")); addView(tgToken); addView(pairInfo)
            addView(button("Unpair Telegram") {
                prefs.telegramChatId = ""
                prefs.resetPairCode()
                refreshPairInfo()
            })
            addView(label("PERMISSIONS (needed once)"))
            addView(button("1. Notification access (read and reply to messages)") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            addView(button("2. Accessibility (screen agent)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(button("App info (if Android blocks restricted settings)") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
            addView(label(""))
            addView(save)
            addView(button("ERASE MEMORY") {
                memory.forgetAll()
                Toast.makeText(this@SettingsActivity, "Memory erased", Toast.LENGTH_SHORT).show()
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#05080F")); addView(col)
        })
    }

    private fun refreshPairInfo() {
        pairInfo.text = when {
            prefs.telegramToken.isEmpty() -> "Paste the bot token, press SAVE, then send /pair CODE to your bot."
            prefs.telegramChatId.isNotEmpty() -> "Paired. Only your chat can control Jarvis."
            else -> "Send this to your bot to pair:  /pair ${prefs.pairCode}"
        }
    }
}
