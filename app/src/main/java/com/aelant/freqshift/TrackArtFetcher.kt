package com.aelant.freqshift

import android.content.Context
import android.media.MediaMetadataRetriever
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.source

/**
 * Coil [Fetcher] that resolves album art for a [Track] in two steps:
 *
 * 1. Try MediaStore's thumbnail URI (`content://media/external/audio/albumart/<id>`).
 *    Fast, cached, but only present when the system has indexed art for the album.
 *
 * 2. Fall back to the audio file's embedded ID3v2 APIC frame (or equivalent for
 *    FLAC/MP4) via [MediaMetadataRetriever.getEmbeddedPicture]. This works for
 *    any tagged file regardless of MediaStore's view of it.
 *
 * # Why a custom fetcher
 *
 * Pointing Coil at the MediaStore URI alone leaves the placeholder icon visible
 * for any file whose album hasn't been indexed — very common for sideloaded
 * music, low-priority albums, or right after a fresh import. Falling back to
 * the embedded picture closes that gap. We hand Coil a [Track] and let this
 * fetcher pick the best source; the UI layer makes no decisions.
 */
class TrackArtFetcher(
    private val track: Track,
    private val context: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        // 1. MediaStore thumbnail. We skip this when [Track.albumId] is 0
        // (folder tracks have no MediaStore-indexed album art).
        if (track.albumId != 0L) {
            runCatching {
                context.contentResolver.openInputStream(track.artUri)
            }.getOrNull()?.let { stream ->
                return@withContext SourceResult(
                    source = ImageSource(
                        source = stream.source().buffer(),
                        context = context,
                    ),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }

        // 2. Embedded APIC / equivalent.
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, track.uri)
            val bytes = mmr.embeddedPicture
            if (bytes != null && bytes.isNotEmpty()) {
                return@withContext SourceResult(
                    source = ImageSource(
                        source = Buffer().apply { write(bytes) },
                        context = context,
                    ),
                    mimeType = null,
                    dataSource = DataSource.MEMORY,
                )
            }
        } catch (_: Throwable) {
            // Unsupported codec, malformed file, or revoked URI permission —
            // fall through to null so Coil shows the placeholder.
        } finally {
            // MediaMetadataRetriever is AutoCloseable only on API 29+; on
            // older devices we must release() manually.
            mmr.release()
        }

        null
    }

    /**
     * Coil factory entry — register via
     * `ImageLoader.Builder.components { add(TrackArtFetcher.Factory(ctx)) }`.
     */
    class Factory(private val context: Context) : Fetcher.Factory<Track> {
        override fun create(
            data: Track,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = TrackArtFetcher(data, context)
    }
}
