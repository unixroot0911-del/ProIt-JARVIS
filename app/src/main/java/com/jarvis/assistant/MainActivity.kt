package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object { const val EXTRA_LISTEN = "listen" }

    private lateinit var prefs: Prefs
    private lateinit var memory: Memory
    private lateinit var brain: Brain
    private lateinit var voice: Voice
    private lateinit var actions: Actions

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        memory = Memory(this)
        brain = Brain(prefs, memory)
        voice = Voice(this, prefs)
        actions = Actions(this)

        buildUi()
        Notifier.show(this)
        requestPermissionsIfNeeded()
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) startListening()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05080F")) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }

        status = TextView(this).apply {
            text = "ONLINE"
            setTextColor(Color.parseColor("#2AA8FF"))
            textSize = 14f
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
        }
        orb = OrbView(this)
        transcript = TextView(this).apply {
            setTextColor(Color.parseColor("#CFE8FF"))
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        val talk = Button(this).apply {
            text = "TALK"
            setTextColor(Color.parseColor("#05080F"))
            setBackgroundColor(Color.parseColor("#2AA8FF"))
            setOnClickListener { startListening() }
        }
        val settings = Button(this).apply {
            text = "SETTINGS"
            setTextColor(Color.parseColor("#2AA8FF"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        column.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(transcript, LinearLayout.LayoutParams(-1, -2))
        column.addView(talk, LinearLayout.LayoutParams(-1, 160).apply { topMargin = 32 })
        column.addView(settings, LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    private fun setState(s: OrbState, label: String) {
        orb.state = s
        status.text = label
    }

    private fun startListening() {
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        busy = true
        setState(OrbState.LISTENING, "LISTENING")
        voice.listen(
            onPartial = { transcript.text = it },
            onResult = { text -> transcript.text = text; handle(text) },
            onFail = { msg -> transcript.text = msg; busy = false; setState(OrbState.IDLE, "ONLINE") }
        )
    }

    private fun handle(userText: String) {
        setState(OrbState.THINKING, "THINKING")
        lifecycleScope.launch {
            val d = brain.think(userText)
            memory.addTurn("user", userText)
            memory.addTurn("model", d.reply)
            d.remember?.let { memory.addFact(it) }

            var reply = d.reply
            val type = d.actionType
            val arg = d.actionArg
            if (type != null && arg != null) {
                if (Guard.needsConfirmation(type)) {
                    confirm(type, arg)
                } else {
                    actions.run(type, arg)?.let { reply = "$reply\n$it" }
                }
            }
            transcript.text = reply
            setState(OrbState.SPEAKING, "SPEAKING")
            voice.speak(d.reply)
            busy = false
            setState(OrbState.IDLE, "ONLINE")
        }
    }

    /** Protected actions (payments, deletions) are never executed without an explicit yes. */
    private fun confirm(type: String, arg: String) {
        AlertDialog.Builder(this)
            .setTitle("Confirm: $type")
            .setMessage(arg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm") { _, _ ->
                transcript.text = "Confirmed. '$type' is a protected action and is not wired up yet in this version."
            }
            .show()
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }
}
