package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

class ScreenDump(val pkg: String, val text: String, val nodes: List<AccessibilityNodeInfo>, val labels: List<String>)

/** The hands of the screen agent. Enabled by the user in system Accessibility settings. */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun dump(): ScreenDump? {
        val root = rootInActiveWindow ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val labels = ArrayList<String>()
        val sb = StringBuilder()

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= 120 || depth > 40) return
            if (n.isVisibleToUser) {
                val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: n.hintText?.toString() ?: "").replace("\n", " ").take(60)
                val interactive = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
                if (label.isNotBlank() || interactive) {
                    val flags = ArrayList<String>()
                    if (n.isClickable) flags.add("click")
                    if (n.isEditable) flags.add("edit")
                    if (n.isScrollable) flags.add("scroll")
                    if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "check")
                    val kind = (n.className?.toString() ?: "View").substringAfterLast('.')
                    sb.append('[').append(nodes.size).append("] ").append(kind)
                    if (label.isNotBlank()) sb.append(" \"").append(label).append('"')
                    if (flags.isNotEmpty()) sb.append(" (").append(flags.joinToString(",")).append(')')
                    sb.append('\n')
                    nodes.add(n)
                    labels.add(label)
                }
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return ScreenDump(root.packageName?.toString() ?: "?", sb.toString(), nodes, labels)
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                find(c)?.let { return it }
            }
            return null
        }
        val target = find(root) ?: return false
        return target.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    /** Waits up to [timeoutMs] for a button (by view id or label) and taps it. Used to press Send in chat apps. */
    fun clickSend(viewIds: List<String>, labels: List<String>, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val root = rootInActiveWindow
            if (root != null) {
                for (id in viewIds) {
                    val hit = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
                    if (hit != null && click(hit)) return true
                }
                val byLabel = findByLabel(root, labels)
                if (byLabel != null && click(byLabel)) return true
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun findByLabel(n: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val d = (n.contentDescription?.toString() ?: n.text?.toString() ?: "").trim().lowercase()
        if (d.isNotEmpty() && labels.any { d == it }) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = findByLabel(c, labels)
            if (r != null) return r
        }
        return null
    }

    /** A real picture of the screen (Android 11+). Null if blocked: protected windows, locked phone, or called too fast. */
    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) { null } finally { hb.close() }
                        if (cont.isActive) cont.resume(bmp)
                    }
                    override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** Taps at screen coordinates with a real touch gesture. Works on custom views, games and stubborn buttons. */
    fun tapXY(x: Float, y: Float): Boolean {
        return try {
            val p = android.graphics.Path().apply { moveTo(x, y) }
            val g = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60)).build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) { false }
    }

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        return tapXY(r.exactCenterX(), r.exactCenterY())
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}

/**
 * The operator: looks at the screen, asks the brain for the next single step, performs it, repeats.
 * It notices when a step changed nothing, switches tactics (real touch gestures, a screenshot for the brain) and refuses to loop.
 * Payments and deletions are never tapped without an explicit yes from the user.
 */
class ScreenAgent(private val context: Context, private val brain: Brain) {

    private val system = """
        You operate an Android phone for the user by reading the screen and choosing ONE next step.
        The screen is a numbered list: [index] Kind "label" (flags). Use the index in actions.
        Respond ONLY with a JSON object:
          {"type": one of "click","type","enter","scroll_down","scroll_up","back","home","open_app","tap_xy","wait","done","ask",
           "index": number (for click/type), "text": string (for type), "app": string (for open_app),
           "x": 0..1, "y": 0..1 (for tap_xy: fractions of the screen width and height, only when the element is missing from the list, e.g. games or custom views),
           "reason": short sentence (for done: the result for the user; for ask: what you need from the user; for tap_xy: what you tap)}
        Rules:
        - One step at a time. Prefer open_app to start. Use "done" as soon as the goal is achieved.
        - An element flagged (edit) is a text field: use "type" with its index. Never click it again and again.
        - To search inside an app: tap the search icon ONCE, then "type" the words into the (edit) field, then tap the matching result.
        - To message someone: open the app, find or search the chat, open it, "type" in the message field, tap Send.
        - If PREVIOUS STEPS say [SCREEN UNCHANGED], that step did nothing. NEVER repeat it: choose a different element, type, scroll, go back, or tap_xy.
        - Use "ask" if you need information only the user has. Never invent screen elements.
        - If you are stuck, say "done" and explain exactly what blocked you.
    """.trimIndent()

    private suspend fun ask(prompt: String, img: String?): String {
        return try {
            brain.complete(system, prompt, emptyList(), img, json = true)
        } catch (e: Exception) {
            if (img == null) throw e
            brain.complete(system, prompt, emptyList(), null, json = true)   // no vision available: continue with text only
        }
    }

    suspend fun run(goal: String, onProgress: (String) -> Unit, confirm: suspend (String) -> Boolean): String {
        if (JarvisAccessibilityService.instance == null) {
            return "The screen agent is off. Enable 'Jarvis screen agent' in Settings, Accessibility."
        }
        val steps = ArrayList<String>()
        val actions = Actions(context)
        var lastHash = 0
        var same = 0
        var lastClickKey = ""

        for (i in 1..45) {
            currentCoroutineContext().ensureActive()
            val svc = JarvisAccessibilityService.instance ?: return "Screen agent disconnected."
            val screen = svc.dump()
            val screenText = screen?.text?.ifBlank { "(empty screen)" } ?: "(no screen content available)"

            val hash = screenText.hashCode()
            if (i > 1 && hash == lastHash) {
                same++
                if (steps.isNotEmpty()) steps[steps.size - 1] = steps.last() + " [SCREEN UNCHANGED]"
            } else same = 0
            lastHash = hash

            val img = if (same >= 2 && Build.VERSION.SDK_INT >= 30) svc.screenshot()?.let { Watcher.toBase64(it, 768) } else null
            val advice = when {
                same >= 5 -> "\nYOU ARE STUCK: $same steps in a row changed nothing. Stop now with \"done\" and explain what blocked you, unless a totally different approach is obvious."
                same >= 2 -> "\nWARNING: your last $same steps changed nothing. Do NOT repeat them. Try a different element, type into the (edit) field, scroll, go back, or tap_xy" +
                    (if (img != null) " (a screenshot is attached to help you aim)." else ".")
                else -> ""
            }

            val prompt = "GOAL: $goal\n\nPREVIOUS STEPS:\n" +
                (if (steps.isEmpty()) "(none)" else steps.takeLast(8).joinToString("\n")) + advice +
                "\n\nCURRENT SCREEN (app ${screen?.pkg ?: "?"}):\n$screenText"

            val raw = try {
                ask(prompt, img)
            } catch (e: Exception) {
                return "Agent stopped, the brain is unavailable: ${e.message}"
            }

            val o = try {
                JSONObject(Brain.extractJson(raw))
            } catch (e: Exception) {
                steps.add("$i. (unreadable answer)")
                delay(800)
                continue
            }
            val type = o.optString("type", "").lowercase()
            val idx = o.optInt("index", -1)
            val reason = o.optString("reason", "")

            var result = "ok"
            when (type) {
                "done" -> return reason.ifBlank { "Done." }
                "ask" -> return "I need your input: $reason"
                "click" -> {
                    val node = screen?.nodes?.getOrNull(idx)
                    val label = screen?.labels?.getOrNull(idx) ?: ""
                    if (node == null) result = "failed: no such element"
                    else {
                        if (Guard.isRiskyText(label) && !confirm("The agent wants to tap \"$label\".\nGoal: $goal")) {
                            return "Stopped: you declined the tap on \"$label\"."
                        }
                        val key = "$idx:$label"
                        result = when {
                            same >= 1 && key == lastClickKey -> if (svc.tapNode(node)) "ok (touch gesture)" else "failed"
                            svc.click(node) -> "ok"
                            svc.tapNode(node) -> "ok (touch gesture)"
                            else -> "failed"
                        }
                        lastClickKey = key
                    }
                    onProgress("Step $i: tap ${label.ifBlank { "#$idx" }}")
                }
                "type" -> {
                    val node = screen?.nodes?.getOrNull(idx)?.takeIf { it.isEditable } ?: screen?.nodes?.firstOrNull { it.isEditable }
                    val text = o.optString("text", "")
                    result = if (node != null && svc.setText(node, text)) "ok" else "failed: no text field on screen"
                    onProgress("Step $i: type text")
                }
                "enter" -> {
                    val node = screen?.nodes?.firstOrNull { it.isEditable }
                    result = if (Build.VERSION.SDK_INT >= 30 && node != null &&
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) "ok" else "failed"
                    onProgress("Step $i: enter")
                }
                "tap_xy" -> {
                    val x = o.optDouble("x", -1.0)
                    val y = o.optDouble("y", -1.0)
                    if (x !in 0.0..1.0 || y !in 0.0..1.0) result = "failed: x and y must be between 0 and 1"
                    else {
                        if (Guard.isRiskyText(reason) && !confirm("The agent wants to tap: \"$reason\".\nGoal: $goal")) {
                            return "Stopped: you declined that tap."
                        }
                        val dm = context.resources.displayMetrics
                        result = if (svc.tapXY((x * dm.widthPixels).toFloat(), (y * dm.heightPixels).toFloat())) "ok" else "failed"
                    }
                    onProgress("Step $i: tap ${reason.ifBlank { "screen position" }}")
                }
                "scroll_down" -> { result = if (svc.scroll(true)) "ok" else "nothing to scroll"; onProgress("Step $i: scroll down") }
                "scroll_up" -> { result = if (svc.scroll(false)) "ok" else "nothing to scroll"; onProgress("Step $i: scroll up") }
                "back" -> { svc.back(); onProgress("Step $i: back") }
                "home" -> { svc.home(); onProgress("Step $i: home") }
                "open_app" -> {
                    val app = o.optString("app", "")
                    result = actions.openApp(app, store = false) ?: "ok"
                    onProgress("Step $i: open $app")
                }
                "wait" -> { delay(2000); onProgress("Step $i: wait") }
                else -> result = "unknown step type '$type'"
            }
            steps.add("$i. $type${if (idx >= 0) " #$idx" else ""} -> $result")
            delay(1200)
        }
        return "I reached the 45 step limit before finishing. Say 'continue' with the same goal if you want me to keep going."
    }
}
