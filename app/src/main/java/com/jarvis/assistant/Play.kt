package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random

/**
 * Real touch gestures, as fractions of the screen (0..1): tap, double tap, hold, swipe, pinch, plus back, home, open an app, type.
 * Used by the AI player and by saved macros. Small random jitter in position and timing makes the touches look human.
 */
object Steps {

    private suspend fun gesture(svc: JarvisAccessibilityService, vararg strokes: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val b = GestureDescription.Builder()
                strokes.forEach { b.addStroke(it) }
                val ok = svc.dispatchGesture(b.build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
                if (!ok && cont.isActive) cont.resume(false)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, w: Float, h: Float): GestureDescription.StrokeDescription {
        val p = Path()
        p.moveTo(x1.coerceIn(1f, w - 1), y1.coerceIn(1f, h - 1))
        if (x1 != x2 || y1 != y2) p.lineTo(x2.coerceIn(1f, w - 1), y2.coerceIn(1f, h - 1))
        return GestureDescription.StrokeDescription(p, 0, ms.coerceIn(10, 20_000))
    }

    suspend fun exec(ctx: Context, svc: JarvisAccessibilityService, o: JSONObject, jitter: Boolean): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        fun j(): Float = if (jitter) Random.nextFloat() * 0.016f - 0.008f else 0f
        fun fx(k: String, def: Double = 0.5) = ((o.optDouble(k, def).toFloat() + j()).coerceIn(0f, 1f)) * w
        fun fy(k: String, def: Double = 0.5) = ((o.optDouble(k, def).toFloat() + j()).coerceIn(0f, 1f)) * h
        fun ms(k: String, def: Long): Long {
            val v = o.optLong(k, def)
            return if (jitter) (v * (0.9 + Random.nextDouble() * 0.2)).toLong() else v
        }
        return when ((o.optString("do").ifBlank { o.optString("type") }).lowercase()) {
            "tap", "click" -> gesture(svc, stroke(fx("x"), fy("y"), fx("x"), fy("y"), 55, w, h).let { it })
            "double_tap" -> {
                val x = fx("x"); val y = fy("y")
                val a = gesture(svc, stroke(x, y, x, y, 50, w, h))
                delay(90)
                gesture(svc, stroke(x, y, x, y, 50, w, h)) && a
            }
            "hold", "long_press" -> {
                val x = fx("x"); val y = fy("y")
                gesture(svc, stroke(x, y, x, y, ms("ms", 800), w, h))
            }
            "swipe", "drag" -> gesture(svc, stroke(fx("x1"), fy("y1"), fx("x2"), fy("y2"), ms("ms", 300).coerceAtLeast(60), w, h))
            "pinch_in", "pinch_out" -> {
                val cx = fx("x"); val cy = fy("y")
                val near = w * 0.04f
                val far = w * 0.22f
                val out = (o.optString("do") + o.optString("type")).contains("out")
                val a = if (out) near else far
                val b = if (out) far else near
                gesture(svc,
                    stroke(cx - a, cy, cx - b, cy, 450, w, h),
                    stroke(cx + a, cy, cx + b, cy, 450, w, h))
            }
            "wait", "sleep" -> { delay(ms("ms", 1000).coerceIn(50, 30_000)); true }
            "back" -> svc.back()
            "home" -> svc.home()
            "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "open_app" -> {
                val a = Actions(ctx)
                val r = a.openApp(o.optString("app"), false)
                delay(2500)
                r != null
            }
            "type" -> {
                val f = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (f != null) svc.setText(f, o.optString("text")) else false
            }
            else -> false
        }
    }
}

/** Limits like "30 minutes", "2 hours", "100 times", "forever". */
class Limit(val ms: Long, val cycles: Int, val forever: Boolean)

private fun parseLimit(raw: String, defaultMs: Long): Limit {
    val s = raw.trim().lowercase()
    if (s.isEmpty()) return Limit(defaultMs, Int.MAX_VALUE, false)
    if (Regex("forever|unlimited|infinite|always|non ?stop|until i say|دائما|دائماً|بلا حدود|لا نهائي|للأبد|للابد|بدون توقف").containsMatchIn(s)) {
        return Limit(8 * 3600_000L, Int.MAX_VALUE, true)
    }
    Regex("""(\d+)\s*(times|time|x|loops?|cycles?|rounds?|مرة|مرات|دورة|جولة|جولات)""").find(s)?.let {
        return Limit(8 * 3600_000L, it.groupValues[1].toInt().coerceIn(1, 100_000), false)
    }
    Regex("""(\d+(?:[.,]\d+)?)\s*(hours?|hrs?|h|ساعة|ساعات|ساعه)""").find(s)?.let {
        return Limit((it.groupValues[1].replace(',', '.').toDouble() * 3600_000).toLong().coerceIn(60_000, 8 * 3600_000L), Int.MAX_VALUE, false)
    }
    Regex("""(\d+(?:[.,]\d+)?)\s*(minutes?|mins?|m|دقيقة|دقائق|دقايق|دقيقه)""").find(s)?.let {
        return Limit((it.groupValues[1].replace(',', '.').toDouble() * 60_000).toLong().coerceIn(30_000, 8 * 3600_000L), Int.MAX_VALUE, false)
    }
    Regex("""(\d+)\s*(seconds?|secs?|s|ثانية|ثواني|ثانيه)""").find(s)?.let {
        return Limit((it.groupValues[1].toLong() * 1000).coerceIn(10_000, 8 * 3600_000L), Int.MAX_VALUE, false)
    }
    s.toIntOrNull()?.let { return Limit((it * 60_000L).coerceIn(30_000, 8 * 3600_000L), Int.MAX_VALUE, false) }   // a bare number = minutes
    return Limit(defaultMs, Int.MAX_VALUE, false)
}

/**
 * Plays games and runs apps for the user on demand, as many times as they ask.
 * Two engines: the AI player (looks at the screen and decides each move) and macros (saved touch routines, no AI, no limits).
 */
object Player {

    @Volatile private var job: Job? = null
    @Volatile var label: String = ""
    val active: Boolean get() = job?.isActive == true

    private const val CH = "jarvis_play"
    private const val NID = 7001

    private val payRx = Regex(
        "(confirm (?:your )?(?:purchase|payment|order)|buy now|subscribe|subscription|pay now|place order|add payment|payment method|" +
            "اشترِ الآن|اشتر الآن|تأكيد الشراء|ادفع|الدفع|اشتراك|confirmer l'achat|acheter)",
        RegexOption.IGNORE_CASE
    )

    private const val SYSTEM = """You are JARVIS, playing a game or operating an app on the user's Android phone like a skilled human player, using touch gestures.
Each turn you receive a screenshot of the phone, sometimes the screen text, the GOAL, and your own notes from earlier turns.
Respond ONLY with JSON:
{"actions":[ up to 10 steps ], "next_ms": 1500, "note": "short memory for yourself: what you are doing, what you learned, level or progress", "say": "", "done": false}
Each step is one of (x and y are fractions 0..1 of the screen width and height, from the top-left corner; aim at the CENTER of the target in the screenshot):
 {"do":"tap","x":0.5,"y":0.5}  {"do":"double_tap","x":..,"y":..}  {"do":"hold","x":..,"y":..,"ms":800}
 {"do":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"ms":300}  {"do":"pinch_in","x":..,"y":..}  {"do":"pinch_out","x":..,"y":..}
 {"do":"wait","ms":800}  {"do":"back"}  {"do":"open_app","app":"name"}  {"do":"type","text":"..."}
Rules:
- Play to achieve the GOAL. Chain several steps in one turn when you know the sequence (many quick taps for a clicker, a swipe path, a series of moves). Add short waits between steps when the game needs time.
- Games with slow decisions (idle, clicker, puzzle, card, board, turn-based, match-3, quests, daily routines) are your strength. If the game is too fast to react to, do the best you can with long, planned swipe or tap sequences.
- Close ads and popups (look for X, Close, Skip, No thanks, Later). Claim FREE rewards and daily bonuses. Dismiss rating prompts.
- NEVER spend real money: never tap Buy, Subscribe, Pay, Purchase, Install, Update, or anything with a price. Never enter passwords, card numbers or personal data. If a payment screen appears, press back.
- If the wrong app is in front (home screen, launcher, other app), use open_app with the right name.
- Never repeat a step that visibly did nothing: try a different spot, a swipe, back, or another approach.
- "done": true ONLY when the goal is fully achieved or truly impossible; then "say" explains in one sentence. While playing, "say" stays "" except for a real milestone (level up, new record, a problem): one short sentence in the user's language (Arabic or Darija if they use it).
- "next_ms": how long to wait before the next turn (1000 to 8000)."""

    private fun report(ctx: Context, text: String) {
        ChatBus.publish("jarvis", text)
        Engine.scope.launch(Dispatchers.IO) {
            try { Memory.get(ctx).addChat("jarvis", text) } catch (e: Exception) { /* ignore */ }
            try { Telegram.sendToOwner(Prefs(ctx), text) } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun notifyOn(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH, "Jarvis playing", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            val stop = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, PlayStopReceiver::class.java).setAction("jarvis.play.stop"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val act = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), "STOP", stop).build()
            nm.notify(NID, Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Jarvis is playing")
                .setContentText(text)
                .setOngoing(true)
                .addAction(act)
                .build())
        } catch (e: Exception) { /* notifications may be off */ }
    }

    private fun notifyOff(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID) } catch (e: Exception) { /* ignore */ }
    }

    private fun lowBattery(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return !bm.isCharging && pct in 1..9
    }

    private fun locked(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    @Suppress("DEPRECATION")
    private fun wake(ctx: Context, ms: Long): PowerManager.WakeLock? = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val l = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:play")
        l.acquire(ms.coerceAtMost(8 * 3600_000L))
        l
    } catch (e: Exception) { null }

    fun stop(): String {
        val was = active
        job?.cancel()
        job = null
        label = ""
        return if (was) "Stopped playing." else ""
    }

    /** Stops the screen agent, the player or macro, and (with [all]) screen watching. */
    fun stopAll(all: Boolean): String {
        val parts = ArrayList<String>()
        val j = Engine.agentJob
        if (j?.isActive == true) { j.cancel(); parts.add("agent") }
        if (active) { stop(); parts.add("player") }
        if (all && Watcher.active) { Watcher.stop(); parts.add("watching") }
        return if (parts.isEmpty()) "Nothing is running." else "Stopped: " + parts.joinToString(", ") + "."
    }

    private fun sig(bmp: Bitmap): IntArray {
        val t = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val px = IntArray(24 * 24)
        t.getPixels(px, 0, 24, 0, 0, 24, 24)
        return IntArray(px.size) { val c = px[it]; (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10 }
    }

    private fun diff(a: IntArray, b: IntArray): Int {
        var s = 0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private fun resolveApp(ctx: Context, name: String): String? {
        if (name.isBlank()) return null
        val a = Actions(ctx)
        return a.findApp(name) ?: a.mentions(name).firstOrNull()?.let { a.findApp(it) }
    }

    private fun launchPkg(ctx: Context, pkg: String) {
        try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun preflight(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 24) return "Playing needs Android 7 or newer."
        JarvisAccessibilityService.instance
            ?: return "Playing needs the Jarvis screen agent. Settings > 2. Accessibility: turn Jarvis off and on again once so it gets the touch permission, then ask again."
        if (active) return "I am already playing \"$label\". Say \"stop playing\" first."
        if (locked(ctx)) return "Your phone is locked. Unlock it and ask again; I cannot operate a locked screen."
        return null
    }

    /** arg = "app or game|what to do (optional)|how long (optional)". Examples: "Monoposto||30 minutes", "Clash of Clans|collect resources and train troops|2 hours". */
    fun startAI(ctx: Context, brain: Brain, arg: String): String {
        preflight(ctx)?.let { return it }
        val prefs = Prefs(ctx)
        if (prefs.geminiKey.isEmpty() && prefs.groqKey.isEmpty()) return "Playing needs a free Gemini key (vision). Add it in Settings."
        val p = arg.split("|", limit = 3).map { it.trim() }
        val appName = p.getOrElse(0) { "" }
        val goalText = p.getOrElse(1) { "" }
        val limit = parseLimit(p.getOrElse(2) { "" }, 20 * 60_000L)
        val pkg = resolveApp(ctx, appName)
        if (appName.isNotBlank() && pkg == null) {
            return "I could not find an app or game called \"$appName\" on this phone. Tell me its exact name, or install it and ask again."
        }
        val goal = goalText.ifBlank { "Play ${appName.ifBlank { "the app on screen" }} well and keep going: progress, collect rewards, avoid losing, close ads and popups." }
        val app = ctx.applicationContext
        label = appName.ifBlank { "current screen" }
        job = Engine.scope.launch { aiLoop(app, brain, label, goal, pkg, limit) }
        val dur = if (limit.forever) "until you say stop (8 hours max per run)" else if (limit.cycles != Int.MAX_VALUE) "${limit.cycles} rounds" else "${limit.ms / 60_000} minutes"
        return "Playing $label for $dur. Say \"stop playing\" any time, or tap STOP in the notification. " +
            "I will report on Telegram and here. (Online multiplayer games may ban automated accounts: your call.)"
    }

    private suspend fun aiLoop(ctx: Context, brain: Brain, name: String, goal: String, pkg: String?, limit: Limit) {
        val lock = wake(ctx, limit.ms + 120_000)
        notifyOn(ctx, name)
        val started = System.currentTimeMillis()
        var note = ""
        var errors = 0
        var turns = 0
        var unchanged = 0
        var reopen = 0
        var ownFront = 0
        var prev: IntArray? = null
        var lastCall = 0L
        var lastSay = 0L
        var lastReport = started
        val history = ArrayDeque<String>()
        var ended = "Finished playing $name."
        try {
            if (pkg != null) { launchPkg(ctx, pkg); delay(4500) }
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now - started > limit.ms) { ended = "Finished playing $name: the time you asked for is up. Ask me again any time."; break }
                if (turns >= limit.cycles) { ended = "Finished $turns rounds of $name."; break }
                if (lowBattery(ctx)) { ended = "Stopped playing $name to protect your battery."; break }
                if (locked(ctx)) { ended = "Stopped playing $name: the phone got locked."; break }
                val svc = JarvisAccessibilityService.instance
                if (svc == null) { ended = "Stopped playing $name: the screen agent was switched off."; break }

                val front = svc.rootInActiveWindow?.packageName?.toString() ?: ""
                if (front == ctx.packageName || front.contains("launcher", true) || front.contains("systemui", true)) {
                    ownFront++
                    if (pkg != null && ownFront % 2 == 0) {
                        reopen++
                        if (reopen > 8) { ended = "Stopped playing $name: I could not stay inside the game."; break }
                        launchPkg(ctx, pkg)
                    }
                    if (ownFront > 25) { ended = "Stopped playing $name: the game is not on the screen."; break }
                    delay(3000)
                    continue
                }
                ownFront = 0

                // free-tier friendly: never faster than one call every ~4.5 s
                val gap = 4500 - (System.currentTimeMillis() - lastCall)
                if (gap > 0) delay(gap)
                lastCall = System.currentTimeMillis()

                val bmp = if (Build.VERSION.SDK_INT >= 30) svc.screenshot() else null
                val text = svc.dump()?.text.orEmpty().take(1200)
                if (payRx.containsMatchIn(text)) { svc.back(); delay(1500); continue }   // never linger on a payment screen
                var frame: String? = null
                if (bmp != null) {
                    val s = sig(bmp)
                    val changed = prev?.let { diff(it, s) >= 3 } ?: true
                    unchanged = if (changed) 0 else unchanged + 1
                    prev = s
                    frame = Watcher.toBase64(bmp, 720)
                }
                if (unchanged >= 14) { ended = "Stopped playing $name: the screen has not changed for a while, so I may be stuck."; break }

                val prompt = "GOAL: $goal\nApp in front: $front\nTurn: ${turns + 1}\n" +
                    "My notes so far: ${note.ifBlank { "none yet" }}\n" +
                    "Recent steps: ${history.joinToString(" | ").ifBlank { "none" }}\n" +
                    (if (unchanged >= 3) "WARNING: the screen did not change after my last $unchanged turns. Try something different.\n" else "") +
                    (if (text.isNotBlank()) "Screen text:\n$text\n" else "") +
                    "Look at the screenshot and decide the next steps."
                val raw = try {
                    brain.complete(SYSTEM, prompt, emptyList(), frame, json = true)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    errors++
                    if (errors >= 6) { ended = "Stopped playing $name: the brain keeps failing (${e.message?.take(80) ?: "error"}). The free limit may be used up for now."; break }
                    delay(15_000L * errors)
                    continue
                }
                errors = 0
                val o = try { JSONObject(Brain.extractJson(raw)) } catch (e: Exception) { turns++; delay(2000); continue }
                note = o.optString("note", note).take(400)
                val acts: JSONArray = o.optJSONArray("actions") ?: JSONArray()
                var summary = ""
                for (i in 0 until minOf(acts.length(), 12)) {
                    currentCoroutineContext().ensureActive()
                    val st = acts.optJSONObject(i) ?: continue
                    Steps.exec(ctx, svc, st, jitter = true)
                    summary += (if (summary.isEmpty()) "" else ",") + st.optString("do").ifBlank { st.optString("type") }
                    delay(120)
                }
                turns++
                history.addLast("t$turns:$summary")
                while (history.size > 5) history.removeFirst()

                val say = o.optString("say", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
                if (o.optBoolean("done", false)) { ended = say ?: "Done: the goal in $name is complete."; break }
                val t = System.currentTimeMillis()
                if (say != null && t - lastSay > 120_000) { lastSay = t; report(ctx, "[$name] $say") }
                else if (t - lastReport > 10 * 60_000L) { lastReport = t; report(ctx, "Still playing $name: ${(t - started) / 60_000} min, $turns turns. ${note.take(120)}") }
                delay(o.optLong("next_ms", 2000).coerceIn(800, 8000))
            }
        } catch (e: CancellationException) {
            ended = "Stopped playing $name."
            throw e
        } catch (e: Exception) {
            ended = "Stopped playing $name: ${e.message?.take(100)}"
        } finally {
            try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* ignore */ }
            notifyOff(ctx)
            job = null
            label = ""
            report(ctx, ended)
        }
    }

    /** arg = "macro name|how long". Runs a saved macro again and again. */
    fun startMacro(ctx: Context, arg: String): String {
        preflight(ctx)?.let { return it }
        val p = arg.split("|", limit = 2).map { it.trim() }
        val m = Macros.find(ctx, p[0]) ?: return "I have no macro called \"${p[0]}\". Say \"list my macros\" or ask me to create one."
        val limit = parseLimit(p.getOrElse(1) { "" }, 10 * 60_000L)
        val data = try { JSONObject(m.readText()) } catch (e: Exception) { return "That macro file is damaged. Ask me to create it again." }
        val name = m.nameWithoutExtension
        val app = ctx.applicationContext
        label = "macro $name"
        job = Engine.scope.launch { macroLoop(app, name, data, limit) }
        val dur = if (limit.forever) "until you say stop (8 hours max per run)" else if (limit.cycles != Int.MAX_VALUE) "${limit.cycles} times" else "${limit.ms / 60_000} minutes"
        return "Running macro \"$name\" for $dur. Say \"stop playing\" any time, or tap STOP in the notification."
    }

    private suspend fun macroLoop(ctx: Context, name: String, data: JSONObject, limit: Limit) {
        val lock = wake(ctx, limit.ms + 120_000)
        notifyOn(ctx, "macro $name")
        val started = System.currentTimeMillis()
        val steps = data.optJSONArray("steps") ?: JSONArray()
        val jitter = data.optBoolean("jitter", true)
        val loopWait = data.optLong("loop_wait_ms", 1000)
        var cycles = 0
        var lastReport = started
        var failed = 0
        var ended = "Macro $name finished."
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (now - started > limit.ms) { ended = "Macro $name finished: $cycles rounds in ${(now - started) / 60_000} min."; break }
                if (cycles >= limit.cycles) { ended = "Macro $name finished: $cycles rounds."; break }
                if (lowBattery(ctx)) { ended = "Macro $name stopped to protect your battery ($cycles rounds)."; break }
                if (locked(ctx)) { ended = "Macro $name stopped: the phone got locked ($cycles rounds)."; break }
                val svc = JarvisAccessibilityService.instance
                if (svc == null) { ended = "Macro $name stopped: the screen agent was switched off."; break }
                var ok = true
                for (i in 0 until steps.length()) {
                    currentCoroutineContext().ensureActive()
                    val st = steps.optJSONObject(i) ?: continue
                    if (!Steps.exec(ctx, svc, st, jitter)) ok = false
                    delay(if (jitter) 60L + Random.nextLong(80) else 60L)
                }
                cycles++
                failed = if (ok) 0 else failed + 1
                if (failed >= 10) { ended = "Macro $name stopped: the touches keep failing. Turn the Jarvis accessibility service off and on again."; break }
                val t = System.currentTimeMillis()
                if (t - lastReport > 10 * 60_000L) { lastReport = t; report(ctx, "Macro $name is running: $cycles rounds, ${(t - started) / 60_000} min.") }
                delay(if (jitter) (loopWait * (0.85 + Random.nextDouble() * 0.3)).toLong() else loopWait)
            }
        } catch (e: CancellationException) {
            ended = "Macro $name stopped after $cycles rounds."
            throw e
        } catch (e: Exception) {
            ended = "Macro $name stopped: ${e.message?.take(100)}"
        } finally {
            try { lock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* ignore */ }
            notifyOff(ctx)
            job = null
            label = ""
            report(ctx, ended)
        }
    }
}

/** Saved touch routines. They replay forever with no AI and no limits. */
object Macros {

    private fun dir(ctx: Context) = File(ctx.filesDir, "macros").apply { mkdirs() }
    private fun safe(n: String) = n.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40)

    fun all(ctx: Context): List<File> = dir(ctx).listFiles { f -> f.extension == "json" }?.toList().orEmpty()

    fun names(ctx: Context): List<String> = all(ctx).map { it.nameWithoutExtension }

    fun find(ctx: Context, q: String): File? {
        val n = q.trim().trim('"')
        if (n.isEmpty()) return null
        val list = all(ctx)
        val a = Actions(ctx)
        return list.firstOrNull { it.nameWithoutExtension.equals(n, true) }
            ?: list.firstOrNull { it.nameWithoutExtension.contains(n, true) }
            ?: list.firstOrNull { a.similar(n, it.nameWithoutExtension) }
    }

    fun list(ctx: Context): String {
        val l = all(ctx)
        if (l.isEmpty()) return "No macros yet. Ask me to create one, for example: \"make a macro that taps the cookie in Cookie Clicker\"."
        return "Your macros:\n" + l.joinToString("\n") { "- " + it.nameWithoutExtension + ": " + try { JSONObject(it.readText()).optString("summary") } catch (e: Exception) { "" } }
    }

    private const val SYSTEM = """You write a MACRO for an Android phone: a short list of touch steps that Jarvis replays again and again.
You may receive a screenshot of the screen the macro starts on. Coordinates are fractions 0..1 of screen width and height from the top-left; aim at the CENTER of each target.
Respond ONLY with JSON: {"summary":"one line","loop_wait_ms":1000,"jitter":true,"steps":[ ... ]}
Step types: {"do":"tap","x":..,"y":..} {"do":"double_tap","x":..,"y":..} {"do":"hold","x":..,"y":..,"ms":800}
{"do":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"ms":300} {"do":"pinch_in","x":..,"y":..} {"do":"pinch_out","x":..,"y":..}
{"do":"wait","ms":1000} {"do":"back"} {"do":"home"} {"do":"open_app","app":"name"}
The steps form ONE round; Jarvis repeats the round. Include waits where the app needs time. Maximum 40 steps. Never include purchases, payments or passwords."""

    /** arg = "macro name|what it should do". The app named in the description is opened first so the brain can see where to aim. */
    suspend fun create(ctx: Context, brain: Brain, arg: String): String {
        val p = arg.split("|", limit = 2).map { it.trim() }
        val desc = p.getOrElse(1) { "" }.ifBlank { p[0] }
        val name = safe(p[0].ifBlank { desc }).ifBlank { "macro" }
        val svc = JarvisAccessibilityService.instance
        var frame: String? = null
        var screenNote = ""
        if (svc != null) {
            val a = Actions(ctx)
            val pkg = a.mentions(desc).firstOrNull()?.let { a.findApp(it) }
            if (pkg != null) {
                try { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* ignore */ }
                delay(4500)
            }
            val front = svc.rootInActiveWindow?.packageName?.toString() ?: ""
            if (front == ctx.packageName) {
                screenNote = "(The macro's app is not on screen, so aim from your knowledge of the app; the user may need to adjust.)"
            } else if (Build.VERSION.SDK_INT >= 30) {
                svc.screenshot()?.let { frame = Watcher.toBase64(it, 900) }
            }
        }
        val raw = brain.complete(SYSTEM, "Macro name: $name\nWhat it must do: $desc\n$screenNote", emptyList(), frame, json = true)
        val o = JSONObject(Brain.extractJson(raw))
        val steps = o.optJSONArray("steps")
        if (steps == null || steps.length() == 0) return "I could not work out the steps. Describe it again with more detail."
        o.put("name", name)
        File(dir(ctx), "$name.json").writeText(o.toString())
        return "Macro \"$name\" saved: ${o.optString("summary", desc)} (${steps.length()} steps). " +
            "Run it with: \"run macro $name for 30 minutes\" (or \"100 times\", or \"forever\"). " +
            (if (frame == null) "I made it without seeing the screen, so test it once and tell me what to fix." else "")
    }

    fun delete(f: File): Boolean = f.delete()
}

class PlayStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Player.stopAll(false)
    }
}
