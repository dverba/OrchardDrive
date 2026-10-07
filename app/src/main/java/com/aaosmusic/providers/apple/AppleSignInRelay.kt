package com.aaosmusic.providers.apple

import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.AUTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Car side of the QR sign-in relay (relay/supabase/functions/signin). The car starts a session,
 * shows a QR code for the phone page, then polls until the phone hands over a Music User Token.
 */
class AppleSignInRelay(
    private val relayUrl: String,
    private val pageUrl: String,
) {
    data class Session(val id: String, val code: String, val expiresAt: Instant) {
        /** Code as shown to the user, e.g. `THQZ3-KS46G`. */
        val displayCode: String get() = code.chunked(5).joinToString("-")
    }

    sealed interface PollResult {
        data object Pending : PollResult
        data object Expired : PollResult
        data class Complete(val musicUserToken: String) : PollResult
    }

    /** URL the QR code encodes. */
    fun pageUrlFor(session: Session): String = pageUrl + "?c=" + URLEncoder.encode(session.code, "UTF-8")

    /** [developerToken] is optional: the relay signs one itself when it holds the MusicKit key. */
    suspend fun start(developerToken: String?): Session {
        val body = post("start", buildJsonObject { developerToken?.let { put("developer_token", it) } })
        return Session(
            id = body.str("session_id")!!,
            code = body.str("code")!!,
            expiresAt = Instant.parse(body.str("expires_at")!!.replace(Regex("\\+00:00$"), "Z")),
        ).also { AppLog.i(AUTH, "Sign-in session started, code ${it.displayCode}") }
    }

    suspend fun poll(session: Session): PollResult {
        val body = post("poll", buildJsonObject { put("session_id", session.id) }, allowGone = true)
        return when (body.str("status")) {
            "complete" -> body.str("music_user_token")?.let { PollResult.Complete(it) } ?: PollResult.Expired
            "pending" -> PollResult.Pending
            else -> PollResult.Expired
        }
    }

    private suspend fun post(route: String, payload: JsonObject, allowGone: Boolean = false): JsonObject =
        withContext(Dispatchers.IO) {
            val conn = (URL("$relayUrl/$route").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299 && !(allowGone && status == 410)) {
                    throw IllegalStateException("Sign-in relay $route failed: HTTP $status")
                }
                Json.parseToJsonElement(text.ifEmpty { "{}" }).jsonObject
            } finally {
                conn.disconnect()
            }
        }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
}
