package com.aaosmusic.providers.apple

import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.MusicItem
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.Rating
import com.aaosmusic.media.models.SearchResults
import com.aaosmusic.media.models.Shelf
import com.aaosmusic.media.models.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Converts Apple Music API JSON into internal models. Apple's response shapes stay inside this
 * file; nothing else in the app sees them.
 *
 * Handles both catalog resources (`songs`, `albums`, …; numeric or `pl.` ids) and library
 * resources (`library-songs`, …; `i.`/`l.`/`r.`/`p.` ids). MusicKit plays by *catalog* id, so a
 * song's [Song.playbackUri] is `applemusic:song:<catalogId>`, or null when the item has no catalog
 * equivalent (uploaded/unmatched tracks), which marks it unplayable.
 */
object AppleMusicJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** Artwork edge length requested from Apple's `{w}x{h}` URL templates. */
    const val ARTWORK_SIZE = 600

    const val PLAYBACK_URI_PREFIX = "applemusic:song:"

    /** Catalog id from a [Song.playbackUri], or null if it isn't an Apple Music one. */
    fun catalogIdFromPlaybackUri(uri: String?): String? = uri?.takeIf { it.startsWith(PLAYBACK_URI_PREFIX) }?.removePrefix(PLAYBACK_URI_PREFIX)

    /** Preview clip URLs keyed by song id (not part of the internal Song model). */
    data class ParsedSearch(val results: SearchResults, val previews: Map<String, String>)

    /** One page of a collection endpoint. [next] is the path of the following page; [total] the collection size if reported. */
    data class Page(val items: List<JsonObject>, val next: String?, val total: Int? = null)

    fun parsePage(body: String): Page {
        val root = json.parseToJsonElement(body).jsonObject
        return Page(root.dataArray(), root.str("next"), root["meta"]?.jsonObject?.get("total")?.jsonPrimitive?.intOrNull)
    }

    fun parseSearch(body: String): ParsedSearch {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject ?: JsonObject(emptyMap())
        val songData = results["songs"]?.jsonObject?.dataArray().orEmpty()
        return ParsedSearch(
            SearchResults(
                songs = songData.map(::song),
                albums = results["albums"]?.jsonObject?.dataArray().orEmpty().map(::album),
                artists = results["artists"]?.jsonObject?.dataArray().orEmpty().map(::artist),
                playlists = results["playlists"]?.jsonObject?.dataArray().orEmpty().map(::playlist),
            ),
            previews = songData.mapNotNull { r ->
                val url = r.attrs()["previews"]?.jsonArray?.firstOrNull()?.jsonObject?.str("url")
                url?.let { r.str("id")!! to it }
            }.toMap(),
        )
    }

    fun parseStorefrontId(body: String): String? = parsePage(body).items.firstOrNull()?.str("id")

    /** Any supported resource, or null for types we don't model (stations, music videos, …). */
    fun item(r: JsonObject): MusicItem? = when (r.str("type")) {
        "songs", "library-songs" -> song(r)
        "albums", "library-albums" -> album(r)
        "artists", "library-artists" -> artist(r)
        "playlists", "library-playlists" -> playlist(r)
        else -> null
    }

    /** One group from `/v1/me/recommendations`, e.g. "More from Radiohead". [kind] is Apple's (`recently-played`, …). */
    data class RecommendationGroup(val title: String, val kind: String?, val items: List<MusicItem>)

    /**
     * Personal recommendations: each group lists albums/playlists/stations under
     * `relationships.contents` (group recommendations nest further groups under `recommendations`).
     * Keeps albums and playlists; stations can't be played.
     */
    fun parseRecommendationGroups(body: String): List<RecommendationGroup> {
        fun groups(r: JsonObject): List<RecommendationGroup> {
            val a = r.attrs()
            val title = a["title"]?.jsonObject?.str("stringForDisplay").orEmpty()
            val items = r.relationship("contents").mapNotNull { item(it)?.takeIf { i -> i is Album || i is Playlist } }
            return listOf(RecommendationGroup(title, a.str("kind"), items)) + r.relationship("recommendations").flatMap(::groups)
        }
        return parsePage(body).items.flatMap(::groups).filter { it.items.isNotEmpty() }
    }

    /** `/v1/catalog/{sf}/charts`: one shelf per chart type, with id `songs`, `albums` or `playlists`. */
    fun parseCharts(body: String): List<Shelf> {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject ?: return emptyList()
        return listOf("songs", "albums", "playlists").mapNotNull { type ->
            val chart = (results[type] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            Shelf(type, chart.str("name").orEmpty(), chart.dataArray().mapNotNull(::item))
        }.filter { it.items.isNotEmpty() }
    }

    fun song(r: JsonObject): Song {
        val a = r.attrs()
        val id = r.str("id")!!
        val catalogId = if (r.str("type") == "songs") id else a["playParams"]?.jsonObject?.str("catalogId")
        return Song(
            id = id,
            title = a.str("name").orEmpty(),
            artist = a.str("artistName").orEmpty(),
            artistId = null,
            album = a.str("albumName").orEmpty(),
            albumId = null,
            durationMs = a["durationInMillis"]?.jsonPrimitive?.longOrNull,
            artworkUri = artwork(a),
            playbackUri = catalogId?.let { PLAYBACK_URI_PREFIX + it },
            trackNumber = a["trackNumber"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** Album with its `tracks` relationship (present when fetched with `include=tracks`). */
    fun album(r: JsonObject): Album {
        val a = r.attrs()
        val artwork = artwork(a)
        val id = r.str("id")!!
        val songs = r.relationship("tracks")
            .filter { it.str("type") == "songs" || it.str("type") == "library-songs" }
            .map(::song)
            .map { it.copy(albumId = id, artworkUri = it.artworkUri ?: artwork) }
        return Album(id, a.str("name").orEmpty(), a.str("artistName").orEmpty(), null, artwork, songs = songs)
    }

    /** Library artists carry no artwork; use the catalog artist's when fetched with `include=catalog`. */
    fun artist(r: JsonObject): Artist {
        val artwork = artwork(r.attrs()) ?: r.relationship("catalog").firstOrNull()?.let { artwork(it.attrs()) }
        return Artist(r.str("id")!!, r.attrs().str("name").orEmpty(), artwork)
    }

    fun playlist(r: JsonObject): Playlist {
        val a = r.attrs()
        val description = a["description"]?.jsonObject?.let { it.str("short") ?: it.str("standard") } ?: a.str("curatorName")
        val songs = r.relationship("tracks").filter { it.str("type") == "songs" || it.str("type") == "library-songs" }.map(::song)
        return Playlist(r.str("id")!!, a.str("name").orEmpty(), description, artwork(a), songs, dateAdded = a.str("dateAdded"))
    }

    /** Rating resource from `/v1/me/ratings/...`: value 1 is Love, -1 is Dislike. */
    fun parseRating(body: String): Rating =
        when (parsePage(body).items.firstOrNull()?.attrs()?.get("value")?.jsonPrimitive?.intOrNull) {
            1 -> Rating.LOVE
            -1 -> Rating.DISLIKE
            else -> Rating.NONE
        }

    /** Request body for PUT `/v1/me/ratings/...`. [rating] must not be [Rating.NONE] (that's a DELETE). */
    fun ratingBody(rating: Rating): String =
        """{"type":"rating","attributes":{"value":${if (rating == Rating.LOVE) 1 else -1}}}"""

    /** Catalog album id of a catalog song (from its `albums` relationship). */
    fun albumIdOf(song: JsonObject): String? = song.relationship("albums").firstOrNull()?.str("id")

    /** Whether a catalog resource fetched with `include=library` is in the user's library. */
    fun inLibrary(r: JsonObject): Boolean = r.relationship("library").isNotEmpty()

    /**
     * Whether every track of a catalog album (fetched with `include=library`) is in the library.
     * Adding one song creates a partial library album, so compare track counts.
     */
    fun albumFullyInLibrary(album: JsonObject): Boolean {
        val copy = album.relationship("library").firstOrNull() ?: return false
        val total = album.attrs()["trackCount"]?.jsonPrimitive?.intOrNull ?: return true
        val have = copy.attrs()["trackCount"]?.jsonPrimitive?.intOrNull ?: return true
        return have >= total
    }

    private fun artwork(attrs: JsonObject): String? =
        attrs["artwork"]?.jsonObject?.str("url")
            ?.replace("{w}", ARTWORK_SIZE.toString())?.replace("{h}", ARTWORK_SIZE.toString())

    private fun JsonObject.dataArray(): List<JsonObject> = (this["data"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.relationship(name: String): List<JsonObject> =
        this["relationships"]?.jsonObject?.get(name)?.jsonObject?.dataArray().orEmpty()

    private fun JsonObject.attrs(): JsonObject = this["attributes"]?.jsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
