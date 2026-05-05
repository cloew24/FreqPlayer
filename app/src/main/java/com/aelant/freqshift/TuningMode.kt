package com.aelant.freqshift

/**
 * How pitch and tempo relate when re-tuning.
 *
 *  * [LINKED]      — pitch and speed move together (vinyl / tape-stretch).
 *                    Mathematically clean: the audio is just resampled. No DSP
 *                    artifacts. The purist "what would the song sound like
 *                    recorded at A=432 Hz" interpretation.
 *  * [INDEPENDENT] — pitch shifts but tempo stays at 1.0× via Sonic
 *                    time-stretching. Useful when you want pitch lock
 *                    without the song playing slower or faster.
 */
enum class TuningMode { LINKED, INDEPENDENT }
