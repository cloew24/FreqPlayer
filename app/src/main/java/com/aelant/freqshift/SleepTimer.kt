package com.aelant.freqshift

import androidx.annotation.StringRes
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Standard sleep-timer durations. Display labels live in `strings.xml`; the
 * UI resolves them via `stringResource(d.labelRes)` so the timer is fully
 * localisable.
 */
enum class SleepDuration(val minutes: Int, @StringRes val labelRes: Int) {
    MIN_15(15, R.string.sleep_15min),
    MIN_30(30, R.string.sleep_30min),
    MIN_60(60, R.string.sleep_1hr),
    MIN_90(90, R.string.sleep_90min),
}

data class SleepTimerState(
    val remainingMs: Long = 0,
    val totalMs: Long = 0,
    val active: Boolean = false,
)

/**
 * Sleep timer with a 30 s linear volume fade at the end, then pause.
 * State emitted at [TICK_INTERVAL_MS] cadence so the UI can show a countdown.
 */
class SleepTimer(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(SleepTimerState())
    val stateFlow: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(player: ExoPlayer, durationMs: Long) {
        cancel()
        _state.value = SleepTimerState(remainingMs = durationMs, totalMs = durationMs, active = true)

        job = scope.launch(Dispatchers.Main) {
            val end = System.nanoTime() + durationMs * 1_000_000L
            val fadeStart = end - FADE_DURATION_MS * 1_000_000L

            // Restore volume in case a previous fade left it down.
            player.volume = 1f

            while (true) {
                val now = System.nanoTime()
                val remaining = ((end - now) / 1_000_000L).coerceAtLeast(0L)
                _state.value = _state.value.copy(remainingMs = remaining)

                if (now >= end) break
                if (now >= fadeStart) {
                    val faded = (end - now).toFloat() / (end - fadeStart).toFloat()
                    player.volume = faded.coerceIn(0f, 1f)
                }
                delay(TICK_INTERVAL_MS)
            }

            player.pause()
            player.volume = 1f  // so the next play isn't silent
            _state.value = SleepTimerState()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState()
    }

    private companion object {
        const val FADE_DURATION_MS = 30_000L
        const val TICK_INTERVAL_MS = 250L
    }
}
