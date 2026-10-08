package com.aaosmusic.ui

import android.content.ComponentName
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.aaosmusic.OrchardDriveApp
import com.aaosmusic.BuildConfig
import com.aaosmusic.automotive.AutomotiveMusicService
import com.aaosmusic.automotive.MediaIds
import com.aaosmusic.util.DebugReport
import kotlinx.coroutines.launch
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Developer diagnostics, reached from Settings → About (tap the version 7 times): environment,
 * provider and MediaSession state, a few playback actions, the debug report and the MusicKit
 * compatibility tests. It connects to the service as an ordinary MediaController, exactly like
 * AAOS does, so it never owns playback.
 */
class DiagnosticsActivity : ComponentActivity() {

    private lateinit var status: TextView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = controllerFuture?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this)
        status = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.MONOSPACE
            setPadding(0, pad / 2, 0, pad / 2)
        }
        column.addView(SettingsUi.section(this, "Status"))
        column.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: (MediaController) -> Unit) = buttons.addView(Button(this).apply {
            text = label
            textSize = 20f
            setOnClickListener { controller?.let(action) }
        })
        button("Play Test Album") {
            it.setMediaItem(MediaItem.Builder().setMediaId(MediaIds.album("alb1")).build())
            it.prepare()
            it.play()
        }
        button("Prev") { it.seekToPrevious() }
        button("Play/Pause") { if (it.isPlaying) it.pause() else it.play() }
        button("Next") { it.seekToNext() }
        button("+30s") { it.seekTo(it.currentPosition + 30_000) }
        column.addView(buttons)
        run {
            // No adb in the car: this is how logs get out. Read them back from the relay's debug_reports table.
            val reportStatus = TextView(this).apply { textSize = 20f }
            column.addView(Button(this).apply {
                text = "Send debug report"
                textSize = 20f
                setOnClickListener {
                    isEnabled = false
                    reportStatus.text = "Sending…"
                    lifecycleScope.launch {
                        runCatching { DebugReport.upload(DebugReport.build(application, status.text.toString())) }
                            .onSuccess { reportStatus.text = "Report sent. ID: ${it.take(8)}" }
                            .onFailure { reportStatus.text = "Couldn't send report: ${it.message}" }
                        isEnabled = true
                    }
                }
            })
            column.addView(reportStatus)
            column.addView(Button(this).apply {
                text = "Apple Music compatibility tests"
                textSize = 20f
                setOnClickListener { startActivity(android.content.Intent(this@DiagnosticsActivity, AppleMusicTestActivity::class.java)) }
            })
            // Apple answers an invalid Music User Token with the same 401/403 as an expired one,
            // so this exercises the re-sign-in path without waiting ~6 months.
            column.addView(Button(this).apply {
                text = "Simulate expired sign-in"
                textSize = 20f
                setOnClickListener {
                    val app = application as OrchardDriveApp
                    if (app.appleTokens.userToken == null) return@setOnClickListener
                    app.appleTokens.userToken = "expired-test-token"
                    text = "Sign-in invalidated: open Media to see the prompt"
                }
            })
        }

        setContentView(SettingsUi.screen(this, "Diagnostics", column))
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, AutomotiveMusicService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also {
            it.addListener({ controller?.addListener(listener); render() }, MoreExecutors.directExecutor())
        }
        render()
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    private fun render() {
        val app = application as OrchardDriveApp
        val c = controller
        val item = c?.currentMediaItem?.mediaMetadata
        val state = when (c?.playbackState) {
            null -> "-"
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> if (c.isPlaying) "PLAYING" else "PAUSED"
            Player.STATE_ENDED -> "ENDED"
            else -> "?"
        }
        status.text = buildString {
            appendLine("Environment")
            appendLine("  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("  Device  ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("  App     ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}")
            appendLine()
            appendLine("Provider")
            appendLine("  Active          ${app.repository.providerName}")
            appendLine("  Apple auth      ${if (BuildConfig.ENABLE_APPLE_AUTH) "enabled" else "disabled"}")
            appendLine("  Apple playback  ${if (BuildConfig.ENABLE_APPLE_PLAYBACK) "enabled" else "disabled"}")
            appendLine()
            appendLine("Media")
            appendLine("  Session   ${if (c != null) "connected" else "connecting…"}")
            appendLine("  State     $state")
            appendLine("  Item      ${item?.title ?: "-"} / ${item?.artist ?: "-"} / ${item?.albumTitle ?: "-"}")
            appendLine("  Queue     ${c?.let { "${it.currentMediaItemIndex + 1} of ${it.mediaItemCount}" } ?: "-"}")
            appendLine("  Position  ${c?.let { "${it.currentPosition / 1000}s / ${it.duration.coerceAtLeast(0) / 1000}s" } ?: "-"}")
            appendLine("  Shuffle   ${c?.shuffleModeEnabled ?: "-"}   Repeat ${c?.repeatMode ?: "-"}")
            c?.playerError?.let { appendLine("  Error     ${it.errorCodeName}") }
        }
    }
}
