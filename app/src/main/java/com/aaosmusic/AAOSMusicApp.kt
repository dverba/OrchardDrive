package com.aaosmusic

import android.app.Application
import android.content.ContentResolver
import com.aaosmusic.media.MusicPlaybackProvider
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

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.USE_FAKE_PROVIDER) {
            AppLog.w(APP, "USE_FAKE_PROVIDER=false but no other provider exists yet; using fake")
        }
        val artworkBase = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/drawable/"
        repository = MusicRepository(FakeMusicProvider(artworkBase), providerName = "fake")
        playbackProvider = FakePlaybackProvider()
        AppLog.i(APP, "Started: provider=${repository.providerName} appleAuth=${BuildConfig.ENABLE_APPLE_AUTH} " +
            "applePlayback=${BuildConfig.ENABLE_APPLE_PLAYBACK} debugMenu=${BuildConfig.ENABLE_DEBUG_MENU}")
    }
}
