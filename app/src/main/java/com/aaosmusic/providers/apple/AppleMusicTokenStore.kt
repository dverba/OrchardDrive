package com.aaosmusic.providers.apple

import android.content.Context
import com.aaosmusic.BuildConfig
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.AUTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the two MusicKit credentials:
 * - developer token: identifies this app to Apple. Normally fetched from the sign-in relay, which
 *   signs it with the MusicKit key held as a server secret, so APKs carry no Apple credential.
 *   Local debug builds may instead embed one signed at build time (BuildConfig).
 * - Music User Token: identifies the signed-in subscriber. Persisted so the car doesn't need a new
 *   sign-in on every start.
 *
 * Storage is plain SharedPreferences (same as Automus). TODO: wrap with an Android Keystore key.
 */
class AppleMusicTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apple_music_auth", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val fetchLock = Mutex()

    /** Current developer token if one is available without network, else null (see [ensureDeveloperToken]). */
    val developerToken: String?
        get() = BuildConfig.APPLE_DEVELOPER_TOKEN.ifEmpty { null }
            ?: prefs.getString(KEY_DEV_TOKEN, null)?.takeIf { prefs.getLong(KEY_DEV_TOKEN_EXPIRES, 0) > System.currentTimeMillis() }

    var userToken: String?
        get() = prefs.getString(KEY_USER_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_USER_TOKEN) else putString(KEY_USER_TOKEN, value)
                remove(KEY_USER_TOKEN_REJECTED)
            }.apply()
            AppLog.i(AUTH, if (value == null) "Music user token cleared" else "Music user token stored: ${AppLog.redact(value)}")
            notifyChanged()
        }

    /** Apple rejected the stored user token (expired, ~6 months, or revoked). A new sign-in clears it. */
    val userTokenRejected: Boolean get() = prefs.getBoolean(KEY_USER_TOKEN_REJECTED, false)

    /** Signed in with a user token Apple hasn't rejected. */
    val signedIn: Boolean get() = userToken != null && !userTokenRejected

    /** Called when Apple answers the user token with 403: everything should now ask for a new sign-in. */
    fun markUserTokenRejected() {
        if (userToken == null || userTokenRejected) return
        prefs.edit().putBoolean(KEY_USER_TOKEN_REJECTED, true).apply()
        AppLog.w(AUTH, "Apple rejected the Music user token; sign-in needed")
        notifyChanged()
    }

    /** Returns a developer token, fetching a fresh one from the relay when missing or within a day of expiry. */
    suspend fun ensureDeveloperToken(): String? {
        BuildConfig.APPLE_DEVELOPER_TOKEN.ifEmpty { null }?.let { return it }
        if (prefs.getLong(KEY_DEV_TOKEN_EXPIRES, 0) - System.currentTimeMillis() > REFRESH_MARGIN_MS) return developerToken
        return fetchLock.withLock {
            if (prefs.getLong(KEY_DEV_TOKEN_EXPIRES, 0) - System.currentTimeMillis() > REFRESH_MARGIN_MS) return@withLock developerToken
            runCatching { fetchDeveloperToken() }
                .onFailure { AppLog.w(AUTH, "Developer token fetch failed: ${it.message}") }
                .getOrNull()
                ?.let { (token, expires) ->
                    val hadToken = developerToken != null
                    prefs.edit().putString(KEY_DEV_TOKEN, token).putLong(KEY_DEV_TOKEN_EXPIRES, expires).apply()
                    AppLog.i(AUTH, "Developer token refreshed: ${AppLog.redact(token)}, expires ${Instant.ofEpochMilli(expires)}")
                    if (!hadToken) notifyChanged()
                }
            developerToken
        }
    }

    /** Called on the setting thread whenever a token changes (sign-in / sign-out / developer token arrives). */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun notifyChanged() = listeners.forEach { it() }

    private suspend fun fetchDeveloperToken(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val conn = URL("${BuildConfig.SIGNIN_RELAY_URL}/developer-token").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val body = Json.parseToJsonElement(conn.inputStream.bufferedReader().use { it.readText() }).jsonObject
            val token = body["token"]?.jsonPrimitive?.contentOrNull ?: error("no token")
            val expires = Instant.parse(body["expires_at"]?.jsonPrimitive?.contentOrNull ?: error("no expiry")).toEpochMilli()
            token to expires
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val KEY_USER_TOKEN = "music_user_token"
        const val KEY_USER_TOKEN_REJECTED = "music_user_token_rejected"
        const val KEY_DEV_TOKEN = "developer_token"
        const val KEY_DEV_TOKEN_EXPIRES = "developer_token_expires"
        const val REFRESH_MARGIN_MS = 24 * 3600 * 1000L
    }
}
