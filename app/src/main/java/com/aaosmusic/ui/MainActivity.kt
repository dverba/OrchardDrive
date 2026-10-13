package com.aaosmusic.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aaosmusic.OrchardDriveApp
import com.aaosmusic.BuildConfig
import com.aaosmusic.R
import com.aaosmusic.automotive.BrowseLayoutStore
import com.aaosmusic.providers.apple.CellularQualitySetting
import com.aaosmusic.ui.SettingsUi.DUSK
import com.aaosmusic.ui.SettingsUi.INK
import com.aaosmusic.ui.SettingsUi.INK_MUTED
import com.aaosmusic.ui.SettingsUi.NIGHT
import com.aaosmusic.ui.SettingsUi.SUNSET

/**
 * Parked-mode settings screen: a rail of sections (Account, Playback, Display, About) next to the
 * selected section's card; in portrait the rail sits above the card. Media Center opens it from its
 * gear icon and its "Sign in" prompt, so Account (QR sign-in) is selected first. Diagnostics stay
 * hidden until the version row is tapped [UNLOCK_TAPS] times (Android's build-number convention).
 */
class MainActivity : ComponentActivity() {

    private enum class Section(val label: String, val icon: Int) {
        ACCOUNT("Account", R.drawable.ic_nav_account),
        PLAYBACK("Playback", R.drawable.ic_nav_playback),
        DISPLAY("Display", R.drawable.ic_nav_display),
        ABOUT("About", R.drawable.ic_nav_about),
    }

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val app by lazy { application as OrchardDriveApp }
    private var versionTaps = 0
    private val navItems = mutableMapOf<Section, LinearLayout>()
    private val panels = mutableMapOf<Section, View>()
    private lateinit var accountDot: View
    private val onTokenChanged: () -> Unit = { runOnUiThread(::updateAccountDot) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        // Narrow landscape screens (e.g. 1024dp) get an icon-only rail so the card keeps its room.
        val narrow = !portrait && resources.configuration.screenWidthDp < WIDE_DP
        val content = FrameLayout(this)
        Section.entries.forEach { s ->
            val panel = panel(s, portrait, narrow)
            panels[s] = panel
            content.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val root = LinearLayout(this).apply {
            setBackgroundColor(NIGHT)
            if (portrait) {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(32), dp(40), dp(32), dp(32))
                addView(brand())
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(20), 0, dp(20))
                    Section.entries.forEach { addView(navItem(it), LinearLayout.LayoutParams(0, dp(64), 1f)) }
                })
                addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(backButton(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(20) })
            } else {
                orientation = LinearLayout.HORIZONTAL
                if (narrow) setPadding(dp(24), dp(32), dp(40), dp(32)) else setPadding(dp(72), dp(56), dp(72), dp(56))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(brand(labels = !narrow))
                    Section.entries.forEach {
                        addView(navItem(it, label = !narrow), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { bottomMargin = dp(6) })
                    }
                    addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 0, 1f))
                    addView(backButton(label = !narrow), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
                }, LinearLayout.LayoutParams(dp(if (narrow) 88 else 360), LinearLayout.LayoutParams.MATCH_PARENT))
                addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(if (narrow) 24 else 48) })
            }
        }
        setContentView(root)
        select(savedInstanceState?.getString(KEY_SECTION)?.let(Section::valueOf) ?: Section.ACCOUNT)
        updateAccountDot()
        app.appleTokens.addListener(onTokenChanged)
    }

    override fun onDestroy() {
        app.appleTokens.removeListener(onTokenChanged)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        panels.entries.firstOrNull { it.value.visibility == View.VISIBLE }?.let { outState.putString(KEY_SECTION, it.key.name) }
    }

    private fun select(section: Section) {
        panels.forEach { (s, v) -> v.visibility = if (s == section) View.VISIBLE else View.GONE }
        navItems.forEach { (s, item) ->
            val on = s == section
            item.background = if (on) SettingsUi.rounded(this, DUSK, 16) else null
            (item.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (on) SUNSET else INK_MUTED)
            (item.getChildAt(1) as TextView).setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** A dot on Account while there's no Apple Music sign-in: the one thing that needs doing. */
    private fun updateAccountDot() {
        accountDot.visibility = if (app.repository.providerName == "apple" && !app.appleTokens.signedIn) View.VISIBLE else View.GONE
    }

    private fun brand(labels: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (labels) 8 else 12), dp(6), dp(8), dp(28))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.brand_icon)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        if (labels) addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 30f
                setTextColor(INK)
                typeface = SettingsUi.serif(this@MainActivity)
            })
            addView(TextView(this@MainActivity).apply { text = "for Apple Music"; textSize = 14f; setTextColor(INK_MUTED) })
        })
    }

    private fun navItem(section: Section, label: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (label) 20 else 31), 0, dp(if (label) 20 else 8), 0)
        contentDescription = section.label
        isClickable = true
        foreground = getDrawable(android.util.TypedValue().also {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId)
        setOnClickListener { select(section) }
        addView(ImageView(this@MainActivity).apply { setImageResource(section.icon) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(TextView(this@MainActivity).apply {
            text = section.label
            textSize = 19f
            setTextColor(INK)
            setPadding(dp(16), 0, dp(8), 0)
            visibility = if (label) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (section == Section.ACCOUNT) {
            accountDot = View(this@MainActivity).apply { background = SettingsUi.rounded(this@MainActivity, SUNSET, 5) }
            addView(accountDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        }
        navItems[section] = this
    }

    private fun backButton(label: Boolean = true) = SettingsUi.pillButton(this, filled = false, heightDp = 64).apply {
        if (label) text = "Back to Media" else contentDescription = "Back to Media"
        textSize = 18f
        if (!label) { minWidth = 0; minimumWidth = 0; setPadding(dp(20), 0, 0, 0) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_arrow_back)?.mutate()?.apply { setTint(INK) }, null, null, null)
        compoundDrawablePadding = dp(12)
        setOnClickListener { finish() }
    }

    private fun panel(section: Section, portrait: Boolean, narrow: Boolean): View = when (section) {
        Section.ACCOUNT ->
            if (app.repository.providerName == "apple") ScrollView(this).apply {
                isFillViewport = true
                addView(AppleAccountView(this@MainActivity, app.appleTokens, lifecycleScope, stacked = portrait, compact = narrow))
            } else card(SettingsUi.row(this, "Test catalog", "This build plays built-in sample music, not Apple Music."))

        Section.PLAYBACK ->
            if (app.repository.providerName == "apple") {
                val quality = CellularQualitySetting(this)
                card(
                    SettingsUi.switch(this, "High quality streaming on cellular", quality.high) { quality.high = it },
                    SettingsUi.subtitleText(this,
                        "256 kbps instead of 128 kbps when the car isn't on Wi-Fi: about 115 MB of data per hour instead of 58. " +
                            "Applies from the next song."),
                )
            } else card(SettingsUi.row(this, "Test catalog", "Streaming quality applies to Apple Music builds only."))

        Section.DISPLAY -> {
            val layout = BrowseLayoutStore(this)
            card(
                SettingsUi.switch(this, "Show albums, artists and playlists as tiles", layout.grid) { layout.grid = it },
                // Media Center keeps the screens it already loaded; switching tabs makes it re-read them.
                SettingsUi.subtitleText(this, "Off shows them as a list. Switch between the Home and Library tabs to see the change."),
            )
        }

        Section.ABOUT -> {
            val diagnostics = SettingsUi.row(this, "Diagnostics", "Status, debug report and test tools") {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
            val version = SettingsUi.row(this, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})") {
                if (!BuildConfig.ENABLE_DEBUG_MENU || diagnostics.visibility == View.VISIBLE) return@row
                if (++versionTaps >= UNLOCK_TAPS) {
                    prefs.edit().putBoolean(KEY_DIAGNOSTICS, true).apply()
                    diagnostics.visibility = View.VISIBLE
                    Toast.makeText(this, "Diagnostics enabled", Toast.LENGTH_SHORT).show()
                }
            }
            diagnostics.visibility =
                if (BuildConfig.ENABLE_DEBUG_MENU && prefs.getBoolean(KEY_DIAGNOSTICS, false)) View.VISIBLE else View.GONE
            card(version, diagnostics)
        }
    }

    /** A Dusk card filling the pane, holding [rows] at the top; scrolls if the screen is short. */
    private fun card(vararg rows: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = SettingsUi.rounded(this@MainActivity, DUSK, 28)
            setPadding(dp(40), dp(24), dp(40), dp(32))
            rows.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        })
    }

    private companion object {
        const val UNLOCK_TAPS = 7
        const val KEY_DIAGNOSTICS = "diagnostics_unlocked"
        const val KEY_SECTION = "section"
        const val WIDE_DP = 1280
    }
}
