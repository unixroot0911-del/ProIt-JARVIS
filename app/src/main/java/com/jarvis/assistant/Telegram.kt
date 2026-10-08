package com.jarvis.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Telegram {
    private fun call(token: String, method: String, body: JSONObject?, readTimeoutMs: Int): String {
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = readTimeoutMs
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("Telegram HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    fun send(token: String, chat: Long, text: String) {
        val body = JSONObject().put("chat_id", chat).put("text", text.take(4000))
        call(token, "sendMessage", body, 15000)
    }

    fun getUpdates(token: String, offset: Long): String =
        call(token, "getUpdates?timeout=25&offset=$offset", null, 40000)

    /** Sends to the paired owner chat, silently doing nothing if Telegram is not set up. */
    fun sendToOwner(prefs: Prefs, text: String) {
        val token = prefs.telegramToken
        val chat = prefs.telegramChatId.toLongOrNull() ?: return
        if (token.isEmpty()) return
        try { send(token, chat, text) } catch (e: Exception) { /* offline, skip */ }
    }
}

/** Remote control: the paired owner chats with Jarvis from Telegram, anywhere. */
class TelegramBot(private val prefs: Prefs, private val assistant: Assistant) {

    suspend fun loop() {
        var offset = 0L
        while (currentCoroutineContext().isActive) {
            val token = prefs.telegramToken
            if (token.isEmpty()) { delay(5000); continue }
            try {
                val res = withContext(Dispatchers.IO) { Telegram.getUpdates(token, offset) }
                val arr = JSONObject(res).getJSONArray("result")
                for (i in 0 until arr.length()) {
                    val u = arr.getJSONObject(i)
                    offset = u.getLong("update_id") + 1
                    val m = u.optJSONObject("message") ?: continue
                    val text = m.optString("text", "")
                    if (text.isEmpty()) continue
                    handle(token, m.getJSONObject("chat").getLong("id"), text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5000)
            }
        }
    }

    private suspend fun handle(token: String, chat: Long, text: String) {
        withContext(Dispatchers.IO) {
            if (text.startsWith("/pair")) {
                val code = text.removePrefix("/pair").trim()
                if (prefs.telegramChatId.isEmpty() && code == prefs.pairCode) {
                    prefs.telegramChatId = chat.toString()
                    prefs.resetPairCode()
                    Telegram.send(token, chat, "Paired. I answer only to this chat now.")
                } else {
                    Telegram.send(token, chat, "Pairing failed.")
                }
                return@withContext
            }
            if (chat.toString() != prefs.telegramChatId) return@withContext   // strangers get no answer at all
        }
        if (chat.toString() != prefs.telegramChatId) return

        val r = assistant.handle(
            text, null,
            confirm = { Confirmer.ask(assistant.appContext, it) },
            onProgress = { msg -> Telegram.sendToOwner(prefs, msg) }
        )
        withContext(Dispatchers.IO) { Telegram.send(token, chat, r.shown) }
    }
}
