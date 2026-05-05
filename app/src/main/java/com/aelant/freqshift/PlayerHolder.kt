package com.aelant.freqshift

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable singleton holding the currently-live [ExoPlayer] instance owned
 * by [PlaybackService]. The activity collects [flow] to know when the player
 * is ready to attach to, so we don't need any polling or busy-wait loops.
 */
object PlayerHolder {
    private val _flow = MutableStateFlow<ExoPlayer?>(null)
    val flow: StateFlow<ExoPlayer?> = _flow.asStateFlow()

    /** Convenience accessor for the current player (may be null). */
    val player: ExoPlayer? get() = _flow.value

    fun set(player: ExoPlayer?) { _flow.value = player }
}
