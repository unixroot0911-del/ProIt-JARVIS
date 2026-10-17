package com.jarvis.assistant

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Connectors: any website or service with an API, linked once and kept until removed. Secrets live in the Vault and are added to requests at the last moment. */
object Connectors {
    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24).ifBlank { "x" }
    private fun row(n: String) = Store.get().one("SELECT * FROM connectors WHERE name = ?", slug(n))

    private class Res(val code: Int, val body: String, val ct: String)

    private fun exec(method: String, url: String, headers: Map<String, String>, body: String?, timeout: Int = 60_000): Res {
        var tries = 0
        while (true) {
            tries++
            val h = URL(url).openConnection() as HttpURLConnection
            h.requestMethod = method; h.connectTimeout = 15_000; h.readTimeout = timeout; h.instanceFollowRedirects = true
            h.setRequestProperty("User-Agent", "AXIOM/" + Axiom.VERSION); h.setRequestProperty("Accept", "application/json, text/plain, */*")
            for ((k, v) in headers) h.setRequestProperty(k, v)
            if (body != null && method != "GET" && method != "HEAD") { h.doOutput = true; h.outputStream.use { it.write(body.toByteArray()) } }
            val code = h.responseCode
            if (code == 429 && tries < 2) { Thread.sleep(minOf(10_000L, (h.getHeaderField("Retry-After")?.toLongOrNull() ?: 3L) * 1000L)); continue }
            val s = if (code in 200..399) h.inputStream else (h.errorStream ?: h.inputStream)
            val buf = java.io.ByteArrayOutputStream(); val tmp = ByteArray(16384); var total = 0
            s.use { while (total < 3_000_000) { val n = it.read(tmp); if (n < 0) break; buf.write(tmp, 0, n); total += n } }
            return Res(code, buf.toString("UTF-8"), h.contentType ?: "")
        }
    }

    private fun authApply(auth: String, secret: String, url: String, headers: MutableMap<String, String>): String {
        if (secret.isEmpty()) return url
        return when {
            auth == "bearer" -> { headers["Authorization"] = "Bearer $secret"; url }
            auth == "basic" -> { headers["Authorization"] = "Basic " + android.util.Base64.encodeToString(secret.toByteArray(), android.util.Base64.NO_WRAP); url }
            auth.startsWith("header:") -> { headers[auth.removePrefix("header:")] = secret; url }
            auth.startsWith("query:") -> url + (if (url.contains("?")) "&" else "?") + URLEncoder.encode(auth.removePrefix("query:"), "UTF-8") + "=" + URLEncoder.encode(secret, "UTF-8")
            else -> url
        }
    }

    private fun toMap(o: Any?): Map<String, String> {
        val j: JSONObject = when (o) { is JSONObject -> o; is String -> try { JSONObject(o) } catch (e: Exception) { return emptyMap() }; else -> return emptyMap() }
        return j.keys().asSequence().associateWith { j.optString(it) }
    }

    private fun present(name: String, r: Res): ToolResult {
        var txt = r.body.trim()
        if (r.ct.contains("json") || txt.startsWith("{") || txt.startsWith("[")) txt = try { if (txt.startsWith("[")) JSONArray(txt).toString(1) else JSONObject(txt).toString(1) } catch (e: Exception) { txt }
        val files = ArrayList<String>()
        if (txt.length > 20_000) {
            try {
                val f = File(File(Workspace.root(), "connectors").apply { mkdirs() }, name + "_" + System.currentTimeMillis() + ".txt")
                f.writeText(txt); files.add(f.path); txt = txt.take(20_000) + "\n... (full response saved in ${f.path})"
            } catch (e: Exception) { txt = txt.take(20_000) }
        }
        val head = "HTTP ${r.code}\n"
        return if (r.code in 200..299) ok(head + txt, files, untrusted = true) else fail(head + Axiom.clip(txt, 1500))
    }

    private fun build(c: Map<String, String>, path: String, query: Any?): Pair<String?, String?> {
        val base = c["base_url"]!!.trimEnd('/')
        var url = if (path.startsWith("http://") || path.startsWith("https://")) path else base + (if (path.startsWith("/") || path.isEmpty()) path else "/$path")
        if (URL(url).host != URL(base).host) return null to "that address is on a different site than the connector"
        val q = toMap(query)
        if (q.isNotEmpty()) url += (if (url.contains("?")) "&" else "?") + q.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        return url to null
    }

    private suspend fun run(c: ToolCtx, a: JSONObject, method: String): ToolResult {
        val r = row(a.getString("name")) ?: return fail("No connector called \"${a.getString("name")}\". Say: connect to <site>.")
        if (r["enabled"] != "1") return fail("This connector is paused.")
        val (url0, err) = build(r, a.getString("path"), a.opt("query"))
        if (url0 == null) return fail(err ?: "bad address")
        val headers = HashMap<String, String>(); headers.putAll(toMap(a.opt("headers")))
        val url = authApply(r["auth_type"] ?: "none", Vault.get(r["secret"] ?: ""), url0, headers)
        var body: String? = null
        val b = a.opt("body")
        if (b != null && method != "GET") {
            val s = b.toString(); body = s
            if (!headers.containsKey("Content-Type")) headers["Content-Type"] = if (s.trim().startsWith("{") || s.trim().startsWith("[")) "application/json" else "text/plain"
        }
        if (method == "DELETE" && !c.confirm("Delete on ${r["name"]}: ${Axiom.clip(a.getString("path"), 120)}?")) return ToolResult(false, "Declined.", declined = true)
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { exec(method, url, headers, body) }
        if (res.code in 200..299) Store.get().exec("UPDATE connectors SET last_ok = ? WHERE name = ?", System.currentTimeMillis(), r["name"])
        return present(r["name"]!!, res)
    }

    suspend fun learn(brain: Brain, name: String, docsUrl: String, task: String): String {
        val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Net.get(docsUrl, maxBytes = 3_000_000) }
        var text = p.body
        val t = text.trim()
        if (t.startsWith("{") && (t.contains("\"openapi\"") || t.contains("\"swagger\""))) {
            try {
                val o = JSONObject(t); val paths = o.optJSONObject("paths") ?: JSONObject()
                val sb = StringBuilder("OpenAPI: " + o.optJSONObject("info")?.optString("title") + "\n")
                o.optJSONArray("servers")?.optJSONObject(0)?.optString("url")?.let { sb.append("server: $it\n") }
                for (k in paths.keys().asSequence().take(150)) {
                    val m = paths.getJSONObject(k)
                    for (verb in m.keys()) if (verb in listOf("get", "post", "put", "patch", "delete")) {
                        val op = m.getJSONObject(verb)
                        sb.append(verb.uppercase()).append(' ').append(k).append(" - ").append(op.optString("summary", op.optString("description")).take(100)).append('\n')
                    }
                }
                text = sb.toString()
            } catch (e: Exception) { /* use the raw text */ }
        } else text = Net.htmlToText(text)
        val sheet = Router.run(brain, Router.T.SUMMARIZE, Router.Opts("Write a compact API cheat sheet from this documentation, at most 2500 characters: base URL, how to authenticate, the most useful endpoints (METHOD /path - what it does - key parameters), pagination, rate limits, and common errors. Only what the text says; plain text.", Axiom.clip(text, 40_000), task = task, maxOut = 1500))
        Store.get().exec("UPDATE connectors SET notes = ? WHERE name = ?", sheet.trim().take(3500), slug(name))
        return sheet.trim()
    }

    fun summaryLine(): String = try {
        Store.get().query("SELECT name, base_url FROM connectors WHERE enabled = 1").joinToString("; ") { it["name"] + " (" + it["base_url"] + ")" }
    } catch (e: Exception) { "" }

    fun register() {
        Tools.register(Tool("connector_add", "Connect AXIOM to any website or service that has an API. It is kept until removed. auth_type: none, bearer, basic (secret is user:password), header:Name or query:param. When a secret is needed a secure box asks the user on the phone (never ask them to type it in chat). Optional docs_url (API docs or an OpenAPI/Swagger JSON): AXIOM reads it and keeps a cheat sheet.",
            listOf(A("name", "short name"), A("base_url", "API base address (https://...)"), A("auth_type", "none, bearer, basic, header:Name or query:param", false), A("docs_url", "link to the API documentation or OpenAPI file", false), A("notes", "anything useful to remember about it", false)),
            Risk.EXTERNAL, 300_000L, group = "connect", run = { c, a ->
                val name = slug(a.getString("name")); val base = a.getString("base_url").trim()
                if (!base.startsWith("http")) return@Tool fail("The address must start with https://")
                val auth = a.optString("auth_type", "none").ifBlank { "none" }.trim()
                val priv = Net.unsafe(base) != null
                if (priv && !c.confirm("This address is on your own network or this phone (${Axiom.clip(base, 80)}). Connect to it?")) return@Tool ToolResult(false, "Declined.", declined = true)
                var secretName = ""
                if (auth != "none") {
                    val s = SecretPrompt.ask(c.app, "Key, token or password for \"$name\"" + (if (auth == "basic") " (as user:password)" else ""))
                        ?: return@Tool fail("No secret was entered. Open AXIOM and ask again so the secure box can appear.")
                    secretName = "conn_$name"; Vault.put(secretName, s)
                }
                Store.get().replace("connectors", ContentValues().apply {
                    put("name", name); put("base_url", base); put("auth_type", auth); put("auth_name", ""); put("secret", secretName); put("notes", a.optString("notes", ""))
                    put("allow_private", if (priv) 1 else 0); put("enabled", 1); put("created", System.currentTimeMillis()); put("last_ok", 0)
                })
                var extra = ""
                val docs = a.optString("docs_url", "")
                if (docs.isNotBlank()) extra = try { "\nAPI cheat sheet saved:\n" + Axiom.clip(learn(c.brain, name, docs, c.task), 600) } catch (e: Exception) { "\n(Could not read the docs: ${e.message})" }
                ok("Connected \"$name\" ($base, auth: $auth). Use connector_get / connector_send with name \"$name\"." + extra)
            }))
        Tools.register(Tool("connector_learn", "Read an API documentation page or OpenAPI file and keep a cheat sheet for a connector.", listOf(A("name", "connector name"), A("docs_url", "documentation link")), Risk.READ, 200_000L, group = "connect", run = { c, a ->
            row(a.getString("name")) ?: return@Tool fail("No such connector.")
            ok(learn(c.brain, a.getString("name"), a.getString("docs_url"), c.task), untrusted = true)
        }))
        Tools.register(Tool("connector_list", "List connected sites and services.", emptyList(), Risk.READ, group = "connect", run = { _, _ ->
            val rows = Store.get().query("SELECT * FROM connectors ORDER BY name")
            if (rows.isEmpty()) ok("No connectors yet.") else ok(rows.joinToString("\n") { it["name"] + "  " + it["base_url"] + "  [auth " + it["auth_type"] + (if (it["enabled"] != "1") ", paused" else "") + "]" })
        }))
        Tools.register(Tool("connector_show", "Show a connector's saved notes and API cheat sheet.", listOf(A("name", "connector name")), Risk.READ, group = "connect", run = { _, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such connector.")
            ok(r["name"] + " " + r["base_url"] + "\n" + (r["notes"] ?: "").ifBlank { "(no notes)" })
        }))
        Tools.register(Tool("connector_get", "Read from a connected service: a GET request to a path (the secret is added automatically).",
            listOf(A("name", "connector name"), A("path", "path after the base address, e.g. /v1/items"), A("query", "query parameters as a JSON object", false, "json"), A("headers", "extra headers as a JSON object", false, "json")), Risk.READ, 90_000L, group = "connect", run = { c, a -> run(c, a, "GET") }))
        Tools.register(Tool("connector_send", "Change something on a connected service: POST, PUT, PATCH or DELETE (asks approval when AXIOM chose it on its own; DELETE always asks).",
            listOf(A("name", "connector name"), A("method", "POST, PUT, PATCH or DELETE"), A("path", "path after the base address"), A("body", "JSON body or text", false, "json"), A("query", "query parameters as JSON", false, "json"), A("headers", "extra headers as JSON", false, "json")), Risk.EXTERNAL, 90_000L, group = "connect", run = { c, a ->
                val m = a.getString("method").uppercase()
                if (m !in listOf("POST", "PUT", "PATCH", "DELETE")) return@Tool fail("Method must be POST, PUT, PATCH or DELETE (use connector_get for reading).")
                run(c, a, m)
            }))
        Tools.register(Tool("connector_remove", "Disconnect a service and forget its secret.", listOf(A("name", "connector name")), Risk.DANGEROUS, group = "connect", run = { c, a ->
            val r = row(a.getString("name")) ?: return@Tool fail("No such connector.")
            if (!c.confirm("Disconnect \"${r["name"]}\" and forget its secret?")) return@Tool ToolResult(false, "Declined.", declined = true)
            Vault.remove(r["secret"] ?: ""); Store.get().delete("connectors", "name = ?", r["name"]!!); ok("Disconnected ${r["name"]}.")
        }))
        Tools.register(Tool("http_request", "Any HTTP request to a public address. Use {{secret:name}} in the url, headers or body to insert a stored secret (the AI never sees the value). For repeated use, make a connector instead.",
            listOf(A("method", "GET, POST, PUT, PATCH, DELETE"), A("url", "https address"), A("headers", "JSON object", false, "json"), A("body", "JSON or text", false, "json")), Risk.EXTERNAL, 90_000L, group = "connect", run = { _, a ->
                val method = a.getString("method").uppercase()
                val (url, m1) = Vault.fill(a.getString("url"))
                if (m1 != null) return@Tool fail("No stored secret named $m1. Use secret_set first.")
                Net.unsafe(url)?.let { return@Tool fail("Not allowed: $it") }
                val hm = HashMap<String, String>()
                for ((k, v) in toMap(a.opt("headers"))) { val (f, mm) = Vault.fill(v); if (mm != null) return@Tool fail("No stored secret named $mm."); hm[k] = f }
                var body: String? = null
                val bo = a.opt("body")
                if (bo != null) {
                    val (f, mm) = Vault.fill(bo.toString()); if (mm != null) return@Tool fail("No stored secret named $mm.")
                    body = f; if (!hm.containsKey("Content-Type")) hm["Content-Type"] = if (f.trim().startsWith("{") || f.trim().startsWith("[")) "application/json" else "text/plain"
                }
                present("http", kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { exec(method, url, hm, body) })
            }))
        Tools.register(Tool("secret_set", "Store a named secret (token, key, password) for later use as {{secret:name}}. A secure box asks the user on the phone; the value never passes through the chat.", listOf(A("name", "secret name"), A("purpose", "what it is for, shown in the box")), Risk.LOCAL, 300_000L, group = "connect", run = { c, a ->
            val n = a.getString("name").trim().replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val v = SecretPrompt.ask(c.app, a.getString("purpose")) ?: return@Tool fail("No secret was entered. Open AXIOM and ask again so the secure box can appear.")
            Vault.put(n, v); ok("Stored the secret \"$n\". Use it as {{secret:$n}}.")
        }))
        Tools.register(Tool("secret_list", "List the names (never the values) of stored secrets.", emptyList(), Risk.READ, group = "connect", run = { _, _ -> ok(Vault.names().joinToString(", ").ifEmpty { "No secrets stored." }) }))
        Tools.register(Tool("secret_delete", "Delete a stored secret.", listOf(A("name", "secret name")), Risk.DANGEROUS, group = "connect", run = { c, a ->
            if (!c.confirm("Delete the stored secret \"${a.getString("name")}\"?")) return@Tool ToolResult(false, "Declined.", declined = true)
            Vault.remove(a.getString("name")); ok("Deleted.")
        }))
        Tools.register(Tool("app_intent", "Link with another Android app by sending it an intent (open a screen, share text, start a function, call an app's own action). mode: activity (default) or broadcast.",
            listOf(A("action", "VIEW, SEND, SENDTO, or a full action name"), A("data", "a uri", false), A("package", "target app package", false), A("type", "mime type", false), A("extras", "JSON object of extras", false, "json"), A("component", "package/class", false), A("mode", "activity or broadcast", false)),
            Risk.EXTERNAL, 30_000L, group = "connect", run = { c, a ->
                val act = a.getString("action").trim().let { if (it.contains('.')) it else "android.intent.action." + it.uppercase() }
                val i = Intent(act)
                val data = a.optString("data", ""); val type = a.optString("type", "")
                if (data.isNotBlank() && type.isNotBlank()) i.setDataAndType(Uri.parse(data), type) else if (data.isNotBlank()) i.data = Uri.parse(data) else if (type.isNotBlank()) i.type = type
                a.optString("package", "").takeIf { it.isNotBlank() }?.let { i.setPackage(it) }
                a.optString("component", "").takeIf { it.contains('/') }?.let {
                    val pkg = it.substringBefore('/'); val cl = it.substringAfter('/')
                    i.component = android.content.ComponentName(pkg, if (cl.startsWith(".")) pkg + cl else cl)
                }
                val ex: JSONObject? = when (val e = a.opt("extras")) { is JSONObject -> e; is String -> try { JSONObject(e) } catch (x: Exception) { null }; else -> null }
                if (ex != null) for (k in ex.keys()) {
                    val key = if (k.contains('.')) k else if (k == "text") Intent.EXTRA_TEXT else if (k == "subject") Intent.EXTRA_SUBJECT else k
                    when (val v = ex.get(k)) {
                        is Int -> i.putExtra(key, v); is Long -> i.putExtra(key, v); is Double -> i.putExtra(key, v); is Boolean -> i.putExtra(key, v)
                        is JSONArray -> i.putStringArrayListExtra(key, ArrayList((0 until v.length()).map { n -> v.optString(n) }))
                        else -> i.putExtra(key, v.toString())
                    }
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    if (a.optString("mode", "activity") == "broadcast") c.app.sendBroadcast(i) else c.app.startActivity(i)
                    ok("Sent the intent $act" + (a.optString("package", "").let { if (it.isNotBlank()) " to $it" else "" }) + ".")
                } catch (e: Exception) { fail("No app could handle that intent (${e.message?.take(100)}).") }
            }))
    }
}
