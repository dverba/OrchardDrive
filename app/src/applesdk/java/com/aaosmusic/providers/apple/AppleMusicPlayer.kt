package com.aaosmusic.providers.apple

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.APPLE_PLAYER
import com.apple.android.music.playback.controller.MediaPlayerController
import com.apple.android.music.playback.model.MediaItemType
import com.apple.android.music.playback.model.MediaPlayerException
import com.apple.android.music.playback.model.PlaybackShuffleMode
import com.apple.android.music.playback.model.PlaybackState
import com.apple.android.music.playback.model.PlayerQueueItem
import com.apple.android.music.playback.queue.CatalogPlaybackQueueItemProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media3 [Player] backed by MusicKit's [MediaPlayerController], so the MediaSession (and through it
 * AAOS, media buttons and the notification) drive Apple Music playback directly.
 *
 * Ownership: this class owns the playlist (the MediaItems the session set, each with an
 * `applemusic:song:<catalogId>` URI) and mirrors it into MusicKit's queue. MusicKit owns actual
 * playback state, position, audio focus and DRM; every MusicKit callback just invalidates the
 * Media3 state, which is rebuilt from the controller in [getState].
 *
 * MusicKit requests audio focus itself, so nothing else may (ExoPlayer is not involved here).
 */
@UnstableApi
class AppleMusicPlayer(
    context: Context,
    private val tokens: AppleMusicTokenStore,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val appContext = context.applicationContext
    private val listener = Listener()

    /**
     * MusicKit opens an AudioTrack when the controller is created, which throws if audioserver is
     * unavailable (e.g. restarting). So creation is lazy and retried on the next prepare/play
     * instead of crashing the media service.
     */
    private var controllerOrNull: MediaPlayerController? = null

    private var playlist: List<MediaItem> = emptyList()
    private var currentIndex = 0
    private var playWhenReady = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var shuffle = false
    /**
     * Shuffle is done here rather than by MusicKit: MusicKit's shuffled order isn't reliably
     * observable, so its queue would diverge from the Media3 timeline AAOS shows. With shuffle on,
     * [playlist] holds the shuffled order (current song first) and this holds the original order.
     */
    private var unshuffled: List<MediaItem>? = null
    private var prepareRequested = false
    /** MusicKit's queue matches [playlist]. */
    private var queueLoaded = false
    private var pendingSeekMs = 0L
    private var ended = false
    private var error: PlaybackException? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** MusicKit caches the user token when the controller is created, so a new sign-in needs a new controller. */
    private val onTokenChanged: () -> Unit = {
        mainHandler.post {
            AppLog.i(APPLE_PLAYER, "User token changed; recreating MusicKit controller")
            controllerOrNull?.let { it.removeListener(listener); it.release() }
            controllerOrNull = null
            queueLoaded = false
            playWhenReady = false
            error = null
            invalidateState()
        }
    }

    init {
        AppleSdkBridgeImpl.loadNativeLibraries()
        tokens.addListener(onTokenChanged)

        // Signed out (or developer token not fetched yet) at startup is not an error until someone tries to play.
        if (tokens.signedIn && tokens.developerToken != null) ensureController()
    }

    private fun ensureController(): MediaPlayerController? = controllerOrNull ?: if (!tokens.signedIn) {
        val message = if (tokens.userTokenRejected) "Your Apple Music sign-in has expired. Sign in again to play." else "Sign in to Apple Music to play"
        error = PlaybackException(message, null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED)
        null
    } else if (tokens.developerToken == null) {
        // Fetched from the relay at startup; the token listener recreates the controller when it arrives.
        error = PlaybackException("Apple Music isn't ready yet; check the internet connection", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        null
    } else try {
        AppleSdkBridgeImpl.createController(appContext, Handler(Looper.getMainLooper()), tokens).also {
            it.addListener(listener)
            controllerOrNull = it
            if (error?.errorCode in RECOVERABLE_ERRORS) error = null
            AppLog.i(APPLE_PLAYER, "MusicKit controller created")
        }
    } catch (e: Exception) {
        AppLog.e(APPLE_PLAYER, "MusicKit controller creation failed; will retry", e)
        error = PlaybackException("Audio output unavailable", e, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED)
        null
    }

    override fun getState(): State {
        val controller = controllerOrNull
        val duration = controller?.duration ?: 0
        // uid = mediaId + occurrence, so shuffling is a reorder of the same items, not new items.
        val seen = HashMap<String, Int>()
        val items = playlist.mapIndexed { i, item ->
            val durationMs = if (i == currentIndex && duration > 0) duration else item.mediaMetadata.durationMs
            val occurrence = seen.merge(item.mediaId, 1, Int::plus)!!
            MediaItemData.Builder("${item.mediaId}#$occurrence")
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setDurationUs(durationMs?.let { it * 1000 } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        val playbackState = when {
            items.isEmpty() || !prepareRequested || error != null -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            controller == null || !queueLoaded || controller.isBuffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else currentIndex.coerceIn(0, items.lastIndex))
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlayerError(error)
            .setContentPositionMs { if (queueLoaded) controller?.currentPosition?.coerceAtLeast(0) ?: 0 else pendingSeekMs }
            .setContentBufferedPositionMs { if (queueLoaded) controller?.bufferedPosition?.coerceAtLeast(0) ?: 0 else 0 }
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffle)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .build()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val start = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        if (shuffle) {
            unshuffled = mediaItems
            playlist = shuffledAround(mediaItems, start)
            currentIndex = 0
        } else {
            unshuffled = null
            playlist = mediaItems
            currentIndex = start
        }
        pendingSeekMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        queueLoaded = false
        ended = false
        if (prepareRequested) loadQueue()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepareRequested = true
        error = null
        // AAOS prepares an empty player when Media Center connects; that's not a request to play,
        // so it mustn't surface "sign in" / "not ready" player errors.
        if (playlist.isEmpty()) return done()
        if (ensureController() == null) return done()
        if (!queueLoaded) loadQueue()
        return done()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        val controller = ensureController() ?: return done()
        when {
            !playWhenReady -> if (queueLoaded) controller.pause()
            ended -> { currentIndex = 0; pendingSeekMs = 0; loadQueue() }
            queueLoaded -> controller.play()
            prepareRequested -> loadQueue()
        }
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        ended = false
        val controller = controllerOrNull
        when {
            controller == null || !queueLoaded || mediaItemIndex !in playlist.indices -> {
                currentIndex = mediaItemIndex.coerceAtLeast(0)
                pendingSeekMs = position
                if (prepareRequested) loadQueue()
            }
            mediaItemIndex == currentIndex -> controller.seekToPosition(position)
            seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                currentIndex = mediaItemIndex
                controller.skipToNextItem()
            }
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                currentIndex = mediaItemIndex
                controller.skipToPreviousItem()
            }
            else -> {
                // Jump to an arbitrary queue entry (e.g. tapped in AAOS's queue view).
                val target = appleQueueItemFor(mediaItemIndex)
                currentIndex = mediaItemIndex
                if (target != null) {
                    controller.skipToQueueItemWithId(target.playbackQueueId)
                    if (position > 0) pendingSeekMs = position
                } else {
                    pendingSeekMs = position
                    loadQueue()
                }
            }
        }
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        // MusicKit's PlaybackRepeatMode constants equal Media3's REPEAT_MODE_* values.
        controllerOrNull?.setRepeatMode(repeatMode)
        return done()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffle == shuffleModeEnabled) return done()
        shuffle = shuffleModeEnabled
        val current = playlist.getOrNull(currentIndex)
        if (shuffleModeEnabled) {
            unshuffled = playlist
            playlist = shuffledAround(playlist, currentIndex)
            currentIndex = 0
        } else {
            unshuffled?.takeIf { it.size == playlist.size }?.let { original ->
                playlist = original
                currentIndex = original.indexOf(current).coerceAtLeast(0)
            }
            unshuffled = null
        }
        AppLog.i(APPLE_PLAYER, "Shuffle ${if (shuffleModeEnabled) "on" else "off"}: reordered ${playlist.size} items")
        reloadKeepingPosition()
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        unshuffled = null // queue edited while shuffled: keep the current order when shuffle turns off
        playlist = playlist.toMutableList().apply { addAll(index, mediaItems) }
        if (index <= currentIndex && playlist.size > mediaItems.size) currentIndex += mediaItems.size
        reloadKeepingPosition()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        unshuffled = null
        val removingCurrent = currentIndex in fromIndex until toIndex
        playlist = playlist.toMutableList().apply { subList(fromIndex, toIndex).clear() }
        when {
            playlist.isEmpty() -> { currentIndex = 0; controllerOrNull?.stop(); queueLoaded = false }
            removingCurrent -> { currentIndex = fromIndex.coerceAtMost(playlist.lastIndex); pendingSeekMs = 0; if (prepareRequested) loadQueue() }
            else -> { if (currentIndex >= toIndex) currentIndex -= toIndex - fromIndex; reloadKeepingPosition() }
        }
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        controllerOrNull?.stop()
        prepareRequested = false
        queueLoaded = false
        playWhenReady = false
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        tokens.removeListener(onTokenChanged)
        controllerOrNull?.release()
        controllerOrNull = null
        return done()
    }

    /** Replaces MusicKit's queue with [playlist], starting at [currentIndex]. */
    private fun loadQueue() {
        val controller = ensureController() ?: return invalidateState()
        val ids = playlist.map { catalogId(it) }
        // AAOS prepares an empty player on connect; nothing to load until items arrive.
        if (ids.isEmpty()) return
        if (ids.any { it == null }) {
            error = PlaybackException("Queue contains items without an Apple Music catalog id", null, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)
            invalidateState()
            return
        }
        val queue = CatalogPlaybackQueueItemProvider.Builder()
            .items(MediaItemType.SONG, *ids.requireNoNulls().toTypedArray())
            .startItemIndex(currentIndex)
            .build()
        AppLog.i(APPLE_PLAYER, "Loading MusicKit queue: ${ids.size} items, start $currentIndex, playWhenReady=$playWhenReady")
        // Re-preparing over a loaded queue and then seeking stalls MusicKit; start from a clean stop.
        if (queueLoaded) controller.stop()
        error = null
        ended = false
        // Seeking while MusicKit is already auto-playing a fresh queue stalls it (position freezes while
        // it reports PLAYING). With a position to restore, load paused; applyPendingSeek() then seeks
        // and resumeIfWanted() starts playback.
        controller.prepare(queue, playWhenReady && pendingSeekMs == 0L)
        controller.setShuffleMode(PlaybackShuffleMode.SHUFFLE_MODE_OFF)
        controller.setRepeatMode(repeatMode)
        queueLoaded = true
    }

    private fun reloadKeepingPosition() {
        val controller = controllerOrNull
        if (!queueLoaded || controller == null) return
        pendingSeekMs = controller.currentPosition.coerceAtLeast(0)
        loadQueue()
    }

    private fun appleQueueItemFor(index: Int): PlayerQueueItem? {
        val id = catalogId(playlist[index]) ?: return null
        return controllerOrNull?.queueItems?.firstOrNull { it.item.subscriptionStoreId == id }
    }

    /** Re-derives [currentIndex] from MusicKit's current item (MusicKit may have advanced or shuffled). */
    private fun syncCurrentIndex(item: PlayerQueueItem?) {
        val id = item?.item?.subscriptionStoreId ?: return
        if (catalogId(playlist.getOrNull(currentIndex)) == id) return
        // Prefer the next match after the current index so repeated songs resolve sensibly.
        val after = playlist.indices.drop(currentIndex + 1).firstOrNull { catalogId(playlist[it]) == id }
        val index = after ?: playlist.indexOfFirst { catalogId(it) == id }
        if (index >= 0) currentIndex = index
    }

    /** [items] with the item at [keepFirst] moved to the front and the rest shuffled. */
    private fun shuffledAround(items: List<MediaItem>, keepFirst: Int): List<MediaItem> {
        if (items.isEmpty()) return items
        val first = items[keepFirst.coerceIn(0, items.lastIndex)]
        return listOf(first) + items.filterIndexed { i, _ -> i != keepFirst }.shuffled()
    }

    private fun catalogId(item: MediaItem?): String? =
        AppleMusicJson.catalogIdFromPlaybackUri(item?.localConfiguration?.uri?.toString())

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private inner class Listener : MediaPlayerController.Listener {
        override fun onPlayerStateRestored(c: MediaPlayerController) = invalidateState()

        override fun onPlaybackStateChanged(c: MediaPlayerController, old: Int, new: Int) {
            when {
                new == PlaybackState.PLAYING -> playWhenReady = true
                // Only a PLAYING → PAUSED transition is an external pause (audio-focus loss, MusicKit
                // itself); STOPPED → PAUSED happens while a new queue loads and must not clear intent.
                new == PlaybackState.PAUSED && old == PlaybackState.PLAYING -> playWhenReady = false
            }
            invalidateState()
        }

        override fun onPlaybackStateUpdated(c: MediaPlayerController) = invalidateState()

        override fun onBufferingStateChanged(c: MediaPlayerController, buffering: Boolean) {
            if (!buffering) {
                applyPendingSeek(c)
                resumeIfWanted(c)
            }
            invalidateState()
        }

        /** Restores a position carried across a queue (re)load: resume after restart, shuffle toggle, queue edit. */
        private fun applyPendingSeek(c: MediaPlayerController) {
            if (pendingSeekMs > 0 && queueLoaded && c.currentItem != null) {
                AppLog.d(APPLE_PLAYER, "Restoring position ${pendingSeekMs / 1000}s")
                c.seekToPosition(pendingSeekMs)
                pendingSeekMs = 0
            }
        }

        override fun onCurrentItemChanged(c: MediaPlayerController, prev: PlayerQueueItem?, cur: PlayerQueueItem?) {
            syncCurrentIndex(cur)
            if (!c.isBuffering) applyPendingSeek(c)
            resumeIfWanted(c)
            invalidateState()
        }

        /** MusicKit ignores play() while a queue is still loading; re-issue it once it can take effect. */
        private fun resumeIfWanted(c: MediaPlayerController) {
            if (playWhenReady && queueLoaded && error == null && !ended && c.playbackState != PlaybackState.PLAYING) c.play()
        }

        override fun onItemEnded(c: MediaPlayerController, item: PlayerQueueItem, position: Long) {
            if (repeatMode == Player.REPEAT_MODE_OFF && !c.canSkipToNextItem()) {
                ended = true
                playWhenReady = false
            }
            invalidateState()
        }

        override fun onMetadataUpdated(c: MediaPlayerController, item: PlayerQueueItem) = invalidateState()
        override fun onPlaybackQueueChanged(c: MediaPlayerController, items: List<PlayerQueueItem>) = invalidateState()
        override fun onPlaybackQueueItemsAdded(c: MediaPlayerController, a: Int, b: Int, d: Int) = invalidateState()

        override fun onPlaybackError(c: MediaPlayerController, e: MediaPlayerException) {
            val code = when (e.type) {
                MediaPlayerException.TYPE_DRM -> PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
                MediaPlayerException.TYPE_IO -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                MediaPlayerException.TYPE_PLAYBACK_LEASE -> PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
                else -> PlaybackException.ERROR_CODE_UNSPECIFIED
            }
            AppLog.e(APPLE_PLAYER, "MusicKit error type=${e.type} code=${e.errorCode} component=${e.componentErrorCode}", e)
            error = PlaybackException("Apple Music playback failed (type ${e.type}, code ${e.errorCode})", e, code)
            playWhenReady = false
            queueLoaded = false
            invalidateState()
        }

        override fun onPlaybackRepeatModeChanged(c: MediaPlayerController, mode: Int) = invalidateState()
        override fun onPlaybackShuffleModeChanged(c: MediaPlayerController, mode: Int) = invalidateState()
    }

    private companion object {
        val RECOVERABLE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
        ).build()

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
