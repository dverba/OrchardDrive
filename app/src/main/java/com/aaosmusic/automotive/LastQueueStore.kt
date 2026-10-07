package com.aaosmusic.automotive

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Persists the current queue, index and position, so after a restart (or the car waking up)
 * AAOS shows the last song and Play resumes where it left off. Items are stored with their
 * metadata and playback URI, so restoring needs no network.
 */
class LastQueueStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_queue", Context.MODE_PRIVATE)

    data class Saved(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    fun save(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) return
        // Keep a window around the current item so huge queues stay small on disk.
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val from = (current - MAX_ITEMS / 2).coerceAtLeast(0)
        val to = minOf(count, from + MAX_ITEMS)
        val items = buildJsonArray {
            for (i in from until to) add(encode(player.getMediaItemAt(i)) ?: continue)
        }
        prefs.edit()
            .putString(KEY_ITEMS, items.toString())
            .putInt(KEY_INDEX, current - from)
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun restore(): Saved? = runCatching {
        val items = Json.parseToJsonElement(prefs.getString(KEY_ITEMS, null) ?: return null).jsonArray
            .map { decode(it.jsonObject) }
        if (items.isEmpty()) return null
        Saved(items, prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex), prefs.getLong(KEY_POSITION, 0))
    }.getOrNull()

    fun clear() = prefs.edit().clear().apply()

    private fun encode(item: MediaItem): JsonObject? {
        val uri = item.localConfiguration?.uri ?: return null
        val m = item.mediaMetadata
        return buildJsonObject {
            put("id", item.mediaId)
            put("uri", uri.toString())
            m.title?.let { put("title", it.toString()) }
            m.artist?.let { put("artist", it.toString()) }
            m.albumTitle?.let { put("album", it.toString()) }
            m.artworkUri?.let { put("art", it.toString()) }
            m.durationMs?.let { put("dur", it) }
            m.trackNumber?.let { put("track", it) }
        }
    }

    private fun decode(o: JsonObject): MediaItem {
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val metadata = MediaMetadata.Builder()
            .setTitle(s("title"))
            .setDisplayTitle(s("title"))
            .setArtist(s("artist"))
            .setSubtitle(s("artist"))
            .setAlbumTitle(s("album"))
            .setAlbumArtist(s("artist"))
            .setArtworkUri(s("art")?.let(Uri::parse))
            .setDurationMs(o["dur"]?.jsonPrimitive?.longOrNull)
            .setTrackNumber(o["track"]?.jsonPrimitive?.int)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder().setMediaId(s("id")!!).setUri(s("uri")).setMediaMetadata(metadata).build()
    }

    private companion object {
        const val MAX_ITEMS = 100 // MusicKit rejects very long queues (522 failed; 300 worked)
        const val KEY_ITEMS = "items"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
    }
}
