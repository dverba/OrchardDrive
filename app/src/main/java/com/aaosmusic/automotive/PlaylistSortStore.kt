package com.aaosmusic.automotive

import android.content.Context
import android.content.SharedPreferences

/** The user's choice of order for the Library's Playlists list: A–Z (default) or newest added first. */
class PlaylistSortStore(context: Context) : LibraryTree.PlaylistOrder {
    private val prefs = context.applicationContext.getSharedPreferences("browse_layout", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    // SharedPreferences holds change listeners weakly, so keep this one in a field.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_BY_DATE) listeners.toList().forEach { it() }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override var byDateAdded: Boolean
        get() = prefs.getBoolean(KEY_BY_DATE, false)
        set(value) { if (value != byDateAdded) prefs.edit().putBoolean(KEY_BY_DATE, value).apply() }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private companion object {
        const val KEY_BY_DATE = "playlists_by_date_added"
    }
}
