package com.aaosmusic.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured logging. Every line is tagged `OrchardDrive/<CATEGORY>` so logcat can be filtered with
 * e.g. `adb logcat -s OrchardDrive/PLAYBACK OrchardDrive/AAOS_LIBRARY`.
 *
 * The last [BUFFER_LINES] lines are also kept in memory for in-car debug reports (the car has no
 * adb). Never pass raw credentials; use [redact].
 */
object AppLog {
    enum class Category { AUTH, APPLE_API, APPLE_PLAYER, MEDIA_SESSION, AAOS_LIBRARY, PLAYBACK, NETWORK, APP }

    private const val BUFFER_LINES = 3000
    private val buffer = ArrayDeque<String>(BUFFER_LINES)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(category: Category, message: String) = log(Log.DEBUG, category, message, null)
    fun i(category: Category, message: String) = log(Log.INFO, category, message, null)
    fun w(category: Category, message: String, t: Throwable? = null) = log(Log.WARN, category, message, t)
    fun e(category: Category, message: String, t: Throwable? = null) = log(Log.ERROR, category, message, t)

    /** Shows only enough of a secret to tell tokens apart: `eyJh…(812 chars)…9xQ`. */
    fun redact(secret: String?): String = when {
        secret.isNullOrEmpty() -> "<none>"
        secret.length <= 12 -> "<redacted>"
        else -> "${secret.take(4)}…(${secret.length} chars)…${secret.takeLast(3)}"
    }

    /** Masks anything token-shaped (JWTs, long base64 runs) in text that leaves the device. */
    fun scrub(text: String): String = text
        .replace(Regex("""[\w-]{10,}\.[\w-]{10,}\.[\w-]{10,}"""), "<jwt>")
        .replace(Regex("""[A-Za-z0-9+/=_.-]{120,}"""), "<long-token>")

    /** Recent log lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    private fun log(priority: Int, category: Category, message: String, t: Throwable?) {
        val tag = "OrchardDrive/${category.name}"
        if (t == null) Log.println(priority, tag, message) else Log.println(priority, tag, message + "\n" + Log.getStackTraceString(t))
        val level = "??VDIWEA"[priority.coerceIn(Log.VERBOSE, Log.ASSERT)] // Log.VERBOSE == 2
        val line = buildString {
            append(synchronized(time) { time.format(Date()) }).append(' ').append(level).append(' ')
            append(category.name).append(": ").append(message)
            t?.let { append(" | ").append(it.javaClass.simpleName).append(": ").append(it.message) }
        }
        synchronized(buffer) {
            if (buffer.size == BUFFER_LINES) buffer.removeFirst()
            buffer.addLast(line)
        }
    }
}
