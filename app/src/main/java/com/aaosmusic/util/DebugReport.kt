package com.aaosmusic.util

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.aaosmusic.AAOSMusicApp
import com.aaosmusic.BuildConfig
import com.aaosmusic.util.AppLog.Category.APP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds and uploads debug reports. The Polestar has no adb, so this is how logs leave the car:
 * environment, account state (signed in yes/no, never tokens), the app's own log buffer, this
 * process's logcat (includes MusicKit's native logs), and the last crash. Everything is scrubbed of
 * token-shaped strings before upload.
 */
object DebugReport {
    private const val CRASH_FILE = "last_crash.txt"

    /** Saves uncaught exceptions (with recent log) so the next report includes them. */
    fun installCrashHandler(context: Context) {
        val crashFile = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText(buildString {
                    appendLine("Crash at ${java.util.Date()} on thread ${thread.name}")
                    appendLine(android.util.Log.getStackTraceString(error))
                    appendLine("--- recent app log ---")
                    AppLog.snapshot().takeLast(300).forEach(::appendLine)
                })
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun build(app: Application, extra: String = ""): String = AppLog.scrub(buildString {
        val a = app as AAOSMusicApp
        appendLine("AAOSMusic debug report")
        appendLine("App        ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device     ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android    ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, ${Build.FINGERPRINT}")
        appendLine("ABIs       ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Page size  ${runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrNull()}")
        appendLine("Provider   ${a.repository.providerName}")
        appendLine("Flags      fake=${BuildConfig.USE_FAKE_PROVIDER} appleAuth=${BuildConfig.ENABLE_APPLE_AUTH} " +
            "applePlayback=${BuildConfig.ENABLE_APPLE_PLAYBACK} sdk=${BuildConfig.APPLE_SDK_AVAILABLE}")
        appendLine("Signed in  ${a.appleTokens.userToken != null}; developer token ${if (a.appleTokens.developerToken != null) "present" else "missing"}")
        if (extra.isNotBlank()) appendLine(extra)
        File(app.filesDir, CRASH_FILE).takeIf { it.exists() }?.let {
            appendLine("\n=== Last crash ===")
            appendLine(it.readText())
        }
        appendLine("\n=== App log (newest last) ===")
        AppLog.snapshot().forEach(::appendLine)
        appendLine("\n=== Process logcat ===")
        appendLine(processLogcat())
    })

    /** Uploads [report]; returns the report id. */
    suspend fun upload(report: String): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("app_version", "${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
            put("report", report.takeLast(250_000))
        }
        val conn = (URL("${BuildConfig.SIGNIN_RELAY_URL}/report").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "Report upload failed: HTTP $status" }
            val id = Json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            AppLog.i(APP, "Debug report uploaded: ${id.take(8)}")
            id
        } finally {
            conn.disconnect()
        }
    }

    /** This process's own logcat (apps may read their own logs without permissions). */
    private fun processLogcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-t", "4000", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.also { p.waitFor() }
    }.getOrElse { "logcat unavailable: $it" }
}
