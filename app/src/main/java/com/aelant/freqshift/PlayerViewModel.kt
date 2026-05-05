package com.aelant.freqshift

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * UI state for the main screen — flat and immutable. Dedicated capsules
 * ([SleepTimer], [AudioExporter]) own sub-feature state and surface their
 * own flows.
 */
data class PlayerUiState(
    val tracks: List<Track> = emptyList(),
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val preset: FrequencyPreset = Frequencies.NONE,
    val isLoadingLibrary: Boolean = true,
    val hasPermission: Boolean = false,
    val shuffle: Boolean = false,
    /** Maps to ExoPlayer.REPEAT_MODE_OFF / _ALL / _ONE. */
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val tuningMode: TuningMode = TuningMode.LINKED,
    val searchQuery: String = "",
    /** False until persisted settings have loaded — gates the wheel render
     *  to prevent an off-by-one anchor on cold starts. */
    val settingsLoaded: Boolean = false,
) {
    val visibleTracks: List<Track>
        get() = if (searchQuery.isBlank()) tracks
                else tracks.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
                }
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val exporter = AudioExporter(app)
    val exportState: StateFlow<ExportState> = exporter.state

    private val sleepTimer = SleepTimer(viewModelScope)
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.stateFlow

    private val settings = SettingsStore(app)
    private var positionJob: Job? = null

    init {
        // Restore once, then collect — so updates from the notification's
        // tuning button (which writes DataStore directly) flow back into UI.
        viewModelScope.launch {
            settings.flow.firstOrNull()?.let { saved ->
                _state.value = _state.value.copy(
                    preset = Frequencies.byId(saved.presetId),
                    tuningMode = saved.tuningMode,
                    shuffle = saved.shuffle,
                    repeatMode = saved.repeatMode,
                    settingsLoaded = true,
                )
            } ?: run {
                _state.value = _state.value.copy(settingsLoaded = true)
            }
            settings.flow.collect { saved ->
                val current = _state.value
                if (saved.presetId != current.preset.id) {
                    _state.value = current.copy(preset = Frequencies.byId(saved.presetId))
                    PlayerHolder.player?.let { applyCurrentTuning(it) }
                }
                if (saved.tuningMode != current.tuningMode) {
                    _state.value = _state.value.copy(tuningMode = saved.tuningMode)
                }
            }
        }
        // Attach to the player whenever the service publishes one, after
        // settings have loaded — otherwise we'd push default shuffle/repeat
        // values into the player before the restored values arrive.
        viewModelScope.launch {
            // Wait for settings to load.
            state.first { it.settingsLoaded }
            // Then track the player. If the service is recreated, detach the
            // old listener and re-attach to the new instance.
            var attached: ExoPlayer? = null
            PlayerHolder.flow.collect { player ->
                if (player == attached) return@collect
                attached?.removeListener(playerListener)
                attached = player
                player?.let(::onPlayerAttached)
            }
        }
    }

    private fun onPlayerAttached(player: ExoPlayer) {
        player.addListener(playerListener)
        // Push restored settings; subsequent changes flow through the listener.
        val s = _state.value
        player.shuffleModeEnabled = s.shuffle
        player.repeatMode = s.repeatMode
        _state.value = s.copy(
            isPlaying = player.isPlaying,
            durationMs = player.duration.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
        )
        applyCurrentTuning(player)
        startPositionUpdates(player)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId?.toLongOrNull() ?: return
            val track = _state.value.tracks.firstOrNull { it.id == id }
            _state.value = _state.value.copy(
                currentTrack = track,
                durationMs = track?.durationMs ?: 0,
            )
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = PlayerHolder.player ?: return
            if (playbackState == Player.STATE_READY) {
                _state.value = _state.value.copy(durationMs = player.duration.coerceAtLeast(0))
            }
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.value = _state.value.copy(shuffle = shuffleModeEnabled)
            viewModelScope.launch { settings.saveShuffle(shuffleModeEnabled) }
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.value = _state.value.copy(repeatMode = repeatMode)
            viewModelScope.launch { settings.saveRepeatMode(repeatMode) }
        }
    }

    fun startService() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, PlaybackService::class.java))
    }

    fun setPermissionGranted(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted) loadLibrary()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingLibrary = true)
            val tracks = MediaLibrary.scan(getApplication())
            _state.value = _state.value.copy(tracks = tracks, isLoadingLibrary = false)
        }
    }

    fun setSearchQuery(q: String) {
        _state.value = _state.value.copy(searchQuery = q)
    }

    fun playTrack(track: Track) {
        val player = PlayerHolder.player ?: return
        val tracks = _state.value.tracks
        val startIndex = tracks.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return

        val items = tracks.map { t ->
            MediaItem.Builder()
                .setMediaId(t.id.toString())
                .setUri(t.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setArtworkUri(t.artUri)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, startIndex, 0L)
        applyCurrentTuning(player)
        player.prepare()
        player.playWhenReady = true
        _state.value = _state.value.copy(currentTrack = track)
    }

    fun togglePlayPause() {
        val p = PlayerHolder.player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() { PlayerHolder.player?.seekToNextMediaItem() }

    fun previous() {
        val p = PlayerHolder.player ?: return
        if (p.currentPosition < PREV_RESTART_THRESHOLD_MS && p.hasPreviousMediaItem()) {
            p.seekToPreviousMediaItem()
        } else {
            p.seekTo(0)
        }
    }

    fun seekTo(ms: Long) { PlayerHolder.player?.seekTo(ms) }

    fun toggleShuffle() {
        val p = PlayerHolder.player ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
    }

    /** Cycles OFF → ALL → ONE → OFF. */
    fun cycleRepeat() {
        val p = PlayerHolder.player ?: return
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun setPreset(preset: FrequencyPreset) {
        if (preset.id == _state.value.preset.id) return
        _state.value = _state.value.copy(preset = preset)
        viewModelScope.launch { settings.savePreset(preset.id) }
        PlayerHolder.player?.let { applyCurrentTuning(it) }
    }

    /** Toggle between LINKED and INDEPENDENT tuning modes. */
    fun toggleTuningMode() {
        val next = if (_state.value.tuningMode == TuningMode.LINKED) TuningMode.INDEPENDENT
                   else TuningMode.LINKED
        _state.value = _state.value.copy(tuningMode = next)
        viewModelScope.launch { settings.saveTuningMode(next) }
        PlayerHolder.player?.let { applyCurrentTuning(it) }
    }

    /**
     * Push the current tuning to the player as a single PlaybackParameters
     * jump. The deep AudioTrack output buffer configured in [PlaybackService]
     * absorbs the Sonic reconfiguration boundary without any audible click —
     * the same trick a low-latency audio driver uses to hide reconfiguration
     * latency behind its output ring.
     */
    private fun applyCurrentTuning(player: ExoPlayer) {
        val s = _state.value
        val ratio = s.preset.pitchRatio
        val speed = if (s.tuningMode == TuningMode.LINKED) ratio else 1f
        player.playbackParameters = PlaybackParameters(speed, ratio)
    }

    // --- Sleep timer ---------------------------------------------------------

    fun startSleepTimer(minutes: Int) {
        val player = PlayerHolder.player ?: return
        sleepTimer.start(player, minutes * 60_000L)
    }
    fun cancelSleepTimer() = sleepTimer.cancel()

    // --- Export --------------------------------------------------------------

    fun exportCurrent() {
        val track = _state.value.currentTrack ?: return
        val preset = _state.value.preset
        if (preset.isStandard) return
        viewModelScope.launch { exporter.export(track, preset, _state.value.tuningMode) }
    }
    fun dismissExportResult() = exporter.reset()

    // --- Internals -----------------------------------------------------------

    private fun startPositionUpdates(player: ExoPlayer) {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                if (player.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        positionJob?.cancel()
        sleepTimer.cancel()
        PlayerHolder.player?.removeListener(playerListener)
        super.onCleared()
    }

    private companion object {
        const val PREV_RESTART_THRESHOLD_MS = 3000L
        const val POSITION_UPDATE_INTERVAL_MS = 500L
    }
}
