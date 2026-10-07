package com.aaosmusic

import android.app.Application
import android.content.ContentResolver
import com.aaosmusic.media.MusicPlaybackProvider
import com.aaosmusic.providers.apple.AppleMusicApiClient
import com.aaosmusic.providers.apple.AppleMusicProvider
import com.aaosmusic.providers.apple.AppleMusicTokenStore
import com.aaosmusic.providers.apple.appleSdkBridge
import com.aaosmusic.providers.fake.FakeMusicProvider
import com.aaosmusic.providers.fake.FakePlaybackProvider
import com.aaosmusic.repository.MusicRepository
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.APP

/** Wires providers from feature flags. The only place that knows which provider is active. */
class AAOSMusicApp : Application() {

    lateinit var repository: MusicRepository
        private set
    lateinit var playbackProvider: MusicPlaybackProvider
        private set
    lateinit var appleTokens: AppleMusicTokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        appleTokens = AppleMusicTokenStore(this)

        val wantApple = !BuildConfig.USE_FAKE_PROVIDER
        val appleReady = appleTokens.developerToken != null
        val applePlayback = if (wantApple && appleReady && BuildConfig.ENABLE_APPLE_PLAYBACK) {
            appleSdkBridge().playbackProvider(appleTokens)
        } else null

        // Apple metadata is only useful with Apple playback (its songs aren't URI-playable).
        if (wantApple && applePlayback != null) {
            repository = MusicRepository(AppleMusicProvider(AppleMusicApiClient(appleTokens), appleTokens), providerName = "apple")
            playbackProvider = applePlayback
        } else {
            if (wantApple) {
                AppLog.w(APP, "Apple provider requested but unavailable (developer token: $appleReady, " +
                    "playback flag: ${BuildConfig.ENABLE_APPLE_PLAYBACK}, SDK: ${appleSdkBridge().available}); using fake")
            }
            val artworkBase = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/drawable/"
            repository = MusicRepository(FakeMusicProvider(artworkBase), providerName = "fake")
            playbackProvider = FakePlaybackProvider()
        }
        AppLog.i(APP, "Started: provider=${repository.providerName} appleAuth=${BuildConfig.ENABLE_APPLE_AUTH} " +
            "applePlayback=${BuildConfig.ENABLE_APPLE_PLAYBACK} debugMenu=${BuildConfig.ENABLE_DEBUG_MENU}")
    }
}
