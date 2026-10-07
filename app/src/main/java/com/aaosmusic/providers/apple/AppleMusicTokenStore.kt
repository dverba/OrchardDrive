package com.aaosmusic.providers.apple

import android.content.Context
import com.aaosmusic.BuildConfig
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.AUTH

/**
 * Holds the two MusicKit credentials:
 * - developer token: identifies this app to Apple. Debug builds get one signed at build time from
 *   the local key; production must fetch it from a backend.
 * - Music User Token: identifies the signed-in subscriber. Persisted so the car doesn't need a new
 *   sign-in on every start.
 *
 * PoC storage is plain SharedPreferences (same as Automus). TODO: wrap with an Android Keystore key.
 */
class AppleMusicTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apple_music_auth", Context.MODE_PRIVATE)

    val developerToken: String? get() = BuildConfig.APPLE_DEVELOPER_TOKEN.ifEmpty { null }

    var userToken: String?
        get() = prefs.getString(KEY_USER_TOKEN, null)
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_USER_TOKEN) else putString(KEY_USER_TOKEN, value) }.apply()
            AppLog.i(AUTH, if (value == null) "Music user token cleared" else "Music user token stored: ${AppLog.redact(value)}")
        }

    private companion object {
        const val KEY_USER_TOKEN = "music_user_token"
    }
}
