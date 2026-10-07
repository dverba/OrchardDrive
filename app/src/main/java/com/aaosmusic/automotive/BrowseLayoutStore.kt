package com.aaosmusic.automotive

import android.content.Context
import android.content.SharedPreferences

/** The user's choice of tiles (grid) or list rows for browse screens in Media Center. */
class BrowseLayoutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("browse_layout", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    // SharedPreferences holds change listeners weakly, so keep this one in a field.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_GRID) listeners.toList().forEach { it() }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    var grid: Boolean
        get() = prefs.getBoolean(KEY_GRID, true)
        set(value) = prefs.edit().putBoolean(KEY_GRID, value).apply()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private companion object {
        const val KEY_GRID = "grid"
    }
}
