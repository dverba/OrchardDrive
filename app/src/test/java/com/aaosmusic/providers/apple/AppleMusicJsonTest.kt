package com.aaosmusic.providers.apple

import com.aaosmusic.media.models.Album
import com.aaosmusic.media.models.Playlist
import com.aaosmusic.media.models.Rating
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicJsonTest {

    private val artwork = """"artwork": {"url": "https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg", "width": 3000}"""

    @Test fun catalogSongPlaysByItsOwnId() {
        val page = AppleMusicJson.parsePage("""{"data": [{"id": "697195462", "type": "songs", "attributes": {
            "name": "One More Time", "artistName": "Daft Punk", "albumName": "Discovery",
            "durationInMillis": 320357, "trackNumber": 1, $artwork}}]}""")
        val song = AppleMusicJson.song(page.items.single())
        assertEquals("697195462", song.id)
        assertEquals("applemusic:song:697195462", song.playbackUri)
        assertEquals(320357L, song.durationMs)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg", song.artworkUri)
        assertEquals("697195462", AppleMusicJson.catalogIdFromPlaybackUri(song.playbackUri))
    }

    @Test fun librarySongPlaysByCatalogId() {
        val page = AppleMusicJson.parsePage("""{"data": [{"id": "i.abc", "type": "library-songs", "attributes": {
            "name": "Left Hand Free", "artistName": "alt-J", "albumName": "This Is All Yours",
            "playParams": {"id": "i.abc", "kind": "song", "isLibrary": true, "catalogId": "887763877"}}}]}""")
        val song = AppleMusicJson.song(page.items.single())
        assertEquals("i.abc", song.id)
        assertEquals("applemusic:song:887763877", song.playbackUri)
    }

    @Test fun uploadedSongWithoutCatalogMatchIsUnplayable() {
        val page = AppleMusicJson.parsePage("""{"data": [{"id": "i.up", "type": "library-songs", "attributes": {
            "name": "My Demo", "artistName": "Me", "albumName": "",
            "playParams": {"id": "i.up", "kind": "song", "isLibrary": true}}}]}""")
        assertNull(AppleMusicJson.song(page.items.single()).playbackUri)
    }

    @Test fun albumIncludesTracksAndInheritsArtwork() {
        val album = AppleMusicJson.album(AppleMusicJson.parsePage("""{"data": [{"id": "l.alb", "type": "library-albums",
            "attributes": {"name": "Discovery", "artistName": "Daft Punk", $artwork},
            "relationships": {"tracks": {"data": [
                {"id": "i.1", "type": "library-songs", "attributes": {"name": "One More Time", "artistName": "Daft Punk",
                    "albumName": "Discovery", "playParams": {"catalogId": "1"}}},
                {"id": "i.2", "type": "library-music-videos", "attributes": {"name": "Video"}}
            ]}}}]}""").items.single())
        assertEquals("Discovery", album.title)
        assertEquals(listOf("i.1"), album.songs.map { it.id })
        assertEquals("l.alb", album.songs.single().albumId)
        assertEquals(album.artworkUri, album.songs.single().artworkUri)
    }

    @Test fun libraryArtistUsesCatalogArtwork() {
        val artist = AppleMusicJson.artist(AppleMusicJson.parsePage("""{"data": [{"id": "r.1", "type": "library-artists",
            "attributes": {"name": "A Perfect Circle"},
            "relationships": {"catalog": {"data": [{"id": "1", "type": "artists", "attributes": {"name": "A Perfect Circle", $artwork}}]}}}]}""").items.single())
        assertEquals("A Perfect Circle", artist.name)
        assertTrue(artist.artworkUri!!.startsWith("https://"))
    }

    @Test fun recommendationGroupsKeepTitlesAndDropStations() {
        val groups = AppleMusicJson.parseRecommendationGroups("""{"data": [
            {"id": "rec1", "type": "personal-recommendation", "attributes": {"kind": "music-recommendations",
                "title": {"stringForDisplay": "Playlists Made for You"}}, "relationships": {"contents": {"data": [
                {"id": "pl.pm-1", "type": "playlists", "attributes": {"name": "Heavy Rotation", "description": {"short": "Tracks you love"}}},
                {"id": "ra.1", "type": "stations", "attributes": {"name": "Station"}},
                {"id": "100", "type": "albums", "attributes": {"name": "Random Access Memories", "artistName": "Daft Punk"}}
            ]}}},
            {"id": "rec2", "type": "personal-recommendation", "attributes": {"title": {"stringForDisplay": "Stations for You"}},
                "relationships": {"contents": {"data": [{"id": "ra.2", "type": "stations", "attributes": {"name": "Station"}}]}}}
        ]}""")
        assertEquals(listOf("Playlists Made for You"), groups.map { it.title })
        val items = groups.single().items
        assertEquals(listOf("pl.pm-1", "100"), items.map { it.id })
        assertEquals("Tracks you love", (items[0] as Playlist).description)
        assertTrue(items[1] is Album)
    }

    @Test fun chartsBecomeShelvesById() {
        val shelves = AppleMusicJson.parseCharts("""{"results": {
            "songs": [{"chart": "most-played", "name": "Top Songs", "data": [{"id": "1", "type": "songs", "attributes": {"name": "Solar Eclipse"}}]}],
            "albums": [{"chart": "most-played", "name": "Top Albums", "data": []}]
        }}""")
        assertEquals(listOf("songs"), shelves.map { it.id })
        assertEquals("Top Songs", shelves.single().title)
        assertEquals("Solar Eclipse", shelves.single().items.single().title)
    }

    @Test fun pageExposesNextLink() {
        val page = AppleMusicJson.parsePage("""{"next": "/v1/me/library/songs?offset=100", "data": []}""")
        assertEquals("/v1/me/library/songs?offset=100", page.next)
        assertNull(AppleMusicJson.parsePage("""{"data": []}""").next)
    }

    @Test fun searchIsCategorizedWithPreviews() {
        val parsed = AppleMusicJson.parseSearch("""{"results": {
            "songs": {"data": [{"id": "1", "type": "songs", "attributes": {"name": "Get Lucky", "artistName": "Daft Punk",
                "albumName": "RAM", "previews": [{"url": "https://audio-ssl.itunes.apple.com/x.m4a"}]}}]},
            "artists": {"data": [{"id": "5468295", "type": "artists", "attributes": {"name": "Daft Punk"}}]}
        }}""")
        assertEquals(listOf("Get Lucky"), parsed.results.songs.map { it.title })
        assertEquals(listOf("Daft Punk"), parsed.results.artists.map { it.name })
        assertTrue(parsed.results.albums.isEmpty())
        assertEquals("https://audio-ssl.itunes.apple.com/x.m4a", parsed.previews["1"])
    }

    @Test fun ratingValuesMapToLoveAndDislike() {
        fun rating(value: Int) = AppleMusicJson.parseRating("""{"data": [{"id": "1", "type": "ratings", "attributes": {"value": $value}}]}""")
        assertEquals(Rating.LOVE, rating(1))
        assertEquals(Rating.DISLIKE, rating(-1))
        assertEquals("""{"type":"rating","attributes":{"value":-1}}""", AppleMusicJson.ratingBody(Rating.DISLIKE))
    }

    @Test fun catalogSongKnowsItsAlbumAndLibraryCopy() {
        val song = AppleMusicJson.parsePage("""{"data": [{"id": "697195462", "type": "songs", "relationships": {
            "albums": {"data": [{"id": "697194953", "type": "albums"}]},
            "library": {"data": [{"id": "i.abc", "type": "library-songs"}]}}}]}""").items.single()
        assertEquals("697194953", AppleMusicJson.albumIdOf(song))
        assertTrue(AppleMusicJson.inLibrary(song))
    }

    @Test fun partlyAddedAlbumIsNotInLibrary() {
        fun album(libraryTracks: Int?) = AppleMusicJson.parsePage("""{"data": [{"id": "697194953", "type": "albums",
            "attributes": {"trackCount": 14}, "relationships": {"library": {"data": [""" +
            (libraryTracks?.let { """{"id": "l.x", "type": "library-albums", "attributes": {"trackCount": $it}}""" } ?: "") +
            """]}}}]}""").items.single()
        assertFalse(AppleMusicJson.albumFullyInLibrary(album(null)))
        assertFalse(AppleMusicJson.albumFullyInLibrary(album(1)))
        assertTrue(AppleMusicJson.albumFullyInLibrary(album(14)))
    }
}
