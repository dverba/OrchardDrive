package com.aaosmusic.automotive

/**
 * Media ID scheme for the browse tree.
 *
 * Fixed nodes use path-like ids (`library/albums`). Entities use `kind:id`. Songs also carry the
 * container they were browsed from (`song:s3@album:alb1`) so that playing one song can queue the
 * rest of its album/playlist/list — AAOS only sends back the single tapped id.
 */
object MediaIds {
    const val ROOT = "root"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val RECENT = "home/recent"
    const val RECOMMENDED = "home/recommended"
    const val PLAYLISTS = "library/playlists"
    const val ARTISTS = "library/artists"
    const val ALBUMS = "library/albums"
    const val SONGS = "library/songs"

    private const val ALBUM = "album:"
    private const val ARTIST = "artist:"
    private const val PLAYLIST = "playlist:"
    private const val SONG = "song:"
    private const val SEARCH = "search:"
    private const val CONTEXT_SEPARATOR = '@'

    fun album(id: String) = ALBUM + id
    fun artist(id: String) = ARTIST + id
    fun playlist(id: String) = PLAYLIST + id
    fun search(query: String) = SEARCH + query
    fun song(id: String, context: String) = "$SONG$id$CONTEXT_SEPARATOR$context"
    fun song(id: String) = SONG + id

    /** Letter folder ("A".."Z" or "#") inside a large library list, e.g. `library/songs/A`. */
    fun bucket(parent: String, letter: String) = "$parent/$letter"
    private val BUCKETABLE = setOf(PLAYLISTS, ARTISTS, ALBUMS, SONGS)

    sealed interface Parsed {
        data class Fixed(val id: String) : Parsed
        data class AlbumId(val id: String) : Parsed
        data class ArtistId(val id: String) : Parsed
        data class PlaylistId(val id: String) : Parsed
        data class SearchContext(val query: String) : Parsed
        /** [context] is itself a media id (album, playlist, fixed list or search), or null. */
        data class SongId(val id: String, val context: String?) : Parsed
        data class Bucket(val parent: String, val letter: String) : Parsed
        data class Unknown(val raw: String) : Parsed
    }

    val FIXED = setOf(ROOT, HOME, LIBRARY, RECENT, RECOMMENDED, PLAYLISTS, ARTISTS, ALBUMS, SONGS)

    fun parse(mediaId: String): Parsed = when {
        mediaId in FIXED -> Parsed.Fixed(mediaId)
        mediaId.startsWith(SONG) -> {
            val body = mediaId.removePrefix(SONG)
            val sep = body.indexOf(CONTEXT_SEPARATOR)
            if (sep < 0) Parsed.SongId(body, null)
            else Parsed.SongId(body.substring(0, sep), body.substring(sep + 1).ifEmpty { null })
        }
        mediaId.startsWith(ALBUM) -> Parsed.AlbumId(mediaId.removePrefix(ALBUM))
        mediaId.startsWith(ARTIST) -> Parsed.ArtistId(mediaId.removePrefix(ARTIST))
        mediaId.startsWith(PLAYLIST) -> Parsed.PlaylistId(mediaId.removePrefix(PLAYLIST))
        mediaId.startsWith(SEARCH) -> Parsed.SearchContext(mediaId.removePrefix(SEARCH))
        mediaId.substringBeforeLast('/') in BUCKETABLE && mediaId.substringAfterLast('/').let { it.length == 1 } ->
            Parsed.Bucket(mediaId.substringBeforeLast('/'), mediaId.substringAfterLast('/'))
        else -> Parsed.Unknown(mediaId)
    }
}
