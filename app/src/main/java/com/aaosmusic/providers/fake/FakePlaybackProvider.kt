package com.aaosmusic.providers.fake

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aaosmusic.media.MusicPlaybackProvider

/** Plain ExoPlayer streaming the fake provider's `playbackUri`s. */
class FakePlaybackProvider : MusicPlaybackProvider {
    override fun createPlayer(context: Context): Player {
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context)
            // Lets ExoPlayer request/abandon audio focus itself, so another media app starting
            // (or a nav prompt) pauses/ducks us the way AAOS expects.
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}
