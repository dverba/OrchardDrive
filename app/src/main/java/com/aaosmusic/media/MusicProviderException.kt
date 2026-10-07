package com.aaosmusic.media

/**
 * Failure from a [MusicProvider]. [signInRequired] means the user must (re-)authenticate before
 * the request can succeed; the AAOS layer turns that into a "Sign in" prompt.
 */
open class MusicProviderException(
    message: String,
    val signInRequired: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
