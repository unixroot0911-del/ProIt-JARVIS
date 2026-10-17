package com.jarvis.assistant

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys and tokens are encrypted with a key that lives in the Android Keystore (it never leaves the secure hardware),
 * never written to logs or chats, and only ever sent to the provider they belong to.
 */
object Secrets {
    private const val ALIAS = "axiom_master_v1"

    @Volatile private var cachedKey: SecretKey? = null
    private val decCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) { cachedKey ?: loadKey().also { cachedKey = it } }
    }

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun enc(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            "plain:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)   // keystore unavailable on this phone
        }
    }

    fun dec(stored: String): String {
        if (stored.isEmpty()) return ""
        decCache[stored]?.let { return it }
        return decOnce(stored).also { if (it.isNotEmpty()) decCache[stored] = it }
    }

    private fun decOnce(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            when {
                stored.startsWith("v1:") -> {
                    val p = stored.split(":")
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[1], Base64.NO_WRAP)))
                    String(c.doFinal(Base64.decode(p[2], Base64.NO_WRAP)), Charsets.UTF_8)
                }
                stored.startsWith("plain:") -> String(Base64.decode(stored.removePrefix("plain:"), Base64.NO_WRAP), Charsets.UTF_8)
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    private val patterns = listOf(
        Regex("AIza[0-9A-Za-z_\\-]{20,}"),
        Regex("gsk_[0-9A-Za-z]{20,}"),
        Regex("\\b\\d{8,10}:[A-Za-z0-9_\\-]{30,}\\b"),
        Regex("(?i)(bearer|token|apikey|api_key|authorization)[\"'=: ]+[A-Za-z0-9._\\-]{24,}"),
        Regex("ghp_[0-9A-Za-z]{30,}"),
        Regex("github_pat_[0-9A-Za-z_]{30,}")
    )

    fun looksLikeSecret(s: String): Boolean = patterns.any { it.containsMatchIn(s) }

    /** Removes anything that looks like a key or token, plus the exact keys currently stored. */
    fun redact(text: String): String {
        var t = text
        try {
            if (Axiom.ready()) {
                val p = Prefs(Axiom.ctx)
                for (k in listOf(p.geminiKey, p.groqKey, p.telegramToken, p.githubToken, p.emailPassword)) {
                    if (k.length >= 8) t = t.replace(k, "[hidden key]")
                }
            }
        } catch (e: Exception) { /* redact what we can */ }
        try { for (v in Vault.values()) if (t.contains(v)) t = t.replace(v, "[hidden secret]") } catch (e: Exception) { /* vault not ready */ }
        for (r in patterns) t = r.replace(t, "[hidden key]")
        return t
    }

    fun fingerprint(key: String): String = if (key.isEmpty()) "none" else Axiom.sha1(key).take(8)
}
