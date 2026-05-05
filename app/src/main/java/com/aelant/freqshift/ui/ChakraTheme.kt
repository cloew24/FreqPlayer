package com.aelant.freqshift.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.aelant.freqshift.FrequencyPreset
import com.aelant.freqshift.Frequencies

/**
 * Active chakra colour, smoothly animated whenever the preset changes. Any
 * composable that wants to react to the current frequency just reads
 * [LocalChakra.current] — no prop drilling.
 *
 * The "raw" preset is exposed too, for callers that need more than the colour
 * (e.g. the hero header showing the Hz label).
 */
data class ChakraTheme(
    val color: Color,
    val preset: FrequencyPreset,
)

val LocalChakra = staticCompositionLocalOf {
    ChakraTheme(Color(0xFF9E9E9E), Frequencies.NONE)
}

/** Wrap a subtree so its descendants see the animated chakra colour. */
@Composable
fun ProvideChakra(preset: FrequencyPreset, content: @Composable () -> Unit) {
    val animated by animateColorAsState(
        targetValue = preset.composeColor(),
        animationSpec = tween(durationMillis = 600),
        label = "chakraColor",
    )
    CompositionLocalProvider(
        LocalChakra provides ChakraTheme(animated, preset),
        content = content,
    )
}
