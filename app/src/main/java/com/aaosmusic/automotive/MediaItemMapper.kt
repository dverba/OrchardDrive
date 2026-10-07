package com.aaosmusic.automotive

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.aaosmusic.media.models.Song

/** Converts browse-tree nodes and songs into Media3 [MediaItem]s. */
object MediaItemMapper {

    fun toMediaItem(node: BrowseNode): MediaItem {
        node.song?.let { song -> return toPlayableItem(song, node.mediaId) }

        val extras = Bundle()
        node.groupTitle?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        if (node.kind == BrowseNode.Kind.BUCKET) {
            // Letter folders read as a compact list; their contents (albums/artists) as a grid.
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        if (node.mediaId == MediaIds.HOME || node.mediaId == MediaIds.LIBRARY) {
            // The tabs' children are artwork-less category folders; rows read better than big blank tiles.
            extras.putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        if (node.kind == BrowseNode.Kind.ALBUM || node.kind == BrowseNode.Kind.PLAYLIST) {
            // Album/playlist contents read best as a track list even though the folders themselves are a grid.
            extras.putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(node.title)
            .setSubtitle(node.subtitle)
            .setArtist(if (node.kind == BrowseNode.Kind.ALBUM) node.subtitle else null)
            .setArtworkUri(ArtworkProvider.toContentUri(node.artworkUri))
            .setIsBrowsable(node.browsable)
            .setIsPlayable(node.playable)
            .setMediaType(
                when (node.kind) {
                    BrowseNode.Kind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
                    BrowseNode.Kind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
                    BrowseNode.Kind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                    else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                },
            )
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(node.mediaId).setMediaMetadata(metadata).build()
    }

    /** A queue-ready item: full metadata plus the stream URI the player needs. */
    fun toPlayableItem(song: Song, mediaId: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setSubtitle(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.artist)
            .setArtworkUri(ArtworkProvider.toContentUri(song.artworkUri))
            .setTrackNumber(song.trackNumber)
            .setDurationMs(song.durationMs)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(song.playbackUri)
            .setMediaMetadata(metadata)
            .build()
    }
}
