package com.aaosmusic.providers.apple

import android.content.Context

/**
 * "High quality on cellular" (256 kbps AAC) vs. MusicKit's cellular default (128 kbps).
 *
 * MusicKit picks the stream flavor per request: on Wi-Fi it always allows the 256 kbps one; on any
 * other network only if its undocumented `high_quality_on_cellular_enabled` preference is set.
 * This mirrors our setting into that preference. MusicKit reads it on every asset request, so a
 * change applies from the next song. The car is usually on its cellular modem.
 */
class CellularQualitySetting(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var high: Boolean
        get() = prefs.getBoolean(KEY, true)
        set(value) {
            prefs.edit().putBoolean(KEY, value).apply()
            apply()
        }

    /** Writes the setting into MusicKit's preferences; call before creating a controller. */
    fun apply() {
        app.getSharedPreferences(SDK_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(SDK_KEY, high).apply()
    }

    private companion object {
        const val KEY = "high_quality_cellular"
        const val SDK_PREFS = "com.apple.android.music.playback_preferences"
        const val SDK_KEY = "high_quality_on_cellular_enabled"
    }
}
