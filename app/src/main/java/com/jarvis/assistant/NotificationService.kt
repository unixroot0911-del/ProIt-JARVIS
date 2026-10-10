package com.jarvis.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Remembers the latest inline-reply action of each conversation so Jarvis can answer through it. */
object ReplyCache {
    class Entry(val pkg: String, val app: String, val title: String, val action: Notification.Action)

    private val map = LinkedHashMap<String, Entry>()

    @Synchronized fun put(e: Entry) {
        val key = e.pkg + "|" + e.title
        map.remove(key)
        map[key] = e
        while (map.size > 40) map.remove(map.keys.first())
    }

    @Synchronized fun find(app: String, contact: String): Entry? {
        val a = app.trim().lowercase()
        val c = contact.trim().lowercase()
        return map.values.reversed().firstOrNull {
            (a.isEmpty() || it.app.lowercase().contains(a) || it.pkg.lowercase().contains(a)) &&
                (c.isEmpty() || it.title.lowercase().contains(c))
        }
    }
}

object Replier {
    /** Sends [message] into an existing chat. Works for any app that offers inline reply (WhatsApp, Telegram, SMS...). */
    fun reply(context: Context, app: String, contact: String, message: String): String {
        if (message.isBlank()) return "No message text to send."
        val e = ReplyCache.find(app, contact)
            ?: return "No recent chat with a reply option found for '$contact' in '$app'. Open the chat once or wait for a new message."
        val inputs = e.action.remoteInputs ?: return "That notification cannot be replied to."
        if (inputs.isEmpty()) return "That notification cannot be replied to."
        return try {
            val intent = Intent()
            val b = Bundle()
            for (ri in inputs) b.putCharSequence(ri.resultKey, message)
            RemoteInput.addResultsToIntent(inputs, intent, b)
            e.action.actionIntent.send(context, 0, intent)
            "Replied to ${e.title} on ${e.app}."
        } catch (ex: PendingIntent.CanceledException) {
            "The chat is no longer available to reply to."
        } catch (ex: Exception) {
            "Reply failed: ${ex.message}"
        }
    }
}

/** Reads incoming notifications into memory. The user enables it in system settings (Notification access). */
class JarvisNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            if (sbn.packageName == packageName) return
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            if (sbn.isOngoing) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return

            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn.packageName
            }
            Memory.get(applicationContext).addNotif(sbn.packageName, app, title, text)
            Monitor.onNotification(applicationContext, app, title, text)

            n.actions?.forEach { a ->
                val ris = a.remoteInputs
                if (ris != null && ris.isNotEmpty()) ReplyCache.put(ReplyCache.Entry(sbn.packageName, app, title, a))
            }
        } catch (e: Exception) {
            // never let a bad notification crash the listener
        }
    }
}
