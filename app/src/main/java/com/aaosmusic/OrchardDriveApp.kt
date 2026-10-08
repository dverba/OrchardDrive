package com.aaosmusic

import android.app.Application
import android.content.ContentResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.aaosmusic.media.MusicPlaybackProvider
import com.aaosmusic.providers.apple.AppleMusicApiClient
import com.aaosmusic.providers.apple.AppleMusicProvider
import com.aaosmusic.providers.apple.AppleMusicTokenStore
import com.aaosmusic.providers.apple.appleSdkBridge
import com.aaosmusic.providers.fake.FakeMusicProvider
import com.aaosmusic.providers.fake.FakePlaybackProvider
import com.aaosmusic.repository.MusicRepository
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.DebugReport
import com.aaosmusic.util.AppLog.Category.APP

/** Wires providers from feature flags. The only place that knows which provider is active. */
class OrchardDriveApp : Application() {

    lateinit var repository: MusicRepository
        private set
    lateinit var playbackProvider: MusicPlaybackProvider
        private set
    lateinit var appleTokens: AppleMusicTokenStore
        private set

    /** Process-wide scope for background work not tied to a component (token refresh, prefetch). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        DebugReport.installCrashHandler(this)
        appleTokens = AppleMusicTokenStore(this)

        val wantApple = !BuildConfig.USE_FAKE_PROVIDER
        val applePlayback = if (wantApple && BuildConfig.ENABLE_APPLE_PLAYBACK) {
            appleSdkBridge().playbackProvider(appleTokens)
        } else null

        // Apple metadata is only useful with Apple playback (its songs aren't URI-playable).
        if (wantApple && applePlayback != null) {
            repository = MusicRepository(AppleMusicProvider(AppleMusicApiClient(appleTokens), appleTokens), providerName = "apple")
            playbackProvider = applePlayback
        } else {
            if (wantApple) {
                AppLog.w(APP, "Apple provider requested but unavailable (playback flag: ${BuildConfig.ENABLE_APPLE_PLAYBACK}, " +
                    "SDK: ${appleSdkBridge().available}); using fake")
            }
            val artworkBase = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/drawable/"
            repository = MusicRepository(FakeMusicProvider(artworkBase), providerName = "fake")
            playbackProvider = FakePlaybackProvider()
        }
        if (repository.providerName == "apple") appScope.launch { appleTokens.ensureDeveloperToken() }
        AppLog.i(APP, "Started: provider=${repository.providerName} appleAuth=${BuildConfig.ENABLE_APPLE_AUTH} " +
            "applePlayback=${BuildConfig.ENABLE_APPLE_PLAYBACK} debugMenu=${BuildConfig.ENABLE_DEBUG_MENU}")
    }
}
