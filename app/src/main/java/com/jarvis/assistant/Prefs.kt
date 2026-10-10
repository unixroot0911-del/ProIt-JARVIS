package com.jarvis.assistant

import android.content.Context
import java.security.SecureRandom

/** Local settings. API keys never leave the phone except in requests to their own provider. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    var geminiKey: String
        get() = str("gemini_key")
        set(v) = put("gemini_key", v.trim())

    var groqKey: String
        get() = str("groq_key")
        set(v) = put("groq_key", v.trim())

    /** Last model that worked for each provider; rediscovered automatically if it is retired. */
    var geminiModel: String
        get() = str("gemini_model")
        set(v) = put("gemini_model", v)

    var groqModel: String
        get() = str("groq_model")
        set(v) = put("groq_model", v)

    var speechLocale: String
        get() = str("speech_locale", "ar-MA")
        set(v) = put("speech_locale", v.trim())

    var speakReplies: Boolean
        get() = sp.getBoolean("speak_replies", true)
        set(v) = sp.edit().putBoolean("speak_replies", v).apply()

    /** "normal" or "study" */
    var mode: String
        get() = str("mode", "normal")
        set(v) = put("mode", v)

    /** Speech to text: false = Groq Whisper first (fast), true = Gemini first (more accurate with Darija, slower). */
    var sttGeminiFirst: Boolean
        get() = sp.getBoolean("stt_gemini_first", false)
        set(v) = sp.edit().putBoolean("stt_gemini_first", v).apply()

    var briefingsOn: Boolean
        get() = sp.getBoolean("briefings_on", true)
        set(v) = sp.edit().putBoolean("briefings_on", v).apply()

    var morningTime: String
        get() = str("morning_time", "07:30")
        set(v) = put("morning_time", v)

    var eveningTime: String
        get() = str("evening_time", "21:00")
        set(v) = put("evening_time", v)

    var overlayOn: Boolean
        get() = sp.getBoolean("overlay_on", false)
        set(v) = sp.edit().putBoolean("overlay_on", v).apply()

    /** Force voice: Jarvis speaks everything aloud, always, with no quiet-mode, headphone or night restrictions. */
    var forceVoice: Boolean
        get() = sp.getBoolean("force_voice", false)
        set(v) = sp.edit().putBoolean("force_voice", v).apply()

    /** Watch mode: seconds between looks, and auto-stop after this many minutes. */
    var watchSeconds: Int
        get() = sp.getInt("watch_s", 10)
        set(v) = sp.edit().putInt("watch_s", v).apply()

    var watchMinutes: Int
        get() = sp.getInt("watch_m", 90)
        set(v) = sp.edit().putInt("watch_m", v).apply()

    var telegramToken: String
        get() = str("tg_token")
        set(v) = put("tg_token", v.trim())

    /** Empty until the owner pairs with /pair CODE. Only this chat may command Jarvis. */
    var telegramChatId: String
        get() = str("tg_chat")
        set(v) = put("tg_chat", v)

    val pairCode: String
        get() {
            var c = str("pair_code")
            if (c.isEmpty()) {
                c = (SecureRandom().nextInt(900000) + 100000).toString()
                put("pair_code", c)
            }
            return c
        }

    fun resetPairCode() = put("pair_code", "")
}
