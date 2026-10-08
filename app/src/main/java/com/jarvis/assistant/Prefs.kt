package com.jarvis.assistant

import android.content.Context

/** Local settings. API keys never leave the phone except in requests to their own provider. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()

    /** ar-MA first; the recognizer falls back to the device default if unsupported. */
    var speechLocale: String
        get() = sp.getString("speech_locale", "ar-MA") ?: "ar-MA"
        set(v) = sp.edit().putString("speech_locale", v).apply()

    var speakReplies: Boolean
        get() = sp.getBoolean("speak_replies", true)
        set(v) = sp.edit().putBoolean("speak_replies", v).apply()
}
