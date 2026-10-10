package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var pairInfo: TextView
    private lateinit var statusView: TextView
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
        val force = switch("FORCE VOICE: speak everything aloud, always (alarm-level volume; ignores headphones, night and quiet rules)", prefs.forceVoice)
        val sttAccurate = switch("Voice: Gemini first (more accurate, slower)", prefs.sttGeminiFirst)
        val study = switch("Study mode (tutor + quizzes)", prefs.mode == "study")
        val briefings = switch("Morning and evening briefings", prefs.briefingsOn)
        val morning = field(prefs.morningTime, "Morning time HH:MM")
        val evening = field(prefs.eveningTime, "Evening time HH:MM")
        val watchEvery = field(prefs.watchSeconds.toString(), "Seconds between looks (default 10)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val watchMax = field(prefs.watchMinutes.toString(), "Auto-stop after minutes (default 90)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val overlay = switch("Floating Jarvis: orb + chat window over other apps", prefs.overlayOn)
        val tgToken = field(prefs.telegramToken, "Telegram bot token (from @BotFather)", true)
        pairInfo = TextView(this).apply { setTextColor(Color.WHITE) }
        statusView = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; text = Health.report(this@SettingsActivity) }
        val testResult = TextView(this).apply { setTextColor(Color.WHITE); setPadding(0, 16, 0, 16) }
        refreshPairInfo()

        val save = button("SAVE") {
            prefs.geminiKey = gemini.text.toString()
            prefs.groqKey = groq.text.toString()
            prefs.speechLocale = locale.text.toString().ifBlank { "ar-MA" }
            prefs.speakReplies = speak.isChecked
            prefs.forceVoice = force.isChecked
            prefs.sttGeminiFirst = sttAccurate.isChecked
            prefs.mode = if (study.isChecked) "study" else "normal"
            prefs.briefingsOn = briefings.isChecked
            prefs.morningTime = morning.text.toString().ifBlank { "07:30" }
            prefs.eveningTime = evening.text.toString().ifBlank { "21:00" }
            prefs.overlayOn = overlay.isChecked
            prefs.watchSeconds = watchEvery.text.toString().toIntOrNull() ?: 10
            prefs.watchMinutes = watchMax.text.toString().toIntOrNull() ?: 90
            prefs.telegramToken = tgToken.text.toString()
            Briefings.schedule(this)
            ContextCompat.startForegroundService(this, Intent(this, CoreService::class.java))
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refreshPairInfo()
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 96)

            addView(label("SETUP STATUS: what works and what is missing")); addView(statusView)
            addView(label("BRAIN 1: GEMINI")); addView(gemini)
            addView(label("BRAIN 2: GROQ (automatic fallback)")); addView(groq)
            addView(label("VOICE")); addView(locale); addView(speak); addView(force); addView(sttAccurate)
            addView(button("TEST VOICE (can Jarvis speak on this phone?)") {
                prefs.forceVoice = force.isChecked
                prefs.speakReplies = speak.isChecked
                testResult.text = "Testing voice..."
                val v = Voice(this@SettingsActivity, prefs)
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1800)
                    testResult.text = v.status()
                    v.speak("Jarvis voice test. If you can hear this, I can speak.", true)
                    kotlinx.coroutines.delay(7000)
                    v.shutdown()
                }
            })
            addView(button("Voice engine settings (install the Arabic voice)") {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            })
            addView(label("MODE")); addView(study)
            addView(label("BRIEFINGS")); addView(briefings); addView(morning); addView(evening)
            addView(label("WATCH MODE (live screen watching)")); addView(watchEvery); addView(watchMax)
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
            addView(button("3. Phone permissions (contacts, SMS, calendar, location)") {
                ActivityCompat.requestPermissions(this@SettingsActivity, arrayOf(
                    Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ), 7)
            })
            addView(button("4. Battery: run unrestricted (keeps alarms, Telegram, watch alive)") {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
            addView(button("5. Exact alarm timing (Android 12+)") {
                if (Build.VERSION.SDK_INT >= 31) {
                    try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) { /* not needed */ }
                }
            })
            addView(label("UPDATES"))
            addView(button("CHECK FOR UPDATE AND INSTALL") {
                testResult.text = "Checking..."
                lifecycleScope.launch { testResult.text = Updater.check(this@SettingsActivity, true) }
            })
            addView(label("DIAGNOSTICS"))
            addView(button("TEST BRAINS") {
                prefs.geminiKey = gemini.text.toString()
                prefs.groqKey = groq.text.toString()
                testResult.text = "Testing..."
                lifecycleScope.launch { testResult.text = Brain(prefs, memory).diagnose() }
            })
            addView(testResult)
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

    override fun onResume() {
        super.onResume()
        statusView.text = Health.report(this)
    }

    private fun refreshPairInfo() {
        pairInfo.text = when {
            prefs.telegramToken.isEmpty() -> "Paste the bot token, press SAVE, then send /pair CODE to your bot."
            prefs.telegramChatId.isNotEmpty() -> "Paired. Only your chat can control Jarvis."
            else -> "Send this to your bot to pair:  /pair ${prefs.pairCode}"
        }
    }
}
