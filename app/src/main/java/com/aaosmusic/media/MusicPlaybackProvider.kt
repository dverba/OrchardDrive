package com.aaosmusic.media

import android.content.Context
import androidx.media3.common.Player

/**
 * Supplies the [Player] the MediaSession wraps.
 *
 * Playback is modelled as a Media3 [Player] rather than a bespoke play/pause interface so the
 * MediaSession stays the single source of truth: AAOS controls, media buttons and the
 * notification all drive the Player directly, and the Player's state is what AAOS displays.
 * The fake/test implementation is ExoPlayer; a future Apple Music implementation would be a
 * `SimpleBasePlayer` wrapping MusicKit's player, mapping its state into Player.State.
 */
interface MusicPlaybackProvider {
    fun createPlayer(context: Context): Player
}
