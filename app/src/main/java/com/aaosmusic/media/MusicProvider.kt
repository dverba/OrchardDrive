package com.aaosmusic.media

import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.MusicItem
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.Rating
import com.aaosmusic.media.models.Shelf
import com.aaosmusic.media.models.SearchResults
import com.aaosmusic.media.models.Song
import com.aaosmusic.media.models.SongLibraryState

/**
 * Source of browsable/searchable music metadata. The AAOS layer only ever talks to this
 * interface (via MusicRepository), so swapping the fake provider for Apple Music changes
 * nothing above it.
 *
 * Lookups return null for unknown ids instead of throwing; transport/auth failures throw.
 */
interface MusicProvider {
    /** True when nothing personal can load until the user signs in (checked before browsing). */
    fun requiresSignIn(): Boolean = false

    /** What AAOS shows at the root while [requiresSignIn]. */
    fun signInMessage(): String = "Sign in to see your music"

    /** Optional: preload expensive data in the background (e.g. a large remote library). */
    suspend fun warmUp() {}

    suspend fun search(query: String): SearchResults

    suspend fun getRecentlyPlayed(): List<Song>

    /** Recently played albums and playlists (as opposed to [getRecentlyPlayed]'s songs). */
    suspend fun getRecentlyPlayedContainers(): List<MusicItem> = emptyList()

    /** Personal mixes made for the user (Apple Music's Favorites Mix, Chill Mix, …). */
    suspend fun getMadeForYou(): List<Playlist> = emptyList()

    /** Titled recommendation groups, roughly Apple Music's Home ("More from …", "New Releases for You"). */
    suspend fun getRecommendations(): List<Shelf>

    /** Albums and playlists the user plays most lately. */
    suspend fun getHeavyRotation(): List<MusicItem> = emptyList()

    /** Newest library additions. */
    suspend fun getRecentlyAdded(): List<MusicItem> = emptyList()

    /** Top charts (songs, albums, playlists) for the user's country. */
    suspend fun getCharts(): List<Shelf> = emptyList()

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

    /**
     * The user's rating and library state for [song], or null when this provider can't edit the
     * user's library (the Now Playing buttons are then hidden).
     */
    suspend fun getLibraryState(song: Song): SongLibraryState? = null

    suspend fun setRating(song: Song, rating: Rating): Unit = throw UnsupportedOperationException()
    suspend fun addSongToLibrary(song: Song): Unit = throw UnsupportedOperationException()

    /** Adds the whole album [song] is on. */
    suspend fun addAlbumToLibrary(song: Song): Unit = throw UnsupportedOperationException()
}
