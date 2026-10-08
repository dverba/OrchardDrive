package com.aaosmusic

import com.aaosmusic.automotive.ArtworkSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkSizeTest {
    @Test fun resizesAppleArtworkUrl() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/source/400x400bb.jpg",
            ArtworkSize.resize("https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/source/600x600bb.jpg", 400),
        )
    }

    @Test fun keepsSuffixAndExtension() {
        assertEquals("https://x.mzstatic.com/a/200x200cc.webp", ArtworkSize.resize("https://x.mzstatic.com/a/600x600cc.webp", 200))
    }

    @Test fun keepsEffectSuffixWithDots() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Features/v4/0e/a4.png/400x400SC.CAHGOY01.jpg",
            ArtworkSize.resize("https://is1-ssl.mzstatic.com/image/thumb/Features/v4/0e/a4.png/600x600SC.CAHGOY01.jpg", 400),
        )
    }

    @Test fun keepsQueryOfGeneratedArt() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/gen/200x200AM.PDCXS19.jpg?c1=9DE1F2&signature=b215",
            ArtworkSize.resize("https://is1-ssl.mzstatic.com/image/thumb/gen/1200x1200AM.PDCXS19.jpg?c1=9DE1F2&signature=b215", 200),
        )
    }

    @Test fun leavesOtherUrlsAlone() {
        val url = "android.resource://com.aaosmusic/drawable/art_album_1"
        assertEquals(url, ArtworkSize.resize(url, 200))
    }
}
