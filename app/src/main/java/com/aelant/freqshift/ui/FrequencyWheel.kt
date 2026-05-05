package com.aelant.freqshift.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

    // Live preset under the centre of the wheel — used for the label so it
    // tracks the user's drag.
    val visualPreset = presets[pagerState.currentPage % n]

    // Pager → state: commit only on settle (no re-pitch on every fling frame).
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .collect { page ->
                val preset = presets[page % n]
                if (preset.id != selected.id) onSelected(preset)
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
        Spacer(Modifier.height(8.dp))
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
            .padding(vertical = 12.dp)
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            selected.title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            selected.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (selected.isStandard) stringResource(R.string.wheel_no_shift)
            else stringResource(R.string.wheel_cents_format, selected.cents),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

private const val SCALE_FALLOFF = 0.32f
private const val ALPHA_FALLOFF = 0.55f
