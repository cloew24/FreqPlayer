package com.aelant.freqshift

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
) {
    /** content:// URI for the album art thumbnail (loaded by Coil). */
    val artUri: Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

    val durationLabel: String
        get() = "%d:%02d".format(durationMs / 1000 / 60, durationMs / 1000 % 60)

    private companion object {
        val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}

object MediaLibrary {

    /** Subdirectory under Music/ where the export writes its files. */
    const val EXPORT_FOLDER = "FreqShift"

    /** Full relative path used for both writes and exclusion filters. */
    val EXPORT_RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/$EXPORT_FOLDER"

    private const val MIN_TRACK_DURATION_MS = 30_000L

    /**
     * Scan the device for audio tracks. Excludes ringtones, notification
     * sounds, and short clips, plus our own re-pitched exports (no point
     * re-pitching an already-pitched file).
     */
    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val baseProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseProjection + MediaStore.Audio.Media.RELATIVE_PATH
        } else {
            baseProjection
        }

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ")
            append("${MediaStore.Audio.Media.DURATION} >= $MIN_TRACK_DURATION_MS")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND (${MediaStore.Audio.Media.RELATIVE_PATH} IS NULL OR ")
                append("${MediaStore.Audio.Media.RELATIVE_PATH} NOT LIKE '%/$EXPORT_FOLDER/%')")
            }
        }

        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    tracks += Track(
                        id = id,
                        title = clean(cursor.getString(titleCol), fallback = "Untitled"),
                        artist = clean(cursor.getString(artistCol), fallback = "—"),
                        album = clean(cursor.getString(albumCol), fallback = ""),
                        albumId = cursor.getLong(albumIdCol),
                        durationMs = cursor.getLong(durationCol),
                        uri = ContentUris.withAppendedId(collection, id),
                    )
                }
            }

        // Dedupe: MediaStore can index the same logical song via multiple
        // storage paths. Collapse by (title, artist, duration±1s) and keep
        // the entry with album art when there's a choice.
        tracks
            .groupBy { Triple(it.title.lowercase(), it.artist.lowercase(), it.durationMs / 1000) }
            .map { (_, dupes) -> dupes.maxByOrNull { if (it.albumId > 0L) 1 else 0 } ?: dupes.first() }
            .sortedBy { it.title.lowercase() }
    }

    /** Replace MediaStore's "<unknown>" sentinel with something readable. */
    private fun clean(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val trimmed = raw.trim()
        return when (trimmed.lowercase()) {
            "<unknown>", "unknown", "unknown artist" -> fallback
            else -> trimmed
        }
    }
}
