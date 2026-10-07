package com.aaosmusic.repository

import com.aaosmusic.media.MusicProvider

/**
 * Single entry point the AAOS layer uses for music data. Today it delegates straight to the
 * active provider; it is the place caching (artwork, library structure, recently played) will
 * go once a network-backed provider exists.
 */
class MusicRepository(
    private val provider: MusicProvider,
    val providerName: String,
) : MusicProvider by provider
