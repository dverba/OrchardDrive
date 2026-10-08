package com.aaosmusic.automotive

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.aaosmusic.BuildConfig
import com.aaosmusic.media.models.Song

/** Converts browse-tree nodes and songs into Media3 [MediaItem]s. */
object MediaItemMapper {

    /**
     * Icons for the fixed folders: tab icons for Home/Library, row artwork for the categories.
     * Media Center resolves these by name; a numeric android.resource://pkg/<id> URI crashes it.
     */
    private val folderIcons = mapOf(
        MediaIds.HOME to "ic_folder_home",
        MediaIds.LIBRARY to "ic_folder_library",
        MediaIds.RECENT to "ic_folder_recent",
        MediaIds.RECOMMENDED to "ic_folder_recommended",
        MediaIds.MADE_FOR_YOU to "ic_folder_made_for_you",
        MediaIds.HEAVY_ROTATION to "ic_folder_heavy_rotation",
        MediaIds.RECENTLY_ADDED to "ic_folder_recently_added",
        MediaIds.CHARTS to "ic_folder_charts",
        MediaIds.PLAYLISTS to "ic_folder_playlists",
        MediaIds.PLAYLISTS_BY_DATE to "ic_sort",
        MediaIds.PLAYLISTS_A_Z to "ic_sort",
        MediaIds.ARTISTS to "ic_folder_artists",
        MediaIds.ALBUMS to "ic_folder_albums",
        MediaIds.SONGS to "ic_folder_songs",
    )

    private val SORT_ROWS = setOf(MediaIds.PLAYLISTS_BY_DATE, MediaIds.PLAYLISTS_A_Z)

    private fun folderIconUri(mediaId: String): Uri? =
        folderIcons[mediaId]?.let { Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/$it") }

    /**
     * [grid] picks tiles or list rows for the children of browsable folders. Songs are always
     * rows, and Home/Library's category folders (icons, not artwork) are always rows.
     */
    fun toMediaItem(node: BrowseNode, grid: Boolean = true): MediaItem {
        node.song?.let { song -> return toPlayableItem(song, node.mediaId, ArtworkSize.ROW) }

        val extras = Bundle()
        node.groupTitle?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        if (node.browsable) {
            val childStyle = when {
                node.mediaId == MediaIds.HOME || node.mediaId == MediaIds.LIBRARY -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
                grid -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                else -> MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            }
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, childStyle)
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        if (node.kind == BrowseNode.Kind.BUCKET || node.mediaId in SORT_ROWS) {
            // Letter folders and sort rows read as a compact list, whatever their siblings use.
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(node.title)
            .setSubtitle(node.subtitle)
            .setArtist(if (node.kind == BrowseNode.Kind.ALBUM) node.subtitle else null)
            .setArtworkUri(
                // Shown as a tile or a row thumbnail, per the layout setting.
                ArtworkProvider.toContentUri(node.artworkUri, if (grid) ArtworkSize.TILE else ArtworkSize.ROW)
                    ?: folderIconUri(node.mediaId),
            )
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

    /**
     * A queue-ready item: full metadata plus the stream URI the player needs. [artSize] defaults
     * to Now Playing size; browse rows pass [ArtworkSize.ROW].
     */
    fun toPlayableItem(song: Song, mediaId: String, artSize: Int = ArtworkSize.FULL): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(song.artist)
            .setSubtitle(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.artist)
            .setArtworkUri(ArtworkProvider.toContentUri(song.artworkUri, artSize))
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
