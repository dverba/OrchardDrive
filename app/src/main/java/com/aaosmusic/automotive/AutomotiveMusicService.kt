package com.aaosmusic.automotive

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.aaosmusic.AAOSMusicApp
import com.aaosmusic.R
import com.aaosmusic.ui.MainActivity
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.MEDIA_SESSION
import com.aaosmusic.util.AppLog.Category.PLAYBACK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the player and the MediaLibrarySession. AAOS Media Center binds to this service directly,
 * so playback lives here and is independent of any Activity.
 */
class AutomotiveMusicService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private lateinit var buttons: NowPlayingButtons
    private lateinit var lastQueue: LastQueueStore
    private lateinit var layout: BrowseLayoutStore
    private lateinit var prefetcher: ArtworkPrefetcher
    private var prefetchJob: Job? = null
    @Volatile private var buffering = false
    private var saveJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AAOSMusicApp
        AppLog.i(MEDIA_SESSION, "Service created (provider: ${app.repository.providerName})")

        val player = app.playbackProvider.createPlayer(this)
        val tree = LibraryTree(app.repository, labels())
        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        lastQueue = LastQueueStore(this)
        layout = BrowseLayoutStore(this)
        buttons = NowPlayingButtons(app.repository, scope)
        val librarySession = MediaLibrarySession.Builder(this, player, AutomotiveLibraryCallback(tree, scope, sessionActivity, lastQueue, layout, buttons))
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(buttons.current(player))
            .build()
        buttons.session = librarySession
        player.addListener(buttons)
        player.addListener(PlayerLogger())
        player.addListener(QueueSaver())
        player.addListener(StaleProgressClearer(librarySession))
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
        })
        prefetcher = ArtworkPrefetcher(app.repository, ArtworkCache(this)) { buffering }
        session = librarySession
        app.appleTokens.addListener(onAccountChanged)
        layout.addListener(onLayoutChanged)

        // Show the last song in AAOS right away (not playing; Play resumes from the saved position).
        lastQueue.restore()?.let {
            player.setMediaItems(it.items, it.index, it.positionMs)
            AppLog.i(PLAYBACK, "Restored last queue: ${it.items.size} items, index ${it.index}, ${it.positionMs / 1000}s")
        }
        warmUp()
    }

    /** Preload the provider's library, then its artwork, in the background so browsing is fast. */
    private fun warmUp() {
        val repo = (application as AAOSMusicApp).repository
        if (!repo.requiresSignIn()) scope.launch {
            runCatching { repo.warmUp() }
            prefetchArtwork()
        }
    }

    /** (Re)starts the artwork prefetch, e.g. after the tile/list setting changes the sizes needed. */
    private fun prefetchArtwork() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            runCatching { prefetcher.run(layout.grid) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) AppLog.w(MEDIA_SESSION, "Artwork prefetch failed: ${it.message}") }
        }
    }

    /** Saves the queue on track changes and pauses, and every 15 s while playing. */
    private inner class QueueSaver : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = save()
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = save()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            save()
            saveJob?.cancel()
            if (isPlaying) saveJob = scope.launch {
                while (true) { delay(15_000); save() }
            }
        }

        private fun save() { session?.player?.let(lastQueue::save) }
    }

    /** New sign-in or sign-out: drop cached data and make AAOS re-browse from the root. */
    private val onAccountChanged: () -> Unit = {
        val app = application as AAOSMusicApp
        app.repository.clearCache()
        session?.let { s ->
            AppLog.i(MEDIA_SESSION, "Account changed; refreshing library")
            listOf(MediaIds.ROOT, MediaIds.HOME, MediaIds.LIBRARY).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
        }
        buttons.reload()
        if (app.appleTokens.userToken == null) {
            prefetchJob?.cancel()
            lastQueue.clear()
        } else {
            warmUp()
        }
    }

    /** Tiles/list switched: every folder's layout hint changed, so make AAOS re-fetch them all. */
    private val onLayoutChanged: () -> Unit = {
        session?.let { s ->
            AppLog.i(MEDIA_SESSION, "Browse layout changed (grid: ${layout.grid}); refreshing library")
            MediaIds.FIXED.forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
        }
        if (!(application as AAOSMusicApp).repository.requiresSignIn()) prefetchArtwork()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * The app's task was swiped away. Keep playing if we're playing (playback must not depend on
     * the Activity); otherwise there's no reason to keep the service around.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            AppLog.i(MEDIA_SESSION, "Task removed while idle; stopping service")
            stopSelf()
        } else {
            AppLog.i(MEDIA_SESSION, "Task removed; continuing playback")
        }
    }

    override fun onDestroy() {
        AppLog.i(MEDIA_SESSION, "Service destroyed")
        session?.player?.let(lastQueue::save)
        (application as AAOSMusicApp).appleTokens.removeListener(onAccountChanged)
        layout.removeListener(onLayoutChanged)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun labels() = LibraryTree.Labels(
        appName = getString(R.string.app_name),
        home = getString(R.string.browse_home),
        library = getString(R.string.browse_library),
        recent = getString(R.string.browse_recent),
        recommended = getString(R.string.browse_recommended),
        madeForYou = getString(R.string.browse_made_for_you),
        heavyRotation = getString(R.string.browse_heavy_rotation),
        recentlyAdded = getString(R.string.browse_recently_added),
        charts = getString(R.string.browse_charts),
        albumsAndPlaylists = getString(R.string.browse_albums_and_playlists),
        playlists = getString(R.string.browse_playlists),
        artists = getString(R.string.browse_artists),
        albums = getString(R.string.browse_albums),
        songs = getString(R.string.browse_songs),
    )

    /**
     * Media Center draws a progress bar on the browse row of the playing song but never removes it
     * when the song changes, so every song played from a list kept a frozen bar (looking like
     * several songs playing at once). Re-sending the previous song's list makes it redraw the rows.
     */
    private class StaleProgressClearer(private val session: MediaLibrarySession) : Player.Listener {
        private var previousId: String? = null

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val previous = previousId
            previousId = mediaItem?.mediaId
            if (previous == null || previous == mediaItem?.mediaId) return
            val list = (MediaIds.parse(previous) as? MediaIds.Parsed.SongId)?.context ?: return
            session.notifyChildrenChanged(list, Int.MAX_VALUE, null)
        }
    }

    /** Logs playback transitions. */
    private class PlayerLogger : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val m = mediaItem?.mediaMetadata
            AppLog.i(PLAYBACK, "Now playing: ${mediaItem?.mediaId} '${m?.title}' by '${m?.artist}' (reason $reason)")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val name = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> playbackState.toString()
            }
            AppLog.d(PLAYBACK, "State: $name")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLog.d(PLAYBACK, if (isPlaying) "Playing" else "Not playing")
        }

        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(PLAYBACK, "Playback error ${error.errorCodeName}", error)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            AppLog.i(PLAYBACK, "Shuffle: $shuffleModeEnabled")
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            AppLog.i(PLAYBACK, "Repeat mode: $repeatMode")
        }
    }
}
