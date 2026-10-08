package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
                val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").replace("\n", " ").take(60)
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

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}

/**
 * The operator: looks at the screen, asks the brain for the next single step, performs it, repeats.
 * Payments and deletions are never tapped without an explicit yes from the user.
 */
class ScreenAgent(private val context: Context, private val brain: Brain) {

    private val system = """
        You operate an Android phone for the user by reading the screen and choosing ONE next step.
        The screen is a numbered list: [index] Kind "label" (flags). Use the index in actions.
        Respond ONLY with a JSON object:
          {"type": one of "click","type","scroll_down","scroll_up","back","home","open_app","wait","done","ask",
           "index": number (for click/type), "text": string (for type), "app": string (for open_app),
           "reason": short sentence (for done: the result for the user; for ask: what you need from the user)}
        Rules: one step at a time. Prefer open_app to start. Use "done" as soon as the goal is achieved.
        Use "ask" if you need information only the user has. Never invent screen elements.
        If you are stuck or repeating, say "done" and explain what blocked you.
    """.trimIndent()

    suspend fun run(goal: String, onProgress: (String) -> Unit, confirm: suspend (String) -> Boolean): String {
        if (JarvisAccessibilityService.instance == null) {
            return "The screen agent is off. Enable 'Jarvis screen agent' in Settings, Accessibility."
        }
        val steps = ArrayList<String>()
        val actions = Actions(context)

        for (i in 1..25) {
            currentCoroutineContext().ensureActive()
            val svc = JarvisAccessibilityService.instance ?: return "Screen agent disconnected."
            val screen = svc.dump()
            val screenText = screen?.text?.ifBlank { "(empty screen)" } ?: "(no screen content available)"

            val prompt = "GOAL: $goal\n\nPREVIOUS STEPS:\n" +
                (if (steps.isEmpty()) "(none)" else steps.takeLast(8).joinToString("\n")) +
                "\n\nCURRENT SCREEN (app ${screen?.pkg ?: "?"}):\n$screenText"

            val raw = try {
                brain.complete(system, prompt, emptyList(), null, json = true)
            } catch (e: Exception) {
                return "Agent stopped, the brain is unavailable: ${e.message}"
            }

            val o = try {
                JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
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
                        result = if (svc.click(node)) "ok" else "failed"
                    }
                    onProgress("Step $i: tap ${label.ifBlank { "#$idx" }}")
                }
                "type" -> {
                    val node = screen?.nodes?.getOrNull(idx)
                    val text = o.optString("text", "")
                    result = if (node != null && svc.setText(node, text)) "ok" else "failed"
                    onProgress("Step $i: type text")
                }
                "scroll_down" -> { result = if (svc.scroll(true)) "ok" else "nothing to scroll"; onProgress("Step $i: scroll down") }
                "scroll_up" -> { result = if (svc.scroll(false)) "ok" else "nothing to scroll"; onProgress("Step $i: scroll up") }
                "back" -> { svc.back(); onProgress("Step $i: back") }
                "home" -> { svc.home(); onProgress("Step $i: home") }
                "open_app" -> {
                    val app = o.optString("app", "")
                    result = actions.openApp(app) ?: "ok"
                    onProgress("Step $i: open $app")
                }
                "wait" -> { delay(2000); onProgress("Step $i: wait") }
                else -> result = "unknown step type '$type'"
            }
            steps.add("$i. $type${if (idx >= 0) " #$idx" else ""} -> $result")
            delay(1300)
        }
        return "I reached the 25 step limit before finishing. Say 'continue' with the same goal if you want me to keep going."
    }
}
