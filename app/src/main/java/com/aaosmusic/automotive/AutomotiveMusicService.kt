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
import kotlinx.coroutines.cancel

/**
 * Owns the player and the MediaLibrarySession. AAOS Media Center binds to this service directly,
 * so playback lives here and is independent of any Activity.
 */
class AutomotiveMusicService : MediaLibraryService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null

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
        val librarySession = MediaLibrarySession.Builder(this, player, AutomotiveLibraryCallback(tree, scope, sessionActivity))
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(PlaybackButtons.forPlayer(player))
            .build()
        player.addListener(PlayerLogger(librarySession))
        session = librarySession
        app.appleTokens.addListener(onAccountChanged)
    }

    /** New sign-in or sign-out: drop cached data and make AAOS re-browse from the root. */
    private val onAccountChanged: () -> Unit = {
        val app = application as AAOSMusicApp
        app.repository.clearCache()
        session?.let { s ->
            AppLog.i(MEDIA_SESSION, "Account changed; refreshing library")
            listOf(MediaIds.ROOT, MediaIds.HOME, MediaIds.LIBRARY).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
        }
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
        (application as AAOSMusicApp).appleTokens.removeListener(onAccountChanged)
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
        playlists = getString(R.string.browse_playlists),
        artists = getString(R.string.browse_artists),
        albums = getString(R.string.browse_albums),
        songs = getString(R.string.browse_songs),
    )

    /** Logs playback transitions and keeps the shuffle/repeat button icons current. */
    private class PlayerLogger(private val session: MediaLibrarySession) : Player.Listener {
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
            session.setMediaButtonPreferences(PlaybackButtons.forPlayer(session.player))
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            AppLog.i(PLAYBACK, "Repeat mode: $repeatMode")
            session.setMediaButtonPreferences(PlaybackButtons.forPlayer(session.player))
        }
    }
}
