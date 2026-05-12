package com.aelant.freqshift.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aelant.freqshift.ExportState
import com.aelant.freqshift.R

/**
 * Full-screen overlay shown while an export is running, completed, or failed.
 * Tapping the dimmed backdrop dismisses (only when not running); the inner
 * card swallows taps without showing a ripple.
 */
@Composable
fun ExportOverlay(exportState: ExportState, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = exportState !is ExportState.Idle,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val accent = LocalChakra.current.color
        val canDismiss = exportState !is ExportState.Running

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(enabled = canDismiss) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    // Swallow taps on the card body — no ripple, no dismiss.
                    .pointerInput(Unit) { detectTapGestures { /* swallow */ } },
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (exportState) {
                        is ExportState.Running -> RunningContent(exportState, accent)
                        is ExportState.Done -> DoneContent(exportState, accent, onDismiss)
                        is ExportState.Failed -> FailedContent(exportState, onDismiss)
                        ExportState.Idle -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningContent(state: ExportState.Running, accent: Color) {
    val transition = rememberInfiniteTransition(label = "breathe")
    val scale by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "breatheScale",
    )

    Box(
        Modifier.size(96.dp).scale(scale).clip(CircleShape)
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent))),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(accent))
    }

    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.export_tuning_to, state.preset.label),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        state.track.title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))

    if (state.progress > 0) {
        LinearProgressIndicator(
            progress = { state.progress / 100f },
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.progress}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DoneContent(state: ExportState.Done, accent: Color, onDismiss: () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.export_done_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        state.displayName,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.export_done_path),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(20.dp))
    DoneButton(accent, onDismiss)
}

@Composable
private fun FailedContent(state: ExportState.Failed, onDismiss: () -> Unit) {
    Icon(
        Icons.Default.ErrorOutline, null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(56.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.export_failed_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        state.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    DoneButton(MaterialTheme.colorScheme.error, onDismiss)
}

@Composable
private fun DoneButton(accent: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.16f),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Close, null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.export_done_button),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
        }
    }
}
