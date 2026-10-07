package com.aaosmusic.providers.apple

import android.content.Context
import android.content.Intent
import com.aaosmusic.media.MusicPlaybackProvider

/** Used when app/libs/apple has no MusicKit AARs. */
internal object AppleSdkBridgeImpl : AppleSdkBridge {
    override val available = false
    override fun signInIntent(context: Context, developerToken: String): Intent? = null
    override fun parseSignInResult(context: Context, data: Intent?): Result<String> =
        Result.failure(IllegalStateException("MusicKit SDK not bundled"))
    override fun playbackProvider(tokens: AppleMusicTokenStore): MusicPlaybackProvider? = null
    override fun createPlayer(context: Context, developerToken: String, userToken: String, log: (String) -> Unit): ApplePlayerHandle =
        throw IllegalStateException("MusicKit SDK not bundled (put the AARs in app/libs/apple)")
}
