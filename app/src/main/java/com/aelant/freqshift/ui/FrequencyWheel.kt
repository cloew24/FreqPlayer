package com.aelant.freqshift.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aelant.freqshift.FrequencyPreset
import com.aelant.freqshift.Frequencies
import com.aelant.freqshift.R
import kotlin.math.abs

/**
 * Infinite wheel of all frequency presets, ordered low → high by Hz.
 *
 * After the highest comes the lowest — the wheel cycles forever in either
 * direction. Implemented by giving the pager Int.MAX_VALUE / 2 pages and using
 * modulo arithmetic over the small set of real presets.
 */
@Composable
fun FrequencyWheel(
    selected: FrequencyPreset,
    onSelected: (FrequencyPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = Frequencies.WHEEL
    val n = presets.size

    // Anchor far from 0 so the user can scroll left for many cycles. Stable
    // across recompositions — external selection changes scroll the pager
    // rather than re-creating it.
    val anchor = remember {
        val base = (Int.MAX_VALUE / 2) - ((Int.MAX_VALUE / 2) % n)
        val selectedIndex = presets.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        base + selectedIndex
    }

    val pagerState = rememberPagerState(initialPage = anchor, pageCount = { Int.MAX_VALUE })

    val haptics = LocalHapticFeedback.current

    // Live preset under the centre of the wheel — used for the label so it
    // tracks the user's drag.
    val visualPreset = presets[pagerState.currentPage % n]

    // Drag-tick: fire a light haptic each time the centred page crosses
    // a preset boundary during interactive scroll. We sample
    // [pagerState.currentPage] (which advances by 1 as the user crosses each
    // page edge) but only emit when the user is actively dragging — fling
    // animation already gets the "settle" tick below.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect {
                if (pagerState.isScrollInProgress) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
    }

    // Pager → state: commit only on settle (no re-pitch on every fling frame).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                val preset = presets[page % n]
                if (preset.id != selected.id) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelected(preset)
                }
            }
    }

    // State → pager: external selection change → animate scroll to match.
    LaunchedEffect(selected.id) {
        val currentMod = pagerState.currentPage % n
        val targetMod = presets.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        if (currentMod == targetMod) return@LaunchedEffect
        val forward = ((targetMod - currentMod) + n) % n
        val signed = if (forward <= n / 2) forward else forward - n
        pagerState.animateScrollToPage(pagerState.currentPage + signed)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 110.dp),
            pageSpacing = 4.dp,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapPositionalThreshold = 0.3f,
            ),
        ) { page ->
            val preset = presets[page % n]
            val pageOffset = (page - pagerState.currentPage) - pagerState.currentPageOffsetFraction
            WheelCard(preset = preset, pageOffset = pageOffset)
        }
        ActiveLabel(selected = visualPreset)
    }
}

@Composable
private fun WheelCard(preset: FrequencyPreset, pageOffset: Float) {
    val distance = abs(pageOffset).coerceIn(0f, 1.5f)
    val scale = 1f - (distance * SCALE_FALLOFF)
    val alpha = 1f - (distance * ALPHA_FALLOFF)
    val accent = preset.composeColor()

    Box(
        modifier = Modifier
            .scale(scale)
            .padding(vertical = 4.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.45f * alpha),
                            accent.copy(alpha = 0.05f * alpha),
                            Color.Transparent,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.20f * alpha)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        preset.hz.toInt().toString(),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Light,
                            fontSize = 28.sp,
                        ),
                        color = accent.copy(alpha = alpha),
                    )
                    Text(
                        "Hz",
                        style = MaterialTheme.typography.bodySmall,
                        color = accent.copy(alpha = 0.7f * alpha),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveLabel(selected: FrequencyPreset) {
    val accent by animateColorAsState(
        targetValue = selected.composeColor(),
        animationSpec = tween(600),
        label = "labelColor",
    )
    val text = if (selected.isStandard) {
        // 440 / off: a "+0.0¢" would be noise — just the subtitle.
        selected.subtitle
    } else {
        // Subtitle · locale-aware cents (e.g. "+15.6 cents" / "−31,8 Cent").
        "${selected.subtitle} · " + stringResource(R.string.wheel_cents_format, selected.cents)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = accent.copy(alpha = 0.85f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    )
}

private const val SCALE_FALLOFF = 0.32f
private const val ALPHA_FALLOFF = 0.55f
