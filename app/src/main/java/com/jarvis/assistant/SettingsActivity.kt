package com.jarvis.assistant

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val memory = Memory(this)

        fun label(t: String) = TextView(this).apply {
            text = t; setTextColor(Color.parseColor("#2AA8FF")); setPadding(0, 40, 0, 8)
        }
        fun field(value: String, hint: String, secret: Boolean = false) = EditText(this).apply {
            setText(value); this.hint = hint
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val gemini = field(prefs.geminiKey, "Gemini API key (free: aistudio.google.com)", true)
        val groq = field(prefs.groqKey, "Groq API key (free: console.groq.com)", true)
        val locale = field(prefs.speechLocale, "Speech language, e.g. ar-MA, ar-SA, en-US")
        val speak = Switch(this).apply {
            text = "Speak replies"; isChecked = prefs.speakReplies; setTextColor(Color.WHITE)
        }
        val save = Button(this).apply {
            text = "SAVE"
            setOnClickListener {
                prefs.geminiKey = gemini.text.toString()
                prefs.groqKey = groq.text.toString()
                prefs.speechLocale = locale.text.toString().ifBlank { "ar-MA" }
                prefs.speakReplies = speak.isChecked
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        val wipe = Button(this).apply {
            text = "ERASE MEMORY"
            setOnClickListener {
                memory.forgetAll()
                Toast.makeText(this@SettingsActivity, "Memory erased", Toast.LENGTH_SHORT).show()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(label("BRAIN 1: GEMINI")); addView(gemini)
            addView(label("BRAIN 2: GROQ (automatic fallback)")); addView(groq)
            addView(label("VOICE")); addView(locale); addView(speak)
            addView(save); addView(wipe)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#05080F")); addView(col)
        })
    }
}
