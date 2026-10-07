package com.aaosmusic.util

import android.util.Log

/**
 * Structured logging. Every line is tagged `AAOSMusic/<CATEGORY>` so logcat can be filtered with
 * e.g. `adb logcat -s AAOSMusic/PLAYBACK AAOSMusic/AAOS_LIBRARY`.
 *
 * Never pass raw credentials; use [redact].
 */
object AppLog {
    enum class Category { AUTH, APPLE_API, APPLE_PLAYER, MEDIA_SESSION, AAOS_LIBRARY, PLAYBACK, NETWORK, APP }

    fun d(category: Category, message: String) = Log.d(tag(category), message)
    fun i(category: Category, message: String) = Log.i(tag(category), message)
    fun w(category: Category, message: String, t: Throwable? = null) = Log.w(tag(category), message, t)
    fun e(category: Category, message: String, t: Throwable? = null) = Log.e(tag(category), message, t)

    /** Shows only enough of a secret to tell tokens apart: `eyJh…(812 chars)…9xQ`. */
    fun redact(secret: String?): String = when {
        secret.isNullOrEmpty() -> "<none>"
        secret.length <= 12 -> "<redacted>"
        else -> "${secret.take(4)}…(${secret.length} chars)…${secret.takeLast(3)}"
    }

    private fun tag(category: Category) = "AAOSMusic/${category.name}"
}
