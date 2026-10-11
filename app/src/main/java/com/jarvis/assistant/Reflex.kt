package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot

/** A tiny copy of a screenshot for fast colour analysis. */
class Small(val w: Int, val h: Int, val px: IntArray)

/** One screenshot at a time, never closer than Android allows (about a third of a second), shared by the player and the controllers. */
object Shots {
    private val lock = Mutex()
    @Volatile private var last = 0L

    suspend fun take(svc: JarvisAccessibilityService): Bitmap? = lock.withLock {
        val wait = 340 - (System.currentTimeMillis() - last)
        if (wait > 0) delay(wait)
        last = System.currentTimeMillis()
        svc.screenshot()
    }
}

/** A continuous screenshot feed (about 3 per second) used by every running controller and by the AI, so nothing waits for a picture. */
object Hub {
    class Frame(val bmp: Bitmap, val small: Small, val at: Long)

    @Volatile var latest: Frame? = null
    @Volatile private var users = 0
    @Volatile private var job: Job? = null

    fun makeSmall(b: Bitmap): Small {
        val w = 96
        val h = (96f * b.height / b.width).toInt().coerceAtLeast(8)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return Small(w, h, px)
    }

    @Synchronized
    fun acquire(svc: JarvisAccessibilityService) {
        users++
        if (job?.isActive != true) job = Engine.scope.launch { loop(svc) }
    }

    @Synchronized
    fun release() {
        if (users > 0) users--
    }

    /** The newest frame if it is recent enough (only while the feed is running). */
    fun fresh(maxAgeMs: Long): Bitmap? {
        val f = latest ?: return null
        return if (users > 0 && System.currentTimeMillis() - f.at <= maxAgeMs) f.bmp else null
    }

    private suspend fun loop(svc: JarvisAccessibilityService) {
        var fails = 0
        while (users > 0) {
            val b = try { Shots.take(svc) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { Diag.record(e); null }
            if (b != null) {
                fails = 0
                latest = Frame(b, makeSmall(b), System.currentTimeMillis())
            } else {
                fails++
                if (fails >= 12) break
                delay(200)
            }
        }
    }
}

/**
 * Controllers: fast control loops that run on the phone in the BACKGROUND, about 3 times a second, while the AI keeps thinking.
 * The AI chooses what to follow (a colour: the road, an obstacle, an item, the player) and how to act; the controller keeps doing it
 * until the AI changes or stops it. Fingers it uses stay down between ticks (see [Touch]).
 *  steer     follow or avoid a colour while moving left and right (cars, runners, flyers, anything that steers sideways)
 *  chase     move toward or away from a colour in two dimensions with a joystick, swipes or taps (top-down games, snake, arenas)
 *  tap_color tap the lowest object of a colour again and again (piano tiles, falling items, targets)
 */
object Reflex {

    private class Ctl(val id: String, val spec: String, var job: Job?, var deadline: Long, @Volatile var status: String)

    private val ctls = ConcurrentHashMap<String, Ctl>()
    private val ended = ConcurrentHashMap<String, String>()

    val active: Boolean get() = ctls.isNotEmpty()

    /** [v] is a controller description, a list of them, or "stop". Returns a short note. */
    fun apply(ctx: Context, v: Any?): String {
        when (v) {
            is JSONObject -> return start(ctx, v)
            is JSONArray -> {
                val l = ArrayList<String>()
                for (i in 0 until minOf(v.length(), 3)) v.optJSONObject(i)?.let { l.add(start(ctx, it)) }
                return l.joinToString("; ")
            }
            is String -> {
                if (v.lowercase() in setOf("stop", "off", "none", "stop_all", "release")) { stopAll(); return "controllers stopped" }
            }
        }
        return ""
    }

    fun stopAll() {
        for (c in ctls.values) c.job?.cancel()
    }

    fun stop(id: String) {
        ctls[id]?.job?.cancel()
    }

    /** What the controllers are doing and why one ended, for the AI's next look. */
    fun statusText(): String {
        val sb = StringBuilder()
        for (c in ctls.values) sb.append("running ").append(c.id).append(": ").append(c.status).append(" | ")
        for ((k, v) in ended) sb.append("ended ").append(k).append(": ").append(v).append(" | ")
        ended.clear()
        val held = Touch.heldNames().filter { !it.startsWith("ctl_") && !it.startsWith("steer") && !it.startsWith("stick") && !it.startsWith("tmp") }
        if (held.isNotEmpty()) sb.append("fingers held down by me: ").append(held.joinToString(","))
        return sb.toString().trim().trimEnd('|').trim()
    }

    private fun start(ctx: Context, spec: JSONObject): String {
        val svc = JarvisAccessibilityService.instance ?: return "controller needs the screen agent"
        val id = spec.optString("id", "").ifBlank { spec.optString("kind", "steer") }.lowercase()
        val text = spec.toString()
        val dur = spec.optLong("duration_ms", 45_000).coerceIn(3_000, 120_000)
        val old = ctls[id]
        if (old != null && old.spec == text && old.job?.isActive == true) {
            old.deadline = System.currentTimeMillis() + dur   // same controller asked again: just keep it running longer
            return "$id extended"
        }
        old?.job?.cancel()
        Touch.start(ctx)
        Hub.acquire(svc)
        val c = Ctl(id, text, null, System.currentTimeMillis() + dur, "starting")
        ctls[id] = c
        val app = ctx.applicationContext
        c.job = Engine.scope.launch {
            val held = HashSet<String>()
            try {
                loop(app, c, spec, held)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (c.status.startsWith("starting") || c.status.startsWith("steer") || c.status.startsWith("chase") || c.status.startsWith("tap")) c.status += " (stopped)"
            } catch (e: Exception) {
                c.status = "failed: ${e.message}"
            } finally {
                for (n in held) Touch.release(n)
                Hub.release()
                if (ctls[id] === c) ctls.remove(id)
                ended[id] = c.status
            }
        }
        return "$id started"
    }

    private fun near(c: Int, t: Int, tol: Int): Boolean =
        abs(Color.red(c) - Color.red(t)) <= tol && abs(Color.green(c) - Color.green(t)) <= tol && abs(Color.blue(c) - Color.blue(t)) <= tol

    private fun colorFrom(o: JSONObject?, sm: Small): Int? {
        if (o == null) return null
        val hex = o.optString("color")
        if (hex.startsWith("#")) { try { return Color.parseColor(hex) } catch (e: Exception) { /* fall through */ } }
        val p = o.optJSONObject("sample") ?: return null
        val cx = (p.optDouble("x", 0.5) * sm.w).toInt().coerceIn(1, sm.w - 2)
        val cy = (p.optDouble("y", 0.5) * sm.h).toInt().coerceIn(1, sm.h - 2)
        var r = 0
        var g = 0
        var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val c = sm.px[(cy + dy) * sm.w + cx + dx]
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
        }
        return Color.rgb(r / 9, g / 9, b / 9)
    }

    private fun roiOf(o: JSONObject?, d: DoubleArray, sm: Small): IntArray {
        val x1 = ((o?.optDouble("x1", d[0]) ?: d[0]) * sm.w).toInt().coerceIn(0, sm.w - 2)
        val y1 = ((o?.optDouble("y1", d[1]) ?: d[1]) * sm.h).toInt().coerceIn(0, sm.h - 2)
        val x2 = ((o?.optDouble("x2", d[2]) ?: d[2]) * sm.w).toInt().coerceIn(x1 + 1, sm.w)
        val y2 = ((o?.optDouble("y2", d[3]) ?: d[3]) * sm.h).toInt().coerceIn(y1 + 1, sm.h)
        return intArrayOf(x1, y1, x2, y2)
    }

    private class Scan(val cov: Float, val cx: Float, val cy: Float, val left: Int, val right: Int)

    private fun scan(sm: Small, r: IntArray, target: Int, tol: Int, refX: Float): Scan {
        var n = 0
        var sx = 0L
        var sy = 0L
        var l = 0
        var rr = 0
        val refPx = refX * sm.w
        for (y in r[1] until r[3]) {
            val row = y * sm.w
            for (x in r[0] until r[2]) {
                if (near(sm.px[row + x], target, tol)) {
                    n++; sx += x; sy += y
                    if (x < refPx) l++ else rr++
                }
            }
        }
        val area = ((r[2] - r[0]) * (r[3] - r[1])).coerceAtLeast(1)
        return Scan(n.toFloat() / area,
            if (n > 0) (sx.toFloat() / n + 0.5f) / sm.w else -1f,
            if (n > 0) (sy.toFloat() / n + 0.5f) / sm.h else -1f, l, rr)
    }

    private suspend fun waitFrame(after: Long): Hub.Frame? {
        val end = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < end) {
            val f = Hub.latest
            if (f != null && f.at > after) return f
            delay(15)
        }
        return null
    }

    private fun xy(o: JSONObject?, dx: Double, dy: Double): FloatArray =
        floatArrayOf((o?.optDouble("x", dx) ?: dx).toFloat(), (o?.optDouble("y", dy) ?: dy).toFloat())

    private suspend fun loop(ctx: Context, c: Ctl, spec: JSONObject, held: MutableSet<String>) {
        val kind = spec.optString("kind", "steer").lowercase()
        val tol = spec.optInt("tol", 45).coerceIn(8, 120)
        val dead = spec.optDouble("dead", if (kind == "chase") 0.03 else 0.04).toFloat()
        val gain = spec.optDouble("gain", 1.0).toFloat()
        val control = spec.optString("control", if (kind == "chase") "joystick" else "buttons").lowercase()
        val avoid = spec.optBoolean("avoid", false)
        val ctrlY = spec.optDouble("ctrl_y", 0.7).toFloat()
        val laneStep = spec.optDouble("lane_step", 0.25).toFloat()
        var ref = spec.optDouble("ref_x", 0.5).toFloat()
        var refY = spec.optDouble("ref_y", 0.6).toFloat()

        var f = waitFrame(0L) ?: run { c.status = "no screenshots (needs Android 11+ and the accessibility screenshot permission)"; return }
        var sm = f.small
        val target = colorFrom(spec, sm) ?: run { c.status = "needs a colour or a sample point"; return }
        val selfColor = colorFrom(spec.optJSONObject("self"), sm)
        val hazColor = colorFrom(spec.optJSONObject("hazard"), sm)
        val dRoi = if (kind == "tap_color") doubleArrayOf(0.0, 0.3, 1.0, 0.85) else if (kind == "chase") doubleArrayOf(0.0, 0.1, 1.0, 0.95) else doubleArrayOf(0.1, 0.45, 0.9, 0.8)
        val roi = roiOf(spec.optJSONObject("roi"), dRoi, sm)
        val far: IntArray? = spec.optJSONObject("roi_far")?.let { roiOf(it, doubleArrayOf(0.2, 0.3, 0.8, 0.45), sm) }
        val selfRoi = roiOf(spec.optJSONObject("self")?.optJSONObject("roi"), doubleArrayOf(0.0, 0.4, 1.0, 0.95), sm)

        spec.optJSONObject("hold")?.let {
            val p = xy(it, 0.9, 0.8)
            val n = "ctl_${c.id}_hold"
            Touch.hold(n, p[0], p[1]); held.add(n)
        }
        val left = spec.optJSONObject("left")
        val right = spec.optJSONObject("right")
        val joy = xy(spec.optJSONObject("joy"), 0.2, 0.8)
        val joyR = spec.optDouble("radius", 0.07).toFloat()

        val first = scan(sm, roi, target, tol, ref)
        val warn = if (first.cov < 0.004f && kind != "tap_color") " WARNING: the sample colour is barely inside the roi, choose a point exactly on it" else ""

        val started = System.currentTimeMillis()
        var ticks = 0; var l = 0; var r = 0; var s = 0; var taps = 0
        var covSum = 0f
        var lost = 0
        var noFrame = 0
        var lastSwipe = 0L
        var finger = ref
        val seen = HashMap<Int, Long>()
        var reason = "time up"

        while (System.currentTimeMillis() < c.deadline) {
            val nf = waitFrame(f.at)
            if (nf == null) { noFrame++; if (noFrame >= 6) { reason = "screenshots stopped"; break }; continue }
            noFrame = 0
            f = nf
            sm = nf.small
            val now = System.currentTimeMillis()

            if (kind == "tap_color") {
                val found = ArrayList<FloatArray>()
                var y = roi[3] - 1
                while (y >= roi[1] && found.isEmpty()) {
                    val row = y * sm.w
                    var x = roi[0]
                    while (x < roi[2]) {
                        if (near(sm.px[row + x], target, tol)) {
                            var e = x
                            while (e + 1 < roi[2] && near(sm.px[row + e + 1], target, tol)) e++
                            val key = ((x + e) / 2) / 3
                            if (e - x >= 1 && now - (seen[key] ?: 0L) > 420) {
                                found.add(floatArrayOf(((x + e) / 2f + 0.5f) / sm.w, (maxOf(y - 2, roi[1]) + 0.5f) / sm.h))
                                seen[key] = now
                            }
                            x = e + 1
                        } else x++
                    }
                    y--
                }
                for ((i, p) in found.take(3).withIndex()) Touch.press(p[0], p[1], 40, i * 45L)
                taps += minOf(found.size, 3)
            } else if (kind == "chase") {
                val tgt = scan(sm, roi, target, tol, 0f)
                if (selfColor != null) {
                    val me = scan(sm, selfRoi, selfColor, tol, 0f)
                    if (me.cov >= 0.0005f) { ref = me.cx; refY = me.cy }
                }
                covSum += tgt.cov
                if (tgt.cov < 0.002f) {
                    lost++
                    Touch.release("stick")
                    s++
                } else {
                    lost = 0
                    var dx = tgt.cx - ref
                    var dy = tgt.cy - refY
                    if (avoid) { dx = -dx; dy = -dy }
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dist > dead) {
                        val ux = dx / dist
                        val uy = dy / dist
                        when (control) {
                            "swipe" -> if (now - lastSwipe > 450) {
                                Touch.swipe(0.5f, 0.5f, 0.5f + ux * 0.3f, 0.5f + uy * 0.3f, 120)
                                lastSwipe = now
                            }
                            "tap" -> if (now - lastSwipe > 400) { Touch.press(tgt.cx, tgt.cy, 40); lastSwipe = now }
                            else -> { Touch.drag("stick", joy[0] + ux * joyR, joy[1] + uy * joyR); held.add("stick") }
                        }
                        if (dx < 0) l++ else r++
                    } else {
                        Touch.release("stick")
                        s++
                    }
                }
            } else {
                // steer: follow the road, or avoid obstacles, sideways
                if (selfColor != null) {
                    val me = scan(sm, selfRoi, selfColor, tol, 0f)
                    if (me.cov >= 0.0005f) ref = 0.5f * ref + 0.5f * me.cx
                }
                val near1 = scan(sm, roi, target, tol, ref)
                covSum += near1.cov
                var err = 0f
                if (!avoid) {
                    if (near1.cov < 0.004f) lost++ else {
                        lost = 0
                        err = near1.cx - ref
                        if (far != null) {
                            val fs = scan(sm, far, target, tol, ref)
                            if (fs.cov >= 0.004f) err = 0.55f * err + 0.45f * (fs.cx - ref)   // the bend ahead pulls the steering early
                        }
                    }
                } else {
                    lost = 0
                    if (near1.cov >= 0.01f) {
                        err = ref - near1.cx
                        if (abs(err) < 0.06f) err = if (near1.left > near1.right) 0.2f else -0.2f
                    }
                }
                if (hazColor != null) {
                    val hs = scan(sm, roi, hazColor, tol, ref)
                    val tot = hs.left + hs.right
                    if (hs.cov > 0.015f && tot > 0) err += 0.30f * ((hs.left - hs.right).toFloat() / tot) * minOf(1f, hs.cov * 12f)   // walls and grass push back
                }
                err *= gain
                if (abs(err) > dead && lost == 0) {
                    when (control) {
                        "drag" -> {
                            val to = (if (!avoid && hazColor == null) near1.cx else finger + err.coerceIn(-0.25f, 0.25f)).coerceIn(0.05f, 0.95f)
                            Touch.drag("steer", to, ctrlY); held.add("steer")
                            if (to > finger) r++ else l++
                            finger = to
                        }
                        "swipe" -> {
                            if (now - lastSwipe > 650) {
                                val dir = if (err < 0) -1f else 1f
                                Touch.swipe(0.5f, ctrlY, 0.5f + 0.3f * dir, ctrlY, 110)
                                lastSwipe = now
                                if (selfColor == null) ref = (ref + laneStep * dir).coerceIn(0.1f, 0.9f)
                                if (dir < 0) l++ else r++
                            } else s++
                        }
                        else -> {
                            val b = if (err < 0) left else right
                            if (b != null) {
                                val p = xy(b, 0.5, 0.8)
                                if (abs(err) >= 0.18f) {
                                    Touch.hold("steer", p[0], p[1]); held.add("steer")      // sharp turn: keep the button pressed
                                } else {
                                    Touch.release("steer")                                    // gentle turn: a light pulse, in proportion
                                    Touch.press(p[0], p[1], (60 + abs(err) * 900).toLong().coerceIn(50, 330))
                                }
                                if (err < 0) l++ else r++
                            } else s++
                        }
                    }
                } else {
                    if (control == "buttons" || control == "drag") Touch.release("steer")
                    s++
                }
            }
            ticks++
            val secs = (now - started) / 1000.0
            c.status = if (kind == "tap_color") "tap_color ${"%.0f".format(secs)}s: $taps taps"
            else "$kind ${"%.0f".format(secs)}s: $ticks ticks, left $l, right $r, straight $s, target coverage ${(covSum / ticks.coerceAtLeast(1) * 100).toInt()}%$warn"
            if (lost >= 8) { reason = "lost sight of the target (game over, a menu, or off the road?)"; break }
            if (ticks % 12 == 0 && JarvisAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == ctx.packageName) { reason = "left the game"; break }
        }
        c.status += " | ended: $reason"
    }
}
