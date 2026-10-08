package com.jarvis.assistant

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Keeps the Telegram bot running and draws the floating orb over other apps. */
class CoreService : Service() {

    private var botJob: Job? = null
    private var orb: OrbView? = null
    private var orbListener: ((OrbState) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, Notifier.ID, Notifier.build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val prefs = Prefs(this)

        if (botJob?.isActive != true) {
            val assistant = Assistant(applicationContext)
            botJob = Engine.scope.launch { TelegramBot(prefs, assistant).loop() }
        }

        if (prefs.overlayOn && Settings.canDrawOverlays(this)) showOrb() else hideOrb()
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
                    if (!moved) {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_LISTEN, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
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
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) {}
        }
        orb = null
    }

    override fun onDestroy() {
        hideOrb()
        botJob?.cancel()
        super.onDestroy()
    }
}
