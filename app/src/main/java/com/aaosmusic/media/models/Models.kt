package com.aaosmusic.media.models

/**
 * Provider-agnostic music models. Providers (fake, Apple Music, ...) normalize their own
 * response types into these; nothing outside a provider package should see provider types.
 *
 * Artwork and playback locations are plain strings (URIs) so these classes stay free of
 * Android dependencies and can be unit tested on the JVM.
 */
sealed interface MusicItem {
    val id: String
    val title: String
    val artworkUri: String?
}

data class Song(
    override val id: String,
    override val title: String,
    val artist: String,
    val artistId: String?,
    val album: String,
    val albumId: String?,
    val durationMs: Long?,
    override val artworkUri: String?,
    /** Stream/file location for URI-based players. Null when the playback provider resolves by [id]. */
    val playbackUri: String?,
    val trackNumber: Int? = null,
) : MusicItem

data class Album(
    override val id: String,
    override val title: String,
    val artist: String,
    val artistId: String?,
    override val artworkUri: String?,
    val year: Int? = null,
    val songs: List<Song> = emptyList(),
) : MusicItem

data class Artist(
    override val id: String,
    val name: String,
    override val artworkUri: String?,
) : MusicItem {
    override val title: String get() = name
}

data class Playlist(
    override val id: String,
    override val title: String,
    val description: String?,
    override val artworkUri: String?,
    val songs: List<Song> = emptyList(),
) : MusicItem

data class SearchResults(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

/** The user's opinion of a song (Apple Music's Love / Suggest Less). */
enum class Rating { NONE, LOVE, DISLIKE }

/** The user's rating and library state for one song, shown as Now Playing buttons. */
data class SongLibraryState(
    val rating: Rating = Rating.NONE,
    val songInLibrary: Boolean = false,
    /** Every track of the song's album is in the library. Null when the song has no album to add. */
    val albumInLibrary: Boolean? = false,
)
