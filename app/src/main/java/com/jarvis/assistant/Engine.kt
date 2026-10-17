package com.jarvis.assistant

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/** Process-wide runtime state shared by the activity, services and background workers. */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Diag.record(e) })
    @Volatile var foreground: Activity? = null

    /** Runs a long loop forever: if it dies for any reason (even an Error), it is recorded and started again after a pause. */
    fun supervise(block: suspend () -> Unit): Job = scope.launch {
        var fails = 0
        while (true) {
            val t0 = System.currentTimeMillis()
            try { block(); return@launch }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) {
                Diag.record(e)
                if (System.currentTimeMillis() - t0 > 120_000L) fails = 0
                fails++
                kotlinx.coroutines.delay(minOf(60_000L, 2_000L * fails))
            }
        }
    }
}

/** Broadcasts the orb state so the in-app orb and the floating orb stay in sync. */
object Bus {
    @Volatile var last: OrbState = OrbState.IDLE
    val listeners = CopyOnWriteArrayList<(OrbState) -> Unit>()
    fun publish(s: OrbState) {
        last = s
        listeners.forEach { it(s) }
    }
}

/** Every chat line (user, jarvis, system) is published here so all chat windows stay in sync. */
object ChatBus {
    val listeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    fun publish(role: String, text: String) {
        listeners.forEach { it(role, text) }
    }
}

/** Asks the user a yes/no question, in the app if it is visible, otherwise over other apps. */
object Confirmer {
    suspend fun ask(context: Context, message: String): Boolean = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            try {
                val act = Engine.foreground
                val ctx: Context = if (act != null) act else {
                    if (!Settings.canDrawOverlays(context)) {
                        if (cont.isActive) cont.resume(false)
                        return@post
                    }
                    ContextThemeWrapper(context.applicationContext, R.style.Theme_Jarvis)
                }
                val dlg = AlertDialog.Builder(ctx)
                    .setTitle("AXIOM needs your OK")
                    .setMessage(message)
                    .setNegativeButton("No") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setPositiveButton("Yes") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                    .create()
                if (act == null) dlg.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dlg.show()
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
