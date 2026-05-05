package com.aelant.freqshift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aelant.freqshift.FrequencyPreset
import com.aelant.freqshift.Frequencies
import com.aelant.freqshift.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencyPickerSheet(
    selected: FrequencyPreset,
    onDismiss: () -> Unit,
    onSelected: (FrequencyPreset) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            item {
                SectionHeader(
                    label = stringResource(R.string.picker_section_tuning),
                    hint = stringResource(R.string.picker_section_tuning_hint),
                )
            }
            items(Frequencies.TUNINGS) { preset ->
                PresetRow(preset, preset.id == selected.id) { onSelected(preset) }
            }

            item {
                Spacer(Modifier.height(24.dp))
                SectionHeader(
                    label = stringResource(R.string.picker_section_solfeggio),
                    hint = stringResource(R.string.picker_section_solfeggio_hint),
                )
            }
            items(Frequencies.SOLFEGGIO) { preset ->
                PresetRow(preset, preset.id == selected.id) { onSelected(preset) }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Disclaimer()
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, hint: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge,
             color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PresetRow(preset: FrequencyPreset, selected: Boolean, onClick: () -> Unit) {
    val accent = preset.composeColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(accent.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(accent))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(8.dp))
                Text(preset.title, style = MaterialTheme.typography.bodySmall, color = accent)
            }
            Text(
                preset.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(4.dp))
            PresetMath(preset)
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.picker_selected_cd),
                tint = accent,
            )
        }
    }
}

@Composable
private fun PresetMath(preset: FrequencyPreset) {
    val text = when {
        preset.isStandard -> stringResource(R.string.picker_standard_playback)
        else -> buildString {
            append(stringResource(R.string.picker_pitch_format, preset.pitchRatio, preset.cents))
            // Show "A→X Hz" only when it differs meaningfully from hz itself
            // (i.e. for SOLFEGGIO presets where the underlying tuning is
            // surprising relative to the named frequency).
            if (preset.equivalentA != preset.hz) {
                append(" · ")
                append(stringResource(R.string.picker_a_format, preset.equivalentA))
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun Disclaimer() {
    Text(
        stringResource(R.string.picker_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
