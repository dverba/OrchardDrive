package com.aaosmusic.automotive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.aaosmusic.BuildConfig
import java.io.FileNotFoundException
import java.net.URL

/**
 * Serves remote artwork to AAOS Media Center as `content://` URIs. Media Center only loads
 * `content://` / `android.resource://` artwork for browse items, so https artwork URLs are
 * rewritten by [toContentUri] and served from [ArtworkCache] (downloaded on first request).
 *
 * Only Apple's artwork CDN is proxied so the exported provider can't be used as an open proxy.
 */
class ArtworkProvider : ContentProvider() {

    private val cache by lazy { ArtworkCache(context!!) }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val remote = uri.lastPathSegment
            ?.let { String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
            ?: throw FileNotFoundException("No artwork URL in $uri")
        if (!isAllowed(remote)) throw FileNotFoundException("Artwork host not allowed")

        val file = cache.fetch(remote)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.artwork"

        /**
         * https artwork → content URI served by this provider, re-sized to [size] px (see
         * [ArtworkSize]); other URIs pass through.
         */
        fun toContentUri(artwork: String?, size: Int): Uri? {
            if (artwork == null) return null
            if (!artwork.startsWith("https://")) return Uri.parse(artwork)
            val sized = ArtworkSize.resize(artwork, size)
            val encoded = Base64.encodeToString(sized.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(encoded).build()
        }

        /** Apple's artwork CDN only; anything else is neither served nor prefetched. */
        fun isAllowed(url: String): Boolean {
            val host = runCatching { URL(url).host }.getOrNull() ?: return false
            return url.startsWith("https://") && (host == "mzstatic.com" || host.endsWith(".mzstatic.com"))
        }
    }
}
