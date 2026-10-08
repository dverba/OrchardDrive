package com.aaosmusic

import com.aaosmusic.automotive.BrowseNode
import com.aaosmusic.automotive.LibraryTree
import com.aaosmusic.automotive.MediaIds
import com.aaosmusic.providers.fake.FakeMusicProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTreeTest {
    private val provider = FakeMusicProvider("res://", latencyMs = 0)
    private val tree = LibraryTree(provider)

    private suspend fun ids(parent: String) = tree.getChildren(parent)!!.map { it.mediaId }

    @Test fun topLevelHierarchy() = runTest {
        assertEquals(listOf(MediaIds.HOME, MediaIds.LIBRARY), ids(MediaIds.ROOT))
        assertEquals(listOf(MediaIds.RECENT, MediaIds.RECOMMENDED), ids(MediaIds.HOME))
        assertEquals(listOf(MediaIds.PLAYLISTS, MediaIds.ARTISTS, MediaIds.ALBUMS, MediaIds.SONGS), ids(MediaIds.LIBRARY))
    }

    @Test fun libraryCounts() = runTest {
        assertEquals(3, ids(MediaIds.ALBUMS).size)
        assertEquals(3, ids(MediaIds.ARTISTS).size)
        assertEquals(3, ids(MediaIds.PLAYLISTS).size)
        assertEquals(10, ids(MediaIds.SONGS).size)
    }

    @Test fun albumChildrenArePlayableSongsCarryingAlbumContext() = runTest {
        val children = tree.getChildren(MediaIds.album("alb1"))!!
        assertEquals(4, children.size)
        assertTrue(children.all { it.playable && !it.browsable && it.kind == BrowseNode.Kind.SONG })
        assertEquals(MediaIds.song("s1", "album:alb1"), children.first().mediaId)
        assertEquals("Test Song", children.first().title)
    }

    @Test fun artistChildrenAreAlbums() = runTest {
        assertEquals(listOf(MediaIds.album("alb1")), ids(MediaIds.artist("art1")))
    }

    @Test fun unknownIdsReturnNull() = runTest {
        assertNull(tree.getChildren("album:missing"))
        assertNull(tree.getChildren("garbage"))
        assertNull(tree.getItem("song:missing"))
    }

    @Test fun playingSongQueuesItsAlbumFromThatTrack() = runTest {
        val queue = tree.resolveQueue(MediaIds.song("s3", MediaIds.album("alb1")))!!
        assertEquals(listOf("s1", "s2", "s3", "s4"), queue.songs.map { it.id })
        assertEquals(2, queue.startIndex)
        assertEquals(MediaIds.song("s4", "album:alb1"), queue.mediaIdOf(queue.songs[3]))
    }

    @Test fun playingSongQueuesItsPlaylist() = runTest {
        val queue = tree.resolveQueue(MediaIds.song("s8", MediaIds.playlist("pl1")))!!
        assertEquals(listOf("s1", "s5", "s8", "s2", "s9"), queue.songs.map { it.id })
        assertEquals(2, queue.startIndex)
    }

    @Test fun songWithoutContextQueuesAlone() = runTest {
        val queue = tree.resolveQueue(MediaIds.song("s7"))!!
        assertEquals(listOf("s7"), queue.songs.map { it.id })
    }

    @Test fun playingAlbumStartsAtFirstTrack() = runTest {
        val queue = tree.resolveQueue(MediaIds.album("alb2"))!!
        assertEquals(listOf("s5", "s6", "s7"), queue.songs.map { it.id })
        assertEquals(0, queue.startIndex)
    }

    @Test fun searchIsCategorized() = runTest {
        val results = tree.search("test")
        val groups = results.map { it.groupTitle }.distinct()
        assertEquals(listOf("Songs", "Albums", "Artists"), groups)
        assertTrue(results.first { it.groupTitle == "Songs" }.mediaId.endsWith("@search:test"))
    }

    @Test fun voiceSearchFallsBackToAlbum() = runTest {
        val queue = tree.resolveSearchQueue("Segfault")!!
        assertEquals(listOf("s8", "s9", "s10"), queue.songs.map { it.id })
    }

    @Test fun longQueuesAreWindowedAroundStart() = runTest {
        val q = tree.resolveQueue(MediaIds.song("s8", MediaIds.SONGS))!!   // 10 songs, start 7
        val w = q.windowed(max = 4, before = 1)
        assertEquals(listOf("s7", "s8", "s9", "s10"), w.songs.map { it.id })
        assertEquals("s8", w.songs[w.startIndex].id)
        val atStart = tree.resolveQueue(MediaIds.song("s1", MediaIds.SONGS))!!.windowed(max = 4, before = 2)
        assertEquals(0, atStart.startIndex)
        assertEquals(4, atStart.songs.size)
    }

    @Test fun unplayableSongsAreHidden() = runTest {
        val withUpload = object : com.aaosmusic.media.MusicProvider by provider {
            override suspend fun getSongs() = provider.getSongs() + provider.getSongs().first().copy(id = "up1", playbackUri = null)
        }
        assertEquals(10, LibraryTree(withUpload).getChildren(MediaIds.SONGS)!!.size)
    }

    @Test fun albumsWithOnlyUploadsAreHidden() = runTest {
        val upload = provider.getAlbums().first().copy(id = "upAlb", title = "My CD Rip")
        val withUploadAlbum = object : com.aaosmusic.media.MusicProvider by provider {
            override suspend fun getAlbums() = provider.getAlbums() + upload
            override suspend fun getSongs() =
                provider.getSongs() + provider.getSongs().first().copy(id = "up1", album = "My CD Rip", playbackUri = null)
        }
        val albums = LibraryTree(withUploadAlbum).getChildren(MediaIds.ALBUMS)!!.map { it.mediaId }
        assertEquals(3, albums.size)
        assertTrue(MediaIds.album("upAlb") !in albums)
    }

    @Test fun emptySearch() = runTest {
        assertTrue(tree.search("   ").isEmpty())
        assertNull(tree.resolveSearchQueue("zzz"))
    }
}

class LibraryTreeBucketTest {
    private val tree = LibraryTree(FakeMusicProvider("res://", latencyMs = 0), bucketThreshold = 5)

    @Test fun largeSongListIsSplitIntoLetterFolders() = runTest {
        val buckets = tree.getChildren(MediaIds.SONGS)!!
        assertTrue(buckets.all { it.kind == BrowseNode.Kind.BUCKET && it.browsable })
        // Fake titles start with A, C, D, E, G, S, T, U (one bucket per distinct letter, sorted).
        assertEquals(buckets.map { it.title }.sorted(), buckets.map { it.title })
        assertEquals(10, buckets.sumOf { it.subtitle!!.substringBefore(' ').toInt() })
        assertTrue(buckets.first().subtitle!!.endsWith(" songs"))
    }

    @Test fun bucketHoldsMatchingSongsAndQueuesWithinBucket() = runTest {
        val d = tree.getChildren(MediaIds.bucket(MediaIds.SONGS, "D"))!!
        assertEquals(listOf("Dangling Reference", "Dispatchers.Main"), d.map { it.title }.sorted())
        val queue = tree.resolveQueue(d.first().mediaId)!!
        assertEquals(2, queue.songs.size)
        assertTrue(queue.songs.all { it.title.startsWith("D") })
    }

    @Test fun smallListsAreNotBucketed() = runTest {
        assertTrue(tree.getChildren(MediaIds.ALBUMS)!!.all { it.kind == BrowseNode.Kind.ALBUM })
    }

    @Test fun bucketLetters() {
        assertEquals("E", com.aaosmusic.automotive.bucketOf("Édith"))
        assertEquals("#", com.aaosmusic.automotive.bucketOf("2Pac"))
        assertEquals("#", com.aaosmusic.automotive.bucketOf("坂本龍一"))
        assertEquals("T", com.aaosmusic.automotive.bucketOf("  the Weeknd"))
    }

    @Test fun bucketIdsParse() {
        assertEquals(MediaIds.Parsed.Bucket(MediaIds.SONGS, "A"), MediaIds.parse("library/songs/A"))
        assertEquals(MediaIds.Parsed.Fixed(MediaIds.SONGS), MediaIds.parse("library/songs"))
    }
}
