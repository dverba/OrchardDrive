package com.aaosmusic.automotive

import android.content.Context
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.NETWORK
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Disk cache for remote artwork, shared by [ArtworkProvider] (on-demand) and
 * [ArtworkPrefetcher] (background). Lives in filesDir, not cacheDir, so the system doesn't wipe
 * it under storage pressure; [trim] keeps it under [maxBytes], evicting least recently used.
 */
class ArtworkCache(context: Context, private val maxBytes: Long = MAX_BYTES) {
    private val dir = File(context.applicationContext.filesDir, "artwork").apply { mkdirs() }

    init {
        // Pre-filesDir location; nothing reads it any more.
        File(context.applicationContext.cacheDir, "artwork").takeIf { it.exists() }?.deleteRecursively()
    }

    fun contains(url: String) = fileFor(url).exists()

    /** The cached file for [url], downloading it first if needed. Marks it recently used. */
    fun fetch(url: String): File {
        val file = fileFor(url)
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
        } else {
            download(url, file)
            if (downloadsSinceTrim.incrementAndGet() >= TRIM_EVERY) trim()
        }
        return file
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes least recently used files until the cache fits in [maxBytes]. */
    @Synchronized
    fun trim() {
        downloadsSinceTrim.set(0)
        val files = dir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fileFor(url: String) = File(dir, sha1(url))

    private fun download(url: String, target: File) {
        // Unique temp name: the provider and the prefetcher may fetch the same image at once.
        val tmp = File(dir, "${target.name}.${UUID.randomUUID()}.tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) throw FileNotFoundException("Artwork HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            AppLog.w(NETWORK, "Artwork download failed: ${e.message}")
            throw e as? FileNotFoundException ?: FileNotFoundException(e.message)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_BYTES = 300L * 1024 * 1024
        private const val TRIM_EVERY = 100
        private val downloadsSinceTrim = java.util.concurrent.atomic.AtomicInteger()

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Artwork edge lengths in pixels, by where the image is shown. */
object ArtworkSize {
    /** Grid tile (~400 px on a 1920×1200 @160 dpi screen). */
    const val TILE = 400
    /** List-row thumbnail. */
    const val ROW = 200
    /** Now Playing and the queue, shown large. */
    const val FULL = 600

    /**
     * `/600x600bb.jpg`, `/600x600SC.FPESS04.jpg` (effect suffixes keep their dots), and generated
     * playlist covers `/gen/1200x1200AM.PDCXS19.jpg?c1=…&signature=…` (the signature allows resizing).
     */
    private val sizeSegment = Regex("""/\d+x\d+([A-Za-z0-9.\-]*)\.(jpg|jpeg|png|webp)(\?.*)?$""")

    /** Apple artwork URL ([AppleMusicJson] requests 600×600) re-sized to [px]; other URLs unchanged. */
    fun resize(url: String, px: Int): String = sizeSegment.replace(url) { "/${px}x$px${it.groupValues[1]}.${it.groupValues[2]}${it.groupValues[3]}" }
}
