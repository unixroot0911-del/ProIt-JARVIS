package com.jarvis.assistant

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Named secrets for connectors and servers (tokens, passwords, API keys). Encrypted with the Android Keystore, entered only through
 * a secure box on the phone (never typed into the chat), substituted into requests at the last moment as {{secret:name}},
 * and removed from every result and log. The model only ever sees the NAME of a secret.
 */
object Vault {
    fun put(name: String, value: String) {
        Store.get().replace("vault", ContentValues().apply { put("name", name); put("enc", Secrets.enc(value.trim())); put("ts", System.currentTimeMillis()) })
    }
    fun get(name: String): String = Store.get().one("SELECT enc FROM vault WHERE name = ?", name)?.get("enc")?.let { Secrets.dec(it) } ?: ""
    fun has(name: String) = Store.get().scalar("SELECT COUNT(*) FROM vault WHERE name = ?", name) > 0
    fun remove(name: String) { Store.get().delete("vault", "name = ?", name) }
    fun names(): List<String> = Store.get().query("SELECT name FROM vault ORDER BY name").map { it["name"]!! }
    fun values(): List<String> = Store.get().query("SELECT enc FROM vault").map { Secrets.dec(it["enc"]!!) }.filter { it.length >= 6 }

    private val rx = Regex("\\{\\{\\s*secret:([A-Za-z0-9_.-]+)\\s*\\}\\}")

    /** Replaces every {{secret:name}} with the stored value. Returns null for the missing name when one is not stored. */
    fun fill(text: String): Pair<String, String?> {
        var missing: String? = null
        val out = rx.replace(text) { m -> val v = get(m.groupValues[1]); if (v.isEmpty()) { missing = m.groupValues[1]; m.value } else v }
        return out to missing
    }
}

/** A secure input box (dots instead of letters). Shown in the app if it is open, otherwise over other apps. */
object SecretPrompt {
    suspend fun ask(context: Context, title: String, hint: String = "Paste it here"): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                val act = Engine.foreground
                val ctx: Context = if (act != null) act else {
                    if (!Settings.canDrawOverlays(context)) { if (cont.isActive) cont.resume(null); return@post }
                    ContextThemeWrapper(context.applicationContext, R.style.Theme_Jarvis)
                }
                val e = EditText(ctx).apply { this.hint = hint; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine(true) }
                val dlg = AlertDialog.Builder(ctx).setTitle("AXIOM needs a secret").setMessage(title + "\n\nIt is stored encrypted on this phone and never shown to the AI.").setView(e)
                    .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(null) }
                    .setPositiveButton("Save") { _, _ -> if (cont.isActive) cont.resume(e.text.toString().trim().ifEmpty { null }) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(null) }
                    .create()
                if (act == null) dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dlg.show()
                cont.invokeOnCancellation { try { dlg.dismiss() } catch (x: Exception) { /* gone */ } }
            } catch (x: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
