package com.aaosmusic.providers.apple

import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.APPLE_API
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AppleMusicApiException(val status: Int, message: String) : Exception(message)

/** Minimal Apple Music API client. Tokens are sent as headers and never logged. */
class AppleMusicApiClient(private val tokens: AppleMusicTokenStore) {

    var lastStatus: Int? = null
        private set

    /** The signed-in user's storefront (country), or null when no user token is stored. */
    suspend fun userStorefront(): String? {
        if (tokens.userToken == null) return null
        return AppleMusicJson.parseStorefrontId(get("/v1/me/storefront", withUserToken = true))
    }

    suspend fun search(term: String, storefront: String = DEFAULT_STOREFRONT, limit: Int = 10): AppleMusicJson.ParsedSearch {
        val q = URLEncoder.encode(term, "UTF-8")
        val body = get("/v1/catalog/$storefront/search?term=$q&types=songs,albums,artists,playlists&limit=$limit")
        return AppleMusicJson.parseSearch(body).also {
            val r = it.results
            AppLog.i(APPLE_API, "Search '$term' ($storefront): ${r.songs.size} songs, ${r.albums.size} albums, " +
                "${r.artists.size} artists, ${r.playlists.size} playlists")
        }
    }

    private suspend fun get(path: String, withUserToken: Boolean = false): String = withContext(Dispatchers.IO) {
        val developerToken = tokens.developerToken
            ?: throw AppleMusicApiException(0, "No developer token (apple-music.properties missing?)")
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $developerToken")
            if (withUserToken) tokens.userToken?.let { setRequestProperty("Music-User-Token", it) }
        }
        try {
            val status = conn.responseCode
            lastStatus = status
            AppLog.d(APPLE_API, "GET ${path.substringBefore('?')} → $status")
            if (status !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw AppleMusicApiException(status, "HTTP $status: ${err.take(300)}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://api.music.apple.com"
        const val DEFAULT_STOREFRONT = "us"
    }
}
