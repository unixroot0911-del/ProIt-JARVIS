package com.jarvis.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Live watching and one-shot looking. Jarvis takes screenshots of the phone through the accessibility service
 * (Android 11+, no recording dialog), sends only frames that changed, and speaks only when it has something worth saying.
 */
object Watcher {

    @Volatile private var job: Job? = null
    @Volatile var topic: String = ""
    val active: Boolean get() = job?.isActive == true

    private const val SYSTEM = """You are JARVIS, watching the user's phone screen live while they do something.
You receive the current frame (a screenshot, or the screen text when pixels are unavailable) and the WATCH TASK.
Speak ONLY when it is genuinely useful: a danger, a mistake, a better move, an answer, something the user asked to be told, or a notable event.
Most of the time stay silent. When you speak: one short punchy sentence, at most 20 words, in the language the user normally uses with you
(Arabic or Darija if they speak it), sharp and confident, no honorifics. Never repeat what you already said.
If the screen shows passwords, banking details or private codes, stay silent.
Respond ONLY with JSON: {"say": "your sentence"} or {"say": ""} when there is nothing worth saying."""

    fun toBase64(bmp: Bitmap, max: Int): String {
        val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun signature(bmp: Bitmap): IntArray {
        val t = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val px = IntArray(24 * 24)
        t.getPixels(px, 0, 24, 0, 0, 24, 24)
        return IntArray(px.size) {
            val c = px[it]
            (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
        }
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var s = 0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private fun lowBattery(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return !bm.isCharging && pct in 1..9
    }

    private suspend fun say(ctx: Context, text: String) {
        withContext(Dispatchers.IO) { Memory.get(ctx).addChat("jarvis", text) }
        ChatBus.publish("jarvis", text)
        Engine.speaker?.invoke(text)
    }

    fun stop(): String {
        val was = active
        job?.cancel()
        job = null
        topic = ""
        return if (was) "Watch mode off." else "I was not watching."
    }

    fun start(ctx: Context, brain: Brain, instruction: String): String {
        JarvisAccessibilityService.instance
            ?: return "Watching needs the Jarvis screen agent. Open Settings > 2. Accessibility, turn Jarvis on, then ask again."
        val prefs = Prefs(ctx)
        val pixels = Build.VERSION.SDK_INT >= 30
        if (pixels && prefs.geminiKey.isEmpty()) return "Watching the screen needs a free Gemini key (vision). Add it in Settings."
        job?.cancel()
        topic = instruction.ifBlank { "Help me with whatever I am doing. Speak only when it really matters." }
        val every = prefs.watchSeconds.coerceIn(4, 120) * 1000L
        val maxMs = prefs.watchMinutes.coerceIn(5, 600) * 60_000L
        val app = ctx.applicationContext
        job = Engine.scope.launch { loop(app, brain, every, maxMs, pixels) }
        return "Watching your screen every ${every / 1000} seconds. " +
            (if (pixels) "" else "(Android 10 or older: I can read screen text, not pixels.) ") +
            "Say 'stop watching' to end it."
    }

    private suspend fun loop(ctx: Context, brain: Brain, every: Long, maxMs: Long, pixels: Boolean) {
        val started = System.currentTimeMillis()
        var prev: IntArray? = null
        var lastSent = 0L
        var lastText = ""
        var errors = 0
        val said = ArrayDeque<String>()

        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (now - started > maxMs) { say(ctx, "Watch mode ended: time limit reached. Ask again if you want me to keep watching."); return }
            if (lowBattery(ctx)) { say(ctx, "Watch mode stopped to protect your battery."); return }
            val svc = JarvisAccessibilityService.instance
            if (svc == null) { say(ctx, "Watch mode stopped: the screen agent was switched off."); return }

            val pkg = svc.rootInActiveWindow?.packageName?.toString() ?: ""
            if (pkg == ctx.packageName) { delay(every); continue }   // do not comment on my own window

            val stale = now - lastSent > 40_000
            val bmp: Bitmap? = if (pixels) svc.screenshot() else null
            var frame: String? = null
            var screenText = ""
            var send: Boolean
            if (bmp != null) {
                val sig = signature(bmp)
                val changed = prev?.let { diff(it, sig) >= 5 } ?: true
                prev = sig
                send = changed || stale
                if (send) frame = toBase64(bmp, 768)
            } else {
                screenText = svc.dump()?.text.orEmpty().take(1500)
                send = screenText.isNotBlank() && (screenText != lastText || stale)
            }

            if (send) {
                lastSent = now
                lastText = screenText
                val prompt = "WATCH TASK: $topic\nApp in front: $pkg\n" +
                    "Already said recently: ${said.joinToString(" / ").ifBlank { "nothing" }}\n" +
                    (if (screenText.isNotBlank()) "Screen text:\n$screenText\n" else "") +
                    "Look at the current frame."
                try {
                    val raw = brain.complete(SYSTEM, prompt, emptyList(), frame, json = true)
                    errors = 0
                    val s = try { JSONObject(Brain.extractJson(raw)).optString("say", "") } catch (e: Exception) { "" }
                    val line = s.trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
                    if (line != null && said.none { it.equals(line, true) }) {
                        said.addLast(line)
                        while (said.size > 4) said.removeFirst()
                        say(ctx, line)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors++
                    if (errors >= 4) { say(ctx, "Watch mode stopped: the brain keeps failing. ${e.message?.take(90) ?: ""}"); return }
                    delay(20_000)   // free tiers rate limit: back off
                }
            }
            delay(every)
        }
    }

    /** One look at the screen right now, answering [question]. */
    suspend fun see(ctx: Context, brain: Brain, question: String): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "Looking at your screen needs the Jarvis screen agent: Settings > 2. Accessibility."
        val pkg = svc.rootInActiveWindow?.packageName?.toString()
        if (pkg == ctx.packageName) {
            return "I am looking at my own window. Go to the app you mean and ask me from the floating chat (or Telegram)."
        }
        val q = question.ifBlank { "What is on my screen? Summarize it and tell me what matters." }
        val sys = brain.plainSystem() +
            "\nYou are looking at the user's phone screen right now. Answer about it directly and briefly, in the user's language. Plain text, no markdown."
        val bmp = if (Build.VERSION.SDK_INT >= 30) svc.screenshot() else null
        if (bmp != null) return brain.complete(sys, q, emptyList(), toBase64(bmp, 1024), json = false).trim()
        val t = svc.dump()?.text.orEmpty()
        if (t.isBlank()) return "I could not read the screen. It may be protected or locked."
        return brain.complete(sys, q + "\n\nSCREEN TEXT:\n" + t.take(3000), emptyList(), null, json = false).trim()
    }
}
