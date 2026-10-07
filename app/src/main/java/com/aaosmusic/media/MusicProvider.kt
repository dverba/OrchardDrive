package com.aaosmusic.media

import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.MusicItem
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.SearchResults
import com.aaosmusic.media.models.Song

/**
 * Source of browsable/searchable music metadata. The AAOS layer only ever talks to this
 * interface (via MusicRepository), so swapping the fake provider for Apple Music changes
 * nothing above it.
 *
 * Lookups return null for unknown ids instead of throwing; transport/auth failures throw.
 */
interface MusicProvider {
    suspend fun search(query: String): SearchResults

    suspend fun getRecentlyPlayed(): List<Song>

    /** Mixed albums/playlists, roughly Apple Music's "Listen Now". */
    suspend fun getRecommendations(): List<MusicItem>

    suspend fun getAlbums(): List<Album>
    suspend fun getArtists(): List<Artist>
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getSongs(): List<Song>

    /** Returned album includes its songs. */
    suspend fun getAlbum(id: String): Album?
    suspend fun getArtist(id: String): Artist?
    suspend fun getArtistAlbums(artistId: String): List<Album>

    /** Returned playlist includes its songs. */
    suspend fun getPlaylist(id: String): Playlist?
    suspend fun getSong(id: String): Song?
}
