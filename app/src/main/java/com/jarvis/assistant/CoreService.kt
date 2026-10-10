package com.jarvis.assistant

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Keeps the Telegram bot running and draws the floating orb and floating chat over other apps. */
class CoreService : Service() {

    private var botJob: Job? = null
    private var orb: OrbView? = null
    private var orbListener: ((OrbState) -> Unit)? = null
    private var assistant: Assistant? = null
    private var voice: Voice? = null
    private var chat: FloatingChat? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(): Boolean {
        val notif = Notifier.build(this)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val data = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // With the microphone type the floating chat can record while another app is in front.
        if (micOk) {
            try {
                ServiceCompat.startForeground(this, Notifier.ID, notif, data or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                return true
            } catch (e: Exception) { /* not allowed right now, fall back to data sync only */ }
        }
        return try {
            ServiceCompat.startForeground(this, Notifier.ID, notif, data)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        val prefs = Prefs(this)

        val a = assistant ?: Assistant(applicationContext).also { assistant = it }
        val v = voice ?: Voice(applicationContext, prefs).also { voice = it }
        if (chat == null) chat = FloatingChat(this, a, v)
        Engine.speaker = { text -> v.speak(text) }
        Scheduler.rearmAll(this)

        if (botJob?.isActive != true) {
            botJob = Engine.scope.launch { TelegramBot(prefs, a).loop() }
        }

        if (prefs.overlayOn && Settings.canDrawOverlays(this)) showOrb() else { hideOrb(); chat?.hide() }
        return START_STICKY
    }

    private fun showOrb() {
        if (orb != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = OrbView(this)
        val size = (76 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 400

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    wm.updateViewLayout(view, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) chat?.toggle()     // tap the orb: open or close the floating chat
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, lp)
        } catch (e: Exception) {
            return
        }
        orb = view
        view.state = Bus.last
        val l: (OrbState) -> Unit = { s -> view.post { view.state = s } }
        orbListener = l
        Bus.listeners.add(l)
    }

    private fun hideOrb() {
        orbListener?.let { Bus.listeners.remove(it) }
        orbListener = null
        orb?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) { /* gone */ }
        }
        orb = null
    }

    override fun onDestroy() {
        Engine.speaker = null
        chat?.hide()
        hideOrb()
        botJob?.cancel()
        voice?.shutdown()
        super.onDestroy()
    }
}
