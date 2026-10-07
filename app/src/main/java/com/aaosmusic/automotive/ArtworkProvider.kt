package com.aaosmusic.automotive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.aaosmusic.BuildConfig
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.NETWORK
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Serves remote artwork to AAOS Media Center as `content://` URIs. Media Center only loads
 * `content://` / `android.resource://` artwork for browse items, so https artwork URLs are
 * rewritten by [toContentUri] and downloaded (and disk-cached) here on first request.
 *
 * Only Apple's artwork CDN is proxied so the exported provider can't be used as an open proxy.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val remote = uri.lastPathSegment
            ?.let { String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
            ?: throw FileNotFoundException("No artwork URL in $uri")
        if (!isAllowed(remote)) throw FileNotFoundException("Artwork host not allowed")

        val dir = File(context!!.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1(remote))
        if (!file.exists()) download(remote, file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"

    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode !in 200..299) throw FileNotFoundException("Artwork HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            AppLog.w(NETWORK, "Artwork download failed: ${e.message}")
            throw e as? FileNotFoundException ?: FileNotFoundException(e.message)
        } finally {
            conn.disconnect()
        }
    }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.artwork"

        /** https artwork → content URI served by this provider; other URIs pass through. */
        fun toContentUri(artwork: String?): Uri? {
            if (artwork == null) return null
            if (!artwork.startsWith("https://")) return Uri.parse(artwork)
            val encoded = Base64.encodeToString(artwork.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(encoded).build()
        }

        private fun isAllowed(url: String): Boolean {
            val host = runCatching { URL(url).host }.getOrNull() ?: return false
            return url.startsWith("https://") && (host == "mzstatic.com" || host.endsWith(".mzstatic.com"))
        }

        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
