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

    private const val SYSTEM = """You are an elite game and app developer. Output ONE complete, self-contained HTML5 file: HTML, CSS and JavaScript inline,
no external libraries, fonts, images or network calls. Draw everything procedurally on a <canvas> (shapes, gradients, particles) or with DOM/CSS.
Requirements for games: designed for a phone (portrait or landscape, resizes to the window), touch controls (virtual joystick and buttons) plus keyboard fallback,
an intro cutscene of several story panels with typed text and animation that can be skipped, a real gameplay loop with progression and difficulty,
sound effects through WebAudio (small, no files), a pause menu, progress saved in localStorage, and a proper ending screen when the story is completed.
Everything must be ORIGINAL: new names, new story, new characters, new art. Never copy the characters, text, story or assets of an existing game or franchise;
only the genre, mechanics and mood may be similar. Write clean working code with no placeholders and no TODOs, rich but compact (aim for 600 to 1500 lines).
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

        val raw = brain.complete(SYSTEM, prompt, emptyList(), null, json = false, big = true)
        val html = clean(raw)
        if (!html.contains("<html", ignoreCase = true) || !html.contains("</html>", ignoreCase = true) || html.length < 1200) {
            return "The brain's answer was cut off or incomplete (free models have output limits). Ask again, or ask for a smaller first version and then upgrade it step by step."
        }
        withContext(Dispatchers.IO) { file.writeText(html) }
        open(ctx, file)
        return "Built \"$name\" (${html.length / 1024} KB) and opened it. It is saved under MY APPS AND GAMES. " +
            "Tell me what to change or add and I will upgrade it."
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
