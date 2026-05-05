package com.aelant.freqshift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aelant.freqshift.ExportState
import com.aelant.freqshift.FrequencyPreset
import com.aelant.freqshift.PlayerUiState
import com.aelant.freqshift.R
import com.aelant.freqshift.SleepTimerState
import com.aelant.freqshift.Track
import com.aelant.freqshift.TuningMode

/* ──── Permission gate ──────────────────────────────────────────────────── */

@Composable
fun PermissionGateScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(32.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF7B1FA2).copy(alpha = 0.6f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White,
                    modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(40.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gate_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(stringResource(R.string.gate_button), modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/* ──── Main screen ──────────────────────────────────────────────────────── */

@Composable
fun MainScreen(
    state: PlayerUiState,
    sleepState: SleepTimerState,
    exportState: ExportState,
    onPlayTrack: (Track) -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onPresetSelected: (FrequencyPreset) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onStartSleep: (Int) -> Unit,
    onCancelSleep: () -> Unit,
    onExport: () -> Unit,
    onDismissExport: () -> Unit,
    onToggleTuningMode: () -> Unit,
    onSearchChanged: (String) -> Unit,
) {
    var presetSheetOpen by remember { mutableStateOf(false) }
    var sleepSheetOpen by remember { mutableStateOf(false) }

    ProvideChakra(state.preset) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (state.currentTrack != null) {
                    NowPlayingBar(
                        state = state,
                        sleepState = sleepState,
                        onTogglePlayPause = onTogglePlayPause,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onSeek = onSeek,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onOpenSleep = { sleepSheetOpen = true },
                        onExport = onExport,
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                FreqHeader(
                    tuningMode = state.tuningMode,
                    onToggleTuningMode = onToggleTuningMode,
                    onShowInfo = { presetSheetOpen = true },
                )
                WheelOrPlaceholder(state, onPresetSelected)
                Spacer(Modifier.height(8.dp))
                LibraryArea(state, onPlayTrack, onSearchChanged)
            }

            if (presetSheetOpen) {
                FrequencyPickerSheet(
                    selected = state.preset,
                    onDismiss = { presetSheetOpen = false },
                    onSelected = {
                        onPresetSelected(it)
                        presetSheetOpen = false
                    },
                )
            }
            if (sleepSheetOpen) {
                SleepTimerSheet(
                    state = sleepState,
                    onDismiss = { sleepSheetOpen = false },
                    onStart = { onStartSleep(it); sleepSheetOpen = false },
                    onCancel = { onCancelSleep(); sleepSheetOpen = false },
                )
            }
            ExportOverlay(exportState = exportState, onDismiss = onDismissExport)
        }
    }
}

/**
 * Render the wheel only after persisted settings have been restored.
 * Otherwise the wheel computes its anchor on the default preset (440 Hz),
 * then a delayed restore tries to scroll it into position — and we'd land
 * off-by-one. While loading, a tiny spinner placeholder occupies the same
 * vertical space so the layout doesn't jump.
 */
@Composable
private fun WheelOrPlaceholder(state: PlayerUiState, onPresetSelected: (FrequencyPreset) -> Unit) {
    if (state.settingsLoaded) {
        FrequencyWheel(selected = state.preset, onSelected = onPresetSelected)
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(WHEEL_PLACEHOLDER_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = LocalChakra.current.color,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun LibraryArea(
    state: PlayerUiState,
    onPlayTrack: (Track) -> Unit,
    onSearchChanged: (String) -> Unit,
) {
    when {
        state.isLoadingLibrary -> Centered {
            CircularProgressIndicator(color = LocalChakra.current.color)
        }
        state.tracks.isEmpty() -> EmptyLibrary()
        else -> LibraryList(
            tracks = state.visibleTracks,
            totalCount = state.tracks.size,
            searchQuery = state.searchQuery,
            onSearchChanged = onSearchChanged,
            currentId = state.currentTrack?.id,
            onClick = onPlayTrack,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/* ──── Header ───────────────────────────────────────────────────────────── */

@Composable
private fun FreqHeader(
    tuningMode: TuningMode,
    onToggleTuningMode: () -> Unit,
    onShowInfo: () -> Unit,
) {
    val accent = LocalChakra.current.color
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.header_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (tuningMode == TuningMode.LINKED) stringResource(R.string.header_mode_vinyl)
                    else stringResource(R.string.header_mode_studio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Surface(
                onClick = onToggleTuningMode,
                shape = CircleShape,
                color = accent.copy(alpha = 0.15f),
            ) {
                Icon(
                    if (tuningMode == TuningMode.LINKED) Icons.Default.Album
                    else Icons.Default.GraphicEq,
                    contentDescription = stringResource(R.string.header_toggle_mode_cd),
                    tint = accent,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onShowInfo,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.header_about_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
        }
    }
}

/* ──── Library list ─────────────────────────────────────────────────────── */

@Composable
private fun LibraryList(
    tracks: List<Track>,
    totalCount: Int,
    searchQuery: String,
    onSearchChanged: (String) -> Unit,
    currentId: Long?,
    onClick: (Track) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SearchBar(query = searchQuery, onChanged = onSearchChanged)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
        ) {
            item {
                Text(
                    if (searchQuery.isBlank())
                        stringResource(R.string.library_count, totalCount)
                    else
                        stringResource(R.string.library_search_count, tracks.size, totalCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
                )
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.library_search_empty, searchQuery),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        isPlaying = currentId == track.id,
                        onClick = { onClick(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChanged: (String) -> Unit) {
    val accent = LocalChakra.current.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChanged,
            singleLine = true,
            cursorBrush = SolidColor(accent),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.library_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChanged("") }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.library_search_clear_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, isPlaying: Boolean, onClick: () -> Unit) {
    val accent = LocalChakra.current.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(track.artUri.toString(), size = 48, glow = if (isPlaying) accent else null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) accent else MaterialTheme.colorScheme.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            track.durationLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Album art with optional chakra-coloured halo. The placeholder music-note icon
 * sits underneath the [AsyncImage] and is hidden by it when art is present.
 */
@Composable
private fun AlbumArt(uri: String, size: Int, glow: Color? = null) {
    val ctx = LocalContext.current
    val outerSize = if (glow != null) size + 10 else size
    Box(
        Modifier.size(outerSize.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (glow != null) {
            Box(
                Modifier
                    .size(outerSize.dp)
                    .clip(RoundedCornerShape((outerSize / 2).dp))
                    .background(
                        Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent))
                    )
            )
        }
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.MusicNote, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size((size * 0.45f).dp),
            )
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(uri).crossfade(true).build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.LibraryMusic, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.library_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ──── Now-playing bar ──────────────────────────────────────────────────── */

@Composable
private fun NowPlayingBar(
    state: PlayerUiState,
    sleepState: SleepTimerState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenSleep: () -> Unit,
    onExport: () -> Unit,
) {
    val track = state.currentTrack ?: return
    val accent = LocalChakra.current.color

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, accent.copy(alpha = 0.5f), Color.Transparent)
                ),
                shape = RoundedCornerShape(0.dp),
            ),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {

            if (sleepState.active) {
                SleepCountdownStrip(sleepState, accent)
            }

            val progress = if (state.durationMs > 0)
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            Slider(
                value = progress,
                onValueChange = { v ->
                    if (state.durationMs > 0) onSeek((v * state.durationMs).toLong())
                },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArt(track.artUri.toString(), size = 44, glow = accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.cd_previous),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.22f))
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.cd_pause else R.string.cd_play
                        ),
                        tint = accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.cd_next),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(
                    icon = Icons.Default.Shuffle,
                    contentDescription = stringResource(R.string.cd_shuffle),
                    on = state.shuffle,
                    accent = accent,
                    onClick = onToggleShuffle,
                )
                ToggleIcon(
                    icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                           else Icons.Default.Repeat,
                    contentDescription = stringResource(R.string.cd_repeat),
                    on = state.repeatMode != Player.REPEAT_MODE_OFF,
                    accent = accent,
                    onClick = onCycleRepeat,
                )
                ToggleIcon(
                    icon = Icons.Default.Bedtime,
                    contentDescription = stringResource(R.string.cd_sleep_timer),
                    on = sleepState.active,
                    accent = accent,
                    onClick = onOpenSleep,
                )
                ToggleIcon(
                    icon = Icons.Default.Download,
                    contentDescription = stringResource(R.string.cd_export),
                    on = false,
                    accent = accent,
                    enabled = !state.preset.isStandard,
                    onClick = onExport,
                )
            }
        }
    }
}

@Composable
private fun ToggleIcon(
    icon: ImageVector,
    contentDescription: String,
    on: Boolean,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        on -> accent
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SleepCountdownStrip(state: SleepTimerState, accent: Color) {
    val mins = (state.remainingMs / 60_000).toInt()
    val secs = ((state.remainingMs / 1000) % 60).toInt()
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Bedtime, null, tint = accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.sleep_pausing_format, mins, secs),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

private const val WHEEL_PLACEHOLDER_HEIGHT_DP = 220
