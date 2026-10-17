package com.jarvis.assistant

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** How much damage a tool can do if it is misused. Drives who must approve it. */
enum class Risk { READ, LOCAL, EXTERNAL, DANGEROUS }

class A(val name: String, val desc: String, val required: Boolean = true, val type: String = "string")

class ToolResult(
    val ok: Boolean,
    val output: String,
    val files: List<String> = emptyList(),
    /** The output contains content from outside (web, file, mail): whatever uses it is tainted. */
    val untrusted: Boolean = false,
    val retriable: Boolean = false,
    val declined: Boolean = false,
    val data: JSONObject? = null
)

fun ok(output: String, files: List<String> = emptyList(), untrusted: Boolean = false, data: JSONObject? = null) = ToolResult(true, output, files, untrusted, data = data)
fun fail(msg: String, retriable: Boolean = false) = ToolResult(false, msg, retriable = retriable)

/** Everything a tool may need while running, and the user-facing hooks it must go through. */
class ToolCtx(
    val app: Context,
    val brain: Brain,
    val prefs: Prefs,
    val task: String = "",
    val project: String = "general",
    var tainted: Boolean = false,
    /** True when the user typed this very order themselves; false for steps an agent chose on its own. */
    val direct: Boolean = false,
    val confirm: suspend (String) -> Boolean = { false },
    val progress: (String) -> Unit = {},
    val cancelled: () -> Boolean = { false }
)

class Tool(
    val name: String,
    val description: String,
    val args: List<A>,
    val risk: Risk = Risk.READ,
    /** 0 = use the user's global tool timeout. */
    val timeoutMs: Long = 0L,
    val permissions: List<String> = emptyList(),
    val group: String = "core",
    /** Returns a description of what is wrong with the result, or null when it checks out. */
    val check: ((ToolCtx, JSONObject, ToolResult) -> String?)? = null,
    val run: suspend (ToolCtx, JSONObject) -> ToolResult
)

/**
 * One door for every tool. The model only PROPOSES a call; this code validates the arguments, checks permissions and the
 * user's approval rules, enforces the time limit, verifies the result, and writes the audit record.
 */
object Tools {
    private val registry = LinkedHashMap<String, Tool>()
    @Volatile private var started = false

    fun register(t: Tool) { synchronized(registry) { registry[t.name] = t } }
    fun unregister(name: String) { synchronized(registry) { registry.remove(name) } }
    fun names(prefix: String): List<String> = synchronized(registry) { registry.keys.filter { it.startsWith(prefix) } }

    fun ensure() {
        if (started) return
        synchronized(this) {
            if (started) return
            FileTools.register()
            Registry.registerAll()
            started = true
        }
    }

    fun get(name: String): Tool? { ensure(); return synchronized(registry) { registry[name] } }
    fun all(): List<Tool> { ensure(); return synchronized(registry) { registry.values.toList() } }

    /** A compact catalogue for the planner: name, what it does, its arguments. */
    fun describe(names: Collection<String>? = null): String =
        all().filter { names == null || it.name in names }.filter { !Config.toolDisabled(it.name) }.joinToString("\n") { t ->
            "- " + t.name + "(" + t.args.joinToString(", ") { it.name + (if (it.required) "" else "?") } + "): " + t.description +
                (if (t.risk == Risk.EXTERNAL || t.risk == Risk.DANGEROUS) " [needs approval when an agent chooses it]" else "")
        }

    /** Names with their arguments only: small enough to give the chat brain. */
    fun signatures(): String = all().filter { !Config.toolDisabled(it.name) && it.group != "core" || it.name == "time_now" }
        .joinToString("; ") { t -> t.name + "(" + t.args.joinToString(",") { it.name + (if (it.required) "" else "?") } + ")" }

    private fun needsApproval(t: Tool, c: ToolCtx): Boolean {
        if (t.risk == Risk.DANGEROUS) return true
        val level = Config.get("approval")
        return when (t.risk) {
            Risk.READ -> false
            Risk.LOCAL -> level == "strict"
            Risk.EXTERNAL -> c.tainted || level == "strict" || (!c.direct && level != "relaxed")
            else -> true
        }
    }

    private fun coerce(t: Tool, raw: JSONObject): Pair<JSONObject?, String?> {
        val out = JSONObject()
        for (a in t.args) {
            val has = raw.has(a.name) && !raw.isNull(a.name)
            if (!has) { if (a.required) return null to "missing argument \"${a.name}\" (${a.desc})"; continue }
            val v = raw.get(a.name)
            when (a.type) {
                "int" -> out.put(a.name, (v as? Number)?.toLong() ?: v.toString().trim().toLongOrNull() ?: return null to "\"${a.name}\" must be a whole number")
                "number" -> out.put(a.name, (v as? Number)?.toDouble() ?: v.toString().trim().toDoubleOrNull() ?: return null to "\"${a.name}\" must be a number")
                "bool" -> out.put(a.name, (v as? Boolean) ?: v.toString().trim().lowercase().let { it == "true" || it == "yes" || it == "1" })
                "json" -> out.put(a.name, v)
                else -> {
                    val s = v.toString()
                    if (s.length > 400_000) return null to "\"${a.name}\" is too long"
                    out.put(a.name, s)
                }
            }
        }
        return out to null
    }

    suspend fun execute(c: ToolCtx, name: String, rawArgs: JSONObject): ToolResult {
        val t = get(name) ?: return fail("There is no tool called \"$name\".")
        val actor = if (c.direct) "user" else "agent"
        val t0 = System.currentTimeMillis()
        val sess = Sessions.cur()
        Progress.note(sess, "Using ${t.name}...")
        fun finish(r: ToolResult, approved: String): ToolResult {
            val ms = System.currentTimeMillis() - t0
            Progress.note(sess, (if (r.ok) "Done: " else "Failed: ") + t.name + (if (!r.ok) " - " + Axiom.clip(r.output, 70) else " (" + (ms / 100) / 10.0 + " s)"))
            Audit.log(actor, t.name, rawArgs.toString(), (if (r.ok) "ok: " else if (r.declined) "DECLINED: " else "FAIL: ") + r.output, approved, c.project, c.task)
            Perf.log("tool", t.name, "", ms, 1, 0, r.ok, if (r.ok) "" else r.output)
            if (!r.ok && !r.declined) Procedures.note("tool", t.name, rawArgs.toString(), false, r.output)
            if (r.untrusted) c.tainted = true
            return ToolResult(r.ok, Secrets.redact(r.output), r.files, r.untrusted, r.retriable, r.declined, r.data)
        }
        if (Config.toolDisabled(t.name)) return finish(fail("The tool ${t.name} is switched off in settings."), "n/a")
        if (c.cancelled()) return finish(fail("Cancelled."), "n/a")
        val (args, err) = coerce(t, rawArgs)
        if (args == null) return finish(fail("Bad call to ${t.name}: $err"), "n/a")
        if (Shield.leaks(args)) return finish(fail("Blocked: the arguments contain what looks like a secret key. Keys are never passed to tools."), "n/a")
        for (p in t.permissions) {
            if (ContextCompat.checkSelfPermission(c.app, p) != PackageManager.PERMISSION_GRANTED) {
                return finish(fail("${t.name} needs the permission ${p.substringAfterLast('.')}. Allow it for AXIOM in the phone settings."), "n/a")
            }
        }
        var approved = "auto"
        if (needsApproval(t, c)) {
            val why = if (c.tainted) "\n(This work used content from the web or a file, so I am double-checking.)" else ""
            val yes = c.confirm("AXIOM wants to use: ${t.name}\n" + Axiom.clip(Secrets.redact(args.toString()), 600) + why)
            if (!yes) return finish(ToolResult(false, "You declined ${t.name}. Nothing was done.", declined = true), "declined")
            approved = "user"
        }
        val limit = if (t.timeoutMs > 0) t.timeoutMs else Config.int("tool_timeout_s") * 1000L
        val res = try {
            withTimeoutOrNull(limit) { t.run(c, args) }
                ?: return finish(fail("${t.name} did not finish within ${limit / 1000} seconds." + (if (t.risk >= Risk.EXTERNAL) " It may or may not have happened; check before repeating it." else ""), retriable = t.risk < Risk.EXTERNAL), approved)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HttpError) {
            return finish(fail("${t.name} failed: ${e.message}", retriable = e.retriable), approved)
        } catch (e: java.io.IOException) {
            return finish(fail("${t.name} could not reach what it needed: ${e.message}", retriable = true), approved)
        } catch (e: Exception) {
            Diag.record(e)
            return finish(fail("${t.name} failed: ${e.message ?: e.javaClass.simpleName}"), approved)
        }
        val problem = try { if (res.ok) t.check?.invoke(c, args, res) else null } catch (e: Exception) { null }
        if (problem != null) return finish(ToolResult(false, "The result of ${t.name} did not pass the check: $problem", res.files, res.untrusted, retriable = true), approved)
        return finish(res, approved)
    }
}
