package com.jarvis.assistant

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth 2.1 sign-in for MCP servers: discovery, automatic client registration, PKCE, the browser round trip, token refresh.
 * Tokens are stored encrypted and never shown to the AI.
 */
object Oauth {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Uri>>()
    private const val REDIRECT = "axiom://oauth"

    fun callback(uri: Uri) { uri.getQueryParameter("state")?.let { waiting[it]?.complete(uri) } }

    private class Http(val code: Int, val text: String)

    private fun http(url: String, method: String = "GET", form: Map<String, String>? = null, json: JSONObject? = null): Http {
        val h = URL(url).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 15_000; h.readTimeout = 30_000
        h.setRequestProperty("Accept", "application/json"); h.setRequestProperty("User-Agent", "AXIOM/" + Axiom.VERSION)
        val body = when {
            form != null -> { h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") } }
            json != null -> { h.setRequestProperty("Content-Type", "application/json"); json.toString() }
            else -> null
        }
        if (body != null) { h.doOutput = true; h.outputStream.use { it.write(body.toByteArray()) } }
        val code = h.responseCode
        val txt = try { (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
        return Http(code, txt)
    }

    private fun getJson(url: String): JSONObject? = try { http(url).takeIf { it.code in 200..299 }?.let { JSONObject(it.text) } } catch (e: Exception) { null }

    private fun origin(u: String): String { val x = URL(u); return x.protocol + "://" + x.host + (if (x.port > 0) ":" + x.port else "") }

    private fun discover(serverUrl: String, wwwAuth: String?): JSONObject {
        val o = origin(serverUrl)
        val path = URL(serverUrl).path.trimEnd('/')
        val hint = wwwAuth?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        val prm = (hint?.let { getJson(it) }) ?: getJson("$o/.well-known/oauth-protected-resource$path") ?: getJson("$o/.well-known/oauth-protected-resource")
        val asUrl = prm?.optJSONArray("authorization_servers")?.optString(0)?.takeIf { it.isNotEmpty() } ?: o
        val ao = origin(asUrl); val ap = URL(asUrl).path.trimEnd('/')
        return getJson("$ao/.well-known/oauth-authorization-server$ap") ?: getJson("$ao/.well-known/oauth-authorization-server")
            ?: getJson("$ao/.well-known/openid-configuration$ap") ?: getJson("$ao/.well-known/openid-configuration")
            ?: JSONObject().put("authorization_endpoint", "$ao/authorize").put("token_endpoint", "$ao/token").put("registration_endpoint", "$ao/register")
    }

    private fun load(name: String): JSONObject = try { JSONObject(Secrets.dec(Store.get().one("SELECT oauth_enc FROM mcp_servers WHERE name = ?", name)?.get("oauth_enc") ?: "")) } catch (e: Exception) { JSONObject() }
    private fun save(name: String, o: JSONObject) { Store.get().exec("UPDATE mcp_servers SET oauth_enc = ? WHERE name = ?", Secrets.enc(o.toString()), name) }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    suspend fun login(name: String, serverUrl: String, wwwAuth: String?) {
        val meta = withContext(Dispatchers.IO) { discover(serverUrl, wwwAuth) }
        val authEp = meta.optString("authorization_endpoint"); val tokenEp = meta.optString("token_endpoint")
        if (authEp.isEmpty() || tokenEp.isEmpty()) throw java.io.IOException("this server does not offer a standard sign-in")
        var st = load(name)
        var clientId = if (st.optString("token_endpoint") == tokenEp) st.optString("client_id") else ""
        if (clientId.isEmpty()) {
            val reg = meta.optString("registration_endpoint")
            if (reg.isEmpty()) throw java.io.IOException("this server needs an app registered by hand, which cannot be done automatically")
            val r = withContext(Dispatchers.IO) {
                http(reg, "POST", json = JSONObject().put("client_name", "AXIOM").put("redirect_uris", org.json.JSONArray().put(REDIRECT))
                    .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token")).put("response_types", org.json.JSONArray().put("code")).put("token_endpoint_auth_method", "none"))
            }
            if (r.code !in 200..299) throw java.io.IOException("client registration refused (${r.code})")
            clientId = JSONObject(r.text).getString("client_id")
        }
        val sr = SecureRandom()
        val verifier = b64url(ByteArray(48).also { sr.nextBytes(it) })
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64url(ByteArray(16).also { sr.nextBytes(it) })
        val resource = serverUrl
        val authUrl = Uri.parse(authEp).buildUpon().appendQueryParameter("response_type", "code").appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT).appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state).appendQueryParameter("resource", resource).build()
        val d = CompletableDeferred<Uri>(); waiting[state] = d
        ChatBus.publish("system", "Sign-in for $name: finish it in the browser that just opened.")
        withContext(Dispatchers.Main) {
            val i = Intent(Intent.ACTION_VIEW, authUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { Axiom.ctx.startActivity(i) } catch (e: Exception) { /* the notification below is the fallback */ }
            try {
                Notify.channels(Axiom.ctx)
                val pi = PendingIntent.getActivity(Axiom.ctx, state.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n = android.app.Notification.Builder(Axiom.ctx, Notify.askChannel(Axiom.ctx)).setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Sign in to $name").setContentText("Tap to continue in the browser").setContentIntent(pi).setAutoCancel(true).build()
                (Axiom.ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).notify(state.hashCode(), n)
            } catch (e: Exception) { /* notifications may be off */ }
        }
        val uri = withTimeoutOrNull(300_000L) { d.await() }; waiting.remove(state)
        if (uri == null) throw java.io.IOException("the sign-in was not completed in 5 minutes")
        uri.getQueryParameter("error")?.let { throw java.io.IOException("the server refused the sign-in: $it") }
        val code = uri.getQueryParameter("code") ?: throw java.io.IOException("no sign-in code came back")
        val t = withContext(Dispatchers.IO) {
            http(tokenEp, "POST", form = mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT, "client_id" to clientId, "code_verifier" to verifier, "resource" to resource))
        }
        if (t.code !in 200..299) throw java.io.IOException("the token request failed (${t.code}): ${t.text.take(120)}")
        st = JSONObject(t.text)
        val out = JSONObject().put("client_id", clientId).put("token_endpoint", tokenEp).put("access", st.getString("access_token")).put("refresh", st.optString("refresh_token", ""))
            .put("exp", System.currentTimeMillis() + st.optLong("expires_in", 3600L) * 1000L).put("resource", resource)
        save(name, out)
        ChatBus.publish("system", "Signed in to $name.")
    }

    /** A valid access token, refreshed first when it is about to expire. Null when there is none. Blocking: call from a background thread. */
    fun token(name: String): String? {
        val st = load(name); val acc = st.optString("access")
        if (acc.isEmpty()) return null
        if (st.optLong("exp") > System.currentTimeMillis() + 60_000L) return acc
        val rf = st.optString("refresh"); if (rf.isEmpty()) return acc
        return try {
            val r = http(st.getString("token_endpoint"), "POST", form = mapOf("grant_type" to "refresh_token", "refresh_token" to rf, "client_id" to st.getString("client_id"), "resource" to st.optString("resource")))
            if (r.code !in 200..299) return acc
            val j = JSONObject(r.text)
            st.put("access", j.getString("access_token")).put("exp", System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000L)
            if (j.optString("refresh_token").isNotEmpty()) st.put("refresh", j.getString("refresh_token"))
            save(name, st); st.getString("access")
        } catch (e: Exception) { acc }
    }

    suspend fun refreshOrLogin(name: String, serverUrl: String, wwwAuth: String?) {
        val st = load(name)
        if (st.optString("refresh").isNotEmpty()) {
            st.put("exp", 0L); save(name, st)
            val fresh = withContext(Dispatchers.IO) { token(name) }
            if (fresh != null && fresh != st.optString("access")) return
        }
        login(name, serverUrl, wwwAuth)
    }
}

/** Receives axiom://oauth?code=... after the browser sign-in and hands it to the waiting login. */
class OAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { Oauth.callback(it) }
        android.widget.Toast.makeText(this, "Signed in. You can return to AXIOM.", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
