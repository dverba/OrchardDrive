package com.aaosmusic.automotive

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.AAOS_LIBRARY
import com.aaosmusic.util.AppLog.Category.MEDIA_SESSION
import com.aaosmusic.util.AppLog.Category.PLAYBACK
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

/**
 * Answers AAOS Media Center's browse/search/play requests from [LibraryTree], and handles the
 * shuffle/repeat custom actions.
 */
class AutomotiveLibraryCallback(
    private val tree: LibraryTree,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {

    /** Last search per query, served from onGetSearchResult. */
    private val searchCache = mutableMapOf<String, List<BrowseNode>>()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        AppLog.i(MEDIA_SESSION, "Controller connected: ${controller.packageName} (uid ${controller.uid})")
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(PlaybackButtons.ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .add(SessionCommand(PlaybackButtons.ACTION_CYCLE_REPEAT, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setMediaButtonPreferences(PlaybackButtons.forPlayer(session.player))
            .build()
    }

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        AppLog.i(MEDIA_SESSION, "Controller disconnected: ${controller.packageName}")
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        AppLog.i(AAOS_LIBRARY, "Root requested by ${browser.packageName}")
        val extras = Bundle().apply {
            // Tells AAOS to show the search button (MediaBrowserServiceCompat's BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED).
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        val rootParams = LibraryParams.Builder().setExtras(extras).build()
        return Futures.immediateFuture(LibraryResult.ofItem(MediaItemMapper.toMediaItem(tree.root), rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children = tree.getChildren(parentId)
        if (children == null) {
            AppLog.w(AAOS_LIBRARY, "Children requested for unknown id '$parentId'")
            LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        } else {
            AppLog.d(AAOS_LIBRARY, "Children of '$parentId': ${children.size} (page $page/$pageSize)")
            LibraryResult.ofItemList(children.page(page, pageSize).map(MediaItemMapper::toMediaItem), params)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        tree.getItem(mediaId)
            ?.let { LibraryResult.ofItem(MediaItemMapper.toMediaItem(it), null) }
            ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val results = tree.search(query)
        searchCache[query] = results
        AppLog.i(AAOS_LIBRARY, "Search '$query': ${results.size} results")
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val results = searchCache[query] ?: tree.search(query).also { searchCache[query] = it }
        LibraryResult.ofItemList(results.page(page, pageSize).map(MediaItemMapper::toMediaItem), params)
    }

    /**
     * AAOS sends only the tapped media id (or a voice search query). Expand it into a full queue
     * with stream URIs and metadata.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val single = mediaItems.singleOrNull()
        val searchQuery = single?.requestMetadata?.searchQuery
        val queue = when {
            searchQuery != null -> tree.resolveSearchQueue(searchQuery)
            single != null -> tree.resolveQueue(single.mediaId)
            else -> null
        }
        if (queue != null) {
            AppLog.i(PLAYBACK, "Play request from ${controller.packageName}: '${single?.mediaId ?: searchQuery}' " +
                "→ ${queue.songs.size} item queue, start ${queue.startIndex}")
            MediaItemsWithStartPosition(
                queue.songs.map { MediaItemMapper.toPlayableItem(it, queue.mediaIdOf(it)) },
                queue.startIndex,
                if (searchQuery == null) startPositionMs else 0L,
            )
        } else {
            // Multi-item request (e.g. a controller restoring a queue): resolve each item individually.
            val resolved = resolveEach(mediaItems)
            AppLog.i(PLAYBACK, "Set ${resolved.size}/${mediaItems.size} items from ${controller.packageName}")
            MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)), startPositionMs)
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future { resolveEach(mediaItems) }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        // TODO: persist the real last queue/position. For now resume from Recently Played.
        val queue = tree.resolveQueue(MediaIds.RECENT)
        AppLog.i(PLAYBACK, "Playback resumption requested by ${controller.packageName}")
        MediaItemsWithStartPosition(
            queue?.let { q -> q.songs.map { MediaItemMapper.toPlayableItem(it, q.mediaIdOf(it)) } }.orEmpty(),
            0,
            0L,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val player = session.player
        when (customCommand.customAction) {
            PlaybackButtons.ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            PlaybackButtons.ACTION_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
        // Button icons are refreshed by the service's player listener.
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    private suspend fun resolveEach(items: List<MediaItem>): List<MediaItem> = items.mapNotNull { item ->
        if (item.localConfiguration != null) return@mapNotNull item
        val song = tree.resolveQueue(item.mediaId)?.let { it.songs[it.startIndex] }
        song?.let { MediaItemMapper.toPlayableItem(it, item.mediaId) }
    }

    private fun <T> List<T>.page(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return this
        val from = page.toLong() * pageSize
        if (from >= size) return emptyList()
        return subList(from.toInt(), minOf(size, (from + pageSize).toInt()))
    }
}

/** Shuffle/repeat buttons exposed to AAOS as custom actions. */
object PlaybackButtons {
    const val ACTION_TOGGLE_SHUFFLE = "com.aaosmusic.TOGGLE_SHUFFLE"
    const val ACTION_CYCLE_REPEAT = "com.aaosmusic.CYCLE_REPEAT"

    fun forPlayer(player: Player): ImmutableList<CommandButton> {
        val shuffle = CommandButton.Builder(
            if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
        )
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle on" else "Shuffle off")
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        val repeat = CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
            )
            .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
        return ImmutableList.of(shuffle, repeat)
    }
}
