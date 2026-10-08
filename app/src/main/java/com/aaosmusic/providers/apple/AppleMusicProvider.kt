package com.aaosmusic.providers.apple

import com.aaosmusic.media.MusicProvider
import com.aaosmusic.media.MusicProviderException
import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.MusicItem
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.Rating
import com.aaosmusic.media.models.SearchResults
import com.aaosmusic.media.models.Song
import com.aaosmusic.media.models.SongLibraryState
import com.aaosmusic.repository.MusicRepository
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.APPLE_API
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * [MusicProvider] backed by the Apple Music API.
 *
 * "Library" lists are the user's library (`/v1/me/library/...`); search, recommendations and
 * anything reached from them are catalog resources. Ids keep Apple's prefixes, which tell the two
 * apart: library `i.`/`l.`/`r.`/`p.`, catalog numeric or `pl.`.
 *
 * Results are cached in memory: lists for a few minutes, individual albums/playlists/songs for
 * the process lifetime, so playing a song (which re-resolves its container) doesn't refetch.
 */
class AppleMusicProvider(
    private val api: AppleMusicApiClient,
    private val tokens: AppleMusicTokenStore,
) : MusicProvider, MusicRepository.Clearable {

    private var storefront: String? = null
    private val songs = ConcurrentHashMap<String, Song>()
    private val albums = ConcurrentHashMap<String, Album>()
    private val playlists = ConcurrentHashMap<String, Playlist>()
    private val artists = ConcurrentHashMap<String, Artist>()
    private val lists = ConcurrentHashMap<String, Pair<Long, List<*>>>()
    /** Catalog album id by catalog song id, learned while loading a song's library state. */
    private val albumOfSong = ConcurrentHashMap<String, String>()
    /** In-flight loads by key, so concurrent requests for the same list/item share one fetch. */
    private val inFlight = ConcurrentHashMap<String, Deferred<Any?>>()

    override fun requiresSignIn() = !tokens.signedIn

    override fun signInMessage() =
        if (tokens.userTokenRejected) AppleMusicApiClient.SIGN_IN_EXPIRED_MESSAGE else super.signInMessage()

    override fun clearCache() {
        storefront = null
        listOf(songs, albums, playlists, artists, lists, albumOfSong).forEach { it.clear() }
    }

    /** Loads the big library lists in the background so browsing them later is instant. */
    override suspend fun warmUp() {
        if (requiresSignIn()) return
        coroutineScope {
            launch { runCatching { getRecentlyPlayed(); getRecommendations() } }
            launch { runCatching { getPlaylists(); getArtists(); getAlbums(); getSongs() } }
        }
        AppLog.i(APPLE_API, "Library warm-up done: ${songs.size} songs cached")
    }

    override suspend fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        return api.search(query, storefront(), limit = 15).results.also { r ->
            remember(r.songs)
            r.albums.forEach { albums.putIfAbsent(it.id, it) }
            r.artists.forEach { artists.putIfAbsent(it.id, it) }
        }
    }

    override suspend fun getRecentlyPlayed(): List<Song> = cachedList("recent", minutes = 2) {
        // This endpoint caps limit at 30.
        api.getAll("/v1/me/recent/played/tracks?types=songs,library-songs", maxItems = 30, pageSize = 30)
            .map(AppleMusicJson::song)
    }.also(::remember)

    override suspend fun getRecommendations(): List<MusicItem> = cachedList("recommendations", minutes = 30) {
        AppleMusicJson.parseRecommendations(api.get("/v1/me/recommendations?limit=10")).take(30)
    }

    override suspend fun getAlbums(): List<Album> = cachedList("library-albums", minutes = LIBRARY_TTL_MIN) {
        api.getAllParallel("/v1/me/library/albums", maxItems = MAX_LIBRARY_ITEMS).map(AppleMusicJson::album).sortedBy { it.title.lowercase() }
    }

    override suspend fun getArtists(): List<Artist> = cachedList("library-artists", minutes = LIBRARY_TTL_MIN) {
        api.getAllParallel("/v1/me/library/artists?include=catalog", maxItems = MAX_LIBRARY_ITEMS).map(AppleMusicJson::artist)
            .sortedBy { it.name.lowercase() }
    }.onEach { artists[it.id] = it }

    override suspend fun getPlaylists(): List<Playlist> = cachedList("library-playlists", minutes = LIBRARY_TTL_MIN) {
        api.getAllParallel("/v1/me/library/playlists", maxItems = MAX_LIBRARY_ITEMS).map(AppleMusicJson::playlist)
    }

    override suspend fun getSongs(): List<Song> = cachedList("library-songs", minutes = LIBRARY_TTL_MIN) {
        api.getAllParallel("/v1/me/library/songs", maxItems = MAX_LIBRARY_ITEMS).map(AppleMusicJson::song).sortedBy { it.title.lowercase() }
    }.also(::remember)

    override suspend fun getAlbum(id: String): Album? {
        albums[id]?.takeIf { it.songs.isNotEmpty() }?.let { return it }
        val path = if (id.isLibraryId()) "/v1/me/library/albums/$id?include=tracks" else "/v1/catalog/${storefront()}/albums/$id?include=tracks"
        return fetchOne(path)?.let(AppleMusicJson::album)?.also { albums[id] = it; remember(it.songs) }
    }

    override suspend fun getArtist(id: String): Artist? {
        artists[id]?.let { return it }
        val path = if (id.isLibraryId()) "/v1/me/library/artists/$id?include=catalog" else "/v1/catalog/${storefront()}/artists/$id"
        return fetchOne(path)?.let(AppleMusicJson::artist)?.also { artists[id] = it }
    }

    override suspend fun getArtistAlbums(artistId: String): List<Album> = cachedList("artist-albums:$artistId", minutes = 10) {
        if (artistId.isLibraryId()) api.getAll("/v1/me/library/artists/$artistId/albums", maxItems = 200)
        else api.getAll("/v1/catalog/${storefront()}/artists/$artistId/albums", maxItems = 50, pageSize = 25)
    }.map(AppleMusicJson::album).onEach { albums.putIfAbsent(it.id, it) }

    override suspend fun getPlaylist(id: String): Playlist? {
        playlists[id]?.let { return it }
        val playlist = if (id.isLibraryId()) {
            val meta = fetchOne("/v1/me/library/playlists/$id")?.let(AppleMusicJson::playlist) ?: return null
            val tracks = api.getAll("/v1/me/library/playlists/$id/tracks", maxItems = 500).map(AppleMusicJson::song)
            meta.copy(songs = tracks)
        } else {
            fetchOne("/v1/catalog/${storefront()}/playlists/$id?include=tracks")?.let(AppleMusicJson::playlist) ?: return null
        }
        playlists[id] = playlist
        remember(playlist.songs)
        return playlist
    }

    override suspend fun getSong(id: String): Song? {
        songs[id]?.let { return it }
        val path = if (id.isLibraryId()) "/v1/me/library/songs/$id" else "/v1/catalog/${storefront()}/songs/$id"
        return fetchOne(path)?.let(AppleMusicJson::song)?.also { songs[id] = it }
    }

    /** Ratings and library membership work on catalog ids; library-only (uploaded) songs have none. */
    override suspend fun getLibraryState(song: Song): SongLibraryState? {
        val songId = song.catalogId() ?: return null
        val sf = storefront()
        return coroutineScope {
            val rating = async { getRating(songId) }
            val catalogSong = fetchWithLibrary("/v1/catalog/$sf/songs/$songId?include=albums,library")
            val albumId = catalogSong?.let(AppleMusicJson::albumIdOf)?.also { albumOfSong[songId] = it }
            val album = albumId?.let { fetchWithLibrary("/v1/catalog/$sf/albums/$it?include=library") }
            SongLibraryState(
                rating = rating.await(),
                songInLibrary = catalogSong?.let(AppleMusicJson::inLibrary) ?: false,
                albumInLibrary = album?.let(AppleMusicJson::albumFullyInLibrary),
            )
        }
    }

    override suspend fun setRating(song: Song, rating: Rating) {
        val path = "/v1/me/ratings/songs/${song.requireCatalogId()}"
        if (rating == Rating.NONE) {
            try { api.send("DELETE", path) } catch (e: AppleMusicApiException) { if (e.status != 404) throw e }
        } else {
            api.send("PUT", path, AppleMusicJson.ratingBody(rating))
        }
        AppLog.i(APPLE_API, "Rated '${song.title}': $rating")
    }

    override suspend fun addSongToLibrary(song: Song) = addToLibrary("songs", song.requireCatalogId(), song.title)

    override suspend fun addAlbumToLibrary(song: Song) {
        val songId = song.requireCatalogId()
        val albumId = albumOfSong[songId]
            ?: fetchOne("/v1/catalog/${storefront()}/songs/$songId")?.let(AppleMusicJson::albumIdOf)
            ?: throw MusicProviderException("'${song.title}' has no album to add")
        addToLibrary("albums", albumId, song.album)
    }

    private suspend fun getRating(catalogSongId: String): Rating = try {
        AppleMusicJson.parseRating(api.get("/v1/me/ratings/songs/$catalogSongId"))
    } catch (e: AppleMusicApiException) {
        if (e.status == 404) Rating.NONE else throw e // 404: never rated
    }

    /** A catalog resource with its `library` relationship, which needs the user token. */
    private suspend fun fetchWithLibrary(path: String): JsonObject? = try {
        AppleMusicJson.parsePage(api.get(path, withUserToken = true)).items.firstOrNull()
    } catch (e: AppleMusicApiException) {
        if (e.status == 404) null else throw e
    }

    private suspend fun addToLibrary(type: String, catalogId: String, title: String) {
        api.send("POST", "/v1/me/library?ids%5B$type%5D=$catalogId")
        AppLog.i(APPLE_API, "Added $type '$title' to library")
        // The library lists changed: refetch them next time they're browsed.
        listOf("library-songs", "library-albums", "library-artists").forEach(lists::remove)
    }

    private fun Song.catalogId(): String? = AppleMusicJson.catalogIdFromPlaybackUri(playbackUri)

    private fun Song.requireCatalogId(): String =
        catalogId() ?: throw MusicProviderException("'$title' isn't in the Apple Music catalog")

    private suspend fun storefront(): String = storefront ?: run {
        val sf = runCatching { api.userStorefront() }
            .onFailure { AppLog.w(APPLE_API, "Storefront lookup failed; using ${AppleMusicApiClient.DEFAULT_STOREFRONT}", it) }
            .getOrNull() ?: AppleMusicApiClient.DEFAULT_STOREFRONT
        sf.also { storefront = it }
    }

    /** First resource at [path], or null on 404. Concurrent calls for the same path share one request. */
    private suspend fun fetchOne(path: String): JsonObject? = shared("one:$path") {
        try {
            AppleMusicJson.parsePage(api.get(path)).items.firstOrNull()
        } catch (e: AppleMusicApiException) {
            if (e.status == 404) null else throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, load: suspend () -> T): T = coroutineScope {
        val mine = async(start = CoroutineStart.LAZY) { load() }
        val existing = inFlight.putIfAbsent(key, mine as Deferred<Any?>)
        if (existing != null) {
            mine.cancel()
            existing.await() as T
        } else {
            try { mine.await() } finally { inFlight.remove(key, mine) }
        }
    }

    private fun remember(list: List<Song>) = list.forEach { songs.putIfAbsent(it.id, it) }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cachedList(key: String, minutes: Int, load: suspend () -> List<T>): List<T> {
        lists[key]?.let { (at, value) -> if (System.currentTimeMillis() - at < minutes * 60_000L) return value as List<T> }
        return shared("list:$key") { load().also { lists[key] = System.currentTimeMillis() to it } }
    }

    private fun String.isLibraryId() = length > 2 && this[1] == '.' && this[0] in "ilrp"

    private companion object {
        const val LIBRARY_TTL_MIN = 30
        /** Upper bound per library list; large lists are split into A–Z folders by LibraryTree. */
        const val MAX_LIBRARY_ITEMS = 25_000
    }
}
