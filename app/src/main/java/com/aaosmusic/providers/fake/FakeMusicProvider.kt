package com.aaosmusic.providers.fake

import com.aaosmusic.media.MusicProvider
import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.MusicItem
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.Rating
import com.aaosmusic.media.models.SearchResults
import com.aaosmusic.media.models.Shelf
import com.aaosmusic.media.models.Song
import com.aaosmusic.media.models.SongLibraryState
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory catalog: 3 artists, 3 albums, 10 songs, 3 playlists.
 *
 * Every call suspends for [latencyMs] so callers are exercised the same way a network-backed
 * provider would exercise them.
 *
 * @param artworkBaseUri prefix that artwork resource names are appended to, e.g.
 *   `android.resource://com.dverba.aaosmusic/drawable/`.
 */
class FakeMusicProvider(
    artworkBaseUri: String,
    private val latencyMs: Long = 50,
) : MusicProvider {

    private val artists = listOf(
        Artist("art1", "Test Artist", artworkBaseUri + "art_artist_1"),
        Artist("art2", "Async Orchestra", artworkBaseUri + "art_artist_2"),
        Artist("art3", "Null Pointers", artworkBaseUri + "art_artist_3"),
    )

    private val songs: List<Song>
    /** Ratings and library additions, kept in memory so the Now Playing buttons can be exercised. */
    private val ratings = ConcurrentHashMap<String, Rating>()
    private val addedSongs = ConcurrentHashMap.newKeySet<String>()
    private val albums: List<Album>
    private val playlists: List<Playlist>

    init {
        data class AlbumSpec(val id: String, val title: String, val artist: Artist, val art: String, val year: Int, val tracks: List<String>)

        val specs = listOf(
            AlbumSpec("alb1", "Test Album", artists[0], "art_album_1", 2024,
                listOf("Test Song", "Assertion Passed", "Green Build", "Edge Case")),
            AlbumSpec("alb2", "Coroutine Dreams", artists[1], "art_album_2", 2023,
                listOf("Suspend Fun", "Structured Concurrency", "Dispatchers.Main")),
            AlbumSpec("alb3", "Segfault Sunset", artists[2], "art_album_3", 2022,
                listOf("Dangling Reference", "Undefined Behaviour", "Core Dumped")),
        )

        // Durations match the SoundHelix example tracks used as playback sources.
        val durationsSec = listOf(373, 426, 344, 303, 354, 280, 421, 325, 389, 527)

        var n = 0
        val builtAlbums = specs.map { spec ->
            val artworkUri = artworkBaseUri + spec.art
            val albumSongs = spec.tracks.mapIndexed { index, title ->
                n++
                Song(
                    id = "s$n",
                    title = title,
                    artist = spec.artist.name,
                    artistId = spec.artist.id,
                    album = spec.title,
                    albumId = spec.id,
                    durationMs = durationsSec[n - 1] * 1000L,
                    artworkUri = artworkUri,
                    playbackUri = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-$n.mp3",
                    trackNumber = index + 1,
                )
            }
            Album(spec.id, spec.title, spec.artist.name, spec.artist.id, artworkUri, spec.year, albumSongs)
        }
        albums = builtAlbums
        songs = builtAlbums.flatMap { it.songs }

        fun pick(vararg ids: String) = ids.map { id -> songs.first { it.id == id } }
        playlists = listOf(
            Playlist("pl1", "Driving Mix", "Upbeat tracks for the road", artworkBaseUri + "art_playlist_1",
                pick("s1", "s5", "s8", "s2", "s9")),
            Playlist("pl2", "Chill Commute", "Low-key listening", artworkBaseUri + "art_playlist_2",
                pick("s3", "s6", "s10")),
            Playlist("pl3", "Favorites", null, artworkBaseUri + "art_playlist_3",
                pick("s1", "s4", "s7", "s10")),
        )
    }

    override suspend fun search(query: String): SearchResults = simulate {
        val q = query.trim()
        if (q.isEmpty()) return@simulate SearchResults()
        fun String.hit() = contains(q, ignoreCase = true)
        SearchResults(
            songs = songs.filter { it.title.hit() || it.artist.hit() || it.album.hit() },
            albums = albums.filter { it.title.hit() || it.artist.hit() },
            artists = artists.filter { it.name.hit() },
            playlists = playlists.filter { it.title.hit() },
        )
    }

    override suspend fun getRecentlyPlayed(): List<Song> = simulate {
        listOf("s5", "s1", "s8", "s3").map { id -> songs.first { it.id == id } }
    }

    override suspend fun getRecentlyPlayedContainers(): List<MusicItem> = simulate { listOf(albums[0], playlists[2]) }

    override suspend fun getMadeForYou(): List<Playlist> = simulate { listOf(playlists[0], playlists[1]) }

    override suspend fun getRecommendations(): List<Shelf> = simulate {
        listOf(
            Shelf("0", "More from ${artists[1].name}", listOf(albums[1])),
            Shelf("1", "New Releases for You", listOf(albums[2], albums[0])),
        )
    }

    override suspend fun getHeavyRotation(): List<MusicItem> = simulate { listOf(playlists[2], albums[1]) }

    override suspend fun getRecentlyAdded(): List<MusicItem> = simulate { albums.reversed() }

    override suspend fun getCharts(): List<Shelf> = simulate {
        listOf(
            Shelf("songs", "Top Songs", songs.take(5)),
            Shelf("albums", "Top Albums", albums),
            Shelf("playlists", "Top Playlists", playlists),
        )
    }

    override suspend fun getAlbums(): List<Album> = simulate { albums }
    override suspend fun getArtists(): List<Artist> = simulate { artists }
    override suspend fun getPlaylists(): List<Playlist> = simulate { playlists }
    override suspend fun getSongs(): List<Song> = simulate { songs }

    override suspend fun getAlbum(id: String): Album? = simulate { albums.find { it.id == id } }
    override suspend fun getArtist(id: String): Artist? = simulate { artists.find { it.id == id } }
    override suspend fun getArtistAlbums(artistId: String): List<Album> =
        simulate { albums.filter { it.artistId == artistId } }

    override suspend fun getPlaylist(id: String): Playlist? = simulate { playlists.find { it.id == id } }
    override suspend fun getSong(id: String): Song? = simulate { songs.find { it.id == id } }

    override suspend fun getLibraryState(song: Song): SongLibraryState = simulate {
        val albumSongs = albums.find { it.id == song.albumId }?.songs
        SongLibraryState(
            rating = ratings[song.id] ?: Rating.NONE,
            songInLibrary = song.id in addedSongs,
            albumInLibrary = albumSongs?.all { it.id in addedSongs },
        )
    }

    override suspend fun setRating(song: Song, rating: Rating) = simulate { ratings[song.id] = rating }
    override suspend fun addSongToLibrary(song: Song) = simulate { addedSongs += song.id; Unit }
    override suspend fun addAlbumToLibrary(song: Song) = simulate {
        albums.find { it.id == song.albumId }?.songs?.forEach { addedSongs += it.id }; Unit
    }

    private suspend inline fun <T> simulate(block: () -> T): T {
        if (latencyMs > 0) delay(latencyMs)
        return block()
    }
}
