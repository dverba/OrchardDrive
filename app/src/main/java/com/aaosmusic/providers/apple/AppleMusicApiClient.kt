package com.aaosmusic.providers.apple

import com.aaosmusic.media.MusicProviderException
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.APPLE_API
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 401/403 (or no stored user token) means the Music User Token is missing/expired: sign in again. */
class AppleMusicApiException(val status: Int, message: String) :
    MusicProviderException(message, signInRequired = status == 401 || status == 403 || status == NO_USER_TOKEN) {

    companion object {
        /** Pseudo-status for calls that need a Music User Token when none is stored. */
        const val NO_USER_TOKEN = -401
    }
}

/** Minimal Apple Music API client. Tokens are sent as headers and never logged. */
class AppleMusicApiClient(private val tokens: AppleMusicTokenStore) {

    var lastStatus: Int? = null
        private set

    /** The signed-in user's storefront (country), or null when no user token is stored. */
    suspend fun userStorefront(): String? {
        if (tokens.userToken == null) return null
        return AppleMusicJson.parseStorefrontId(get("/v1/me/storefront"))
    }

    /** GET a path; [withUserToken] adds the Music-User-Token header (required for /v1/me/...). */
    suspend fun get(path: String, withUserToken: Boolean = path.startsWith("/v1/me/")): String {
        if (withUserToken && tokens.userToken == null) {
            throw AppleMusicApiException(AppleMusicApiException.NO_USER_TOKEN, "Not signed in to Apple Music")
        }
        return request("GET", path, withUserToken)
    }

    /** PUT/POST/DELETE on the user's account (`/v1/me/...`); [jsonBody] is sent as the request body. */
    suspend fun send(method: String, path: String, jsonBody: String? = null): String {
        if (tokens.userToken == null) {
            throw AppleMusicApiException(AppleMusicApiException.NO_USER_TOKEN, "Not signed in to Apple Music")
        }
        return request(method, path, withUserToken = true, jsonBody)
    }

    /**
     * Fetches a whole collection. When the first page reports `meta.total` (library endpoints do),
     * the remaining pages are fetched in parallel; otherwise `next` links are followed.
     */
    suspend fun getAllParallel(path: String, maxItems: Int, pageSize: Int = 100, concurrency: Int = 8): List<kotlinx.serialization.json.JsonObject> =
        kotlinx.coroutines.coroutineScope {
            val sep = if ('?' in path) '&' else '?'
            val first = AppleMusicJson.parsePage(get("$path${sep}limit=$pageSize&offset=0"))
            val total = first.total ?: return@coroutineScope (first.items + getAll(first.next ?: return@coroutineScope first.items, maxItems - first.items.size, pageSize)).take(maxItems)
            val offsets = (pageSize until minOf(total, maxItems) step pageSize).toList()
            val gate = kotlinx.coroutines.sync.Semaphore(concurrency)
            val pages = offsets.map { offset ->
                async { gate.withPermit { AppleMusicJson.parsePage(get("$path${sep}limit=$pageSize&offset=$offset")).items } }
            }.awaitAll()
            AppLog.d(APPLE_API, "Fetched ${path.substringBefore('?')}: $total items in ${offsets.size + 1} pages")
            (first.items + pages.flatten()).take(maxItems)
        }

    /** Follows `next` links until [maxItems] resources are collected or pages run out. */
    suspend fun getAll(path: String, maxItems: Int, pageSize: Int = 100): List<kotlinx.serialization.json.JsonObject> {
        val items = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var next: String? = path
        while (next != null && items.size < maxItems) {
            // Apple's `next` links carry the offset but not always the limit.
            val url = if ("limit=" in next) next else next + (if ('?' in next) '&' else '?') + "limit=$pageSize"
            val page = AppleMusicJson.parsePage(get(url))
            items += page.items
            next = page.next
        }
        return items.take(maxItems)
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

    private suspend fun request(method: String, path: String, withUserToken: Boolean, jsonBody: String? = null): String = withContext(Dispatchers.IO) {
        val developerToken = tokens.ensureDeveloperToken()
            ?: throw AppleMusicApiException(0, "No Apple developer token (relay unreachable or not configured)")
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $developerToken")
            if (withUserToken) tokens.userToken?.let { setRequestProperty("Music-User-Token", it) }
            requestMethod = method
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            jsonBody?.let { body -> conn.outputStream.use { it.write(body.toByteArray()) } }
            val status = conn.responseCode
            lastStatus = status
            AppLog.d(APPLE_API, "$method ${path.substringBefore('?')} → $status")
            if (status !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                AppLog.w(APPLE_API, "$method ${path.substringBefore('?')} failed: HTTP $status ${err.take(300)}")
                // 403 with a Music-User-Token means Apple no longer accepts it (expired or revoked).
                if (status == 403 && withUserToken) tokens.markUserTokenRejected()
                // Shown to the user by Media Center, so plain language rather than Apple's JSON.
                throw AppleMusicApiException(status, when (status) {
                    401, 403 -> SIGN_IN_EXPIRED_MESSAGE
                    else -> "Apple Music isn't responding (error $status). Try again in a moment."
                })
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://api.music.apple.com"
        const val DEFAULT_STOREFRONT = "us"
        const val SIGN_IN_EXPIRED_MESSAGE = "Your Apple Music sign-in has expired. Sign in again to see your music."
    }
}
