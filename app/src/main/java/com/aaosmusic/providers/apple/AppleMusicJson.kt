package com.aaosmusic.providers.apple

import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.SearchResults
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
 */
object AppleMusicJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** Artwork edge length requested from Apple's `{w}x{h}` URL templates. */
    const val ARTWORK_SIZE = 600

    /** Preview clip URLs keyed by song id (not part of the internal Song model). */
    data class ParsedSearch(val results: SearchResults, val previews: Map<String, String>)

    fun parseSearch(body: String): ParsedSearch {
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonObject ?: JsonObject(emptyMap())
        val songData = results.data("songs")
        return ParsedSearch(
            SearchResults(
                songs = songData.map(::song),
                albums = results.data("albums").map(::album),
                artists = results.data("artists").map(::artist),
                playlists = results.data("playlists").map(::playlist),
            ),
            previews = songData.mapNotNull { r ->
                val url = r.attrs()["previews"]?.jsonArray?.firstOrNull()?.jsonObject?.str("url")
                url?.let { r.str("id")!! to it }
            }.toMap(),
        )
    }

    fun parseStorefrontId(body: String): String? =
        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.str("id")

    fun song(r: JsonObject): Song {
        val a = r.attrs()
        return Song(
            id = r.str("id")!!,
            title = a.str("name").orEmpty(),
            artist = a.str("artistName").orEmpty(),
            artistId = null,
            album = a.str("albumName").orEmpty(),
            albumId = null,
            durationMs = a["durationInMillis"]?.jsonPrimitive?.longOrNull,
            artworkUri = artwork(a),
            // Full tracks are DRM-protected and played by MusicKit by id, not by URL.
            playbackUri = null,
            trackNumber = a["trackNumber"]?.jsonPrimitive?.intOrNull,
        )
    }

    fun album(r: JsonObject): Album {
        val a = r.attrs()
        return Album(r.str("id")!!, a.str("name").orEmpty(), a.str("artistName").orEmpty(), null, artwork(a))
    }

    fun artist(r: JsonObject): Artist = Artist(r.str("id")!!, r.attrs().str("name").orEmpty(), artwork(r.attrs()))

    fun playlist(r: JsonObject): Playlist {
        val a = r.attrs()
        val description = a["description"]?.jsonObject?.str("short") ?: a.str("curatorName")
        return Playlist(r.str("id")!!, a.str("name").orEmpty(), description, artwork(a))
    }

    private fun artwork(attrs: JsonObject): String? =
        attrs["artwork"]?.jsonObject?.str("url")
            ?.replace("{w}", ARTWORK_SIZE.toString())?.replace("{h}", ARTWORK_SIZE.toString())

    private fun JsonObject.data(type: String): List<JsonObject> =
        (this[type]?.jsonObject?.get("data") as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.attrs(): JsonObject = this["attributes"]?.jsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
