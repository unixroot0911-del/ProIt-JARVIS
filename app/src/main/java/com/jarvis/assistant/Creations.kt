package com.jarvis.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Jarvis builds real apps and games: the brain writes one complete self-contained HTML5 page, Jarvis saves it
 * on the phone and runs it in its own full-screen window. Asking again with the same name upgrades it.
 */
object Creations {

    private const val SYSTEM = """You are a senior product engineer and designer. Output ONE complete, self-contained HTML5 file: HTML, CSS and JavaScript inline,
no external libraries, fonts, images or network calls (use system font stacks, inline SVG, CSS gradients, canvas). Quality bar: a professional, finished product, never a demo or a skeleton.
FOR WEBSITES AND APPS: semantic HTML5 with proper <title>, meta description, lang and dir (full RTL support when the language is Arabic or Hebrew), a considered visual design (a real colour system with CSS variables,
typographic scale, spacing, dark and light mode through prefers-color-scheme), fully responsive from 320px up, a sticky navigation with smooth scrolling and a mobile menu, a strong hero, every section the subject really needs
(for example about, services or products, pricing, gallery, testimonials, FAQ, contact), real and specific written content in the user's language (never lorem ipsum, never "your text here"), a working contact or sign-up form with
validation and a visible success state, micro-interactions and tasteful animation (respecting prefers-reduced-motion), accessibility (labels, alt text, focus states, contrast, keyboard use), Open Graph tags, and a footer.
Apps must really work: state, edit and delete, saving in localStorage, empty states, error handling.
FOR GAMES: designed for a phone (resizes to the window), touch controls plus keyboard fallback, an intro of several story panels that can be skipped, a real gameplay loop with progression and rising difficulty,
WebAudio sound effects, a pause menu, saved progress, and a proper ending.
Everything must be ORIGINAL; never copy characters, text, story or assets of an existing franchise. No placeholders, no TODOs, no commented-out stubs. Rich but compact (aim for 800 to 2500 lines).
Output ONLY the code of the file, starting with <!DOCTYPE html>, no markdown fences, no commentary."""

    private fun dir(ctx: Context): File = File(ctx.filesDir, "creations").apply { mkdirs() }

    private fun slug(n: String): String =
        n.lowercase().replace(Regex("[^a-z0-9\\u0600-\\u06FF]+"), "-").trim('-').take(40).ifEmpty { "creation" }

    fun names(ctx: Context): List<String> =
        dir(ctx).listFiles()?.filter { it.extension == "html" }?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension } ?: emptyList()

    fun find(ctx: Context, name: String): File? {
        val s = slug(name)
        val all = dir(ctx).listFiles()?.filter { it.extension == "html" } ?: return null
        return all.firstOrNull { it.nameWithoutExtension == s } ?: all.firstOrNull { it.nameWithoutExtension.contains(s) || s.contains(it.nameWithoutExtension) }
    }

    private fun clean(raw: String): String {
        var t = raw.trim()
        val start = t.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: t.indexOf("<html", ignoreCase = true)
        if (start > 0) t = t.substring(start)
        val end = t.lastIndexOf("</html>", ignoreCase = true)
        if (end >= 0) t = t.substring(0, end + 7)
        return t.trim()
    }

    suspend fun build(ctx: Context, brain: Brain, arg: String, onProgress: (String) -> Unit): String {
        val p = arg.split("|", limit = 2)
        val name = p[0].trim().ifEmpty { "creation" }
        val spec = p.getOrElse(1) { "" }.trim().ifEmpty { name }
        val file = File(dir(ctx), slug(name) + ".html")
        val existing = if (file.exists()) withContext(Dispatchers.IO) { file.readText().take(70000) } else null

        onProgress("Building \"$name\". This can take up to a minute or two.")
        val prompt = if (existing != null)
            "UPGRADE this existing app named \"$name\". Requested change: $spec\n\nKeep everything that works, apply the change, and return the COMPLETE updated file.\n\nCURRENT FILE:\n$existing"
        else
            "Build this: $name. Details from the user: $spec"

        var html = clean(brain.complete(SYSTEM, prompt, emptyList(), null, json = false, big = true))
        // Check the result like a reviewer would: complete file, scripts that parse. Repair up to 2 times.
        for (round in 1..2) {
            val problem = problemsOf(html) ?: break
            onProgress("Checking and repairing (round $round): $problem")
            val fix = try {
                clean(brain.complete(SYSTEM, "The file below has this problem: $problem\nReturn the COMPLETE corrected file, changing only what is needed.\n\nFILE:\n" + html.take(90000), emptyList(), null, json = false, big = true))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { break }
            if (fix.length > 1200) html = fix
        }
        problemsOf(html)?.let {
            return "I could not get a clean version: $it. Nothing was saved. Ask again, or ask for it in two parts (structure first, then upgrade it)."
        }
        withContext(Dispatchers.IO) { file.writeText(html) }
        val copy = try { val d = File(Workspace.project("sites"), slug(name) + ".html"); d.writeText(html); Workspace.note(d.path, "build_app", name); d.path } catch (e: Exception) { null }
        open(ctx, file)
        return "Built \"$name\" (${html.length / 1024} KB) and opened it. It is saved under MY APPS AND GAMES. " +
            (if (copy != null) "A copy for sharing or hosting is in $copy. " else "") + "Tell me what to change or add and I will upgrade it."
    }

    /** Null when the page is complete and its scripts parse; otherwise what is wrong. */
    private suspend fun problemsOf(html: String): String? {
        if (!html.contains("<html", ignoreCase = true) || !html.contains("</html>", ignoreCase = true) || html.length < 1200)
            return "the file is cut off or incomplete"
        val scripts = Regex("(?is)<script(?![^>]*\\bsrc=)[^>]*>(.*?)</script>").findAll(html).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
        for ((i, src) in scripts.withIndex()) {
            val (good, out) = JsSandbox.run("try { new Function(DATA); return 'OK'; } catch (e) { return 'SYNTAX ' + e.message; }", src, 8000)
            if (!good || !out.contains("OK")) return "script ${i + 1} has a JavaScript syntax error (" + Axiom.clip(out.replace("=> ", ""), 160) + ")"
        }
        return null
    }

    fun openByName(ctx: Context, name: String): String {
        val f = find(ctx, name) ?: return "I have not built anything called \"$name\" yet. Built so far: ${names(ctx).joinToString(", ").ifEmpty { "nothing" }}."
        open(ctx, f)
        return "Opening ${f.nameWithoutExtension}."
    }

    /** Starts the player; also posts a notification, because Android may block windows opened from the background. */
    fun open(ctx: Context, file: File) {
        val intent = Intent(ctx, GameActivity::class.java)
            .putExtra("file", file.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: Exception) { /* notification below is the fallback */ }
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("jarvis_creations", "Jarvis creations", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(ctx, file.name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, "jarvis_creations")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Play: ${file.nameWithoutExtension}")
                .setContentText("Tap to open")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(file.name.hashCode(), n)
        } catch (e: Exception) { /* notifications may be off */ }
    }
}

/** Full-screen player for the apps and games Jarvis built. Fully offline. */
class GameActivity : Activity() {

    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val path = intent.getStringExtra("file")
        val root = File(filesDir, "creations").canonicalPath
        val f = path?.let { File(it) }
        if (f == null || !f.exists() || !f.canonicalPath.startsWith(root)) { finish(); return }

        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true   // offline only
        }
        w.loadDataWithBaseURL("https://jarvis.local/${f.nameWithoutExtension}/", f.readText(), "text/html", "utf-8", null)
        web = w
        setContentView(w, ViewGroup.LayoutParams(-1, -1))
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
