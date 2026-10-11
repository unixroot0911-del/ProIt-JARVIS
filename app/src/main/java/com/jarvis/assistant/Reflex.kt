package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Reflexes: a fast control loop that runs on the phone itself, with no AI call per move.
 * The AI looks at the game, decides WHAT to follow (for example "the grey road ahead", "the red obstacles", "the black tiles"),
 * and this loop then watches that colour about 2 to 3 times a second and steers, dodges or taps for several seconds.
 * Then the AI looks again. This is how Jarvis can drive a car along a road or dodge things between its slow, careful decisions.
 */
object Reflex {
    @Volatile private var lastShot = 0L

    private class Small(val w: Int, val h: Int, val px: IntArray)

    /** Android allows a screenshot only every ~1/3 s. */
    private suspend fun grab(svc: JarvisAccessibilityService): Bitmap? {
        val wait = 360 - (System.currentTimeMillis() - lastShot)
        if (wait > 0) delay(wait)
        lastShot = System.currentTimeMillis()
        return svc.screenshot()
    }

    private fun small(b: Bitmap): Small {
        val w = 96
        val h = (96f * b.height / b.width).toInt().coerceAtLeast(8)
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        return Small(w, h, px)
    }

    private fun near(c: Int, t: Int, tol: Int): Boolean =
        abs(Color.red(c) - Color.red(t)) <= tol && abs(Color.green(c) - Color.green(t)) <= tol && abs(Color.blue(c) - Color.blue(t)) <= tol

    private fun sample(s: Small, p: JSONObject): Int {
        val cx = (p.optDouble("x", 0.5) * s.w).toInt().coerceIn(1, s.w - 2)
        val cy = (p.optDouble("y", 0.5) * s.h).toInt().coerceIn(1, s.h - 2)
        var r = 0
        var g = 0
        var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val c = s.px[(cy + dy) * s.w + cx + dx]
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
        }
        return Color.rgb(r / 9, g / 9, b / 9)
    }

    private fun touch(x: Double, y: Double, ms: Long, at: Long): JSONObject =
        JSONObject().put("x", x).put("y", y).put("ms", ms).put("at", at)

    suspend fun run(ctx: Context, svc: JarvisAccessibilityService, spec: JSONObject): String {
        val kind = spec.optString("kind", "steer").lowercase()
        val tick = spec.optLong("tick_ms", 400).coerceIn(360, 1500)
        val dur = spec.optLong("duration_ms", 8000).coerceIn(1500, 20_000)
        val tol = spec.optInt("tol", 45).coerceIn(8, 120)
        val dead = spec.optDouble("dead", 0.05).toFloat()
        val control = spec.optString("control", "buttons").lowercase()
        val avoid = spec.optBoolean("avoid", false)
        val ctrlY = spec.optDouble("ctrl_y", 0.7).toFloat()
        val laneStep = spec.optDouble("lane_step", 0.25).toFloat()
        var ref = spec.optDouble("ref_x", 0.5).toFloat()
        val ro = spec.optJSONObject("roi")
        val rx1 = (ro?.optDouble("x1", 0.1) ?: 0.1).toFloat()
        val ry1 = (ro?.optDouble("y1", 0.45) ?: 0.45).toFloat()
        val rx2 = (ro?.optDouble("x2", 0.9) ?: 0.9).toFloat()
        val ry2 = (ro?.optDouble("y2", 0.8) ?: 0.8).toFloat()

        var bmp = grab(svc) ?: return "Reflex could not take a screenshot (needs Android 11 or newer)."
        var sm = small(bmp)
        var col: Int? = null
        val hex = spec.optString("color")
        if (hex.startsWith("#")) col = try { Color.parseColor(hex) } catch (e: Exception) { null }
        if (col == null) spec.optJSONObject("sample")?.let { col = sample(sm, it) }
        val target: Int = col ?: return "Reflex needs a colour or a sample point."

        val x1 = (rx1 * sm.w).toInt().coerceIn(0, sm.w - 2)
        val x2 = (rx2 * sm.w).toInt().coerceIn(x1 + 1, sm.w)
        val y1 = (ry1 * sm.h).toInt().coerceIn(0, sm.h - 2)
        val y2 = (ry2 * sm.h).toInt().coerceIn(y1 + 1, sm.h)
        val area = ((x2 - x1) * (y2 - y1)).coerceAtLeast(1)

        val started = System.currentTimeMillis()
        var ticks = 0
        var l = 0
        var r = 0
        var s = 0
        var taps = 0
        var covSum = 0f
        var lost = 0
        var shotFail = 0
        var finger = ref
        var lastSwipe = 0L
        var ended = "time up"
        val seen = HashMap<Int, Long>()

        while (System.currentTimeMillis() - started < dur) {
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            val t = JSONArray()

            if (kind == "tap_color") {
                val found = ArrayList<DoubleArray>()
                var y = y2 - 1
                while (y >= y1 && found.isEmpty()) {
                    val row = y * sm.w
                    var x = x1
                    while (x < x2) {
                        if (near(sm.px[row + x], target, tol)) {
                            var e = x
                            while (e + 1 < x2 && near(sm.px[row + e + 1], target, tol)) e++
                            val key = ((x + e) / 2) / 3
                            if (e - x >= 1 && now - (seen[key] ?: 0L) > 420) {
                                val fx = ((x + e) / 2.0 + 0.5) / sm.w
                                val fy = (maxOf(y - 2, y1) + 0.5) / sm.h
                                found.add(doubleArrayOf(fx, fy))
                                seen[key] = now
                            }
                            x = e + 1
                        } else x++
                    }
                    y--
                }
                for ((i, f) in found.take(3).withIndex()) t.put(touch(f[0], f[1], 40, i * 45L))
                taps += minOf(found.size, 3)
            } else {
                var n = 0
                var sx = 0L
                var ln = 0
                var rn = 0
                val refPx = ref * sm.w
                for (y in y1 until y2) {
                    val row = y * sm.w
                    for (x in x1 until x2) {
                        if (near(sm.px[row + x], target, tol)) {
                            n++; sx += x
                            if (x < refPx) ln++ else rn++
                        }
                    }
                }
                val cov = n.toFloat() / area
                val cx = if (n > 0) (sx.toFloat() / n + 0.5f) / sm.w else ref
                covSum += cov
                var err = 0f
                if (!avoid) {
                    if (cov < 0.004f) lost++ else { lost = 0; err = cx - ref }
                } else {
                    lost = 0
                    if (cov >= 0.01f) {
                        err = ref - cx
                        if (abs(err) < 0.06f) err = if (ln > rn) 0.2f else -0.2f
                    }
                }
                spec.optJSONObject("hold")?.let { t.put(touch(it.optDouble("x", 0.5), it.optDouble("y", 0.9), tick, 0)) }
                if (abs(err) > dead && lost == 0) {
                    when (control) {
                        "drag" -> {
                            val to = (if (!avoid) cx else finger + err.coerceIn(-0.25f, 0.25f)).coerceIn(0.05f, 0.95f)
                            t.put(JSONObject().put("x", finger.toDouble()).put("y", ctrlY.toDouble()).put("x2", to.toDouble()).put("y2", ctrlY.toDouble())
                                .put("ms", (tick * 0.8).toLong()).put("at", 10))
                            if (to > finger) r++ else l++
                            finger = to
                        }
                        "swipe" -> {
                            if (now - lastSwipe > 650) {
                                val dir = if (err < 0) -1f else 1f
                                t.put(JSONObject().put("x", 0.5).put("y", ctrlY.toDouble()).put("x2", (0.5f + 0.3f * dir).toDouble())
                                    .put("y2", ctrlY.toDouble()).put("ms", 110).put("at", 0))
                                lastSwipe = now
                                ref = (ref + laneStep * dir).coerceIn(0.1f, 0.9f)
                                if (dir < 0) l++ else r++
                            } else s++
                        }
                        else -> {
                            val b = spec.optJSONObject(if (err < 0) "left" else "right")
                            if (b != null) {
                                val ms = (80 + abs(err) * 1000).toLong().coerceIn(70, tick - 20)
                                t.put(touch(b.optDouble("x", 0.5), b.optDouble("y", 0.8), ms, 10))
                                if (err < 0) l++ else r++
                            } else s++
                        }
                    }
                } else s++
            }
            ticks++

            // do this tick's touches while the next screenshot is taken at the same time
            coroutineScope {
                val g = async { if (t.length() > 0) Steps.multi(ctx, svc, t, false) else { delay(tick); true } }
                val sh = async { grab(svc) }
                g.await()
                val nb = sh.await()
                if (nb != null) { bmp = nb; sm = small(nb); shotFail = 0 } else shotFail++
            }
            if (shotFail >= 6) { ended = "screenshots keep failing"; break }
            if (lost >= 6) { ended = "lost sight of the target (game over, menu, or off the road?)"; break }
            if (ticks % 6 == 0 && svc.rootInActiveWindow?.packageName?.toString() == ctx.packageName) { ended = "left the game"; break }
        }
        val secs = (System.currentTimeMillis() - started) / 1000.0
        return if (kind == "tap_color") "Reflex tap_color ran ${"%.1f".format(secs)}s: $ticks ticks, $taps taps. Ended: $ended."
        else "Reflex steer ran ${"%.1f".format(secs)}s: $ticks ticks, left $l, right $r, straight $s, target coverage ${(covSum / ticks.coerceAtLeast(1) * 100).toInt()}%. Ended: $ended."
    }
}
