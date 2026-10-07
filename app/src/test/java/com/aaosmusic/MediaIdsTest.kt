package com.aaosmusic

import com.aaosmusic.automotive.MediaIds
import com.aaosmusic.automotive.MediaIds.Parsed
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIdsTest {
    @Test fun fixedIds() {
        assertEquals(Parsed.Fixed(MediaIds.ALBUMS), MediaIds.parse("library/albums"))
    }

    @Test fun entityIds() {
        assertEquals(Parsed.AlbumId("alb1"), MediaIds.parse(MediaIds.album("alb1")))
        assertEquals(Parsed.ArtistId("art2"), MediaIds.parse(MediaIds.artist("art2")))
        assertEquals(Parsed.PlaylistId("pl3"), MediaIds.parse(MediaIds.playlist("pl3")))
    }

    @Test fun songWithContextRoundTrips() {
        assertEquals(Parsed.SongId("s3", "album:alb1"), MediaIds.parse(MediaIds.song("s3", MediaIds.album("alb1"))))
        assertEquals(Parsed.SongId("s3", "library/songs"), MediaIds.parse(MediaIds.song("s3", MediaIds.SONGS)))
        assertEquals(Parsed.SongId("s3", null), MediaIds.parse(MediaIds.song("s3")))
    }

    @Test fun searchContextMayContainSeparator() {
        val id = MediaIds.song("s1", MediaIds.search("a@b"))
        assertEquals(Parsed.SongId("s1", "search:a@b"), MediaIds.parse(id))
    }

    @Test fun unknown() {
        assertEquals(Parsed.Unknown("nope"), MediaIds.parse("nope"))
    }
}
