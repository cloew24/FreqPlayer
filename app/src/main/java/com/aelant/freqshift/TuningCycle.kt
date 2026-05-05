package com.aelant.freqshift

/**
 * Wheel navigation primitive. Used by both the in-app wheel and the
 * notification's tuning buttons so the cycling order can never drift between
 * the two surfaces.
 */
object TuningCycle {

    fun next(current: FrequencyPreset): FrequencyPreset = step(current, +1)
    fun previous(current: FrequencyPreset): FrequencyPreset = step(current, -1)

    private fun step(current: FrequencyPreset, delta: Int): FrequencyPreset {
        val wheel = Frequencies.WHEEL
        val index = wheel.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
        return wheel[(index + delta + wheel.size) % wheel.size]
    }
}
