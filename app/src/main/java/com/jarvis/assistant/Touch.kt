package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * The touch engine. One pump sends every touch of a play session, so fingers can STAY DOWN between decisions:
 *  - hold("gas", x, y): a finger that keeps pressing (gas pedal, fire button, run) until release("gas"); it never lifts between two gestures
 *  - drag("stick", x, y): a finger that stays down and slides (joystick, drag-to-steer)
 *  - press / swipe: short touches scheduled at an exact time, at the same moment as the held fingers
 * Coordinates are fractions of the screen (0..1).
 */
object Touch {

    private class Hold(val x: Float, val y: Float, val move: Boolean)
    private class Item(val startAt: Long, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dur: Long, val done: CompletableDeferred<Unit>?)

    private val lock = Any()
    private val holds = LinkedHashMap<String, Hold>()
    private val queue = ArrayList<Item>()

    // pump-thread state: the stroke of each held finger that the next gesture continues
    private val cont = HashMap<String, GestureDescription.StrokeDescription>()
    private val contAt = HashMap<String, FloatArray>()

    @Volatile private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    private const val SEG = 320L

    fun heldNames(): List<String> = synchronized(lock) { holds.keys.toList() }

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        job = Engine.scope.launch { pump(app) }
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(lock) {
            holds.clear()
            queue.forEach { it.done?.complete(Unit) }
            queue.clear()
        }
        cont.clear()
        contAt.clear()
    }

    /** Keeps a finger down at (x, y); if it already is elsewhere it is lifted and pressed again at the new place. */
    fun hold(name: String, x: Float, y: Float) {
        synchronized(lock) { holds[name] = Hold(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), false) }
    }

    /** Keeps a finger down and slides it to (x, y) without lifting. */
    fun drag(name: String, x: Float, y: Float) {
        synchronized(lock) { holds[name] = Hold(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), true) }
    }

    fun release(name: String) {
        synchronized(lock) { holds.remove(name) }
    }

    fun releaseAll() {
        synchronized(lock) { holds.clear() }
    }

    fun press(x: Float, y: Float, ms: Long, delayMs: Long = 0, done: CompletableDeferred<Unit>? = null) {
        swipe(x, y, x, y, ms, delayMs, done)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, delayMs: Long = 0, done: CompletableDeferred<Unit>? = null) {
        val it = Item(System.currentTimeMillis() + delayMs.coerceAtLeast(0), x1.coerceIn(0f, 1f), y1.coerceIn(0f, 1f),
            x2.coerceIn(0f, 1f), y2.coerceIn(0f, 1f), ms.coerceIn(20, 900), done)
        synchronized(lock) { queue.add(it) }
    }

    /** Executes one step description through the pump and returns when its touches are done. */
    suspend fun exec(ctx: Context, type: String, o: JSONObject, jitter: Boolean): Boolean {
        fun j(): Float = if (jitter) Random.nextFloat() * 0.016f - 0.008f else 0f
        fun f(k: String, def: Double = 0.5) = (o.optDouble(k, def).toFloat() + j()).coerceIn(0f, 1f)
        fun ms(k: String, def: Long): Long {
            val v = o.optLong(k, def)
            return if (jitter) (v * (0.9 + Random.nextDouble() * 0.2)).toLong() else v
        }
        when (type) {
            "hold_on", "press_and_keep" -> { hold(o.optString("name", "finger"), f("x"), f("y")); return true }
            "hold_off", "release" -> { if (o.optString("name").isBlank()) releaseAll() else release(o.optString("name")); return true }
            "release_all" -> { releaseAll(); return true }
            "tap", "click" -> { val d = CompletableDeferred<Unit>(); press(f("x"), f("y"), 55, 0, d); d.await(); return true }
            "double_tap" -> {
                val x = f("x"); val y = f("y")
                val d = CompletableDeferred<Unit>()
                press(x, y, 45, 0); press(x, y, 45, 120, d); d.await(); return true
            }
            "repeat_tap", "rapid_tap", "spam_tap" -> {
                val n = o.optInt("count", 10).coerceIn(1, 400)
                val gap = o.optLong("interval_ms", 80).coerceIn(30, 2000)
                val x = f("x"); val y = f("y")
                var last: CompletableDeferred<Unit>? = null
                for (i in 0 until n) {
                    val d = if (i == n - 1) CompletableDeferred<Unit>() else null
                    press(x + (if (jitter) j() * 0.5f else 0f), y + (if (jitter) j() * 0.5f else 0f), 30, i * gap, d)
                    if (d != null) last = d
                }
                last?.await()
                return true
            }
            "hold", "long_press" -> {
                val x = f("x"); val y = f("y")
                val total = ms("ms", 800)
                if (total <= 900) { val d = CompletableDeferred<Unit>(); press(x, y, total, 0, d); d.await() }
                else { hold("tmp_long", x, y); delay(total.coerceAtMost(60_000)); release("tmp_long"); delay(60) }
                return true
            }
            "swipe", "drag" -> {
                val total = ms("ms", 300).coerceAtLeast(60)
                if (total <= 900) {
                    val d = CompletableDeferred<Unit>()
                    swipe(f("x1"), f("y1"), f("x2"), f("y2"), total, 0, d); d.await()
                } else {
                    // a long drag (joystick push): finger down at the start, slide to the end, stay a moment, lift
                    hold("tmp_drag", f("x1"), f("y1")); delay(120)
                    drag("tmp_drag", f("x2"), f("y2")); delay((total - 120).coerceAtMost(60_000)); release("tmp_drag"); delay(60)
                }
                return true
            }
            "pinch_in", "pinch_out" -> {
                val cx = f("x"); val cy = f("y")
                val out = type.contains("out")
                val near = 0.04f; val far = 0.22f
                val a = if (out) near else far
                val b = if (out) far else near
                val d = CompletableDeferred<Unit>()
                swipe(cx - a, cy, cx - b, cy, 450, 0); swipe(cx + a, cy, cx + b, cy, 450, 0, d); d.await()
                return true
            }
            "multi" -> {
                val ts = o.optJSONArray("touches") ?: return false
                var lastEnd = 0L
                var lastD: CompletableDeferred<Unit>? = null
                for (i in 0 until minOf(ts.length(), 20)) {
                    val t = ts.optJSONObject(i) ?: continue
                    val x = (t.optDouble("x", 0.5).toFloat() + j()).coerceIn(0f, 1f)
                    val y = (t.optDouble("y", 0.5).toFloat() + j()).coerceIn(0f, 1f)
                    val x2 = if (t.has("x2")) t.optDouble("x2", 0.5).toFloat() else x
                    val y2 = if (t.has("y2")) t.optDouble("y2", 0.5).toFloat() else y
                    val at = t.optLong("at", 0).coerceAtLeast(0)
                    val total = t.optLong("ms", 100)
                    if (total > 900 && x == x2 && y == y2) {
                        // a long press inside a timed sequence becomes a held finger between "at" and "at + ms"
                        val name = "tmp_multi_$i"
                        Engine.scope.launch { delay(at); hold(name, x, y); delay(total.coerceAtMost(60_000)); release(name) }
                    } else {
                        val d = CompletableDeferred<Unit>()
                        swipe(x, y, x2, y2, total, at, d)
                        if (at + total >= lastEnd) { lastEnd = at + total; lastD = d }
                    }
                    if (at + total > lastEnd) lastEnd = at + total
                }
                lastD?.await()
                if (lastEnd > 900) delay(0)
                return true
            }
        }
        return false
    }

    private suspend fun dispatch(svc: AccessibilityService, g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val ok = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    private fun path(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    private suspend fun pump(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        fun px(x: Float) = (x * w).coerceIn(1f, w - 1)
        fun py(y: Float) = (y * h).coerceIn(1f, h - 1)
        try {
            while (job?.isActive == true) {
                val svc = JarvisAccessibilityService.instance ?: break
                val now = System.currentTimeMillis()
                var due: List<Item> = emptyList()
                var hs: Map<String, Hold> = emptyMap()
                synchronized(lock) {
                    due = queue.filter { it.startAt < now + SEG }.take(6)
                    queue.removeAll(due.toSet())
                    hs = HashMap(holds)
                }

                val strokes = ArrayList<GestureDescription.StrokeDescription>()
                var len = 0L
                for (it in due) {
                    val off = (it.startAt - now).coerceAtLeast(0)
                    val p = Path().apply { moveTo(px(it.x1), py(it.y1)); if (it.x1 != it.x2 || it.y1 != it.y2) lineTo(px(it.x2), py(it.y2)) }
                    strokes.add(GestureDescription.StrokeDescription(p, off, it.dur))
                    len = maxOf(len, off + it.dur)
                }
                val glen = if (hs.isNotEmpty()) maxOf(len, SEG) else len

                // fingers that were let go: finish their stroke
                for (name in cont.keys.toList()) {
                    if (name !in hs) {
                        val at = contAt[name]
                        try {
                            if (at != null) strokes.add(cont[name]!!.continueStroke(path(at[0], at[1]), 0, 15, false))
                        } catch (e: Exception) { /* already ended */ }
                        cont.remove(name); contAt.remove(name)
                    }
                }
                // fingers that stay down: continue their stroke (slide when asked to), or lift and press again when they jump
                for ((name, hd) in hs) {
                    if (strokes.size >= 9) break
                    val tx = px(hd.x)
                    val ty = py(hd.y)
                    val prev = cont[name]
                    val at = contAt[name]
                    if (prev != null && at != null) {
                        val same = kotlin.math.abs(at[0] - tx) < 2f && kotlin.math.abs(at[1] - ty) < 2f
                        if (same || hd.move) {
                            val p = Path().apply { moveTo(at[0], at[1]); if (!same) lineTo(tx, ty) }
                            try {
                                val s = prev.continueStroke(p, 0, glen, true)
                                cont[name] = s; contAt[name] = floatArrayOf(tx, ty); strokes.add(s)
                                continue
                            } catch (e: Exception) { cont.remove(name); contAt.remove(name) }
                        } else {
                            // jump: lift the old position briefly, press the new one a moment later
                            try { strokes.add(prev.continueStroke(path(at[0], at[1]), 0, 15, false)) } catch (e: Exception) { /* ignore */ }
                            val s = GestureDescription.StrokeDescription(path(tx, ty), 40, (glen - 40).coerceAtLeast(40), true)
                            cont[name] = s; contAt[name] = floatArrayOf(tx, ty); strokes.add(s)
                            continue
                        }
                    }
                    val s = GestureDescription.StrokeDescription(path(tx, ty), 0, glen.coerceAtLeast(SEG), true)
                    cont[name] = s; contAt[name] = floatArrayOf(tx, ty); strokes.add(s)
                }

                if (strokes.isEmpty()) { delay(30); continue }
                val b = GestureDescription.Builder()
                for (s in strokes.take(10)) b.addStroke(s)
                val ok = dispatch(svc, b.build())
                if (!ok) { cont.clear(); contAt.clear() }   // the system cancelled it: every finger was lifted
                due.forEach { it.done?.complete(Unit) }
                if (!ok) delay(60)
            }
        } finally {
            // lift everything that is still down
            try {
                val svc = JarvisAccessibilityService.instance
                if (svc != null && cont.isNotEmpty()) {
                    val b = GestureDescription.Builder()
                    for (name in cont.keys.toList()) {
                        val at = contAt[name] ?: continue
                        try { b.addStroke(cont[name]!!.continueStroke(path(at[0], at[1]), 0, 15, false)) } catch (e: Exception) { /* ignore */ }
                    }
                    svc.dispatchGesture(b.build(), null, null)
                }
            } catch (e: Exception) { /* ignore */ }
            cont.clear(); contAt.clear()
            synchronized(lock) { queue.forEach { it.done?.complete(Unit) }; queue.clear() }
        }
    }
}
