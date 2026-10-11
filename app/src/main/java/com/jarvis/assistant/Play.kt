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
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, w: Float, h: Float, at: Long = 0L): GestureDescription.StrokeDescription {
        val p = Path()
        p.moveTo(x1.coerceIn(1f, w - 1), y1.coerceIn(1f, h - 1))
        if (x1 != x2 || y1 != y2) p.lineTo(x2.coerceIn(1f, w - 1), y2.coerceIn(1f, h - 1))
        return GestureDescription.StrokeDescription(p, at.coerceIn(0, 40_000), ms.coerceIn(10, 20_000))
    }

    /** Several fingers at once, each with its own start delay: hold the gas while steering, two thumbs, joystick plus a button. */
    suspend fun multi(ctx: Context, svc: JarvisAccessibilityService, touches: JSONArray, jitter: Boolean): Boolean {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val list = ArrayList<GestureDescription.StrokeDescription>()
        for (i in 0 until minOf(touches.length(), 10)) {
            val t = touches.optJSONObject(i) ?: continue
            val x = t.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f) * w
            val y = t.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f) * h
            val x2 = if (t.has("x2")) t.optDouble("x2", 0.5).toFloat().coerceIn(0f, 1f) * w else x
            val y2 = if (t.has("y2")) t.optDouble("y2", 0.5).toFloat().coerceIn(0f, 1f) * h else y
            list.add(stroke(x, y, x2, y2, t.optLong("ms", 100), w, h, t.optLong("at", 0)))
        }
        if (list.isEmpty()) return false
        return gesture(svc, *list.toTypedArray())
    }

    private val pumpOnly = setOf("hold_on", "hold_off", "release", "release_all", "press_and_keep", "repeat_tap", "rapid_tap", "spam_tap")
    private val pumpable = pumpOnly + setOf("tap", "click", "double_tap", "hold", "long_press", "swipe", "drag", "pinch_in", "pinch_out", "multi")

    suspend fun exec(ctx: Context, svc: JarvisAccessibilityService, o: JSONObject, jitter: Boolean): Boolean {
        val kindStr = (o.optString("do").ifBlank { o.optString("type") }).lowercase()
        if (kindStr in pumpOnly || (Touch.heldNames().isNotEmpty() && kindStr in pumpable)) {
            if (!Touch.running) Touch.start(ctx)
            return Touch.exec(ctx, kindStr, o, jitter)
        }
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
            "multi" -> multi(ctx, svc, o.optJSONArray("touches") ?: JSONArray(), jitter)
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

    /** Only a real checkout is a payment screen; ordinary menus with words like "buy" or "subscribe" are NOT. */
    private val payRx = Regex(
        "(confirm (?:your )?(?:purchase|payment|order)|confirm and pay|authorize (?:the )?(?:payment|purchase)|تأكيد الشراء|تأكيد الدفع|confirmer (?:l'achat|le paiement))",
        RegexOption.IGNORE_CASE
    )

    private fun paymentScreen(front: String, text: String): Boolean =
        front == "com.android.vending" || front.contains("billing", true) || front.contains("wallet", true) ||
            front.contains("paypal", true) || payRx.containsMatchIn(text)

    private const val RECON = """You are JARVIS, an expert gamer. Look at this phone screenshot (a thin yellow grid labelled 0.1 to 0.9 gives exact coordinates) and work out where you are.
Respond ONLY with JSON:
{"game":"name of the game or app, best guess","genre":"racing|runner|platformer|shooter|puzzle|card|board|idle|rpg|story|sports|simulation|other",
 "screen_state":"menu|cutscene|dialog|gameplay|ad|loading|game_over|other",
 "controls":"how this game is controlled RIGHT NOW: list every visible button, pedal, arrow, joystick or gesture with its x,y position (fractions), and what it does",
 "goal":"what to do next to make progress, and why",
 "tips":"the best strategy for this kind of game in two sentences"}
Be concrete and specific. Never answer with generic words: describe what is really on this screen."""

    private const val SYSTEM = """You are JARVIS, an expert gamer playing on the user's Android phone with real touches. Each turn you get a screenshot (a thin yellow grid labelled 0.1 to 0.9 gives exact coordinates), the GOAL, and what you already know about this game.
THINK AHEAD. Your answer takes a few seconds and the game keeps running, so never only react to the picture: predict what the screen will look like 3 to 10 seconds from now (a bend coming up, an enemy walking closer, a timer ending, hunger or health dropping, a resource running out) and prepare for it now. Your plan is executed while you already start thinking about the next one.
Respond ONLY with compact JSON:
{"state":"menu|cutscene|dialog|gameplay|ad|loading|game_over|other","see":"one sentence: what is on screen and where your character or vehicle is",
 "ahead":"what you expect in the next 3 to 10 seconds and how you prepare (for example: gentle right bend in about 1.5 s, so one short light steer, then straight)",
 "plan":"one sentence: what you do now","strategy":"your overall approach and next milestones; CHANGE it when it stops working, never repeat a failing routine",
 "actions":[ up to 10 steps ],"hold":null,"release":null,"reflex":null,"ask":null,"interrupt":false,"layout":null,"lesson":"",
 "note":"memory, max 400 characters: rules and progress","next_ms":1500,"say":"","done":false}
A "multi" step with timed touches ("at" delays) schedules a whole predicted sequence in advance: for example hold the gas for 6 s, a light steer at 1.5 s and a counter-steer at 3 s. Gentle bends need short light presses (80 to 150 ms), sharp ones longer presses (300 ms and more). A steer reflex with "roi_far" anticipates bends on its own.
"interrupt": true only when the plan still running must be cancelled right now (danger). "layout": whenever you see a screen state whose button positions are missing from the remembered controls, or they changed: {"gameplay":"gas (0.88,0.8), brake (0.75,0.85), left (0.1,0.85), right (0.25,0.85)"}; they are kept so you recognise them instantly next time. "lesson": after a loss, a mistake or a discovery, one reusable sentence (what happened, what to do differently); it is kept for every future play of this game.
Coordinates are fractions 0..1 (x from the left, y from the top). Aim at the CENTER of the target.
STEPS: {"do":"tap","x":..,"y":..} {"do":"double_tap","x":..,"y":..} {"do":"hold","x":..,"y":..,"ms":800} {"do":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"ms":300} (long ms = drag or joystick push)
 {"do":"pinch_in","x":..,"y":..} {"do":"pinch_out","x":..,"y":..} {"do":"wait","ms":800} {"do":"back"} {"do":"open_app","app":"name"} {"do":"type","text":".."}
 {"do":"multi","touches":[{"x":0.9,"y":0.8,"at":0,"ms":3000},{"x":0.1,"y":0.8,"at":500,"ms":300}]} = several fingers at once ("at" = start delay ms, "ms" = duration, optional x2,y2 slides that finger). Use it to hold gas or fire while steering.
 {"do":"hold_on","name":"gas","x":0.9,"y":0.8} keeps a finger PRESSED until {"do":"hold_off","name":"gas"}: it never lifts between your decisions (gas pedal, fire, run). Same thing with the top-level fields "hold":{"gas":{"x":0.9,"y":0.8}} and "release":["gas"] (or "all").
 {"do":"repeat_tap","x":..,"y":..,"count":20,"interval_ms":70} = rapid taps (clickers, button mashing). {"do":"hold","x":..,"y":..,"ms":3000} = one long press. {"do":"swipe",...,"ms":1500} = a slow drag or joystick push.
CONTROLLERS run on the phone in the BACKGROUND about 3 times a second while you think, until you replace or stop them ("reflex":"stop"). Use them for anything continuous: driving, flying, running, dodging, following, aiming, rapid collecting. Never try to steer with a few one-off presses: put the gas finger down with hold, start a controller, then supervise using the CONTROLLERS status in your next look. "reflex" is one object or a list of up to 3.
 steer: {"kind":"steer","sample":{"x":0.5,"y":0.72},"roi":{"x1":0.15,"y1":0.5,"x2":0.85,"y2":0.78},"roi_far":{"x1":0.3,"y1":0.35,"x2":0.7,"y2":0.5},"ref_x":0.5,"control":"buttons","left":{"x":0.1,"y":0.85},"right":{"x":0.25,"y":0.85},"hazard":{"sample":{"x":0.05,"y":0.7}}}
   follows (avoid:false) or avoids (avoid:true) a colour sideways. "sample" = a point exactly ON the colour (the road surface just ahead of the car, or an obstacle). "roi" = the area ahead to look at; "roi_far" = the road further ahead, so it turns early and gently into a bend; "ref_x" = where the player is horizontally, or "self":{"sample":{..}} = a point on the player or car so it is tracked by its colour; "hazard" = a colour to stay away from (walls, grass, water, track edges), it pushes back. "control": "buttons" (positions of the left and right buttons: sharp turns keep the button pressed, gentle ones are light pulses), "drag" (a finger slides along height ctrl_y), "swipe" (lane changes, lane_step = lane width).
 chase: {"kind":"chase","sample":{"x":..,"y":..},"self":{"sample":{"x":..,"y":..}},"avoid":false,"control":"joystick","joy":{"x":0.2,"y":0.8},"radius":0.08} moves toward (or away from) a colour in two dimensions: "joystick" (a finger slides from the joystick centre), "swipe" (swipes that way) or "tap" (taps the target place).
 tap_color: {"kind":"tap_color","sample":{"x":..,"y":..},"roi":{"x1":0,"y1":0.3,"x2":1,"y2":0.85}} taps the lowest object of that colour again and again (piano tiles, falling items, rhythm lanes, targets).
 All accept "tol" (colour tolerance 8 to 120, default 45), "dead" (dead zone), "gain", "id", "hold" (a constant finger) and "duration_ms" (default 45000; sending the identical controller again keeps it alive). Pick sample points exactly on the colour in the screenshot. If the status warns the colour is not inside the roi, or says it lost the target, fix the points.
HOW TO PLAY:
- Racing or driving: hold the gas (pedal or reflex hold) and steer with a steer reflex following the road colour; avoid traffic with a second reflex (avoid:true). Runners: swipe lanes (control swipe, avoid:true). Platformers and action: multi with direction held plus jump taps. Shooters: joystick swipe plus a held fire button.
- Puzzle, card, board, match-3, turn-based: study the board, find the best move, tap precisely, wait for animations. Idle and clickers: bursts of taps, buy upgrades, collect rewards. Story and RPG: tap through dialogue, follow the quest marker.
- state menu: press Play, Continue or Start. state loading: wait. state ad: find and tap the close X, Skip or Back. state game_over: restart and use the lesson. A popup asking for a rating or permission: dismiss or deny.
- DRIVING, step by step: 1) find the gas control (none if the car accelerates by itself) and put a finger on it with hold; 2) start a steer controller on the road colour with a hazard colour for walls, grass and edges, and "roi_far" for bends; 3) use brake or nitro as plain steps; 4) each turn read the CONTROLLERS status and the road ahead, refine the points, and never let go of the gas between decisions. The same pattern works for flying, running, boats and any game with continuous movement.
- Survival, building and open-world games: set milestones (gather wood and stone, craft tools, build shelter, find food and water, watch health and night), keep an eye on the status bars, retreat or heal before danger, and change the plan as the situation changes. Shooters and fighters: learn enemy patterns and adapt.
- Do not run a fixed loop. If the same routine repeats without progress, change the strategy.
- A start, title, home or menu screen: press the biggest Play, Start, Tap-to-start or Continue button right away. Never answer with no actions unless the state is loading or a controller is running: an idle phone loses the game. If no button is clear, tap the middle of the screen.
- NEVER spend real money (Buy, Subscribe, Pay, Install, Update, anything with a price). Never type passwords or card data. A payment screen: press back. Wrong app in front: open_app. If the screen did not change after your last move, do something different.
ASKING THE USER ("ask":{"q":"...","default":"yes","key":"short_id"}; a YES or NO dialog appears; no answer in a minute = your default): ask only when it truly matters to them: the first cutscene or long video (skip it?), a story choice that changes the outcome, something irreversible, spending a rare resource, deleting progress. Decide everything routine yourself. If "Standing answers" already has your key, apply it silently. When you ask, actions and reflex are ignored that turn.
"done": true only when the goal is fully achieved or truly impossible. "say" stays empty except for a real milestone or problem: one short sentence in the user's language (Arabic or Darija if they use it). "next_ms": 1000 to 8000; short when using a reflex."""

    private fun mem(ctx: Context) = ctx.getSharedPreferences("jarvis_play_mem", Context.MODE_PRIVATE)

    private fun sizeSig(ctx: Context): String {
        val d = ctx.resources.displayMetrics
        return "${d.widthPixels}x${d.heightPixels}"
    }

    /** Remembered button positions per screen state (menu, gameplay...). Only valid for the same screen size. */
    private fun loadScreens(sp: SharedPreferences, key: String, size: String): JSONObject {
        val raw = sp.getString("layout:$key", null) ?: return JSONObject()
        return try {
            val o = JSONObject(raw)
            if (o.optString("size") == size) (o.optJSONObject("screens") ?: JSONObject()) else JSONObject()
        } catch (e: Exception) { JSONObject() }
    }

    private fun saveScreens(sp: SharedPreferences, key: String, size: String, screens: JSONObject) {
        sp.edit().putString("layout:$key", JSONObject().put("size", size).put("screens", screens).toString()).apply()
    }

    private fun screensText(s: JSONObject): String {
        val l = ArrayList<String>()
        for (k in s.keys()) l.add("$k: ${s.optString(k)}")
        return l.joinToString("; ")
    }

    private fun lessons(sp: SharedPreferences, key: String): MutableList<String> =
        (sp.getString("lessons:$key", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    private fun addLesson(sp: SharedPreferences, key: String, list: MutableList<String>, text: String) {
        val t = text.replace("\n", " ").take(220)
        if (list.any { it.equals(t, true) || it.contains(t, true) || t.contains(it, true) }) return
        list.add(t)
        while (list.size > 14) list.removeAt(0)
        sp.edit().putString("lessons:$key", list.joinToString("\n")).apply()
    }

    private fun statsParts(sp: SharedPreferences, key: String): List<String> =
        (sp.getString("stats:$key", "0|0|0") ?: "0|0|0").split("|")

    private fun statsText(sp: SharedPreferences, key: String): String {
        val p = statsParts(sp, key)
        return "played ${p.getOrElse(0) { "0" }} times, ${p.getOrElse(1) { "0" }} min in total"
    }

    private fun bumpStats(sp: SharedPreferences, key: String, minutes: Long, turns: Int) {
        val p = statsParts(sp, key)
        val s = (p.getOrNull(0)?.toIntOrNull() ?: 0) + 1
        val m = (p.getOrNull(1)?.toLongOrNull() ?: 0L) + minutes
        val t = (p.getOrNull(2)?.toIntOrNull() ?: 0) + turns
        sp.edit().putString("stats:$key", "$s|$m|$t").apply()
    }

    /** "What do you remember about X?" */
    fun memoryReport(ctx: Context, arg: String): String {
        val key = (resolveApp(ctx, arg) ?: arg.trim()).lowercase()
        val sp = mem(ctx)
        val screens = loadScreens(sp, key, sizeSig(ctx))
        val ls = lessons(sp, key)
        if (screens.length() == 0 && ls.isEmpty() && sp.getString("note:$key", "").isNullOrBlank()) {
            return "I remember nothing about \"$arg\" yet. Ask me to play it and I will learn it."
        }
        return "About ${arg.ifBlank { key }}: ${statsText(sp, key)}.\nControls I know: ${screensText(screens).ifBlank { "none" }}\nLessons:\n" +
            ls.joinToString("\n") { "- $it" }.ifBlank { "none yet" }
    }

    /** Rough time a list of steps will take, so the next decision can be prepared while they run. */
    private fun execEstimate(acts: JSONArray): Long {
        var t = 0L
        for (i in 0 until acts.length()) {
            val a = acts.optJSONObject(i) ?: continue
            t += 120 + when (a.optString("do").ifBlank { a.optString("type") }) {
                "wait", "sleep" -> a.optLong("ms", 1000)
                "hold", "long_press" -> a.optLong("ms", 800)
                "swipe", "drag" -> a.optLong("ms", 300)
                "open_app" -> 2500L
                "multi" -> {
                    var m = 0L
                    val ts = a.optJSONArray("touches")
                    if (ts != null) for (j in 0 until ts.length()) {
                        val x = ts.optJSONObject(j) ?: continue
                        m = maxOf(m, x.optLong("at", 0) + x.optLong("ms", 100))
                    }
                    m
                }
                else -> 100L
            }
        }
        return t
    }

    @Volatile private var stepTrace = 0

    private suspend fun execActions(ctx: Context, svc: JarvisAccessibilityService, acts: JSONArray) {
        for (i in 0 until minOf(acts.length(), 12)) {
            currentCoroutineContext().ensureActive()
            val st = acts.optJSONObject(i) ?: continue
            var res = "ok"
            try { if (!Steps.exec(ctx, svc, st, jitter = true)) res = "REFUSED by Android" } catch (e: CancellationException) { throw e } catch (e: Exception) { res = "ERROR " + (e.message ?: e.toString()).take(80); Diag.record(e) }
            if (stepTrace < 6 || res != "ok" && stepTrace < 12) { stepTrace++; report(ctx, "[step] " + st.toString().take(110) + " -> " + res) }
            delay(120)
        }
    }

    /** Forgets what Jarvis learned about a game and the user's standing yes/no answers (blank = everything). */
    fun forget(ctx: Context, arg: String): String {
        val key = (resolveApp(ctx, arg) ?: arg.trim()).lowercase()
        val sp = mem(ctx)
        val e = sp.edit()
        for (k in sp.all.keys) if (k.contains(":$key")) e.remove(k)
        e.apply()
        return "Forgot what I learned and your standing answers for ${arg.ifBlank { "every game" }}."
    }

    /** A downscaled screenshot with a labelled grid so the brain can read exact coordinates. */
    fun frame(bmp: Bitmap, max: Int): String {
        val sc = max.toFloat() / maxOf(bmp.width, bmp.height)
        val b = if (sc < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) else bmp
        return try {
            val m = b.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(m)
            val line = Paint().apply { color = Color.argb(55, 255, 255, 0); strokeWidth = 1f }
            val txt = Paint().apply { color = Color.WHITE; textSize = m.width / 38f; isFakeBoldText = true; setShadowLayer(3f, 0f, 0f, Color.BLACK) }
            for (i in 1..9) {
                val x = m.width * i / 10f
                val y = m.height * i / 10f
                c.drawLine(x, 0f, x, m.height.toFloat(), line)
                c.drawLine(0f, y, m.width.toFloat(), y, line)
                c.drawText("0.$i", x - txt.textSize, txt.textSize, txt)
                c.drawText("0.$i", 2f, y + txt.textSize / 3f, txt)
            }
            Watcher.toBase64(m, max)
        } catch (e: Exception) {
            Watcher.toBase64(b, max)
        }
    }

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
        val memKey = (pkg ?: name).lowercase()
        val sp = mem(ctx)
        val size = sizeSig(ctx)
        var note = sp.getString("note:$memKey", "") ?: ""
        val learned = note
        val screens = loadScreens(sp, memKey, size)
        val lessonList = lessons(sp, memKey)
        val decisions = HashMap<String, String>()
        for ((k, v) in sp.all) if (k.startsWith("dec:$memKey:") && v is String) decisions[k.removePrefix("dec:$memKey:")] = v
        val scope = CoroutineScope(currentCoroutineContext())
        Touch.start(ctx)
        stepTrace = 0
        var hubOn = false
        try { JarvisAccessibilityService.instance?.let { Hub.acquire(it); hubOn = true } } catch (e: Exception) { Diag.record(e) }
        var execJob: Job? = null
        var execEnd = 0L
        var nextAt = 0L
        var lastDecision = ""
        var reflexResult = ""
        var reconDone = screens.length() > 0
        var knowledge = ""
        var lastPlan = ""
        var lastAhead = ""
        var strategy = ""
        var lastState = ""
        var lastNarr = 0L
        var lastTele = 0L
        var parseFail = 0
        var payHits = 0
        var blocker = ""
        var lastProgress = started
        var lastWatch = 0L
        var emptyTurns = 0
        var errors = 0
        var turns = 0
        var blind = 0
        var traced = 0
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
                // never stay silent: say what is blocking me
                if (now - lastProgress > 35_000 && now - lastWatch > 35_000) {
                    lastWatch = now
                    report(ctx, "[$name] No move yet. Front app: ${front.ifBlank { "unknown" }}. ${Touch.problem.ifBlank { blocker }.ifBlank { "Waiting for the brain's answer." }}")
                }
                if (front == ctx.packageName || front.contains("launcher", true) || front.contains("systemui", true)) {
                    ownFront++
                    blocker = "The game is not in front (I see ${front.ifBlank { "nothing" }}); reopening it."
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

                // free-tier friendly: one decision every ~6 s at most; the previous plan keeps running meanwhile
                val readyAt = maxOf(lastCall + 6000, nextAt)
                val gap = readyAt - System.currentTimeMillis()
                if (gap > 0) delay(gap)
                lastCall = System.currentTimeMillis()

                val bmp = if (Build.VERSION.SDK_INT >= 30) (Hub.fresh(1000) ?: Shots.take(svc)) else null
                val text = svc.dump()?.text.orEmpty().take(1200)
                if (paymentScreen(front, text)) {
                    payHits++
                    blocker = "A payment screen is in front; I pressed back."
                    if (payHits == 1) report(ctx, "[$name] A payment or store screen appeared: I am going back, I never pay.")
                    execJob?.cancel(); svc.back(); delay(1500)
                    if (payHits > 8) { ended = "Stopped playing $name: it keeps opening payment screens."; break }
                    continue
                }
                payHits = 0
                if (Build.VERSION.SDK_INT >= 30 && bmp == null) {
                    blind++
                    if (blind == 3) report(ctx, "[$name] I cannot capture the screen yet (front app: ${front.ifBlank { "unknown" }}). Retrying.")
                    if (blind >= 6) { ended = "Stopped playing $name: Android will not let me capture the screen (the game may block screenshots). I will not play blind."; break }
                    delay(1500)
                    continue
                }
                blind = 0
                if (traced < 3) report(ctx, "[trace ${traced + 1}] front=${front.ifBlank { "?" }} screen=${bmp?.let { "${it.width}x${it.height}" } ?: "none"} touch=${if (Touch.running) "on" else "OFF"}${Touch.problem.let { if (it.isBlank()) "" else " problem=$it" }}")
                var frame: String? = null
                if (bmp != null) {
                    val sg = sig(bmp)
                    val changed = prev?.let { diff(it, sg) >= 3 } ?: true
                    unchanged = if (changed) 0 else unchanged + 1
                    prev = sg
                    frame = Player.frame(bmp, 840)
                }
                if (unchanged >= 14) { ended = "Stopped playing $name: the screen has not changed for a while, so I may be stuck."; break }

                // a brand new game: work out WHAT it is, WHERE we are and HOW it is controlled (skipped when the controls are remembered)
                if (!reconDone) {
                    reconDone = true
                    val rp = "App in front: $front\nUser's goal: $goal\nEarlier notes about this game: ${learned.ifBlank { "none" }}\n" +
                        (if (text.isNotBlank()) "Screen text:\n$text\n" else "") + "Describe this screen precisely."
                    var info: JSONObject? = null
                    for (attempt in 1..2) {
                        try {
                            info = JSONObject(Brain.extractJson(brain.complete(RECON, rp, emptyList(), frame, json = true, play = true)))
                            break
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { delay(3000) }
                    }
                    if (info != null) {
                        val g = info.optString("game")
                        knowledge = "Game: $g. Genre: ${info.optString("genre")}. Controls: ${info.optString("controls")}. Plan: ${info.optString("goal")}. Tips: ${info.optString("tips")}".take(1100)
                        val st = info.optString("screen_state").ifBlank { "gameplay" }
                        if (info.optString("controls").isNotBlank()) {
                            screens.put(st, info.optString("controls").take(500))
                            saveScreens(sp, memKey, size, screens)
                        }
                        report(ctx, "I see: $g (${info.optString("genre")}), screen: $st.\nControls: ${info.optString("controls")}\nPlan: ${info.optString("goal")}".take(900))
                    } else {
                        report(ctx, "[$name] I could not read the game screen yet. I will keep trying while I play.")
                    }
                    lastCall = 0L
                    continue
                }

                val running = execJob?.isActive == true
                val prompt = "GOAL: $goal\nApp in front: $front\nTurn: ${turns + 1}\n" +
                    (if (knowledge.isNotBlank()) "What I know about this game: $knowledge\n" else "") +
                    (if (screens.length() > 0) "Remembered controls for this screen size (trust them unless the screenshot contradicts them; correct them with \"layout\" if they changed): ${screensText(screens)}\n" else "") +
                    (if (lessonList.isNotEmpty()) "Lessons from earlier plays (strategy memory):\n" + lessonList.joinToString("\n") { "- $it" } + "\n" else "") +
                    (if (learned.isNotBlank()) "Notes from earlier sessions: $learned\n" else "") +
                    "Standing answers from the user: ${decisions.entries.joinToString { it.key + "=" + it.value }.ifBlank { "none" }}\n" +
                    (if (strategy.isNotBlank()) "Current strategy: $strategy\n" else "") +
                    "My notes this session: ${note.ifBlank { "none" }}\n" +
                    "Recent steps: ${history.joinToString(" | ").ifBlank { "none" }}\n" +
                    (if (lastPlan.isNotBlank()) "My previous plan was: $lastPlan. Did it work? Compare with the screen now; if not, change approach.\n" else "") +
                    (if (lastAhead.isNotBlank()) "What I predicted would come next: $lastAhead. Check it against the screen now.\n" else "") +
                    (if (running) "My previous plan is still executing, about ${((execEnd - System.currentTimeMillis()).coerceAtLeast(0) / 100) / 10.0} s left: plan for the situation when it ends; set interrupt true only if it must be cancelled right now.\n" else "") +
                    (if (lastState == "game_over") "The game just ended or I lost: write the lesson, then restart with a better approach.\n" else "") +
                    (if (turns > 0 && turns % 8 == 0) "REFLECT: am I really progressing? If the recent steps repeat without progress, change the strategy now and write a lesson.\n" else "") +
                    (if (lastDecision.isNotBlank()) "USER ANSWER: $lastDecision\n" else "") +
                    (Reflex.statusText().let { if (it.isNotBlank()) "CONTROLLERS AND HELD FINGERS: $it\n" else "" }) +
                    (sp.getString("ctl:$memKey", null)?.let { "Controller recipe I used last time in this game (reuse it if it fits; adapt the points to this screenshot): $it\n" } ?: "") +
                    (if (emptyTurns >= 2) "WARNING: I made no move for $emptyTurns turns. I MUST act now: tap the most likely Play, Start, Continue or Tap-to-start button, or the middle of the screen.\n" else "") +
                    (if (unchanged >= 3) "WARNING: the screen did not change after my last $unchanged turns. Try something different.\n" else "") +
                    (if (text.isNotBlank()) "Screen text:\n$text\n" else "") +
                    "Look at the screenshot, think ahead, and decide."
                lastDecision = ""
                reflexResult = ""
                val raw = try {
                    brain.complete(SYSTEM, prompt, emptyList(), frame, json = true, play = true)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    errors++
                    blocker = "Brain error: ${e.message?.take(120)}"
                    report(ctx, "[$name] Brain error ($errors/6): ${e.message?.take(140)}")
                    if (errors >= 6) { ended = "Stopped playing $name: the brain keeps failing (${e.message?.take(80) ?: "error"}). The free limit may be used up for now."; break }
                    delay(15_000L * errors)
                    continue
                }
                errors = 0
                val o = try { JSONObject(Brain.extractJson(raw)) } catch (e: Exception) {
                    parseFail++
                    if (parseFail == 2) report(ctx, "[$name] The brain's last answers were unreadable. Retrying.")
                    if (parseFail >= 5) { ended = "Stopped playing $name: the brain keeps returning unreadable answers."; break }
                    delay(1500)
                    continue
                }
                parseFail = 0
                lastProgress = System.currentTimeMillis()
                blocker = ""
                if (traced < 3) { traced++; report(ctx, "[trace $traced] brain: " + raw.replace("\n", " ").take(260)) }
                if (Touch.problem.isNotBlank()) { ended = "Stopped playing $name: " + Touch.problem; break }
                note = o.optString("note", note).take(700)
                val see = o.optString("see", "").trim()
                val plan = o.optString("plan", "").trim()
                lastPlan = plan
                lastAhead = o.optString("ahead", "").trim().take(300)
                lastState = o.optString("state", "")
                o.optString("strategy", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }?.let { strategy = it.take(400) }
                o.optJSONObject("layout")?.let { l ->
                    var changed = false
                    for (k in l.keys()) {
                        val v = l.optString(k).take(500)
                        if (v.isNotBlank() && screens.optString(k) != v) { screens.put(k, v); changed = true }
                    }
                    if (changed) saveScreens(sp, memKey, size, screens)
                }
                val lesson = o.optString("lesson", "").trim()
                var learnedNow = ""
                if (lesson.length > 8 && !lesson.equals("null", true)) { addLesson(sp, memKey, lessonList, lesson); learnedNow = lesson }
                val tn = System.currentTimeMillis()
                if (see.isNotEmpty() && tn - lastNarr > 20_000) {
                    lastNarr = tn
                    val line = "[$name | ${lastState.ifBlank { "?" }}] $see -> " + (if (lastAhead.isNotEmpty()) "expecting: $lastAhead; " else "") + "now: $plan" +
                        (if (learnedNow.isNotEmpty()) "\nLearned: $learnedNow" else "")
                    ChatBus.publish("jarvis", line)
                    if (tn - lastTele > 90_000) {
                        lastTele = tn
                        Engine.scope.launch(Dispatchers.IO) { try { Telegram.sendToOwner(Prefs(ctx), line) } catch (e: Exception) { /* ignore */ } }
                    }
                }

                // a question for the user: YES or NO on the screen
                val ask = o.optJSONObject("ask")
                val q = ask?.optString("q", "")?.trim().orEmpty()
                if (ask != null && q.isNotEmpty()) {
                    execJob?.cancel()
                    val key = ask.optString("key", "").trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
                    val def = !ask.optString("default", "yes").lowercase().startsWith("n")
                    val known = if (key.isNotEmpty()) decisions[key] else null
                    if (known != null) {
                        lastDecision = "standing answer $key=$known: apply it, do not ask again."
                    } else {
                        report(ctx, "[$name] $q  (answer YES or NO on the phone screen)")
                        val ans = Ask.yesNo(ctx, "[$name]\n\n$q\n\n(No answer in 60 s means ${if (def) "YES" else "NO"}.)", 60_000)
                        val yes = ans ?: def
                        lastDecision = "the user answered ${if (yes) "YES" else "NO"}${if (ans == null) " (no reply, default)" else ""} to: $q"
                        if (ans != null && key.isNotEmpty()) {
                            decisions[key] = if (yes) "yes" else "no"
                            sp.edit().putString("dec:$memKey:$key", decisions[key]).apply()
                        }
                    }
                    turns++
                    history.addLast("t$turns:asked")
                    delay(800)
                    continue
                }

                // execute: the plan runs in the background while the next decision is already being prepared (reflexes run right here)
                val acts: JSONArray = o.optJSONArray("actions") ?: JSONArray()
                val rf = o.opt("reflex")
                if (o.optBoolean("interrupt", false)) execJob?.cancel()
                if (withTimeoutOrNull(12_000) { execJob?.join(); true } == null) execJob?.cancel()
                var summary = (0 until acts.length()).mapNotNull { acts.optJSONObject(it)?.let { s -> s.optString("do").ifBlank { s.optString("type") } } }.joinToString(",")
                // fingers that stay down between decisions
                o.optJSONObject("hold")?.let { hh ->
                    for (k in hh.keys()) {
                        val p = hh.optJSONObject(k) ?: continue
                        Touch.hold(k, p.optDouble("x", 0.5).toFloat(), p.optDouble("y", 0.5).toFloat())
                        summary += (if (summary.isEmpty()) "" else ",") + "hold($k)"
                    }
                }
                val rel = o.opt("release")
                if (rel is JSONArray) { for (i in 0 until rel.length()) Touch.release(rel.optString(i)) }
                else if (rel is String && rel.isNotBlank() && !rel.equals("null", true)) { if (rel.equals("all", true)) Touch.releaseAll() else Touch.release(rel) }
                // controllers keep driving in the background while the next decision is prepared
                if (rf != null && rf != JSONObject.NULL && Build.VERSION.SDK_INT >= 30) {
                    val n0 = Reflex.apply(ctx, rf)
                    summary += (if (summary.isEmpty()) "" else ",") + "controller($n0)"
                    if (rf is JSONObject || rf is JSONArray) sp.edit().putString("ctl:$memKey", rf.toString().take(2500)).apply()
                }
                if (acts.length() > 0) {
                    execEnd = System.currentTimeMillis() + execEstimate(acts)
                    execJob = scope.launch { try { execActions(ctx, svc, acts) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* ignore */ } }
                }
                nextAt = System.currentTimeMillis() + o.optLong("next_ms", 2000).coerceIn(800, 8000)
                val didSomething = acts.length() > 0 || (rf != null && rf != JSONObject.NULL) || o.optJSONObject("hold") != null
                emptyTurns = if (didSomething || lastState == "loading" || o.optBoolean("done", false)) 0 else emptyTurns + 1
                turns++
                history.addLast("t$turns:$summary")
                while (history.size > 5) history.removeFirst()
                if (turns % 5 == 0 && note.isNotBlank()) sp.edit().putString("note:$memKey", note).apply()

                val say = o.optString("say", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
                if (o.optBoolean("done", false)) { withTimeoutOrNull(12_000) { execJob?.join() }; ended = say ?: "Done: the goal in $name is complete."; break }
                val t = System.currentTimeMillis()
                if (say != null && t - lastSay > 120_000) { lastSay = t; report(ctx, "[$name] $say") }
                else if (t - lastReport > 10 * 60_000L) { lastReport = t; report(ctx, "Still playing $name: ${(t - started) / 60_000} min, $turns turns. ${note.take(120)}") }
            }
        } catch (e: CancellationException) {
            ended = "Stopped playing $name."
            throw e
        } catch (e: Exception) {
            ended = "Stopped playing $name: ${e.message?.take(100)}"
        } finally {
            try { execJob?.cancel() } catch (e: Exception) { /* ignore */ }
            try { Reflex.stopAll(); Touch.stop() } catch (e: Exception) { /* ignore */ }
            try { if (hubOn) Hub.release() } catch (e: Exception) { /* ignore */ }
            try { if (note.isNotBlank()) sp.edit().putString("note:$memKey", note).apply() } catch (e: Exception) { /* ignore */ }
            try { bumpStats(sp, memKey, (System.currentTimeMillis() - started) / 60_000, turns) } catch (e: Exception) { /* ignore */ }
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
            try { Touch.stop() } catch (e: Exception) { /* ignore */ }
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
                svc.screenshot()?.let { frame = Player.frame(it, 900) }
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

/** YES / NO question on the screen, also over a game. Returns null when nobody answered in time. */
object Ask {
    suspend fun yesNo(ctx: Context, message: String, timeoutMs: Long): Boolean? = suspendCancellableCoroutine { cont ->
        val h = Handler(Looper.getMainLooper())
        var dlg: AlertDialog? = null
        h.post {
            try {
                val act = Engine.foreground
                val c: Context = act ?: run {
                    if (!Settings.canDrawOverlays(ctx)) { if (cont.isActive) cont.resume(null); return@post }
                    ContextThemeWrapper(ctx.applicationContext, R.style.Theme_Jarvis)
                }
                val d = AlertDialog.Builder(c)
                    .setTitle("Jarvis asks")
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton("NO") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setPositiveButton("YES") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .create()
                if (act == null) d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dlg = d
                d.show()
                h.postDelayed({
                    if (cont.isActive) cont.resume(null)
                    try { d.dismiss() } catch (e: Exception) { /* already gone */ }
                }, timeoutMs)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
        cont.invokeOnCancellation { h.post { try { dlg?.dismiss() } catch (e: Exception) { /* ignore */ } } }
    }
}

class PlayStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Player.stopAll(false)
    }
}
