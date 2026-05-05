package com.aelant.freqshift

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.Effect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Discriminated state of an export job. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val track: Track, val preset: FrequencyPreset, val progress: Int) : ExportState
    data class Done(val outputUri: Uri, val displayName: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/**
 * Renders a track to a new audio file with the current pitch ratio baked in.
 * Uses Media3's [Transformer] (the modern offline-render API) with the same
 * [SonicAudioProcessor] the live player uses — output is bit-identical to
 * what the user hears in the app.
 *
 * The result is registered with MediaStore under `Music/FreqShift/`, so it
 * appears in the system music library immediately.
 */
class AudioExporter(private val context: Context) {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var currentJob: Job? = null

    /** Reset to idle and cancel any in-flight render. */
    fun reset() {
        currentJob?.cancel()
        currentJob = null
        _state.value = ExportState.Idle
    }

    suspend fun export(track: Track, preset: FrequencyPreset, mode: TuningMode): Uri? =
        coroutineScope {
            currentJob = coroutineContext[Job]
            withContext(Dispatchers.Main) { runExport(track, preset, mode) }
        }

    private suspend fun runExport(
        track: Track, preset: FrequencyPreset, mode: TuningMode,
    ): Uri? {
        val displayName = buildOutputName(track, preset, mode)
        _state.value = ExportState.Running(track, preset, 0)

        val cacheFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.m4a")
        cacheFile.delete()

        val sonic = SonicAudioProcessor().apply {
            setPitch(preset.pitchRatio)
            setSpeed(if (mode == TuningMode.LINKED) preset.pitchRatio else 1f)
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(track.uri))
            .setEffects(Effects(
                ImmutableList.of<AudioProcessor>(sonic),
                ImmutableList.of<Effect>(),
            ))
            .setRemoveVideo(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()

        val deferred = CompletableDeferred<Boolean>()
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                deferred.complete(true)
            }
            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                deferred.completeExceptionally(exportException)
            }
        })

        try {
            transformer.start(edited, cacheFile.absolutePath)

            val holder = ProgressHolder()
            while (!deferred.isCompleted) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    (_state.value as? ExportState.Running)?.let {
                        _state.value = it.copy(progress = holder.progress)
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
            deferred.await()
        } catch (cancel: CancellationException) {
            transformer.cancel()
            cacheFile.delete()
            _state.value = ExportState.Idle
            throw cancel
        } catch (t: Throwable) {
            cacheFile.delete()
            _state.value = ExportState.Failed(t.message ?: "Export failed")
            return null
        }

        val publicUri = publishToMediaStore(cacheFile, displayName)
        cacheFile.delete()

        _state.value = if (publicUri != null) ExportState.Done(publicUri, displayName)
                       else ExportState.Failed("Could not save to music library")
        return publicUri
    }

    /** Build a filesystem-safe filename like `Imagine_vinyl_chakra_528hz.m4a`. */
    private fun buildOutputName(track: Track, preset: FrequencyPreset, mode: TuningMode): String {
        val safeTitle = track.title
            .replace(NON_FILENAME_CHARS, "")
            .trim()
            .replace(WHITESPACE, "_")
            .ifBlank { "Track" }
        val modeTag = if (preset.isStandard) "" else
                      if (mode == TuningMode.LINKED) "vinyl_" else "tuned_"
        return "${safeTitle}_${modeTag}${preset.fileTag}.m4a"
    }

    /** Publish the rendered file into MediaStore so it appears in the music library. */
    private fun publishToMediaStore(source: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, MediaLibrary.EXPORT_RELATIVE_PATH)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val target = resolver.insert(collection, values) ?: return null
        return try {
            val out = resolver.openOutputStream(target) ?: run {
                resolver.delete(target, null, null); return null
            }
            out.use { source.inputStream().use { input -> input.copyTo(it) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(target, values, null, null)
            }
            target
        } catch (t: Throwable) {
            resolver.delete(target, null, null)
            null
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
        const val MIME_TYPE = "audio/mp4"
        val NON_FILENAME_CHARS = Regex("[^A-Za-z0-9 _-]")
        val WHITESPACE = Regex("\\s+")
    }
}
