package com.jarvis.assistant

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

/**
 * Notices when the USER touches the phone while Jarvis is playing or running a macro, freezes Jarvis at once
 * (every finger lifted, queued touches dropped, the held fingers remembered), and carries on from the same point
 * when the user has finished. Jarvis' own touches are recognised by their timing and ignored.
 */
object UserWatch {
    @Volatile var armed = false
    @Volatile var gamePkg: String? = null
    @Volatile var lastUser = 0L
    /** Increases at every takeover so a plan made before it is never carried out after it. */
    @Volatile var epoch = 0
    private val own = ConcurrentLinkedQueue<Long>()
    private var watcher: Job? = null
    private var overlay: View? = null
    private var appId = ""

    /** Jarvis is about to put a finger down at this time. */
    fun own(at: Long = System.currentTimeMillis()) {
        own.add(at)
        val old = System.currentTimeMillis() - 4000
        while (true) { val h = own.peek() ?: break; if (h < old) own.poll() else break }
    }

    private fun mine(now: Long): Boolean = own.any { abs(it - now) < 350 }

    fun onTouch() {
        if (!armed) return
        val now = System.currentTimeMillis()
        if (mine(now)) return
        lastUser = now
        if (!Touch.paused) { epoch++; Touch.pause() }
    }

    fun install(svc: JarvisAccessibilityService) {
        appId = svc.packageName
        Handler(Looper.getMainLooper()).post {
            try {
                if (overlay != null) return@post
                val v = View(svc)
                v.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onTouch(); false }
                val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT)
                lp.gravity = Gravity.TOP or Gravity.START
                (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
                overlay = v
            } catch (e: Throwable) { Diag.record(e) }
        }
    }

    fun remove(svc: JarvisAccessibilityService) {
        val v = overlay ?: return
        overlay = null
        Handler(Looper.getMainLooper()).post {
            try { (svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Throwable) { /* ignore */ }
        }
    }

    fun arm(pkg: String?) {
        gamePkg = pkg?.takeIf { it.isNotBlank() }
        armed = true
        if (watcher?.isActive != true) watcher = Engine.scope.launch { while (armed) { try { tick() } catch (e: Exception) { Diag.record(e) }; delay(300) } }
    }

    fun disarm() {
        armed = false
        Touch.resume()
    }

    private fun tick() {
        val svc = JarvisAccessibilityService.instance ?: return
        val front = svc.rootInActiveWindow?.packageName?.toString() ?: ""
        val now = System.currentTimeMillis()
        if (!Touch.paused) {
            if (front.contains("systemui", true)) { lastUser = now; epoch++; Touch.pause() }   // notifications or quick settings pulled down
            return
        }
        val idle = now - lastUser
        val gp = gamePkg
        val go = when {
            front.isEmpty() || front.contains("systemui", true) -> false
            gp != null && front == gp -> idle >= 2500
            front == appId -> idle >= 25_000
            gp != null -> idle >= 10_000
            else -> idle >= 2500
        }
        if (go) { resumedAt = now; Touch.resume() }
    }

    @Volatile var resumedAt = 0L
}

/** Finds a button or label by its visible text or description, so Jarvis can press "Play" instead of guessing where it is. */
object TextTap {
    private fun label(n: AccessibilityNodeInfo): String =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString()).joinToString(" ").lowercase().trim()

    fun find(svc: JarvisAccessibilityService, text: String, exact: Boolean): List<AccessibilityNodeInfo> {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val root = svc.rootInActiveWindow ?: return emptyList()
        val hits = ArrayList<Pair<Int, AccessibilityNodeInfo>>()
        var seen = 0
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (seen++ > 900 || d > 45) return
            if (n.isVisibleToUser) {
                val l = label(n)
                if (l.isNotEmpty()) {
                    val own = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any { it.trim().lowercase() == q }
                    if (own) hits.add(0 to n) else if (!exact && l.contains(q)) hits.add(1 to n)
                }
            }
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; walk(c, d + 1) }
        }
        walk(root, 0)
        val r = Rect(); val r2 = Rect()
        return hits.sortedWith { a, b ->
            if (a.first != b.first) a.first - b.first else {
                a.second.getBoundsInScreen(r); b.second.getBoundsInScreen(r2)
                if (r.top != r2.top) r.top - r2.top else r.left - r2.left
            }
        }.map { it.second }
    }

    fun tap(svc: JarvisAccessibilityService, text: String, nth: Int, exact: Boolean): Boolean {
        val hits = find(svc, text, exact)
        val node = hits.getOrNull(nth.coerceIn(0, maxOf(0, hits.size - 1))) ?: return false
        UserWatch.own()
        return svc.click(node) || svc.tapNode(node)
    }

    suspend fun waitFor(svc: JarvisAccessibilityService, text: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms.coerceIn(500, 30_000)
        while (System.currentTimeMillis() < end) {
            if (find(svc, text, false).isNotEmpty()) return true
            delay(250)
        }
        return false
    }
}
