package com.aaosmusic.automotive

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.aaosmusic.media.MusicProvider
import com.aaosmusic.media.models.Rating
import com.aaosmusic.media.models.Song
import com.aaosmusic.media.models.SongLibraryState
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.PLAYBACK
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Now Playing custom actions: Love, Add to Library, shuffle, repeat, Dislike and Add Album.
 *
 * Loads the playing song's rating and library state on every track change and keeps the session's
 * media button preferences current. Rating/library changes update the buttons right away and roll
 * back if the provider call fails.
 */
class NowPlayingButtons(
    private val music: MusicProvider,
    private val scope: CoroutineScope,
) : Player.Listener {

    /** Set once the session is built; button changes are pushed to it. */
    var session: MediaLibrarySession? = null

    private var song: Song? = null
    /** Null while the provider can't edit the library (or hasn't said so yet): those buttons are hidden. */
    private var state: SongLibraryState? = null
    private var loadJob: Job? = null

    fun current(player: Player): ImmutableList<CommandButton> = build(player, state)

    /** Reloads the playing song's state, e.g. after the signed-in account changed. */
    fun reload() = load(session?.player?.currentMediaItem)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = load(mediaItem)
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
    override fun onRepeatModeChanged(repeatMode: Int) = publish()

    /** Handles [action] if it's one of these buttons; false otherwise. */
    fun handle(player: Player, action: String): Boolean {
        val s = state
        when (action) {
            ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            ACTION_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            ACTION_LOVE -> if (s != null) rate(if (s.rating == Rating.LOVE) Rating.NONE else Rating.LOVE)
            ACTION_DISLIKE -> if (s != null) rate(if (s.rating == Rating.DISLIKE) Rating.NONE else Rating.DISLIKE)
            // The Apple Music API can't remove from the library, so "In library" taps do nothing.
            ACTION_ADD_SONG -> if (s != null && !s.songInLibrary) {
                change("add song", s.copy(songInLibrary = true), libraryChanged = true) { music.addSongToLibrary(it) }
            }
            ACTION_ADD_ALBUM -> if (s != null && s.albumInLibrary == false) {
                change("add album", s.copy(songInLibrary = true, albumInLibrary = true), libraryChanged = true) { music.addAlbumToLibrary(it) }
            }
            else -> return false
        }
        // Shuffle/repeat icons are refreshed by this listener's player callbacks.
        return true
    }

    private fun rate(rating: Rating) = state?.let { s -> change("rate $rating", s.copy(rating = rating)) { music.setRating(it, rating) } }

    private fun load(item: MediaItem?) {
        loadJob?.cancel()
        song = null
        // Keep the buttons in place (defaults) while the new song's state loads, so they don't jump.
        state = state?.let { SongLibraryState() }
        publish()
        val id = (item?.mediaId?.let(MediaIds::parse) as? MediaIds.Parsed.SongId)?.id ?: return
        loadJob = scope.launch {
            try {
                val loaded = music.getSong(id) ?: return@launch
                song = loaded
                state = music.getLibraryState(loaded)
                AppLog.d(PLAYBACK, "Library state of '${loaded.title}': $state")
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(PLAYBACK, "Couldn't load rating/library state for $id: ${e.message}")
            }
        }
    }

    /** Shows [optimistic] right away, runs [call], and restores the previous state if it fails. */
    private fun change(what: String, optimistic: SongLibraryState, libraryChanged: Boolean = false, call: suspend (Song) -> Unit) {
        val target = song ?: return
        val before = state ?: return
        state = optimistic
        publish()
        scope.launch {
            try {
                call(target)
                if (libraryChanged) session?.let { s ->
                    listOf(MediaIds.SONGS, MediaIds.ALBUMS, MediaIds.ARTISTS).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(PLAYBACK, "Couldn't $what for '${target.title}': ${e.message}")
                if (song === target) {
                    state = before
                    publish()
                }
            }
        }
    }

    private fun publish() {
        session?.let { it.setMediaButtonPreferences(build(it.player, state)) }
    }

    private fun build(player: Player, library: SongLibraryState?): ImmutableList<CommandButton> {
        val buttons = ImmutableList.builder<CommandButton>()
        if (library != null) {
            val loved = library.rating == Rating.LOVE
            buttons.add(button(if (loved) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED, if (loved) "Loved" else "Love", ACTION_LOVE))
            buttons.add(
                if (library.songInLibrary) button(CommandButton.ICON_CHECK_CIRCLE_FILLED, "In library", ACTION_ADD_SONG)
                else button(CommandButton.ICON_PLUS_CIRCLE_UNFILLED, "Add to library", ACTION_ADD_SONG),
            )
        }
        buttons.add(
            button(
                if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
                if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off",
                ACTION_TOGGLE_SHUFFLE,
            ),
        )
        buttons.add(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> button(CommandButton.ICON_REPEAT_ONE, "Repeat one", ACTION_CYCLE_REPEAT)
                Player.REPEAT_MODE_ALL -> button(CommandButton.ICON_REPEAT_ALL, "Repeat all", ACTION_CYCLE_REPEAT)
                else -> button(CommandButton.ICON_REPEAT_OFF, "Repeat off", ACTION_CYCLE_REPEAT)
            },
        )
        if (library != null) {
            val disliked = library.rating == Rating.DISLIKE
            buttons.add(button(if (disliked) CommandButton.ICON_THUMB_DOWN_FILLED else CommandButton.ICON_THUMB_DOWN_UNFILLED, if (disliked) "Disliked" else "Dislike", ACTION_DISLIKE))
            // Last, so it can disappear once the album is added without moving the other buttons.
            if (library.albumInLibrary == false) buttons.add(button(CommandButton.ICON_PLAYLIST_ADD, "Add album to library", ACTION_ADD_ALBUM))
        }
        return buttons.build()
    }

    private fun button(icon: Int, name: String, action: String) = CommandButton.Builder(icon)
        .setDisplayName(name)
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

    companion object {
        const val ACTION_TOGGLE_SHUFFLE = "com.aaosmusic.TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "com.aaosmusic.CYCLE_REPEAT"
        const val ACTION_LOVE = "com.aaosmusic.LOVE"
        const val ACTION_DISLIKE = "com.aaosmusic.DISLIKE"
        const val ACTION_ADD_SONG = "com.aaosmusic.ADD_SONG"
        const val ACTION_ADD_ALBUM = "com.aaosmusic.ADD_ALBUM"

        private val ACTIONS = listOf(ACTION_TOGGLE_SHUFFLE, ACTION_CYCLE_REPEAT, ACTION_LOVE, ACTION_DISLIKE, ACTION_ADD_SONG, ACTION_ADD_ALBUM)

        /** [base] plus a session command for every button. */
        fun withCommands(base: SessionCommands): SessionCommands =
            base.buildUpon().apply { ACTIONS.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }.build()
    }
}
