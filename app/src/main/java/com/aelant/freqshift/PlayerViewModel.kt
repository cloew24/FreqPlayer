package com.aelant.freqshift

import android.app.Application
import android.content.Intent
import android.net.Uri
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
    /** Full music library scanned from MediaStore. Stable across folder
     *  playback sessions — switching to folder mode doesn't drop this. */
    val tracks: List<Track> = emptyList(),
    /** The list backing the player's current MediaItem queue. May be the
     *  library (when [playTrack] was called) or a folder scan (when
     *  [playFolder] was called). Used by [onMediaItemTransition] to resolve
     *  the new media item back to a [Track] regardless of source. */
    val currentQueue: List<Track> = emptyList(),
    /** When true, the library list UI shows [currentQueue] instead of
     *  [tracks]. Set by [playFolder], cleared by [returnToLibrary] or by
     *  the next [playTrack] call. */
    val folderActive: Boolean = false,
    /** Display name of the active folder (last path segment of the SAF
     *  tree URI). Null when [folderActive] is false. */
    val folderName: String? = null,
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
    /** True while [PlayerViewModel.playFolder] is walking a SAF tree. UI
     *  shows a transient overlay so the user knows the folder is loading. */
    val isScanningFolder: Boolean = false,
    /** Set when a folder scan failed or returned no audio; activity reads
     *  this to show a snackbar then calls [PlayerViewModel.clearFolderError]. */
    val folderError: FolderError? = null,
) {
    /** The list of tracks the library UI displays — folder content when
     *  [folderActive], otherwise the full library. Search filter applies
     *  the same way in both modes. */
    val listSource: List<Track>
        get() = if (folderActive) currentQueue else tracks

    val visibleTracks: List<Track>
        get() = if (searchQuery.isBlank()) listSource
                else listSource.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
                }
}

/** Reasons a [PlayerViewModel.playFolder] call can fail to start playback. */
enum class FolderError {
    /** The picked folder contains no audio files (or all were too short). */
    NO_AUDIO,
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
            // Observe DataStore for *external* changes — currently just the
            // notification's prev/next tuning buttons which write the preset
            // ID directly. We re-emit our own writes too, but those are
            // no-ops here because the in-memory state already matches.
            //
            // Sub-logic note: when an external change arrives, we apply it
            // to BOTH the in-memory state AND the live player, using the
            // values straight out of [saved] (never re-reading _state.value).
            // This avoids the race where two near-simultaneous emissions
            // could see each other's half-updated state.
            settings.flow.collect { saved ->
                val newPreset = Frequencies.byId(saved.presetId)
                val current = _state.value
                val presetChanged = newPreset.id != current.preset.id
                val modeChanged = saved.tuningMode != current.tuningMode
                if (!presetChanged && !modeChanged) return@collect

                _state.value = current.copy(
                    preset = if (presetChanged) newPreset else current.preset,
                    tuningMode = if (modeChanged) saved.tuningMode else current.tuningMode,
                )
                // Either change (preset OR tuningMode) needs the player's
                // PlaybackParameters re-applied — the previous code only
                // applied on preset changes.
                PlayerHolder.player?.let {
                    applyTuning(it, newPreset, saved.tuningMode)
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
                if (player == null) {
                    // Service was destroyed. Stop the position polling
                    // loop so we don't keep ticking on a dead reference.
                    positionJob?.cancel()
                    positionJob = null
                } else {
                    onPlayerAttached(player)
                }
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
            // Look up in the current queue, not the library — for folder
            // playback the new track won't be in [tracks] at all.
            val track = _state.value.currentQueue.firstOrNull { it.id == id }
            _state.value = _state.value.copy(
                currentTrack = track,
                durationMs = track?.durationMs ?: 0,
            )
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            // For folder playback the queue tracks are filename-based
            // placeholders. ExoPlayer reads real tag metadata as it
            // prepares each file and fires this callback — patch the
            // displayed track so the now-playing bar shows proper info.
            //
            // Skip library tracks (albumId != 0): their MediaStore metadata
            // is already correct and shouldn't drift on decode.
            val current = _state.value.currentTrack ?: return
            if (current.albumId != 0L) return
            val newTitle = mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: current.title
            val newArtist = mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: current.artist
            val newAlbum = mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: current.album
            if (newTitle == current.title && newArtist == current.artist && newAlbum == current.album) {
                return
            }
            val enriched = current.copy(title = newTitle, artist = newArtist, album = newAlbum)
            // Patch the queue too so onMediaItemTransition resolves the
            // enriched track on subsequent transitions (and back-navigation).
            val newQueue = _state.value.currentQueue.map {
                if (it.id == enriched.id) enriched else it
            }
            _state.value = _state.value.copy(currentTrack = enriched, currentQueue = newQueue)
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
        // Tapping a track in the library list always exits folder mode.
        // If the user wants to stay in folder mode, they're tapping a
        // track that's already in [currentQueue] and we use that.
        val source = if (_state.value.folderActive) _state.value.currentQueue
                     else _state.value.tracks
        val startIndex = source.indexOfFirst { it.id == track.id }
        if (startIndex < 0) return
        // Only flip out of folder mode if the tapped track came from the
        // library (i.e. the source we're using is the library list).
        if (!_state.value.folderActive) {
            _state.value = _state.value.copy(folderName = null)
        }
        startQueue(source, startIndex, displayedTrack = track)
    }

    /**
     * Scan a Storage Access Framework folder and play it. Switches the
     * library UI to show the folder contents — tapping [returnToLibrary]
     * brings the full library back.
     *
     * If the folder contains no audio (nothing matched, all files too short),
     * we surface that via [PlayerUiState.folderError] for the activity to
     * show as a snackbar.
     */
    fun playFolder(treeUri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isScanningFolder = true, folderError = null)
            val folderTracks = MediaLibrary.scanFolder(getApplication(), treeUri)
            _state.value = _state.value.copy(isScanningFolder = false)
            if (folderTracks.isEmpty()) {
                _state.value = _state.value.copy(folderError = FolderError.NO_AUDIO)
                return@launch
            }
            // Flip into folder mode BEFORE startQueue so the list updates
            // atomically with the now-playing track.
            _state.value = _state.value.copy(
                folderActive = true,
                folderName = folderDisplayName(treeUri),
                searchQuery = "",
            )
            startQueue(folderTracks, startIndex = 0, displayedTrack = folderTracks[0])
        }
    }

    /** Switch the library UI back to the full MediaStore library. Doesn't
     *  touch playback — the folder's queue keeps playing until the user
     *  taps a library track or skips through the end of the folder. */
    fun returnToLibrary() {
        _state.value = _state.value.copy(folderActive = false, folderName = null, searchQuery = "")
    }

    fun clearFolderError() {
        _state.value = _state.value.copy(folderError = null)
    }

    /**
     * Best-effort human-readable name from a SAF tree URI. URIs look like
     *   content://com.android.externalstorage.documents/tree/primary%3AMusic%2FFavorites
     * — we pull the last segment after `:` and `/` and URL-decode it.
     * Falls back to "Folder" if anything goes wrong.
     */
    private fun folderDisplayName(treeUri: Uri): String {
        val raw = treeUri.lastPathSegment ?: return "Folder"
        val afterColon = raw.substringAfterLast(':')
        val decoded = runCatching { Uri.decode(afterColon) }.getOrNull() ?: afterColon
        val tail = decoded.substringAfterLast('/').trim()
        return tail.ifBlank { "Folder" }
    }

    /**
     * Shared "set up the player's queue and start playback" path. Used by
     * both [playTrack] (queue = whole library) and [playFolder] (queue =
     * folder scan).
     */
    private fun startQueue(tracks: List<Track>, startIndex: Int, displayedTrack: Track) {
        val player = PlayerHolder.player ?: return
        val items = tracks.map { t ->
            val builder = MediaItem.Builder()
                .setMediaId(t.id.toString())
                .setUri(t.uri)
            // Library tracks: we have real MediaStore metadata, pin it so
            // the now-playing display matches the library row.
            // Folder tracks: filename-only placeholders aren't worth pinning
            // — let ExoPlayer's decoded ID3/Vorbis/MP4 tags surface via
            // [Player.Listener.onMediaMetadataChanged] instead.
            if (t.albumId != 0L) {
                builder.setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(t.title)
                        .setArtist(t.artist)
                        .setAlbumTitle(t.album)
                        .setArtworkUri(t.artUri)
                        .build()
                )
            }
            builder.build()
        }
        player.setMediaItems(items, startIndex, 0L)
        applyCurrentTuning(player)
        player.prepare()
        player.playWhenReady = true
        _state.value = _state.value.copy(
            currentTrack = displayedTrack,
            currentQueue = tracks,
        )
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
        // Apply with the explicit new preset — never re-read state.value
        // because the MutableStateFlow's write may not be visible to a
        // subsequent reader on every dispatcher.
        PlayerHolder.player?.let {
            applyTuning(it, preset, _state.value.tuningMode)
        }
    }

    /** Toggle between LINKED and INDEPENDENT tuning modes. */
    fun toggleTuningMode() {
        val next = if (_state.value.tuningMode == TuningMode.LINKED) TuningMode.INDEPENDENT
                   else TuningMode.LINKED
        _state.value = _state.value.copy(tuningMode = next)
        viewModelScope.launch { settings.saveTuningMode(next) }
        PlayerHolder.player?.let {
            applyTuning(it, _state.value.preset, next)
        }
    }

    /**
     * Push a specific (preset, mode) pair to the player as a single
     * PlaybackParameters jump. The deep AudioTrack output buffer configured
     * in [DeepBufferRenderersFactory] absorbs the Sonic reconfiguration
     * boundary without an audible click — the same trick a low-latency
     * audio driver uses to hide reconfiguration latency behind its ring.
     *
     * # Why explicit args
     *
     * Earlier this read `_state.value` directly. That worked only because
     * MutableStateFlow writes are synchronous and the caller had just
     * written the new state on the same thread. Taking explicit args makes
     * the function pure with respect to its inputs and removes the implicit
     * coupling — callers from the DataStore observer can pass the values
     * straight from the [PersistedSettings] snapshot without an intervening
     * state write.
     */
    private fun applyTuning(player: ExoPlayer, preset: FrequencyPreset, mode: TuningMode) {
        val ratio = preset.pitchRatio
        val speed = if (mode == TuningMode.LINKED) ratio else 1f
        player.playbackParameters = PlaybackParameters(speed, ratio)
    }

    /** Compatibility shim — used by [onPlayerAttached] and [startQueue]
     *  which apply whatever the current state says. */
    private fun applyCurrentTuning(player: ExoPlayer) {
        val s = _state.value
        applyTuning(player, s.preset, s.tuningMode)
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
