package com.aaosmusic.automotive

import com.aaosmusic.media.MusicProvider
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.NETWORK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads library artwork in the background so browse tiles appear without a loading
 * placeholder. Fetches the same sized URLs [MediaItemMapper] hands to Media Center, most-seen
 * screens first, a few at a time, and waits while [isBuffering] so it never competes with
 * playback for bandwidth.
 */
class ArtworkPrefetcher(
    private val music: MusicProvider,
    private val cache: ArtworkCache,
    private val isBuffering: () -> Boolean,
) {
    /** Runs until everything is cached, the cache is nearly full, or the caller cancels. */
    suspend fun run(grid: Boolean) = withContext(Dispatchers.IO) {
        val browseSize = if (grid) ArtworkSize.TILE else ArtworkSize.ROW
        val urls = LinkedHashSet<String>()
        fun add(url: String?, size: Int) { url?.takeIf(ArtworkProvider::isAllowed)?.let { urls += ArtworkSize.resize(it, size) } }
        runCatching { music.getPlaylists().forEach { add(it.artworkUri, browseSize) } }
        runCatching { music.getArtists().forEach { add(it.artworkUri, browseSize) } }
        runCatching { music.getRecentlyPlayed().forEach { add(it.artworkUri, ArtworkSize.ROW) } }
        runCatching { music.getAlbums().forEach { add(it.artworkUri, browseSize) } }

        val missing = urls.filterNot(cache::contains)
        AppLog.i(NETWORK, "Artwork prefetch: ${missing.size} of ${urls.size} to download")
        val budget = cache.sizeBytes()
        if (budget >= PREFETCH_LIMIT) return@withContext

        val downloaded = AtomicInteger()
        val bytes = AtomicLong(budget)
        val permits = Semaphore(CONCURRENCY)
        coroutineScope {
            for (url in missing) {
                if (bytes.get() >= PREFETCH_LIMIT) break
                while (isBuffering()) delay(1_000)
                permits.acquire()
                launch {
                    try {
                        bytes.addAndGet(cache.fetch(url).length())
                        downloaded.incrementAndGet()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Logged by the cache; skip it, Media Center will retry on demand.
                    } finally {
                        permits.release()
                    }
                }
            }
        }
        cache.trim()
        AppLog.i(NETWORK, "Artwork prefetch done: $downloaded downloaded, cache ${cache.sizeBytes() / 1_048_576} MB")
    }

    private companion object {
        const val CONCURRENCY = 4
        /** Leave headroom so prefetching never evicts art the user has actually viewed. */
        const val PREFETCH_LIMIT = ArtworkCache.MAX_BYTES * 8 / 10
    }
}
