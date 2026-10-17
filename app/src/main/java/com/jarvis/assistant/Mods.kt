package com.jarvis.assistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mods: small programs AXIOM writes to extend itself. A mod is JavaScript running in an isolated page that has no network and no files
 * of its own; everything it does goes through `axiom.call(tool, args)`, which uses AXIOM's normal tools, permissions and approvals.
 * Kinds: command (answers a phrase or /command), panel (a screen of its own), task (runs every N minutes), notification (reacts to notifications).
 */
object ModRuntime {
    const val PRELUDE = "var __axN=0,__axP={};function __axRes(i,ok,v){var p=__axP[i];if(!p)return;delete __axP[i];ok?p[0](v):p[1](new Error(v));}" +
        "if(typeof AxiomBridge==='undefined'){window.AxiomBridge={call:function(i,t,a){setTimeout(function(){__axRes(i,true,'(preview)')},0)},ask:function(i,q){setTimeout(function(){__axRes(i,true,'(preview)')},0)},kvGet:function(){return ''},kvSet:function(){},notify:function(){},say:function(){},done:function(){},close:function(){}}}" +
        ";var axiom={input:'',call:function(t,a){return new Promise(function(r,j){var i=++__axN;__axP[i]=[r,j];AxiomBridge.call(i,t,JSON.stringify(a||{}))})}," +
        "ask:function(q){return new Promise(function(r,j){var i=++__axN;__axP[i]=[r,j];AxiomBridge.ask(i,String(q))})}," +
        "get:function(k){return AxiomBridge.kvGet(String(k))},set:function(k,v){AxiomBridge.kvSet(String(k),String(v))}," +
        "notify:function(t){AxiomBridge.notify(String(t))},say:function(t){AxiomBridge.say(String(t))},close:function(){AxiomBridge.close()}};"

    class Host(val mod: String, val dry: Boolean, val sess: String, val done: CompletableDeferred<Pair<Boolean, String>>?, val onClose: (() -> Unit)? = null) {
        @Volatile var web: WebView? = null
        private val calls = AtomicInteger()

        private fun resolve(id: Int, good: Boolean, text: String) {
            Handler(Looper.getMainLooper()).post { try { web?.evaluateJavascript("__axRes($id,$good,${JSONObject.quote(text)})", null) } catch (e: Exception) { /* page gone */ } }
        }

        @JavascriptInterface fun call(id: Int, tool: String, args: String) {
            if (calls.incrementAndGet() > 80) { resolve(id, false, "too many tool calls in one run"); return }
            if (dry) { resolve(id, true, "(dry run: not executed)"); return }
            Engine.scope.launch(Dispatchers.Default + Sessions.element(sess)) {
                try {
                    val a = try { JSONObject(args) } catch (e: Exception) { JSONObject() }
                    val ctx = ToolCtx(Axiom.ctx, Shared.brain(), Prefs(Axiom.ctx), "mod:$mod", Prefs(Axiom.ctx).activeProject, false, false, { Confirmer.ask(Axiom.ctx, "Mod \"$mod\" asks:\n$it") })
                    val r = Tools.execute(ctx, tool, a)
                    resolve(id, r.ok, r.output)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Throwable) { resolve(id, false, e.message ?: "failed") }
            }
        }

        @JavascriptInterface fun ask(id: Int, prompt: String) {
            if (dry) { resolve(id, true, "(dry run)"); return }
            Engine.scope.launch(Dispatchers.Default) {
                try { resolve(id, true, Router.run(Shared.brain(), Router.T.CHAT, Router.Opts("You answer for a small program inside AXIOM. Be direct and brief.", prompt, task = "mod:$mod"))) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { resolve(id, false, e.message ?: "failed") }
            }
        }

        @JavascriptInterface fun kvGet(k: String): String = Store.get().one("SELECT v FROM mod_kv WHERE mod = ? AND k = ?", mod, k)?.get("v") ?: ""
        @JavascriptInterface fun kvSet(k: String, v: String) { if (!dry) Store.get().replace("mod_kv", ContentValues().apply { put("mod", mod); put("k", k.take(80)); put("v", v.take(50_000)) }) }
        @JavascriptInterface fun notify(t: String) { if (!dry) Notify.post("info", "mod:$mod", "AXIOM: $mod", t, 5_000L) }
        @JavascriptInterface fun say(t: String) { if (!dry) ChatBus.publishTo("main", "jarvis", "[$mod] $t") }
        @JavascriptInterface fun done(out: String, ok: Boolean) { done?.complete(ok to out.take(20_000)) }
        @JavascriptInterface fun close() { onClose?.invoke() }
    }

    fun blockingClient() = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val u = request?.url?.toString() ?: return null
            if (u.startsWith("https://mod.local/") || u.startsWith("data:") || u.startsWith("blob:")) return null
            return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** Null when the code parses as the body of an async function; otherwise the syntax error. */
    suspend fun syntaxProblem(code: String): String? {
        val (good, out) = JsSandbox.run("try{var AF=Object.getPrototypeOf(async function(){}).constructor;new AF(DATA);return 'OK'}catch(e){return 'SYNTAX '+e.message}", code, 8000)
        return if (good && out.contains("OK")) null else out.replace("=> ", "").trim()
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(mod: Map<String, String>, input: String, dry: Boolean = false, timeoutMs: Long = 120_000L): Pair<Boolean, String> {
        val code = mod["code"] ?: ""
        if (code.isBlank()) return false to "This mod has no code to run."
        syntaxProblem(code)?.let { return false to "Syntax error: $it" }
        val done = CompletableDeferred<Pair<Boolean, String>>()
        var web: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                val w = WebView(Axiom.ctx); web = w
                w.settings.javaScriptEnabled = true; w.settings.allowFileAccess = false; w.settings.allowContentAccess = false; w.settings.blockNetworkLoads = true
                val host = Host(mod["name"] ?: "mod", dry, Sessions.cur(), done); host.web = w
                w.addJavascriptInterface(host, "AxiomBridge")
                w.webViewClient = blockingClient()
                val safe = code.replace("</script", "<\\/script")
                val html = "<html><body><script>" + PRELUDE + "window.onerror=function(m){AxiomBridge.done('Error: '+m,false)};axiom.input=" + JSONObject.quote(input) +
                    ";(async function(){try{var __r=await (async function(){" + safe + "\n})();AxiomBridge.done(__r===undefined||__r===null?'':(typeof __r==='object'?JSON.stringify(__r):String(__r)),true)}catch(e){AxiomBridge.done('Error: '+(e&&e.message||e),false)}})();</script></body></html>"
                w.loadDataWithBaseURL("https://mod.local/", html, "text/html", "utf-8", null)
            }
            return withTimeoutOrNull(timeoutMs) { done.await() } ?: (false to "The mod did not finish in ${timeoutMs / 1000} seconds.")
        } finally {
            withContext(Dispatchers.Main) { try { web?.destroy() } catch (e: Exception) { /* gone */ } }
        }
    }
}

object Mods {
    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(30).ifBlank { "mod" }
    fun row(n: String) = Store.get().one("SELECT * FROM mods WHERE name = ?", slug(n))

    fun withPrelude(html: String): String {
        val tag = "<script>" + ModRuntime.PRELUDE + "</script>"
        val m = Regex("(?i)<head[^>]*>").find(html)
        return if (m != null) html.substring(0, m.range.last + 1) + tag + html.substring(m.range.last + 1) else tag + html
    }

    fun save(name: String, descr: String, kind: String, trig: String, code: String, html: String, every: Int): String {
        val n = slug(name)
        val v = (Store.get().scalar("SELECT COALESCE(MAX(version),0) FROM mod_versions WHERE name = ?", n) + 1).toInt()
        val now = System.currentTimeMillis(); val old = row(n)
        Store.get().replace("mod_versions", ContentValues().apply { put("name", n); put("version", v); put("code", code); put("html", html); put("ts", now) })
        Store.get().replace("mods", ContentValues().apply {
            put("name", n); put("descr", descr.take(300)); put("kind", kind); put("trig", trig.take(200)); put("code", code); put("html", html); put("every_min", every)
            put("last_run", old?.get("last_run")?.toLongOrNull() ?: 0L); put("version", v); put("enabled", 1); put("created", old?.get("created")?.toLongOrNull() ?: now); put("runs", old?.get("runs")?.toLongOrNull() ?: 0L)
        })
        return n
    }

    /** A command mod whose phrase or /command matches this message, with the text to give it. */
    fun matchCommand(text: String): Pair<Map<String, String>, String>? {
        val rows = try { Store.get().query("SELECT * FROM mods WHERE enabled = 1 AND kind = 'command'") } catch (e: Exception) { return null }
        val t = text.trim(); val low = t.lowercase()
        for (r in rows) {
            val trig = (r["trig"] ?: "").trim(); if (trig.isEmpty()) continue
            when {
                trig.startsWith("/") -> if (low == trig.lowercase() || low.startsWith(trig.lowercase() + " ")) return r to t.substring(trig.length).trim()
                trig.startsWith("re:") -> if (try { Regex(trig.removePrefix("re:"), RegexOption.IGNORE_CASE).containsMatchIn(t) } catch (e: Exception) { false }) return r to t
                trig.length >= 4 -> if (low.contains(trig.lowercase())) return r to t
            }
        }
        return null
    }

    suspend fun runMod(m: Map<String, String>, input: String, dry: Boolean = false): Pair<Boolean, String> {
        val res = ModRuntime.run(m, input, dry)
        if (!dry) try { Store.get().exec("UPDATE mods SET runs = runs + 1, last_run = ? WHERE name = ?", System.currentTimeMillis(), m["name"]) } catch (e: Exception) { /* counter only */ }
        return res
    }

    private fun deliver(name: String, out: String) {
        val o = out.trim(); if (o.isEmpty() || o.startsWith("(silent)")) return
        ChatBus.publishTo("main", "jarvis", "[$name] $o")
        try { Memory.get(Axiom.ctx).addChat("jarvis", "[$name] $o") } catch (e: Exception) { /* history only */ }
        try { Telegram.sendToOwner(Prefs(Axiom.ctx), "[$name] ${Axiom.clip(o, 1500)}") } catch (e: Exception) { /* no Telegram */ }
        Notify.post("info", "mod:$name", "AXIOM: $name", o)
    }

    suspend fun tick(c: ToolCtx) {
        val now = System.currentTimeMillis()
        for (m in Store.get().query("SELECT * FROM mods WHERE enabled = 1 AND kind = 'task' AND every_min > 0")) {
            if ((m["last_run"]?.toLongOrNull() ?: 0L) + (m["every_min"]?.toLongOrNull() ?: 60L) * 60_000L > now) continue
            Store.get().exec("UPDATE mods SET last_run = ? WHERE name = ?", now, m["name"])
            val (good, out) = runMod(m, "")
            if (good) deliver(m["name"]!!, out) else deliver(m["name"]!!, "failed: " + Axiom.clip(out, 300))
        }
    }

    suspend fun onNotification(pkg: String, app: String, title: String, text: String) {
        val rows = try { Store.get().query("SELECT * FROM mods WHERE enabled = 1 AND kind = 'notification'") } catch (e: Exception) { return }
        if (rows.isEmpty()) return
        val hay = "$pkg $app $title $text".lowercase()
        for (m in rows) {
            val keys = (m["trig"] ?: "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (keys.isEmpty() || keys.none { hay.contains(it) }) continue
            val (good, out) = runMod(m, JSONObject().put("pkg", pkg).put("app", app).put("title", title).put("text", text).toString())
            if (good) deliver(m["name"]!!, out)
        }
    }

    fun openPanel(ctx: Context, name: String) {
        val i = Intent(ctx, ModActivity::class.java).putExtra("mod", name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { /* the notification below is the fallback */ }
        try {
            Notify.channels(ctx)
            val pi = PendingIntent.getActivity(ctx, name.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = android.app.Notification.Builder(ctx, Notify.askChannel(ctx)).setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Open: $name").setContentText("Tap to open").setContentIntent(pi).setAutoCancel(true).build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(name.hashCode(), n)
        } catch (e: Exception) { /* notifications may be off */ }
    }

    private const val SYSTEM = "You write MODS: small programs that extend an AI assistant app on an Android phone. A mod runs in an isolated JavaScript page with NO network and NO files. " +
        "It reaches the world only through these functions (all return Promises, so use await):\n" +
        "  await axiom.call(toolName, argsObject)  -> the tool's text output (rejects with an Error on failure). Any AXIOM tool works: web_search, web_fetch, connector_get, email_inbox, calendar_list, notes, file tools, mcp_* tools...\n" +
        "  await axiom.ask(prompt) -> a short answer from the AI\n" +
        "  axiom.get(key) / axiom.set(key, value) -> small persistent storage for this mod (strings)\n" +
        "  axiom.notify(text) -> a phone notification;  axiom.say(text) -> a line in the chat;  axiom.input -> the input text\n" +
        "KINDS: \"command\" (trigger = a /command or a phrase or re:regex; code is an async function BODY whose returned string is the reply; axiom.input is the rest of the message), " +
        "\"task\" (runs every every_min minutes; return a string to be shown, or return '(silent)' when there is nothing to say), " +
        "\"notification\" (trigger = comma separated keywords matched against incoming notifications; axiom.input is JSON {pkg,app,title,text}), " +
        "\"panel\" (a full screen app: put a COMPLETE self-contained HTML page with inline CSS and JS in html; it can call axiom.call etc.; mobile first, dark theme, real content, working controls, polished design; leave code empty).\n" +
        "Return ONE JSON object: {\"name\":\"snake_case\",\"description\":\"what it does\",\"kind\":\"command|task|notification|panel\",\"trigger\":\"...\",\"every_min\":0,\"code\":\"...\",\"html\":\"...\",\"test_input\":\"a realistic input for testing\"}. " +
        "Handle errors with try/catch and return a clear message. No placeholders."

    private fun slugKind(k: String) = if (k in listOf("command", "task", "notification", "panel")) k else "command"

    fun register() {
        Ticker.hook { c -> tick(c) }
        UiTheme.register()
        Tools.register(Tool("mod_make", "Build a mod from a description: AXIOM writes it, test-runs it (tools are not really executed during tests), looks at panels like a reviewer, repairs it, and saves it. Use for new commands, automations, dashboards, panels, reactions to notifications.",
            listOf(A("request", "what the mod should do, in the user's words, with any detail")), Risk.LOCAL, 900_000L, group = "mods", run = { c, a ->
                var feedback = ""; var last: JSONObject? = null; val log = StringBuilder()
                for (round in 1..3) {
                    if (c.cancelled()) return@Tool fail("Cancelled.")
                    c.progress("Mod, round $round: writing...")
                    val j = try { Router.runJson(c.brain, Router.T.CODE, Router.Opts(SYSTEM + feedback, a.getString("request"), json = true, maxOut = 9000, task = c.task)) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return@Tool fail("The model could not write the mod: ${e.message}", retriable = true) }
                    last = j
                    val kind = slugKind(j.optString("kind")); val code = j.optString("code"); val html = j.optString("html")
                    var problem: String? = null
                    if (kind == "panel") {
                        if (html.length < 300) problem = "the html is missing or too short"
                        else {
                            c.progress("Mod, round $round: running the panel and looking at it...")
                            val rep = Preview.inspect(withPrelude(html), null)
                            if (rep != null) {
                                val v = Preview.review(c.brain, a.getString("request"), html, rep)
                                log.append("round $round: panel quality ${v.score}/10\n")
                                if (!v.pass) problem = v.issues.joinToString("; ") { Axiom.clip(it, 160) }
                            }
                        }
                    } else {
                        c.progress("Mod, round $round: test run...")
                        val (good, out) = ModRuntime.run(mapOf("name" to slug(j.optString("name")), "code" to code), j.optString("test_input", ""), dry = true, timeoutMs = 30_000L)
                        log.append("round $round: test " + (if (good) "passed" else "failed") + "\n")
                        if (!good) problem = out
                        else if (code.length < 20) problem = "the code is empty"
                    }
                    if (problem == null || round == 3) {
                        if (kind != "panel" && problem != null) return@Tool fail("The mod still fails its own test after 3 rounds, so I did not save it: ${Axiom.clip(problem, 300)}")
                        val n = save(j.optString("name", "mod"), j.optString("description", ""), kind, j.optString("trigger", ""), code, html, j.optInt("every_min", 0))
                        return@Tool ok("Mod \"$n\" saved ($kind" + (if (j.optString("trigger").isNotBlank()) ", trigger: ${j.optString("trigger")}" else "") + (if (kind == "task") ", every ${j.optInt("every_min")} min" else "") + "). " + j.optString("description") + "\n" + log +
                            (if (problem != null) "Remaining notes: " + Axiom.clip(problem, 300) + "\n" else "") + (if (kind == "panel") "Open it with: open $n." else ""))
                    }
                    feedback = "\nYOUR PREVIOUS ATTEMPT:\n" + Axiom.clip(j.toString(), 12000) + "\nPROBLEM FOUND WHEN RUNNING IT:\n" + problem + "\nFix it and return the full JSON again."
                }
                fail("Could not build the mod. " + (last?.optString("name") ?: ""))
            }))
        Tools.register(Tool("mod_create", "Save a mod you already have the code for (see mod_make for the API). kind: command, task, notification or panel.",
            listOf(A("name", "mod name"), A("description", "what it does"), A("kind", "command, task, notification or panel"), A("trigger", "command phrase, /command, re:regex, or notification keywords", false), A("code", "async function body (not for panels)", false), A("html", "full page (panels)", false), A("every_min", "minutes between runs (task)", false, "int")), Risk.LOCAL, 120_000L, group = "mods", run = { _, a ->
                val kind = slugKind(a.getString("kind")); val code = a.optString("code", "")
                if (kind != "panel") { if (code.isBlank()) return@Tool fail("A $kind mod needs code."); ModRuntime.syntaxProblem(code)?.let { return@Tool fail("Syntax error: $it") } }
                ok("Mod \"${save(a.getString("name"), a.getString("description"), kind, a.optString("trigger", ""), code, a.optString("html", ""), a.optInt("every_min", 0))}\" saved.")
            }))
        Tools.register(Tool("mod_update", "Change a mod's code, page, trigger or schedule. The old version is kept.", listOf(A("name", "mod name"), A("code", "new code", false), A("html", "new page", false), A("trigger", "new trigger", false), A("description", "new description", false), A("every_min", "new interval", false, "int")), Risk.LOCAL, 60_000L, group = "mods", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such mod.")
            val code = a.optString("code", r["code"] ?: "")
            if (r["kind"] != "panel" && code.isNotBlank()) ModRuntime.syntaxProblem(code)?.let { return@Tool fail("Syntax error: $it") }
            val n = save(r["name"]!!, a.optString("description", r["descr"] ?: ""), r["kind"] ?: "command", a.optString("trigger", r["trig"] ?: ""), code, a.optString("html", r["html"] ?: ""), a.optInt("every_min", r["every_min"]?.toIntOrNull() ?: 0))
            ok("Mod $n updated to version ${row(n)?.get("version")}.")
        }))
        Tools.register(Tool("mod_list", "List mods with their kind, trigger and state.", emptyList(), Risk.READ, group = "mods", run = { _, _ ->
            val rs = Store.get().query("SELECT * FROM mods ORDER BY name")
            if (rs.isEmpty()) ok("No mods yet.") else ok(rs.joinToString("\n") { it["name"] + " v" + it["version"] + " [" + it["kind"] + (if (it["trig"]!!.isNotBlank()) ", " + it["trig"] else "") + (if (it["kind"] == "task") ", every " + it["every_min"] + " min" else "") + (if (it["enabled"] != "1") ", off" else "") + "] " + Axiom.clip(it["descr"] ?: "", 80) })
        }))
        Tools.register(Tool("mod_show", "Show a mod's code or page.", listOf(A("name", "mod name")), Risk.READ, group = "mods", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such mod.")
            ok(r["name"] + " v" + r["version"] + " [" + r["kind"] + "] " + r["trig"] + "\n" + r["descr"] + "\n\n" + Axiom.clip((r["code"] ?: "").ifBlank { r["html"] ?: "" }, 8000))
        }))
        Tools.register(Tool("mod_run", "Run a mod now with an input text.", listOf(A("name", "mod name"), A("input", "input text", false)), Risk.LOCAL, 150_000L, group = "mods", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such mod.")
            if (r["kind"] == "panel") { Mods.openPanel(Axiom.ctx, r["name"]!!); return@Tool ok("Opening the panel ${r["name"]}.") }
            val (good, out) = runMod(r, a.optString("input", ""))
            if (good) ok(out.ifBlank { "(done, no output)" }) else fail(out)
        }))
        Tools.register(Tool("mod_open", "Open a panel mod (a screen of its own).", listOf(A("name", "mod name")), Risk.LOCAL, group = "mods", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such mod."); if (r["kind"] != "panel") return@Tool fail("That mod is not a panel; use mod_run.")
            Mods.openPanel(Axiom.ctx, r["name"]!!); ok("Opening ${r["name"]}.")
        }))
        Tools.register(Tool("mod_toggle", "Turn a mod on or off.", listOf(A("name", "mod name"), A("enabled", "true or false", true, "bool")), Risk.LOCAL, group = "mods", run = { _, a ->
            Store.get().exec("UPDATE mods SET enabled = ? WHERE name = ?", if (a.optBoolean("enabled", true)) 1 else 0, slug(a.getString("name"))); ok("Done.")
        }))
        Tools.register(Tool("mod_rollback", "Go back to an earlier version of a mod.", listOf(A("name", "mod name"), A("version", "version number", true, "int")), Risk.LOCAL, group = "mods", run = { _, a ->
            val n = slug(a.getString("name")); val v = Store.get().one("SELECT * FROM mod_versions WHERE name = ? AND version = ?", n, a.getLong("version").toString()) ?: return@Tool fail("No such version.")
            Store.get().exec("UPDATE mods SET code = ?, html = ?, version = ? WHERE name = ?", v["code"], v["html"], a.getLong("version"), n); ok("Mod $n is now version ${a.getLong("version")}.")
        }))
        Tools.register(Tool("mod_delete", "Delete a mod (always asks).", listOf(A("name", "mod name")), Risk.DANGEROUS, group = "mods", run = { c, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such mod.")
            if (!c.confirm("Delete the mod \"${r["name"]}\"?")) return@Tool ToolResult(false, "Declined.", declined = true)
            Store.get().delete("mods", "name = ?", r["name"]!!); ok("Deleted.")
        }))
    }
}

/** A screen for a panel mod. It has the same isolated page and the same bridge to AXIOM's tools as any mod. */
class ModActivity : Activity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("mod")
        val m = if (name != null) Mods.row(name) else null
        if (m == null) { finish(); return }
        val w = WebView(this); web = w
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true; w.settings.allowFileAccess = false; w.settings.allowContentAccess = false; w.settings.blockNetworkLoads = true
        val host = ModRuntime.Host(m["name"]!!, false, "main", null) { runOnUiThread { finish() } }; host.web = w
        w.addJavascriptInterface(host, "AxiomBridge")
        w.webViewClient = ModRuntime.blockingClient()
        w.loadDataWithBaseURL("https://mod.local/" + m["name"] + "/", Mods.withPrelude(m["html"] ?: ""), "text/html", "utf-8", null)
        setContentView(w)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
