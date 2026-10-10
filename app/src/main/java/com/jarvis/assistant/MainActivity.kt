package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object { const val EXTRA_LISTEN = "listen" }

    private lateinit var prefs: Prefs
    private lateinit var assistant: Assistant
    private lateinit var voice: Voice

    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var input: EditText

    private var busy = false
    private var pendingImage: String? = null
    private val photoFile: File by lazy { File(cacheDir, "images").apply { mkdirs() }.let { File(it, "shot.jpg") } }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) prepareImage() else transcript.text = "No photo taken."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        assistant = Assistant(this)
        voice = Voice(this, prefs)

        buildUi()
        requestPermissionsIfNeeded()
        ContextCompat.startForegroundService(this, Intent(this, CoreService::class.java))
        Briefings.schedule(this)
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LISTEN, false)) startListening()
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
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#05080F")) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 80, 40, 32)
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
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 16)
            maxLines = 8
        }
        input = EditText(this).apply {
            hint = "Type to Jarvis"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
            }
        }

        fun btn(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextColor(if (filled) Color.parseColor("#05080F") else Color.parseColor("#2AA8FF"))
            setBackgroundColor(if (filled) Color.parseColor("#2AA8FF") else Color.TRANSPARENT)
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("TALK", true) { startListening() }, LinearLayout.LayoutParams(0, 150, 1f))
        row.addView(btn("CHAT", true) { startActivity(Intent(this, ChatActivity::class.java)) }, LinearLayout.LayoutParams(0, 150, 1f).apply { marginStart = 16 })
        row.addView(btn("LOOK", true) { takeShot() }, LinearLayout.LayoutParams(0, 150, 1f).apply { marginStart = 16 })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btn("STOP AGENT", false) {
            val j = Engine.agentJob
            if (j?.isActive == true) { j.cancel(); transcript.text = "Agent stopped." } else transcript.text = "No agent is running."
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(btn("WATCH", false) {
            transcript.text = if (Watcher.active) Watcher.stop()
            else Watcher.start(this, assistant.brain, "Coach me: comment only when you notice something genuinely useful")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(btn("SETTINGS", false) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(0, -2, 1f))

        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        column.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(transcript, LinearLayout.LayoutParams(-1, -2))
        column.addView(input, LinearLayout.LayoutParams(-1, -2))
        column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 })
        column.addView(row2, LinearLayout.LayoutParams(-1, -2))
        column.addView(btn("MY APPS AND GAMES", false) { showCreations() }, LinearLayout.LayoutParams(-1, -2))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showCreations() {
        val names = Creations.names(this)
        val b = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("My apps and games")
        if (names.isEmpty()) b.setMessage("Nothing yet. Ask Jarvis: \"build me a game about ...\"")
        else b.setItems(names.toTypedArray()) { _, i -> Creations.find(this, names[i])?.let { Creations.open(this, it) } }
        b.setPositiveButton("Close", null).show()
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
        Bus.publish(s)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        busy = true
        transcript.text = t
        process(t)
    }

    private var listening = false

    private fun startListening() {
        if (listening) { voice.finishRecording(); return }   // tapping TALK again ends the recording
        if (busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            transcript.text = "Allow the microphone, then tap TALK again."
            requestPermissionsIfNeeded()
            return
        }
        busy = true
        listening = true
        setState(OrbState.LISTENING, "LISTENING  (tap TALK to finish)")
        lifecycleScope.launch {
            try {
                val wav = voice.record()
                listening = false
                if (wav == null) {
                    Engine.converse = false
                    transcript.text = "I did not hear anything."
                    busy = false
                    setState(OrbState.IDLE, "ONLINE")
                    return@launch
                }
                setState(OrbState.THINKING, "TRANSCRIBING")
                val text = assistant.brain.transcribe(wav)
                if (text.isBlank()) {
                    Engine.converse = false
                    transcript.text = "I did not catch that."
                    busy = false
                    setState(OrbState.IDLE, "ONLINE")
                    return@launch
                }
                transcript.text = text
                process(text)
            } catch (e: Exception) {
                listening = false
                busy = false
                transcript.text = e.message ?: "Microphone error."
                setState(OrbState.IDLE, "ONLINE")
            }
        }
    }

    private fun takeShot() {
        if (busy) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", photoFile)
            takePhoto.launch(uri)
        } catch (e: Exception) {
            transcript.text = "Camera unavailable: ${e.message}"
        }
    }

    private fun prepareImage() {
        lifecycleScope.launch {
            val b64 = withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    var bmp = BitmapFactory.decodeFile(photoFile.absolutePath, opts) ?: return@withContext null
                    val max = 1280
                    val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
                    if (scale < 1f) {
                        bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                    }
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                } catch (e: Exception) {
                    null
                }
            }
            if (b64 == null) {
                transcript.text = "Could not read the photo."
            } else {
                pendingImage = b64
                transcript.text = "Photo ready. Ask your question."
                startListening()
            }
        }
    }

    private fun process(userText: String) {
        setState(OrbState.THINKING, "THINKING")
        val img = pendingImage
        pendingImage = null
        lifecycleScope.launch {
            val r = assistant.handle(
                userText, img,
                confirm = { Confirmer.ask(this@MainActivity, it) },
                onProgress = { msg -> runOnUiThread { transcript.text = msg } }
            )
            transcript.text = r.shown
            if (!r.failed) {
                setState(OrbState.SPEAKING, "SPEAKING")
                if (!Voice.forced(this@MainActivity)) voice.speak(r.spoken)
                if (Engine.converse) voice.awaitSpeech()
            } else {
                Engine.converse = false
            }
            busy = false
            setState(OrbState.IDLE, "ONLINE")
            if (Engine.converse) startListening()
        }
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }
}
