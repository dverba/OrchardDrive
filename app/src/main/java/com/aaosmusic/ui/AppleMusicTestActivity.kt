package com.aaosmusic.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.aaosmusic.AAOSMusicApp
import com.aaosmusic.BuildConfig
import com.aaosmusic.providers.apple.AppleMusicApiClient
import com.aaosmusic.providers.apple.AppleMusicTokenStore
import com.aaosmusic.providers.apple.ApplePlayerHandle
import com.aaosmusic.providers.apple.appleSdkBridge
import com.aaosmusic.util.AppLog
import com.aaosmusic.util.AppLog.Category.APPLE_API
import com.aaosmusic.util.AppLog.Category.APPLE_PLAYER
import com.aaosmusic.util.AppLog.Category.AUTH
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Isolated MusicKit-on-AAOS compatibility tests (docs/MUSICKIT_AAOS_COMPATIBILITY.md). Debug only;
 * deliberately separate from the production media flow.
 *
 * In debug builds a Music User Token can be injected for testing:
 * `adb shell am start -n com.aaosmusic/.ui.AppleMusicTestActivity --es com.aaosmusic.extra.USER_TOKEN '<token>'`
 * (tools/musickit_token.py does this for you).
 */
class AppleMusicTestActivity : ComponentActivity() {

    private lateinit var tokens: AppleMusicTokenStore
    private lateinit var api: AppleMusicApiClient
    private val sdk = appleSdkBridge()

    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var logView: TextView
    private val logLines = ArrayDeque<String>()

    private var storefront = AppleMusicApiClient.DEFAULT_STOREFRONT
    private var previewPlayer: ExoPlayer? = null
    private var applePlayer: ApplePlayerHandle? = null

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        sdk.parseSignInResult(this, result.data)
            .onSuccess { tokens.userToken = it; log("Test 2: Apple SDK sign-in OK") }
            .onFailure { log("Test 2: Apple SDK sign-in failed: ${it.message} (resultCode ${result.resultCode})") }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shared instance: the media service listens on it to refresh AAOS after sign-in.
        tokens = (application as AAOSMusicApp).appleTokens
        api = AppleMusicApiClient(tokens)
        buildUi()
        consumeInjectedToken(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeInjectedToken(intent)
        render()
    }

    override fun onDestroy() {
        previewPlayer?.release()
        applePlayer?.release()
        super.onDestroy()
    }

    private fun consumeInjectedToken(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_USER_TOKEN) ?: return
        intent.removeExtra(EXTRA_USER_TOKEN)
        if (!BuildConfig.DEBUG) return
        tokens.userToken = token
        log("User token injected via intent")
        applePlayer?.release()
        applePlayer = null
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Apple Music Test"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 18f; typeface = Typeface.MONOSPACE }
        column.addView(status)

        column.addView(row(
            button("Sign in (Apple SDK)") { startAppleSignIn() },
            button("Clear user token") { tokens.userToken = null; applePlayer?.release(); applePlayer = null; render() },
            button("Init MusicKit player") { ensureApplePlayer() },
            button("Pause/Resume MusicKit") { applePlayer?.togglePause() },
            button("Measure output (5s)") { measureOutput() },
        ))

        val query = EditText(this).apply { setText("Daft Punk"); textSize = 20f; minEms = 12; setSingleLine() }
        column.addView(row(query, button("Search") { search(query.text.toString()) }))

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(results)

        logView = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; setPadding(0, pad, 0, 0) }
        column.addView(logView)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun render() {
        status.text = buildString {
            appendLine("Developer token  ${if (tokens.developerToken != null) "available" else "MISSING"}")
            appendLine("User token       ${if (tokens.userToken != null) "available" else "missing"}")
            appendLine("MusicKit SDK     ${if (sdk.available) "bundled" else "not bundled"}")
            appendLine("Storefront       $storefront")
            append("Last HTTP status ${api.lastStatus ?: "-"}")
        }
    }

    private fun startAppleSignIn() {
        val dev = tokens.developerToken ?: return log("No developer token")
        val intent = sdk.signInIntent(this, dev) ?: return log("Test 2: SDK not bundled")
        log("Test 2: launching Apple SDK sign-in")
        runCatching { signIn.launch(intent) }.onFailure { log("Test 2: launch failed: $it") }
    }

    /** Test 1: can MusicKit's native player initialize on this device? */
    private fun ensureApplePlayer(): ApplePlayerHandle? {
        applePlayer?.let { return it }
        val dev = tokens.developerToken ?: run { log("Test 1: no developer token"); return null }
        val user = tokens.userToken ?: run { log("Test 1: no user token yet; creating player with empty token") ; "" }
        return runCatching { sdk.createPlayer(this, dev, user) { log("[MusicKit] $it") } }
            .onSuccess { applePlayer = it; log("Test 1: MusicKit player initialized") }
            .onFailure { log("Test 1: MusicKit init FAILED: $it"); AppLog.e(APPLE_PLAYER, "MusicKit init failed", it) }
            .getOrNull()
    }

    /** Test 4: catalog search with the developer token (plus storefront from the user token if present). */
    private fun search(term: String) {
        lifecycleScope.launch {
            runCatching {
                api.userStorefront()?.let { storefront = it }
                api.search(term, storefront)
            }.onSuccess { parsed ->
                val r = parsed.results
                log("Test 4: search '$term' OK: ${r.songs.size} songs, ${r.albums.size} albums, ${r.artists.size} artists, ${r.playlists.size} playlists")
                results.removeAllViews()
                r.songs.forEach { song ->
                    val label = TextView(this@AppleMusicTestActivity).apply {
                        text = "${song.title} — ${song.artist}  [${song.id}]"
                        textSize = 18f
                        minEms = 20
                    }
                    results.addView(row(
                        label,
                        button("Preview") { parsed.previews[song.id]?.let(::playPreview) ?: log("No preview for ${song.id}") },
                        button("Play (MusicKit)") { playWithMusicKit(song.id, song.title) },
                    ))
                }
            }.onFailure {
                log("Test 4: search FAILED: ${it.message}")
                AppLog.e(APPLE_API, "Search failed", it)
            }
            render()
        }
    }

    private fun playPreview(url: String) {
        applePlayer?.togglePause()
        val player = previewPlayer ?: ExoPlayer.Builder(this).build().also { previewPlayer = it }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        log("Preview (plain AAC, no DRM) playing")
    }

    /** Test 5: full-length, DRM-protected playback through MusicKit. */
    private fun playWithMusicKit(id: String, title: String) {
        if (tokens.userToken == null) log("Test 5: warning, no user token; expect failure")
        previewPlayer?.pause()
        val player = ensureApplePlayer() ?: return
        log("Test 5: MusicKit play '$title' ($id)")
        player.playSong(id)
    }

    /**
     * Peak level of the mixed audio output over 5 s, via a Visualizer on the output mix (session 0).
     * -9600 mB is digital silence. Debug builds only (needs RECORD_AUDIO, grant with
     * `adb shell pm grant com.aaosmusic android.permission.RECORD_AUDIO`).
     */
    private fun measureOutput() {
        val viz = runCatching {
            android.media.audiofx.Visualizer(0).apply {
                measurementMode = android.media.audiofx.Visualizer.MEASUREMENT_MODE_PEAK_RMS
                enabled = true
            }
        }.getOrElse { log("Meter unavailable: $it"); return }
        val m = android.media.audiofx.Visualizer.MeasurementPeakRms()
        var maxPeak = Int.MIN_VALUE
        var maxRms = Int.MIN_VALUE
        lifecycleScope.launch {
            repeat(20) {
                kotlinx.coroutines.delay(250)
                viz.getMeasurementPeakRms(m)
                maxPeak = maxOf(maxPeak, m.mPeak)
                maxRms = maxOf(maxRms, m.mRms)
            }
            viz.release()
            log("Output level over 5s: peak ${maxPeak / 100.0} dBFS, rms ${maxRms / 100.0} dBFS" +
                if (maxPeak <= -9600) " (SILENT)" else "")
        }
    }

    private fun log(message: String) {
        AppLog.i(if (message.startsWith("Test 2")) AUTH else APPLE_PLAYER, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.addFirst("$time $message")
        while (logLines.size > 60) logLines.removeLast()
        logView.text = logLines.joinToString("\n")
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach(::addView)
    }

    companion object {
        const val EXTRA_USER_TOKEN = "com.aaosmusic.extra.USER_TOKEN"
    }
}
