package com.aaosmusic.providers.apple

import android.util.JsonReader
import android.util.JsonToken

/**
 * Called from MusicKit's own bytecode, patched at build time by `MusicKitSongIdFix` (buildSrc).
 *
 * MusicKit parses the webPlayback response's `songId` as an int, which throws for catalog ids above
 * Int.MAX_VALUE. [read] reads it as a long instead (returning MusicKit's int copy, which only feeds
 * [widen]), and [widen] hands the full id back where MusicKit converts its copy to a long. Both run
 * on the same thread within one response parse.
 */
object MusicKitSongIds {
    private val last = ThreadLocal<Long>()

    @JvmStatic
    fun read(reader: JsonReader, default: Int): Int {
        if (reader.peek() == JsonToken.NULL) {
            reader.skipValue()
            last.remove()
            return default
        }
        return reader.nextLong().also { last.set(it) }.toInt()
    }

    @JvmStatic
    fun widen(value: Int): Long = last.get()?.takeIf { it.toInt() == value } ?: value.toLong()
}
