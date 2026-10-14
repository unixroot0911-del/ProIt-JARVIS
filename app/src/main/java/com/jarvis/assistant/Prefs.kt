package com.jarvis.assistant

import android.content.Context
import java.security.SecureRandom

/** Local settings. API keys never leave the phone except in requests to their own provider. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    /** Keys and tokens are stored encrypted (Android Keystore). Old plain values are migrated on first read. */
    private fun secret(name: String): String {
        val enc = sp.getString(name + "_enc", null)
        if (enc != null) return Secrets.dec(enc)
        val old = sp.getString(name, null)
        if (!old.isNullOrEmpty()) { putSecret(name, old); return old }
        return ""
    }

    private fun putSecret(name: String, v: String) {
        val clean = v.trim()
        val e = sp.edit().remove(name)
        if (clean.isEmpty()) e.remove(name + "_enc") else e.putString(name + "_enc", Secrets.enc(clean))
        e.apply()
    }

    /** A key that was revoked is remembered by its hash only, so it cannot quietly come back. */
    fun isRevoked(key: String): Boolean = key.isNotEmpty() && (sp.getStringSet("revoked", emptySet()) ?: emptySet()).contains(Axiom.sha1(key))

    fun revoke(name: String) {
        val cur = secret(name)
        if (cur.isNotEmpty()) {
            val set = HashSet(sp.getStringSet("revoked", emptySet()) ?: emptySet())
            set.add(Axiom.sha1(cur))
            sp.edit().putStringSet("revoked", set).apply()
        }
        putSecret(name, "")
    }

    private fun setKey(name: String, v: String) { if (!isRevoked(v.trim())) putSecret(name, v) }

    var geminiKey: String
        get() = secret("gemini_key")
        set(v) = setKey("gemini_key", v)

    var groqKey: String
        get() = secret("groq_key")
        set(v) = setKey("groq_key", v)

    var githubToken: String
        get() = secret("github_token")
        set(v) = setKey("github_token", v)

    var emailPassword: String
        get() = secret("email_password")
        set(v) = setKey("email_password", v)

    var emailAddress: String
        get() = str("email_address")
        set(v) = put("email_address", v.trim())

    var emailImapHost: String
        get() = str("email_imap", "imap.gmail.com")
        set(v) = put("email_imap", v.trim())

    var emailSmtpHost: String
        get() = str("email_smtp", "smtp.gmail.com")
        set(v) = put("email_smtp", v.trim())

    /** The project new work goes into until the user switches. */
    var activeProject: String
        get() = str("active_project", "general")
        set(v) = put("active_project", v.trim().ifEmpty { "general" })

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

    /** Jarvis may ask a clarifying question (with answer buttons) when something is really unclear. */
    var smartAsk: Boolean
        get() = sp.getBoolean("smart_ask", true)
        set(v) = sp.edit().putBoolean("smart_ask", v).apply()

    var watchMinutes: Int
        get() = sp.getInt("watch_m", 90)
        set(v) = sp.edit().putInt("watch_m", v).apply()

    var telegramToken: String
        get() = secret("tg_token")
        set(v) = setKey("tg_token", v)

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
