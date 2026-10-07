package com.aaosmusic.providers.apple

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.aaosmusic.media.MusicPlaybackProvider
import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.controller.MediaPlayerControllerFactory
import com.apple.android.music.playback.model.MediaItemType
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider
import com.apple.android.sdk.authentication.AuthenticationFactory
import com.apple.android.sdk.authentication.TokenProvider

internal object AppleSdkBridgeImpl : AppleSdkBridge {
    override val available = true

    override fun signInIntent(context: Context, developerToken: String): Intent =
        AuthenticationFactory.createAuthenticationManager(context)
            .createIntentBuilder(developerToken)
            .setHideStartScreen(false)
            .setStartScreenMessage("Connect Apple Music to Apple Music Test")
            .build()

    override fun parseSignInResult(context: Context, data: Intent?): Result<String> {
        val result = AuthenticationFactory.createAuthenticationManager(context).handleTokenResult(data)
        return if (result.isError) Result.failure(IllegalStateException("TokenError.${result.error}"))
        else Result.success(result.musicUserToken)
    }

    /** The SDK doesn't load its own native code; the host app must, before creating a controller. */
    private val nativeLibs by lazy {
        System.loadLibrary("c++_shared")
        System.loadLibrary("appleMusicSDK")
    }

    fun loadNativeLibraries() = nativeLibs

    /**
     * The SDK only requests the 256 kbps stream (flavor "LWHQ") on Wi-Fi; on any other network it caps at
     * 128 kbps ("LW") unless this undocumented preference is set. The car is usually on its cellular modem.
     */
    private fun preferHighQualityStreams(context: Context) {
        context.applicationContext
            .getSharedPreferences("com.apple.android.music.playback_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean("high_quality_on_cellular_enabled", true).apply()
    }

    /** Controller whose tokens are read live from [tokens], so a new sign-in takes effect without recreating it. */
    fun createController(context: Context, handler: Handler, tokens: AppleMusicTokenStore): MediaPlayerController {
        preferHighQualityStreams(context)
        return MediaPlayerControllerFactory.createLocalController(context.applicationContext, handler, object : TokenProvider {
            override fun getDeveloperToken() = tokens.developerToken.orEmpty()
            override fun getUserToken() = tokens.userToken.orEmpty()
        })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun playbackProvider(tokens: AppleMusicTokenStore): MusicPlaybackProvider = object : MusicPlaybackProvider {
        override fun createPlayer(context: Context): Player = AppleMusicPlayer(context, tokens)
    }

    override fun createPlayer(context: Context, developerToken: String, userToken: String, log: (String) -> Unit): ApplePlayerHandle {
        nativeLibs
        log("native libraries loaded")
        val tokens = object : TokenProvider {
            override fun getDeveloperToken() = developerToken
            override fun getUserToken() = userToken
        }
        preferHighQualityStreams(context)
        val controller = MediaPlayerControllerFactory.createLocalController(
            context.applicationContext, Handler(Looper.getMainLooper()), tokens,
        )
        controller.addListener(LoggingListener(log))
        log("MediaPlayerController created")
        return object : ApplePlayerHandle {
            override fun playSong(catalogId: String) {
                val queue = CatalogPlaybackQueueItemProvider.Builder().items(MediaItemType.SONG, catalogId).build()
                controller.prepare(queue, true)
                log("prepare(song $catalogId, playWhenReady=true)")
            }

            override fun playUploaded(libraryId: String) {
                val queue = CatalogPlaybackQueueItemProvider.Builder().items(MediaItemType.UPLOADED_AUDIO, libraryId).build()
                controller.prepare(queue, true)
                log("prepare(uploaded $libraryId, playWhenReady=true)")
            }

            override fun togglePause() {
                if (controller.playbackState == PlaybackState.PLAYING) controller.pause() else controller.play()
            }

            override fun release() = controller.release()
        }
    }

    private class LoggingListener(private val log: (String) -> Unit) : MediaPlayerController.Listener {
        private fun state(s: Int) = when (s) {
            PlaybackState.PLAYING -> "PLAYING"
            PlaybackState.PAUSED -> "PAUSED"
            PlaybackState.STOPPED -> "STOPPED"
            else -> s.toString()
        }

        private fun PlayerQueueItem?.label() = this?.item?.let { "'${it.title}' by ${it.artistName}" } ?: "-"

        override fun onPlayerStateRestored(c: MediaPlayerController) = log("state restored")
        override fun onPlaybackStateChanged(c: MediaPlayerController, old: Int, new: Int) =
            log("state ${state(old)} → ${state(new)} @ ${c.currentPosition}ms/${c.duration}ms")
        override fun onPlaybackStateUpdated(c: MediaPlayerController) {}
        override fun onBufferingStateChanged(c: MediaPlayerController, buffering: Boolean) = log("buffering=$buffering")
        override fun onCurrentItemChanged(c: MediaPlayerController, prev: PlayerQueueItem?, cur: PlayerQueueItem?) =
            log("current item: ${cur.label()}")
        override fun onItemEnded(c: MediaPlayerController, item: PlayerQueueItem, position: Long) = log("item ended @ ${position}ms")
        override fun onMetadataUpdated(c: MediaPlayerController, item: PlayerQueueItem) = log("metadata: ${item.label()}")
        override fun onPlaybackQueueChanged(c: MediaPlayerController, items: List<PlayerQueueItem>) = log("queue: ${items.size} items")
        override fun onPlaybackQueueItemsAdded(c: MediaPlayerController, a: Int, b: Int, d: Int) {}
        override fun onPlaybackError(c: MediaPlayerController, e: MediaPlayerException) {
            val type = when (e.type) {
                MediaPlayerException.TYPE_IO -> "IO"
                MediaPlayerException.TYPE_DRM -> "DRM"
                MediaPlayerException.TYPE_PLAYBACK_QUEUE -> "QUEUE"
                MediaPlayerException.TYPE_PLAYBACK_LEASE -> "LEASE"
                MediaPlayerException.TYPE_EXPLICIT_VERIFY_AGE -> "EXPLICIT_VERIFY_AGE"
                else -> "UNKNOWN(${e.type})"
            }
            log("ERROR type=$type code=${e.errorCode} component=${e.componentErrorCode} ${e.cause ?: ""}")
        }
        override fun onPlaybackRepeatModeChanged(c: MediaPlayerController, mode: Int) {}
        override fun onPlaybackShuffleModeChanged(c: MediaPlayerController, mode: Int) {}
    }
}
