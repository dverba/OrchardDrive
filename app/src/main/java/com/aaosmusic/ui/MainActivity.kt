package com.aaosmusic.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aaosmusic.AAOSMusicApp
import com.aaosmusic.BuildConfig
import com.aaosmusic.automotive.BrowseLayoutStore

/**
 * Parked-mode settings screen: Apple Music account (QR sign-in), display options and About.
 * Media Center opens it from its gear icon and its "Sign in" prompt. Diagnostics stay hidden
 * until the version row is tapped [UNLOCK_TAPS] times (Android's build-number convention).
 */
class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var versionTaps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AAOSMusicApp
        val column = LinearLayout(this)

        column.addView(SettingsUi.section(this, "Apple Music"))
        if (app.repository.providerName == "apple") {
            column.addView(AppleAccountView(this, app.appleTokens, lifecycleScope))
        } else {
            column.addView(SettingsUi.row(this, "Test catalog", "This build plays built-in sample music, not Apple Music."))
        }

        column.addView(SettingsUi.section(this, "Display"))
        val layout = BrowseLayoutStore(this)
        column.addView(Switch(this).apply {
            text = "Show albums, artists and playlists as tiles"
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            isChecked = layout.grid
            minimumHeight = (76 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, checked -> layout.grid = checked }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Media Center keeps the screens it already loaded; switching tabs makes it re-read them.
        column.addView(SettingsUi.subtitleText(this, "Off shows them as a list. Switch between the Home and Library tabs to see the change."))

        column.addView(SettingsUi.section(this, "About"))
        val diagnostics = SettingsUi.row(this, "Diagnostics", "Status, debug report and test tools") {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        column.addView(SettingsUi.row(this, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})") {
            if (!BuildConfig.ENABLE_DEBUG_MENU || diagnostics.visibility == View.VISIBLE) return@row
            if (++versionTaps >= UNLOCK_TAPS) {
                prefs.edit().putBoolean(KEY_DIAGNOSTICS, true).apply()
                diagnostics.visibility = View.VISIBLE
                Toast.makeText(this, "Diagnostics enabled", Toast.LENGTH_SHORT).show()
            }
        })
        diagnostics.visibility =
            if (BuildConfig.ENABLE_DEBUG_MENU && prefs.getBoolean(KEY_DIAGNOSTICS, false)) View.VISIBLE else View.GONE
        column.addView(diagnostics)

        setContentView(SettingsUi.screen(this, "Settings", column))
    }

    private companion object {
        const val UNLOCK_TAPS = 7
        const val KEY_DIAGNOSTICS = "diagnostics_unlocked"
    }
}
