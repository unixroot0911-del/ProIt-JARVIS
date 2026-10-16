package com.jarvis.assistant

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Files the user sends (Telegram, chat attach) land in the project's inbox folder and are remembered for the next message. */
object Inbox {
    private fun sp() = Axiom.ctx.getSharedPreferences("axiom_inbox", android.content.Context.MODE_PRIVATE)

    fun safeName(n: String): String = n.replace(Regex("[^\\p{L}\\p{N}._ -]+"), "_").trim().ifBlank { "file" }.takeLast(90)

    fun target(project: String, name: String): File {
        val dir = Workspace.inside(project, "inbox") ?: File(Workspace.root(), "inbox")
        dir.mkdirs()
        var f = File(dir, safeName(name))
        if (f.exists()) f = File(dir, System.currentTimeMillis().toString(36) + "_" + f.name)
        return f
    }

    fun note(f: File) { sp().edit().putString("last", f.absolutePath).putLong("ts", System.currentTimeMillis()).apply() }

    /** A line for the brain's context: the file the user just sent, if recent. */
    fun contextLine(): String {
        val p = sp().getString("last", null) ?: return ""
        if (System.currentTimeMillis() - sp().getLong("ts", 0L) > 30 * 60_000L) return ""
        val f = File(p)
        return if (f.exists()) "File the user just sent: ${f.absolutePath} (${Workspace.human(f.length())}). Messages like \"this file\" or \"read it\" refer to it.\n" else ""
    }

    fun copyFrom(ctx: android.content.Context, uri: Uri, project: String): File? = try {
        var name = "file"
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name }
        val f = target(project, name)
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        if (f.length() > 0) f.also { note(it) } else null
    } catch (e: Exception) { null }

    /** Downloads a file the owner sent to the Telegram bot (up to the 20 MB the bot API allows). */
    fun fromTelegram(token: String, fileId: String, name: String, project: String): Pair<File?, String?> {
        return try {
            val meta = JSONObject(Net.get("https://api.telegram.org/bot$token/getFile?file_id=$fileId", 15000, 100_000).body)
            if (!meta.optBoolean("ok")) return null to "Telegram would not give me that file (files over 20 MB cannot be fetched by bots)."
            val path = meta.getJSONObject("result").getString("file_path")
            val f = target(project, name.ifBlank { path.substringAfterLast('/') })
            val c = URL("https://api.telegram.org/file/bot$token/$path").openConnection() as HttpURLConnection
            try { c.connectTimeout = 15000; c.readTimeout = 60000; c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } } } finally { c.disconnect() }
            note(f); f to null
        } catch (e: Exception) { null to Secrets.redact(e.message ?: "download failed") }
    }

    fun register() {
        Tools.register(Tool("inbox_list", "List the files the user sent (Telegram or chat attach), newest first.", emptyList(), Risk.READ, group = "docs",
            run = { c, _ ->
                val d = Workspace.inside(c.project, "inbox")
                val fs = d?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(30) ?: emptyList()
                ok(if (fs.isEmpty()) "The inbox is empty." else fs.joinToString("\n") { "${it.path} (${Workspace.human(it.length())})" })
            }))
        Tools.register(Tool("js_run", "Run JavaScript in an isolated sandbox with no network and no files (calculations, parsing, transforming data, quick algorithms). Use console.log for output; the value you return is shown too. DATA holds the optional data string.",
            listOf(A("code", "JavaScript function body"), A("data", "optional text available as DATA", false)), Risk.LOCAL, 40_000L, group = "code",
            run = { _, a ->
                val (good, out) = JsSandbox.run(a.getString("code"), a.optString("data", ""))
                if (good) ok(out.ifBlank { "(no output)" }) else fail(out)
            }))
    }
}

/** A WebView with the network blocked, no file access, and a hard time limit. */
object JsSandbox {
    private class Bridge(val done: CompletableDeferred<Pair<Boolean, String>>) {
        @JavascriptInterface fun done(out: String, ok: Boolean) { done.complete(ok to out) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(code: String, data: String, timeoutMs: Long = 15_000L): Pair<Boolean, String> {
        val done = CompletableDeferred<Pair<Boolean, String>>()
        var web: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                val w = WebView(Axiom.ctx); web = w
                w.settings.javaScriptEnabled = true
                w.settings.allowFileAccess = false; w.settings.allowContentAccess = false
                w.settings.blockNetworkLoads = true
                w.settings.domStorageEnabled = false
                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
                w.addJavascriptInterface(Bridge(done), "Bridge")
                val safeCode = code.replace("</script", "<\\/script")
                val html = "<html><body><script>var DATA=" + JSONObject.quote(data) + ";var __o=[];console.log=function(){__o.push([].slice.call(arguments).map(function(a){try{return typeof a==='object'?JSON.stringify(a):String(a)}catch(e){return String(a)}}).join(' '))};" +
                    "function __fin(r){if(r!==undefined)__o.push('=> '+(typeof r==='object'?JSON.stringify(r):String(r)));Bridge.done(__o.join('\\n').substring(0,20000),true)}" +
                    "try{var __r=(function(){" + safeCode + "\n})();if(__r&&typeof __r.then==='function'){__r.then(__fin,function(e){Bridge.done(__o.join('\\n')+'\\nError: '+e,false)})}else{__fin(__r)}}catch(e){Bridge.done(__o.join('\\n')+'\\nError: '+e,false)}</script></body></html>"
                w.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
            }
            return withTimeoutOrNull(timeoutMs) { done.await() } ?: (false to "The code did not finish within ${timeoutMs / 1000} seconds and was stopped.")
        } finally {
            withContext(Dispatchers.Main) { try { web?.destroy() } catch (e: Exception) { /* gone */ } }
        }
    }
}
