package com.aaosmusic.providers.apple

import android.content.Context
import android.content.Intent

/**
 * Seam around Apple's MusicKit for Android SDK, which can't be committed. The real implementation
 * (src/applesdk) is compiled only when the AARs are in app/libs/apple; otherwise a stub
 * (src/noapplesdk) reports the SDK as unavailable.
 */
interface AppleSdkBridge {
    val available: Boolean

    /** Intent for Apple's own sign-in flow (needs the Apple Music app), or null if unavailable. */
    fun signInIntent(context: Context, developerToken: String): Intent?

    /** Music User Token from the sign-in result, or a failure describing Apple's TokenError. */
    fun parseSignInResult(context: Context, data: Intent?): Result<String>

    /**
     * Creates MusicKit's local player. Throws if the native library or SDK can't initialize.
     * [log] receives one line per player event.
     */
    fun createPlayer(context: Context, developerToken: String, userToken: String, log: (String) -> Unit): ApplePlayerHandle
}

/** Thin handle on MusicKit's MediaPlayerController for the compatibility tests. */
interface ApplePlayerHandle {
    fun playSong(catalogId: String)
    fun togglePause()
    fun release()
}

fun appleSdkBridge(): AppleSdkBridge = AppleSdkBridgeImpl
