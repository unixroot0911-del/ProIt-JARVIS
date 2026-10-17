package com.jarvis.assistant

import android.content.ContentValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A complete Model Context Protocol client. Any MCP server on the internet (or on your own network) can be linked: its tools become
 * AXIOM tools, its resources and prompts can be read. Streamable HTTP and the older SSE transport are both supported, OAuth sign-in
 * is handled (browser sign-in, refresh), and a link stays alive: it is saved, re-established after restarts and dropped connections,
 * and only ends when you remove it.
 */
object Mcp {
    class AuthNeeded(val header: String?) : RuntimeException("the server asks for sign-in")
    private class SessionExpired : RuntimeException("session expired")

    class Conn(val name: String, val url: String) {
        val seq = AtomicLong()
        @Volatile var sessionId: String? = null
        @Volatile var protocol = "2025-06-18"
        @Volatile var initialized = false
        @Volatile var legacy = false
        @Volatile var postUrl: String? = null
        @Volatile var alive = false
        @Volatile var instructions = ""
        @Volatile var serverName = ""
        @Volatile var lastUse = System.currentTimeMillis()
        val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
        val endpoint = CompletableDeferred<String>()
        var reader: Thread? = null
        var tools = JSONArray()
    }

    private val conns = ConcurrentHashMap<String, Conn>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val fails = ConcurrentHashMap<String, Int>()
    private val registered = ConcurrentHashMap<String, Set<String>>()

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "x" }
    private fun row(name: String) = Store.get().one("SELECT * FROM mcp_servers WHERE name = ?", name)

    // ---------------- transport ----------------

    private fun authHeaders(c: Conn): Map<String, String> {
        val r = row(c.name) ?: return emptyMap()
        val auth = r["auth"] ?: "none"
        val out = HashMap<String, String>()
        when {
            auth == "oauth" -> Oauth.token(c.name)?.let { out["Authorization"] = "Bearer $it" }
            auth == "bearer" -> Vault.get("mcp_" + c.name).takeIf { it.isNotEmpty() }?.let { out["Authorization"] = "Bearer $it" }
            auth.startsWith("header:") -> Vault.get("mcp_" + c.name).takeIf { it.isNotEmpty() }?.let { out[auth.removePrefix("header:")] = it }
        }
        return out
    }

    private fun open(c: Conn, url: String, method: String, body: String?, accept: String, timeout: Int): HttpURLConnection {
        val h = URL(url).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 15_000; h.readTimeout = timeout; h.instanceFollowRedirects = true
        h.setRequestProperty("Accept", accept); h.setRequestProperty("User-Agent", "AXIOM/" + Axiom.VERSION)
        c.sessionId?.let { h.setRequestProperty("Mcp-Session-Id", it) }
        if (c.initialized) h.setRequestProperty("MCP-Protocol-Version", c.protocol)
        for ((k, v) in authHeaders(c)) h.setRequestProperty(k, v)
        if (body != null) { h.doOutput = true; h.setRequestProperty("Content-Type", "application/json"); h.outputStream.use { it.write(body.toByteArray()) } }
        return h
    }

    private fun failFor(h: HttpURLConnection, code: Int): Nothing {
        if (code == 401) throw AuthNeeded(h.getHeaderField("WWW-Authenticate"))
        val err = try { (h.errorStream ?: h.inputStream).bufferedReader().readText().take(400) } catch (e: Exception) { "" }
        throw HttpError(code, err)
    }

    private fun pick(o: Any, id: Long): JSONObject? {
        val arr = if (o is JSONArray) (0 until o.length()).mapNotNull { o.optJSONObject(it) } else listOfNotNull(o as? JSONObject)
        return arr.firstOrNull { it.opt("id")?.toString() == id.toString() && (it.has("result") || it.has("error")) }
    }

    private fun readSse(r: BufferedReader, onData: (String, String) -> Boolean) {
        var ev = "message"; val data = StringBuilder()
        while (true) {
            val line = r.readLine() ?: break
            when {
                line.isEmpty() -> { if (data.isNotEmpty()) { if (onData(ev, data.toString())) return }; ev = "message"; data.setLength(0) }
                line.startsWith("event:") -> ev = line.removePrefix("event:").trim()
                line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").removePrefix(" ")) }
            }
        }
        if (data.isNotEmpty()) onData(ev, data.toString())
    }

    private suspend fun rpc(c: Conn, method: String, params: JSONObject?, timeout: Int = 60_000): JSONObject {
        val id = c.seq.incrementAndGet()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) msg.put("params", params)
        c.lastUse = System.currentTimeMillis()
        val resp: JSONObject = if (c.legacy) {
            val d = CompletableDeferred<JSONObject>(); c.pending[id] = d
            try {
                withContext(Dispatchers.IO) {
                    val h = open(c, c.postUrl ?: throw IllegalStateException("no endpoint"), "POST", msg.toString(), "application/json", timeout)
                    val code = h.responseCode; if (code !in 200..299) failFor(h, code); h.inputStream.close()
                }
                withTimeoutOrNull(timeout.toLong()) { d.await() } ?: throw java.io.IOException("the server did not answer $method in time")
            } finally { c.pending.remove(id) }
        } else withContext(Dispatchers.IO) {
            val h = open(c, c.url, "POST", msg.toString(), "application/json, text/event-stream", timeout)
            val code = h.responseCode
            h.getHeaderField("Mcp-Session-Id")?.let { c.sessionId = it }
            if (code == 404 && c.sessionId != null && method != "initialize") throw SessionExpired()
            if (code !in 200..299) failFor(h, code)
            val ct = h.contentType ?: ""
            var found: JSONObject? = null
            if (ct.contains("text/event-stream")) {
                h.inputStream.use { s -> readSse(BufferedReader(InputStreamReader(s))) { _, d -> found = try { pick(if (d.trim().startsWith("[")) JSONArray(d) else JSONObject(d), id) } catch (e: Exception) { null }; found != null } }
            } else {
                val txt = h.inputStream.bufferedReader().readText()
                found = try { pick(if (txt.trim().startsWith("[")) JSONArray(txt) else JSONObject(txt), id) } catch (e: Exception) { null }
            }
            found ?: throw java.io.IOException("the server sent no answer to $method")
        }
        resp.optJSONObject("error")?.let { throw McpError(it.optInt("code"), it.optString("message")) }
        return resp.optJSONObject("result") ?: JSONObject()
    }

    class McpError(val code: Int, msg: String) : RuntimeException(msg)

    private suspend fun notify(c: Conn, method: String) {
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method)
        withContext(Dispatchers.IO) {
            try {
                val url = if (c.legacy) c.postUrl else c.url
                if (url != null) { val h = open(c, url, "POST", msg.toString(), "application/json, text/event-stream", 15_000); h.responseCode; try { h.inputStream.close() } catch (e: Exception) { /* no body */ } }
            } catch (e: Exception) { /* notifications are best effort */ }
        }
    }

    private fun startLegacy(c: Conn) {
        val t = Thread {
            try {
                val h = open(c, c.url, "GET", null, "text/event-stream", 0)
                val code = h.responseCode; if (code !in 200..299) failFor(h, code)
                h.inputStream.use { s ->
                    readSse(BufferedReader(InputStreamReader(s))) { ev, d ->
                        if (ev == "endpoint") { try { c.endpoint.complete(URL(URL(c.url), d.trim()).toString()) } catch (e: Exception) { c.endpoint.completeExceptionally(e) } }
                        else try {
                            val o = JSONObject(d); val id = o.opt("id")?.toString()?.toLongOrNull()
                            if (id != null) c.pending[id]?.complete(o)
                        } catch (e: Exception) { /* not a response */ }
                        false
                    }
                }
            } catch (e: Throwable) { if (!c.endpoint.isCompleted) c.endpoint.completeExceptionally(e) }
            c.alive = false
        }
        t.isDaemon = true; c.reader = t; t.start()
    }

    private suspend fun handshake(c: Conn) {
        val params = JSONObject().put("protocolVersion", c.protocol).put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "AXIOM").put("version", Axiom.VERSION))
        val res = rpc(c, "initialize", params, 30_000)
        res.optString("protocolVersion").takeIf { it.isNotEmpty() }?.let { c.protocol = it }
        c.instructions = res.optString("instructions", "")
        c.serverName = res.optJSONObject("serverInfo")?.optString("name", "") ?: ""
        c.initialized = true
        notify(c, "notifications/initialized")
    }

    private suspend fun loadTools(c: Conn) {
        val all = JSONArray(); var cursor: String? = null
        for (i in 0 until 20) {
            val r = rpc(c, "tools/list", if (cursor != null) JSONObject().put("cursor", cursor) else JSONObject(), 30_000)
            val a = r.optJSONArray("tools") ?: JSONArray()
            for (k in 0 until a.length()) all.put(a.get(k))
            cursor = r.optString("nextCursor", "").ifEmpty { null }
            if (cursor == null) break
        }
        c.tools = all
    }

    /** Opens (or reuses) the link to a saved server. Signs in through the browser when the server asks and no valid sign-in exists. */
    suspend fun ensure(name: String, interactiveLogin: Boolean = true): Conn {
        conns[name]?.let { if (it.alive || (!it.legacy && it.initialized)) return it }
        val r = row(name) ?: throw IllegalStateException("There is no MCP server called \"$name\".")
        var attempt = 0
        while (true) {
            attempt++
            val c = Conn(name, r["url"]!!)
            try {
                try { handshake(c) }
                catch (e: HttpError) {
                    if (e.code in listOf(400, 404, 405, 415)) {
                        // the older transport: a long-lived event stream plus a POST endpoint
                        c.legacy = true; c.initialized = false; c.alive = true; startLegacy(c)
                        c.postUrl = withTimeoutOrNull(20_000) { c.endpoint.await() } ?: throw java.io.IOException("the server did not announce an endpoint")
                        handshake(c)
                    } else throw e
                }
                loadTools(c)
                c.alive = true; conns[name] = c; backoff.remove(name); fails.remove(name)
                Store.get().exec("UPDATE mcp_servers SET last_ok = ?, tools_json = ?, info = ? WHERE name = ?", System.currentTimeMillis(), c.tools.toString(), (c.serverName + "\n" + c.instructions).take(1500), name)
                registerTools(name, c.tools)
                return c
            } catch (e: AuthNeeded) {
                if (!interactiveLogin || attempt > 1) throw e
                val auth = r["auth"] ?: "none"
                if (auth == "none" || auth == "oauth" || auth == "auto") {
                    Oauth.login(name, c.url, e.header)
                    Store.get().exec("UPDATE mcp_servers SET auth = 'oauth' WHERE name = ?", name)
                } else throw e
            }
        }
    }

    // ---------------- tools ----------------

    private fun argType(t: String): String = when (t) { "integer" -> "int"; "number" -> "number"; "boolean" -> "bool"; "object", "array" -> "json"; else -> "string" }

    private fun registerTools(server: String, tools: JSONArray) {
        val names = HashSet<String>()
        val prefix = "mcp_" + slug(server) + "_"
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val remote = t.optString("name"); if (remote.isEmpty()) continue
            val local = (prefix + slug(remote)).take(60)
            names.add(local)
            val schema = t.optJSONObject("inputSchema") ?: JSONObject()
            val props = schema.optJSONObject("properties") ?: JSONObject()
            val req = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val args = ArrayList<A>()
            for (k in props.keys()) {
                val p = props.optJSONObject(k) ?: continue
                val type = argType(p.optString("type", "string"))
                args.add(A(k, (p.optString("description", k) + (if (p.has("enum")) " One of: " + p.optJSONArray("enum").toString() else "")).take(300), k in req, type))
            }
            val ann = t.optJSONObject("annotations")
            val risk = when {
                ann?.optBoolean("readOnlyHint", false) == true -> Risk.READ
                ann?.optBoolean("destructiveHint", false) == true -> Risk.DANGEROUS
                else -> Risk.EXTERNAL
            }
            Tools.register(Tool(local, (t.optString("description", remote).ifBlank { remote }).take(500) + " [MCP server: $server]", args, risk, 150_000L, group = "mcp",
                run = { c, a -> call(server, remote, a) }))
        }
        registered[server]?.minus(names)?.forEach { Tools.unregister(it) }
        registered[server] = names
    }

    /** Tools of every saved server are known from the last connection, so they exist right after a restart; they connect when first used. */
    fun registerStored() {
        try {
            for (r in Store.get().query("SELECT name, tools_json FROM mcp_servers WHERE enabled = 1")) {
                val a = try { JSONArray(r["tools_json"]) } catch (e: Exception) { continue }
                registerTools(r["name"]!!, a)
            }
        } catch (e: Exception) { Diag.record(e) }
    }

    private fun saveBlob(server: String, mime: String, b64: String): String? = try {
        val ext = when { mime.contains("png") -> "png"; mime.contains("jpeg") || mime.contains("jpg") -> "jpg"; mime.contains("pdf") -> "pdf"; mime.contains("json") -> "json"; mime.contains("text") -> "txt"; else -> "bin" }
        val f = File(File(Workspace.root(), "mcp").apply { mkdirs() }, slug(server) + "_" + System.currentTimeMillis() + "." + ext)
        f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)); f.path
    } catch (e: Exception) { null }

    private fun contentText(server: String, content: JSONArray?, files: MutableList<String>): String {
        val sb = StringBuilder()
        if (content != null) for (i in 0 until content.length()) {
            val it = content.optJSONObject(i) ?: continue
            when (it.optString("type")) {
                "text" -> sb.append(it.optString("text")).append('\n')
                "image", "audio" -> saveBlob(server, it.optString("mimeType"), it.optString("data"))?.let { p -> files.add(p); sb.append("[saved ${it.optString("type")}: $p]\n") }
                "resource" -> it.optJSONObject("resource")?.let { r ->
                    if (r.has("text")) sb.append(r.optString("text")).append('\n')
                    else if (r.has("blob")) saveBlob(server, r.optString("mimeType"), r.optString("blob"))?.let { p -> files.add(p); sb.append("[saved resource: $p]\n") } else Unit
                }
                "resource_link" -> sb.append("[link: ").append(it.optString("uri")).append("]\n")
            }
        }
        return sb.toString().trim()
    }

    private suspend fun <T> withRetry(server: String, block: suspend (Conn) -> T): T {
        var tries = 0
        while (true) {
            tries++
            val c = ensure(server)
            try { return block(c) }
            catch (e: SessionExpired) { conns.remove(server); if (tries > 1) throw java.io.IOException("the server keeps dropping the session") }
            catch (e: AuthNeeded) { conns.remove(server); if (tries > 1) throw e; Oauth.refreshOrLogin(server, c.url, e.header) }
            catch (e: java.io.IOException) { conns.remove(server); if (tries > 1) throw e }
        }
    }

    suspend fun call(server: String, tool: String, args: JSONObject): ToolResult {
        val res = try {
            withRetry(server) { c -> rpc(c, "tools/call", JSONObject().put("name", tool).put("arguments", args), 140_000) }
        } catch (e: McpError) { return fail("The server refused: ${e.message}") }
        catch (e: AuthNeeded) { return fail("The server needs you to sign in again. Say: sign in to $server.") }
        val files = ArrayList<String>()
        var text = contentText(server, res.optJSONArray("content"), files)
        if (text.isBlank()) res.optJSONObject("structuredContent")?.let { text = it.toString() }
        if (res.optBoolean("isError", false)) return fail(text.ifBlank { "The tool reported an error." })
        return ok(Axiom.clip(text.ifBlank { "(done, no output)" }, 20_000), files, untrusted = true)
    }

    private suspend fun list(server: String, method: String, key: String): JSONArray = withRetry(server) { c ->
        val all = JSONArray(); var cursor: String? = null
        for (i in 0 until 10) {
            val r = rpc(c, method, if (cursor != null) JSONObject().put("cursor", cursor) else JSONObject(), 30_000)
            val a = r.optJSONArray(key) ?: JSONArray(); for (k in 0 until a.length()) all.put(a.get(k))
            cursor = r.optString("nextCursor", "").ifEmpty { null }; if (cursor == null) break
        }
        all
    }

    // ---------------- keeping links alive ----------------

    /** Called every minute by the ticker: reconnects dropped links (with growing pauses) and refreshes tool lists now and then. */
    suspend fun keepAlive() {
        for (r in Store.get().query("SELECT name FROM mcp_servers WHERE enabled = 1")) {
            val n = r["name"]!!
            val c = conns[n]
            if (c != null && c.alive && System.currentTimeMillis() - c.lastUse < 600_000L) continue
            if ((backoff[n] ?: 0L) > System.currentTimeMillis()) continue
            try {
                if (c != null && c.alive) { rpc(c, "ping", null, 15_000); c.lastUse = System.currentTimeMillis(); loadTools(c); registerTools(n, c.tools); Store.get().exec("UPDATE mcp_servers SET last_ok = ?, tools_json = ? WHERE name = ?", System.currentTimeMillis(), c.tools.toString(), n) }
                else ensure(n, interactiveLogin = false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) {
                conns.remove(n)
                val f = (fails[n] ?: 0) + 1; fails[n] = f
                backoff[n] = System.currentTimeMillis() + minOf(30 * 60_000L, 30_000L * (1L shl minOf(f, 6)))
            }
        }
    }

    // ---------------- registered tools ----------------

    fun register() {
        registerStored()
        Tools.register(Tool("mcp_add", "Link an MCP server (any URL speaking the Model Context Protocol). Its tools appear as AXIOM tools and the link is kept for good until removed. auth: auto (default: tries without, signs in through the browser if the server asks), none, bearer or header:HeaderName (then a secure box asks you for the token on the phone).",
            listOf(A("name", "short name, letters and digits"), A("url", "the server address (https://...)"), A("auth", "auto, none, oauth, bearer or header:Name", false)), Risk.EXTERNAL, 400_000L, group = "mcp", run = { c, a ->
                val name = slug(a.getString("name")); val url = a.getString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) return@Tool fail("The address must start with http:// or https://")
                val auth = a.optString("auth", "auto").ifBlank { "auto" }.trim()
                if (Net.unsafe(url) != null && !c.confirm("This server is on your own network or this phone (${Axiom.clip(url, 80)}). Link it?")) return@Tool ToolResult(false, "Declined.", declined = true)
                if (auth == "bearer" || auth.startsWith("header:")) {
                    val tok = SecretPrompt.ask(c.app, "Token or key for the MCP server \"$name\"") ?: return@Tool fail("No token was entered. Open AXIOM and ask again so the secure box can appear.")
                    Vault.put("mcp_$name", tok)
                }
                Store.get().replace("mcp_servers", ContentValues().apply { put("name", name); put("url", url); put("auth", auth); put("oauth_enc", ""); put("enabled", 1); put("created", System.currentTimeMillis()); put("last_ok", 0); put("tools_json", "[]"); put("info", "") })
                c.progress("Connecting to $name...")
                val conn = try { ensure(name) } catch (e: AuthNeeded) { return@Tool fail("The server wants a sign-in that I could not complete. If it uses a token, say: link $name with bearer.") }
                catch (e: Exception) { return@Tool fail("Saved, but I could not connect yet: ${e.message}. I will keep retrying in the background.") }
                ok("Linked $name${if (conn.serverName.isNotEmpty()) " (" + conn.serverName + ")" else ""}: ${conn.tools.length()} tools now available" + (if (conn.legacy) " (older SSE transport)" else "") + ". The link stays until you remove it.\n" +
                    (0 until minOf(conn.tools.length(), 15)).joinToString(", ") { conn.tools.getJSONObject(it).optString("name") } + (if (conn.instructions.isNotBlank()) "\nServer notes: " + Axiom.clip(conn.instructions, 400) else ""), untrusted = true)
            }))
        Tools.register(Tool("mcp_list", "Show the linked MCP servers, whether each is connected, and how many tools it gives.", emptyList(), Risk.READ, group = "mcp", run = { _, _ ->
            val rows = Store.get().query("SELECT * FROM mcp_servers ORDER BY name")
            if (rows.isEmpty()) ok("No MCP servers are linked.") else ok(rows.joinToString("\n") { r ->
                val n = r["name"]!!; val cn = conns[n]
                val cnt = try { JSONArray(r["tools_json"]).length() } catch (e: Exception) { 0 }
                "$n  ${r["url"]}  [" + (if (r["enabled"] != "1") "paused" else if (cn?.alive == true) "connected" else "will reconnect") + ", auth ${r["auth"]}, $cnt tools]"
            })
        }))
        Tools.register(Tool("mcp_login", "Sign in (again) to a linked MCP server that uses OAuth: opens the browser sign-in.", listOf(A("name", "server name")), Risk.EXTERNAL, 400_000L, group = "mcp", run = { _, a ->
            val n = slug(a.getString("name")); val r = row(n) ?: return@Tool fail("No such server.")
            Oauth.login(n, r["url"]!!, null); conns.remove(n); ensure(n)
            ok("Signed in to $n.")
        }))
        Tools.register(Tool("mcp_remove", "Unlink an MCP server and forget its sign-in.", listOf(A("name", "server name")), Risk.DANGEROUS, group = "mcp", run = { c, a ->
            val n = slug(a.getString("name")); if (row(n) == null) return@Tool fail("No such server.")
            if (!c.confirm("Unlink the MCP server \"$n\" and forget its sign-in?")) return@Tool ToolResult(false, "Declined.", declined = true)
            conns.remove(n)?.let { it.alive = false; try { it.reader?.interrupt() } catch (e: Exception) { /* gone */ } }
            registered.remove(n)?.forEach { Tools.unregister(it) }
            Store.get().delete("mcp_servers", "name = ?", n); Vault.remove("mcp_$n")
            ok("Unlinked $n.")
        }))
        Tools.register(Tool("mcp_toggle", "Pause or resume a linked MCP server without removing it.", listOf(A("name", "server name"), A("enabled", "true or false", true, "bool")), Risk.LOCAL, group = "mcp", run = { _, a ->
            val n = slug(a.getString("name")); val on = a.optBoolean("enabled", true)
            Store.get().exec("UPDATE mcp_servers SET enabled = ? WHERE name = ?", if (on) 1 else 0, n)
            if (!on) { conns.remove(n); registered.remove(n)?.forEach { Tools.unregister(it) } } else try { ensure(n) } catch (e: Exception) { return@Tool ok("Resumed; it will connect shortly (${e.message}).") }
            ok(if (on) "Resumed $n." else "Paused $n.")
        }))
        Tools.register(Tool("mcp_refresh", "Reconnect to a linked server and reload its tool list.", listOf(A("name", "server name")), Risk.READ, 120_000L, group = "mcp", run = { _, a ->
            val n = slug(a.getString("name")); conns.remove(n); val c = ensure(n); ok("$n: ${c.tools.length()} tools.")
        }))
        Tools.register(Tool("mcp_call", "Call any tool of a linked MCP server by its real name (use when the tool is not listed as its own AXIOM tool).",
            listOf(A("server", "server name"), A("tool", "tool name on the server"), A("args", "arguments as a JSON object", false, "json")), Risk.EXTERNAL, 150_000L, group = "mcp", run = { _, a ->
                val ar = a.opt("args"); val obj = when (ar) { is JSONObject -> ar; is String -> try { JSONObject(ar) } catch (e: Exception) { JSONObject() }; else -> JSONObject() }
                call(slug(a.getString("server")), a.getString("tool"), obj)
            }))
        Tools.register(Tool("mcp_resources", "List the resources (files, records, documents) a linked MCP server offers.", listOf(A("server", "server name")), Risk.READ, 90_000L, group = "mcp", run = { _, a ->
            val s = slug(a.getString("server"))
            val rs = list(s, "resources/list", "resources"); val ts = try { list(s, "resources/templates/list", "resourceTemplates") } catch (e: Exception) { JSONArray() }
            val sb = StringBuilder()
            for (i in 0 until rs.length()) rs.getJSONObject(i).let { sb.append(it.optString("uri")).append("  ").append(it.optString("name")).append("  ").append(it.optString("description").take(100)).append('\n') }
            for (i in 0 until ts.length()) ts.getJSONObject(i).let { sb.append("template ").append(it.optString("uriTemplate")).append("  ").append(it.optString("name")).append('\n') }
            ok(sb.toString().trim().ifEmpty { "This server offers no resources." }, untrusted = true)
        }))
        Tools.register(Tool("mcp_read", "Read one resource of a linked MCP server by its URI.", listOf(A("server", "server name"), A("uri", "resource URI")), Risk.READ, 90_000L, group = "mcp", run = { _, a ->
            val s = slug(a.getString("server"))
            val r = withRetry(s) { c -> rpc(c, "resources/read", JSONObject().put("uri", a.getString("uri")), 60_000) }
            val cs = r.optJSONArray("contents") ?: JSONArray(); val files = ArrayList<String>(); val sb = StringBuilder()
            for (i in 0 until cs.length()) cs.getJSONObject(i).let { if (it.has("text")) sb.append(it.optString("text")).append('\n') else if (it.has("blob")) saveBlob(s, it.optString("mimeType"), it.optString("blob"))?.let { p -> files.add(p); sb.append("[saved: $p]\n") } else Unit }
            ok(Axiom.clip(sb.toString().trim().ifEmpty { "(empty)" }, 20_000), files, untrusted = true)
        }))
        Tools.register(Tool("mcp_prompts", "List the prompt templates a linked MCP server offers.", listOf(A("server", "server name")), Risk.READ, 90_000L, group = "mcp", run = { _, a ->
            val ps = list(slug(a.getString("server")), "prompts/list", "prompts"); val sb = StringBuilder()
            for (i in 0 until ps.length()) ps.getJSONObject(i).let { p ->
                val args = p.optJSONArray("arguments")?.let { x -> (0 until x.length()).joinToString(", ") { x.getJSONObject(it).optString("name") } } ?: ""
                sb.append(p.optString("name")).append("(").append(args).append("): ").append(p.optString("description").take(120)).append('\n')
            }
            ok(sb.toString().trim().ifEmpty { "This server offers no prompts." }, untrusted = true)
        }))
        Tools.register(Tool("mcp_prompt", "Fetch a prompt template of a linked MCP server with its arguments filled in.", listOf(A("server", "server name"), A("name", "prompt name"), A("args", "arguments as a JSON object", false, "json")), Risk.READ, 90_000L, group = "mcp", run = { _, a ->
            val s = slug(a.getString("server")); val ar = a.opt("args")
            val obj = when (ar) { is JSONObject -> ar; is String -> try { JSONObject(ar) } catch (e: Exception) { JSONObject() }; else -> JSONObject() }
            val r = withRetry(s) { c -> rpc(c, "prompts/get", JSONObject().put("name", a.getString("name")).put("arguments", obj), 60_000) }
            val ms = r.optJSONArray("messages") ?: JSONArray(); val sb = StringBuilder()
            for (i in 0 until ms.length()) ms.getJSONObject(i).let { m -> sb.append(m.optString("role")).append(": ").append(m.optJSONObject("content")?.optString("text") ?: "").append('\n') }
            ok(sb.toString().trim(), untrusted = true)
        }))
    }
}
