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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/** Process-wide runtime state shared by the activity, services and background workers. */
object Engine {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var agentJob: Job? = null
    @Volatile var foreground: Activity? = null
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
                    .setTitle("Jarvis needs your OK")
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
