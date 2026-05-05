package com.aelant.freqshift

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/**
 * RenderersFactory that gives the audio pipeline a deep output buffer.
 *
 * # Why this exists
 *
 * The ExoPlayer pipeline is roughly:
 *
 *     Decoder → AudioProcessors (Sonic ↦ pitch+speed) → AudioTrack → speaker
 *
 * When [androidx.media3.common.PlaybackParameters] change, Sonic flushes its
 * internal frame and starts producing samples at the new ratio. With Media3's
 * default ~100 ms PCM buffer, that flush boundary is close to the speaker
 * and the discontinuity is audible as a click.
 *
 * The fix is the same trick a low-latency audio driver (CoreAudio, ASIO,
 * JACK) uses: a deeper output ring. Make AudioTrack hold ~750 ms of already-
 * processed PCM. When Sonic reconfigures, the AudioTrack keeps draining
 * pre-buffered samples while Sonic stabilises, then the new-ratio samples
 * arrive smoothly behind them. The reconfiguration boundary is buried deep
 * inside the buffer where rapid-fire updates can no longer surface clicks.
 *
 * # Why 750 ms
 *
 * Media3's default `PCM_BUFFER_MULTIPLICATION_FACTOR` produces roughly
 * 100–250 ms of buffered audio. We need significantly more headroom than
 * Sonic's settling time (~50 ms) to fully absorb its reconfiguration. 750 ms
 * is conservative and works on every device. The latency cost (≈¾ second
 * from press to audible change) is irrelevant for a music tuning app.
 *
 * # Caveats
 *
 * Larger buffers consume more RAM (≈300 KB at 44.1 kHz 16-bit stereo per
 * additional second) and add latency to seek operations. Both are fine here.
 *
 * # API note
 *
 * `DefaultAudioTrackBufferSizeProvider` and `DefaultAudioSink.Builder` are
 * marked `@UnstableApi` in Media3. Project-wide opt-in lives in
 * `app/build.gradle.kts`; the file-level [@UnstableApi] below makes the
 * dependency explicit.
 */
@UnstableApi
class DeepBufferRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        // Disable AudioTrack-native pitch path at the renderer level too,
        // so it never gets routed away from Sonic (which is what our deep
        // buffer is in front of).
        setEnableAudioTrackPlaybackParams(false)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(BUFFER_MIN_US)
            .setPcmBufferMultiplicationFactor(PCM_FACTOR)
            .build()

        // We force AudioTrack-native pitch off: when enabled, ExoPlayer can
        // route pitch through AudioTrack's own setPlaybackParams which has
        // different (and worse) reconfiguration behaviour — and crucially is
        // not buffered the same way Sonic's output is. Force application-
        // level Sonic so the deep buffer can absorb the click.
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(false)
            .setAudioTrackBufferSizeProvider(bufferSizeProvider)
            .build()
    }

    private companion object {
        /** Minimum duration of buffered PCM in the AudioTrack ring. */
        const val BUFFER_MIN_US: Int = 750_000   // 750 ms

        /** Default is 4 — keep at the default; the min duration above is the
         *  binding constraint for our use case. */
        const val PCM_FACTOR: Int = 4
    }
}
