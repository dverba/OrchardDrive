package com.aaosmusic.automotive

import com.aaosmusic.automotive.MediaIds.Parsed
import com.aaosmusic.media.MusicProvider
import com.aaosmusic.media.MusicProviderException
import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Artist
import com.aaosmusic.media.models.MusicItem
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.Song

/** One entry in the browse tree, independent of Media3 so the tree can be unit tested. */
data class BrowseNode(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUri: String? = null,
    val browsable: Boolean,
    val playable: Boolean,
    val kind: Kind,
    val song: Song? = null,
    /** Section header shown by AAOS (used for categorized search results). */
    val groupTitle: String? = null,
) {
    enum class Kind { FOLDER, BUCKET, ALBUM, ARTIST, PLAYLIST, SONG }
}

/** Index letter for A–Z folders: first letter (accents stripped), or "#" for digits/symbols/other scripts. */
internal fun bucketOf(title: String): String {
    val first = java.text.Normalizer.normalize(title.trimStart(), java.text.Normalizer.Form.NFD)
        .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

/** Songs without a playback location (e.g. Apple uploads with no catalog match) can't be queued. */
private val Song.isPlayable: Boolean get() = playbackUri != null

/**
 * A resolved play request: what to queue and where to start. [contextId] is the container the
 * queue came from, so queued items keep context-carrying media ids.
 */
data class PlayQueue(val songs: List<Song>, val startIndex: Int, val contextId: String?) {
    fun mediaIdOf(song: Song): String = contextId?.let { MediaIds.song(song.id, it) } ?: MediaIds.song(song.id)

    /**
     * At most [max] songs around the start: a few before (so Previous works) and the rest after.
     * MusicKit fails to load very long queues (a 522-song queue failed; ~100 is fine), and a
     * 14k-song queue would be pointless in a car anyway.
     */
    fun windowed(max: Int = 100, before: Int = 10): PlayQueue {
        if (songs.size <= max) return this
        val from = (startIndex - before).coerceIn(0, songs.size - max)
        return PlayQueue(songs.subList(from, from + max), startIndex - from, contextId)
    }
}

/**
 * Builds the browse hierarchy AAOS shows:
 *
 * ```
 * root
 *  ├── Home      (tab)
 *  │    ├── Recently Played
 *  │    └── Recommended
 *  └── Library   (tab)
 *       ├── Playlists
 *       ├── Artists
 *       ├── Albums
 *       └── Songs
 * ```
 */
class LibraryTree(
    private val music: MusicProvider,
    private val labels: Labels = Labels(),
    /** Library lists longer than this are split into A–Z letter folders (a 14k-row list is unusable in a car). */
    private val bucketThreshold: Int = 300,
) {
    data class Labels(
        val appName: String = "AAOSMusic for Apple Music",
        val home: String = "Home",
        val library: String = "Library",
        val recent: String = "Recently Played",
        val recommended: String = "Recommended",
        val playlists: String = "Playlists",
        val artists: String = "Artists",
        val albums: String = "Albums",
        val songs: String = "Songs",
    )

    private fun folder(id: String, title: String) =
        BrowseNode(id, title, browsable = true, playable = false, kind = BrowseNode.Kind.FOLDER)

    private val fixedNodes: Map<String, BrowseNode> = listOf(
        folder(MediaIds.ROOT, labels.appName),
        folder(MediaIds.HOME, labels.home),
        folder(MediaIds.LIBRARY, labels.library),
        folder(MediaIds.RECENT, labels.recent),
        folder(MediaIds.RECOMMENDED, labels.recommended),
        folder(MediaIds.PLAYLISTS, labels.playlists),
        folder(MediaIds.ARTISTS, labels.artists),
        folder(MediaIds.ALBUMS, labels.albums),
        folder(MediaIds.SONGS, labels.songs),
    ).associateBy { it.mediaId }

    val root: BrowseNode get() = fixedNodes.getValue(MediaIds.ROOT)

    /**
     * Children of [parentId], or null if the id is unknown. Throws a sign-in-required
     * [MusicProviderException] at the root when the provider needs authentication, so AAOS shows
     * its sign-in prompt immediately instead of an empty-looking hierarchy.
     */
    suspend fun getChildren(parentId: String): List<BrowseNode>? {
        if (parentId == MediaIds.ROOT && music.requiresSignIn()) {
            throw MusicProviderException(music.signInMessage(), signInRequired = true)
        }
        return children(parentId)
    }

    private suspend fun children(parentId: String): List<BrowseNode>? = when (val p = MediaIds.parse(parentId)) {
        is Parsed.Fixed -> when (p.id) {
            MediaIds.ROOT -> fixed(MediaIds.HOME, MediaIds.LIBRARY)
            MediaIds.HOME -> fixed(MediaIds.RECENT, MediaIds.RECOMMENDED)
            MediaIds.LIBRARY -> fixed(MediaIds.PLAYLISTS, MediaIds.ARTISTS, MediaIds.ALBUMS, MediaIds.SONGS)
            MediaIds.RECENT -> music.getRecentlyPlayed().playable().map { songNode(it, MediaIds.RECENT) }
            MediaIds.RECOMMENDED -> music.getRecommendations().map { itemNode(it) }
            MediaIds.PLAYLISTS, MediaIds.ARTISTS, MediaIds.ALBUMS, MediaIds.SONGS -> {
                val all = libraryList(p.id)
                if (all.size <= bucketThreshold) all else bucketFolders(p.id, all)
            }
            else -> null
        }
        is Parsed.Bucket -> libraryList(p.parent, songContext = parentId).filter { bucketOf(it.title) == p.letter }
        is Parsed.AlbumId -> music.getAlbum(p.id)?.songs?.playable()?.map { songNode(it, parentId) }
        is Parsed.ArtistId -> music.getArtist(p.id)?.let { music.getArtistAlbums(p.id).withPlayableSongs().map { albumNode(it) } }
        is Parsed.PlaylistId -> music.getPlaylist(p.id)?.songs?.playable()?.map { songNode(it, parentId) }
        is Parsed.SongId, is Parsed.SearchContext, is Parsed.Unknown -> null
    }

    private suspend fun libraryList(listId: String, songContext: String = listId): List<BrowseNode> = when (listId) {
        MediaIds.PLAYLISTS -> music.getPlaylists().map { playlistNode(it) }
        MediaIds.ARTISTS -> music.getArtists().map { artistNode(it) }
        MediaIds.ALBUMS -> music.getAlbums().withPlayableSongs().map { albumNode(it) }
        MediaIds.SONGS -> music.getSongs().playable().map { songNode(it, songContext) }
        else -> emptyList()
    }

    private fun bucketFolders(parentId: String, nodes: List<BrowseNode>): List<BrowseNode> =
        nodes.groupingBy { bucketOf(it.title) }.eachCount()
            .toSortedMap(compareBy { if (it == "#") "~" else it })
            .map { (letter, count) ->
                BrowseNode(MediaIds.bucket(parentId, letter), letter, "$count ${noun(parentId)}", null,
                    browsable = true, playable = false, kind = BrowseNode.Kind.BUCKET)
            }

    private fun noun(listId: String) = when (listId) {
        MediaIds.PLAYLISTS -> labels.playlists
        MediaIds.ARTISTS -> labels.artists
        MediaIds.ALBUMS -> labels.albums
        else -> labels.songs
    }.lowercase()

    /**
     * Songs the active player can actually play. Apple uploads/unmatched tracks (no catalog id) are
     * hidden rather than shown as dead entries.
     */
    private fun List<Song>.playable() = filter { it.isPlayable }

    /**
     * Albums that would open to at least one playable song. Albums made only of the user's own
     * uploads (no Apple Music catalog match) would otherwise open empty. Library songs don't carry
     * an album id, so this matches on album title: an album is never hidden if any playable song
     * has its title.
     */
    private suspend fun List<Album>.withPlayableSongs(): List<Album> {
        val titles = music.getSongs().filter { it.isPlayable }.mapTo(HashSet()) { albumKey(it.album) }
        if (titles.isEmpty()) return this // library songs unavailable: don't hide everything
        return filter { albumKey(it.title) in titles }
    }

    private fun albumKey(title: String) = title.trim().lowercase()

    /** The node for [mediaId], or null if unknown. */
    suspend fun getItem(mediaId: String): BrowseNode? = when (val p = MediaIds.parse(mediaId)) {
        is Parsed.Fixed -> fixedNodes[p.id]
        is Parsed.AlbumId -> music.getAlbum(p.id)?.let { albumNode(it) }
        is Parsed.ArtistId -> music.getArtist(p.id)?.let { artistNode(it) }
        is Parsed.PlaylistId -> music.getPlaylist(p.id)?.let { playlistNode(it) }
        is Parsed.SongId -> music.getSong(p.id)?.let { songNode(it, p.context) }
        is Parsed.Bucket -> BrowseNode(mediaId, p.letter, null, null, browsable = true, playable = false, kind = BrowseNode.Kind.BUCKET)
        is Parsed.SearchContext, is Parsed.Unknown -> null
    }

    /** Categorized search results: songs, then albums, artists, playlists. */
    suspend fun search(query: String): List<BrowseNode> {
        val results = music.search(query)
        val context = MediaIds.search(query)
        return results.songs.playable().map { songNode(it, context).copy(groupTitle = labels.songs) } +
            results.albums.map { albumNode(it).copy(groupTitle = labels.albums) } +
            results.artists.map { artistNode(it).copy(groupTitle = labels.artists) } +
            results.playlists.map { playlistNode(it).copy(groupTitle = labels.playlists) }
    }

    /**
     * Turns a played media id into a queue. A song plays within the container it was browsed
     * from; an album or playlist plays from its first track. Returns null if nothing playable.
     */
    suspend fun resolveQueue(mediaId: String): PlayQueue? = when (val p = MediaIds.parse(mediaId)) {
        is Parsed.SongId -> {
            // Resolve via the container first: some providers' ids (e.g. Apple library-playlist
            // entries) can only be looked up through their container, not individually.
            val context = p.context?.let { contextSongs(it) }.orEmpty().filter { it.isPlayable }
            val index = context.indexOfFirst { it.id == p.id }
            if (index >= 0) PlayQueue(context, index, p.context)
            else music.getSong(p.id)?.takeIf { it.isPlayable }?.let { PlayQueue(listOf(it), 0, null) }
        }
        is Parsed.AlbumId, is Parsed.PlaylistId, is Parsed.Fixed, is Parsed.SearchContext, is Parsed.Bucket ->
            contextSongs(mediaId).filter { it.isPlayable }.takeIf { it.isNotEmpty() }?.let { PlayQueue(it, 0, mediaId) }
        is Parsed.ArtistId ->
            music.getArtistAlbums(p.id).flatMap { music.getAlbum(it.id)?.songs.orEmpty() }
                .filter { it.isPlayable }.takeIf { it.isNotEmpty() }?.let { PlayQueue(it, 0, null) }
        is Parsed.Unknown -> null
    }

    /** Queue for a free-text play request (e.g. voice "play Daft Punk"). */
    suspend fun resolveSearchQueue(query: String): PlayQueue? {
        val results = music.search(query)
        val songs = results.songs.filter { it.isPlayable }
        if (songs.isNotEmpty()) return PlayQueue(songs, 0, MediaIds.search(query))
        results.albums.firstOrNull()?.let { return resolveQueue(MediaIds.album(it.id)) }
        results.playlists.firstOrNull()?.let { return resolveQueue(MediaIds.playlist(it.id)) }
        results.artists.firstOrNull()?.let { return resolveQueue(MediaIds.artist(it.id)) }
        return null
    }

    private suspend fun contextSongs(contextId: String): List<Song> = when (val p = MediaIds.parse(contextId)) {
        is Parsed.AlbumId -> music.getAlbum(p.id)?.songs.orEmpty()
        is Parsed.PlaylistId -> music.getPlaylist(p.id)?.songs.orEmpty()
        is Parsed.SearchContext -> music.search(p.query).songs
        is Parsed.Bucket -> if (p.parent == MediaIds.SONGS) music.getSongs().filter { bucketOf(it.title) == p.letter } else emptyList()
        is Parsed.Fixed -> when (p.id) {
            MediaIds.RECENT -> music.getRecentlyPlayed()
            MediaIds.SONGS -> music.getSongs()
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun fixed(vararg ids: String) = ids.map { fixedNodes.getValue(it) }

    private fun itemNode(item: MusicItem): BrowseNode = when (item) {
        is Song -> songNode(item, null)
        is Album -> albumNode(item)
        is Artist -> artistNode(item)
        is Playlist -> playlistNode(item)
    }

    private fun songNode(song: Song, context: String?) = BrowseNode(
        mediaId = if (context != null) MediaIds.song(song.id, context) else MediaIds.song(song.id),
        title = song.title,
        subtitle = song.artist,
        artworkUri = song.artworkUri,
        browsable = false,
        playable = song.isPlayable,
        kind = BrowseNode.Kind.SONG,
        song = song,
    )

    private fun albumNode(album: Album) = BrowseNode(
        MediaIds.album(album.id), album.title, album.artist, album.artworkUri,
        browsable = true, playable = false, kind = BrowseNode.Kind.ALBUM,
    )

    private fun artistNode(artist: Artist) = BrowseNode(
        MediaIds.artist(artist.id), artist.name, null, artist.artworkUri,
        browsable = true, playable = false, kind = BrowseNode.Kind.ARTIST,
    )

    private fun playlistNode(playlist: Playlist) = BrowseNode(
        MediaIds.playlist(playlist.id), playlist.title, playlist.description, playlist.artworkUri,
        browsable = true, playable = false, kind = BrowseNode.Kind.PLAYLIST,
    )
}
