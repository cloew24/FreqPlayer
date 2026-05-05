package com.aelant.freqshift

import androidx.compose.ui.graphics.Color
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

/**
 * Frequency presets and the math that turns each one into a playback-pitch ratio.
 *
 * For every preset frequency `targetHz`, we find the nearest 12-TET note in
 * standard tuning (A=440) and compute the ratio that shifts that note exactly
 * onto `targetHz`. This is the semantically correct interpretation of "tune
 * music to X Hz" — some note in the music will vibrate at exactly X Hz, with
 * minimal disturbance to the rest. Resulting shifts are always within ±50 cents.
 *
 *     semitones        = 12 · log₂(targetHz / 440)
 *     nearestNoteFreq  = 440 · 2^(round(semitones) / 12)
 *     pitchRatio       = targetHz / nearestNoteFreq
 *
 * Speed handling depends on [TuningMode]: in LINKED mode both speed and pitch
 * are scaled by `pitchRatio` (vinyl); in INDEPENDENT mode only pitch shifts.
 *
 * Healing claims associated with these frequencies come from alternative
 * sound-therapy traditions, not mainstream medicine. The app is a tuning tool.
 */

private const val A4_HZ: Double = 440.0

/** Pitch ratio for [targetHz] under the nearest-12-TET-note convention. */
private fun pitchRatioFor(targetHz: Double): Double {
    val semitones = 12.0 * log2(targetHz / A4_HZ)
    val nearestNoteFreq = A4_HZ * 2.0.pow(round(semitones) / 12.0)
    return targetHz / nearestNoteFreq
}

enum class PresetKind { TUNING, SOLFEGGIO }

/**
 * A single tuneable frequency. All math is precomputed at construction so
 * runtime reads (e.g. on every animation frame) are pure field access.
 */
class FrequencyPreset(
    val id: String,
    val label: String,        // e.g. "432 Hz"
    val title: String,        // e.g. "Verdi Tuning"
    val subtitle: String,     // chakra / association
    val description: String,  // longer blurb shown in the picker sheet
    val color: Long,          // chakra colour as 0xAARRGGBB
    val kind: PresetKind,
    /** The frequency in Hz as advertised to the user. */
    val hz: Double,
    /** Identifier used in exported filenames. Self-contained so the exporter
     *  doesn't need to switch on [id]. */
    val fileTag: String,
) {
    private val pitchRatioDouble: Double = pitchRatioFor(hz)

    /** Pitch ratio fed into ExoPlayer's PlaybackParameters. 1.0 = no shift. */
    val pitchRatio: Float = pitchRatioDouble.toFloat()

    /**
     * The "equivalent A" tuning — what value of A4 corresponds to playing the
     * music at [pitchRatio]. For 528 Hz this is ≈444; for 432 Hz it's 432.
     * Used in the picker sheet to surface that "528 Hz" really means A=444.
     */
    val equivalentA: Double = A4_HZ * pitchRatioDouble

    /** Pitch shift in cents (100 cents = 1 semitone). */
    val cents: Double = 1200.0 * log2(pitchRatioDouble)

    /** True for the no-shift standard preset; lets callers avoid magic IDs. */
    val isStandard: Boolean = pitchRatioDouble == 1.0

    fun composeColor(): Color = Color(color.toInt())
}

object Frequencies {

    val NONE = FrequencyPreset(
        id = "off",
        label = "440 Hz",
        title = "Standard Tuning",
        subtitle = "No pitch shift",
        description = "Concert pitch A = 440 Hz, the modern international standard. " +
            "Plays your music exactly as recorded.",
        color = 0xFF9E9E9E,
        kind = PresetKind.TUNING,
        hz = 440.0,
        fileTag = "440hz",
    )

    /** Tuning references — the music is pitched so that A lands on this Hz. */
    val TUNINGS: List<FrequencyPreset> = listOf(
        NONE,
        FrequencyPreset(
            id = "a432",
            label = "432 Hz",
            title = "Verdi Tuning · Earth Resonance",
            subtitle = "Often called \"nature's tuning\"",
            description = "A = 432 Hz. Associated in alternative-tuning circles with Schumann " +
                "resonance, Verdi, and Pythagorean harmony. About 32 cents (≈⅓ semitone) " +
                "below standard tuning.",
            color = 0xFF4CAF50,
            kind = PresetKind.TUNING,
            hz = 432.0,
            fileTag = "432hz",
        ),
    )

    /** Solfeggio chakra frequencies. */
    val SOLFEGGIO: List<FrequencyPreset> = listOf(
        FrequencyPreset(
            id = "s174", label = "174 Hz", title = "Foundation",
            subtitle = "Pain relief · grounding",
            description = "The lowest Solfeggio tone. Associated with a sense of safety, " +
                "security, and easing physical discomfort.",
            color = 0xFF8D6E63, kind = PresetKind.SOLFEGGIO, hz = 174.0,
            fileTag = "chakra_174hz",
        ),
        FrequencyPreset(
            id = "s285", label = "285 Hz", title = "Tissue Renewal",
            subtitle = "Regeneration · energy field",
            description = "Said to influence energy fields and support healing of " +
                "tissues by reminding the body of its original blueprint.",
            color = 0xFF795548, kind = PresetKind.SOLFEGGIO, hz = 285.0,
            fileTag = "chakra_285hz",
        ),
        FrequencyPreset(
            id = "s396", label = "396 Hz", title = "Root · Muladhara",
            subtitle = "Liberating fear & guilt",
            description = "Root chakra. Grounding, security, and release of fear and guilt. " +
                "Visualise the colour red at the base of the spine.",
            color = 0xFFE53935, kind = PresetKind.SOLFEGGIO, hz = 396.0,
            fileTag = "chakra_396hz",
        ),
        FrequencyPreset(
            id = "s417", label = "417 Hz", title = "Sacral · Svadhishthana",
            subtitle = "Change & creativity",
            description = "Sacral chakra. Associated with undoing situations, breaking " +
                "negative patterns, and releasing emotional blockages.",
            color = 0xFFFB8C00, kind = PresetKind.SOLFEGGIO, hz = 417.0,
            fileTag = "chakra_417hz",
        ),
        FrequencyPreset(
            id = "s528", label = "528 Hz", title = "Solar Plexus · Manipura",
            subtitle = "The \"Miracle\" / Love frequency",
            description = "Solar plexus chakra. Known in sound-healing tradition as the " +
                "Miracle Tone or Love Frequency — associated with transformation, " +
                "self-confidence, and (in some accounts) DNA repair.",
            color = 0xFFFDD835, kind = PresetKind.SOLFEGGIO, hz = 528.0,
            fileTag = "chakra_528hz",
        ),
        FrequencyPreset(
            id = "s639", label = "639 Hz", title = "Heart · Anahata",
            subtitle = "Connection & relationships",
            description = "Heart chakra. Improves communication, understanding, and the " +
                "harmony of relationships. Visualise green light at the chest.",
            color = 0xFF43A047, kind = PresetKind.SOLFEGGIO, hz = 639.0,
            fileTag = "chakra_639hz",
        ),
        FrequencyPreset(
            id = "s741", label = "741 Hz", title = "Throat · Vishuddha",
            subtitle = "Expression & detox",
            description = "Throat chakra. Self-expression, problem-solving, and a " +
                "\"cleansing\" tone associated with releasing toxins from cells.",
            color = 0xFF1E88E5, kind = PresetKind.SOLFEGGIO, hz = 741.0,
            fileTag = "chakra_741hz",
        ),
        FrequencyPreset(
            id = "s852", label = "852 Hz", title = "Third Eye · Ajna",
            subtitle = "Intuition & insight",
            description = "Third eye chakra. Associated with awakening intuition, returning " +
                "to spiritual order, and seeing through illusion.",
            color = 0xFF3949AB, kind = PresetKind.SOLFEGGIO, hz = 852.0,
            fileTag = "chakra_852hz",
        ),
        FrequencyPreset(
            id = "s963", label = "963 Hz", title = "Crown · Sahasrara",
            subtitle = "Higher consciousness",
            description = "Crown chakra. The \"frequency of the gods\" — associated with " +
                "pineal-gland activation, oneness, and divine connection.",
            color = 0xFF8E24AA, kind = PresetKind.SOLFEGGIO, hz = 963.0,
            fileTag = "chakra_963hz",
        ),
    )

    val ALL: List<FrequencyPreset> = TUNINGS + SOLFEGGIO

    /** All presets ordered by ascending Hz — the order shown in the wheel.
     *  After 963 Hz the wheel cycles back to 174 Hz. */
    val WHEEL: List<FrequencyPreset> = ALL.sortedBy { it.hz }

    fun byId(id: String?): FrequencyPreset = ALL.firstOrNull { it.id == id } ?: NONE
}
