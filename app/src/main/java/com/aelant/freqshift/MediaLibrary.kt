package com.aelant.freqshift

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
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
    /** Seconds since epoch when the file was added to MediaStore. 0 for
     *  tracks loaded from a folder (Storage Access Framework) since the
     *  underlying DocumentFile API doesn't expose this. */
    val dateAddedSec: Long = 0L,
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
            MediaStore.Audio.Media.DATE_ADDED,
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

        // Newest-first. We sort here in SQL for correctness (NULL handling)
        // and re-sort after dedup since the dedup step picks one row per
        // logical track and may break the order.
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val tracks = mutableListOf<Track>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

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
                        dateAddedSec = cursor.getLong(dateAddedCol),
                    )
                }
            }

        // Dedupe: MediaStore can index the same logical song via multiple
        // storage paths. Collapse by (title, artist, duration±1s) keeping the
        // entry with album art when there's a choice; among ties, keep the
        // most recently added so the newest copy wins.
        tracks
            .groupBy { Triple(it.title.lowercase(), it.artist.lowercase(), it.durationMs / 1000) }
            .map { (_, dupes) ->
                dupes
                    .sortedWith(
                        compareByDescending<Track> { if (it.albumId > 0L) 1 else 0 }
                            .thenByDescending { it.dateAddedSec }
                    )
                    .first()
            }
            .sortedByDescending { it.dateAddedSec }
    }

    /**
     * Scan a Storage Access Framework folder tree for audio files.
     *
     * **Fast path**: returns immediately with skeleton [Track] entries built
     * from filenames only — no [MediaMetadataRetriever] decode per file.
     * That keeps the scan O(directory listings) instead of O(file decodes),
     * so even a 500-file folder returns in well under a second.
     *
     * Real titles, durations, and embedded artwork come from ExoPlayer
     * once each track actually starts playing — the now-playing bar
     * updates from [Player.Listener.onMediaMetadataChanged].
     *
     * Walks subdirectories breadth-first so an album folder containing
     * per-track files is picked up correctly.
     *
     * Tracks built this way carry [Track.id] as a stable hash of their URI
     * (since they have no MediaStore _ID), [Track.albumId] = 0 (so the
     * MediaStore-thumbnail path in [TrackArtFetcher] is skipped and the
     * embedded-picture fallback is used), [Track.durationMs] = 0 (filled
     * in by ExoPlayer at playback time), and [Track.dateAddedSec] = 0.
     *
     * Capped at [MAX_FOLDER_TRACKS] to keep the synchronous scan responsive.
     */
    suspend fun scanFolder(context: Context, treeUri: Uri): List<Track> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            val out = mutableListOf<Track>()
            val queue: ArrayDeque<DocumentFile> = ArrayDeque<DocumentFile>().apply { add(root) }

            while (queue.isNotEmpty() && out.size < MAX_FOLDER_TRACKS) {
                val dir = queue.removeFirst()
                // Sort children for deterministic scan order: subfolders
                // first (so we go depth-first within each branch), then
                // files by name.
                val children = dir.listFiles().sortedWith(
                    compareByDescending<DocumentFile> { it.isDirectory }
                        .thenBy { it.name?.lowercase().orEmpty() }
                )
                for (child in children) {
                    if (out.size >= MAX_FOLDER_TRACKS) break
                    when {
                        child.isDirectory -> queue.add(child)
                        child.isFile && isAudio(child) -> {
                            buildFolderTrack(child)?.let(out::add)
                        }
                    }
                }
            }
            out
        }

    /** Cap synchronous folder scans so a misclick on a 10k-file directory
     *  doesn't block the UI for minutes. 500 covers typical folders. */
    private const val MAX_FOLDER_TRACKS = 500

    private fun isAudio(file: DocumentFile): Boolean {
        val mime = file.type?.lowercase() ?: return matchesAudioExtension(file.name)
        return mime.startsWith("audio/") || matchesAudioExtension(file.name)
    }

    private fun matchesAudioExtension(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        return AUDIO_EXTENSIONS.any { lower.endsWith(it) }
    }

    private val AUDIO_EXTENSIONS = listOf(
        ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus",
        ".wav", ".wma", ".mp4", ".3gp", ".amr",
    )

    /**
     * Build a skeleton [Track] from a [DocumentFile] without decoding the
     * file. Title comes from the filename minus extension; duration is 0
     * (ExoPlayer fills it on prepare). The whole call is essentially
     * pointer arithmetic — no I/O beyond what `DocumentFile` already did.
     */
    private fun buildFolderTrack(file: DocumentFile): Track? {
        val uri = file.uri
        val name = file.name ?: return null
        val title = name.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: name
        return Track(
            // Stable synthetic ID: hash of the URI string. Two scans of the
            // same folder produce the same IDs, so the player can restore
            // position across rotations / process death.
            id = uri.toString().hashCode().toLong() and 0x7FFF_FFFF_FFFFL,
            title = title,
            artist = "—",
            album = "",
            albumId = 0L,
            durationMs = 0L,
            uri = uri,
            dateAddedSec = 0L,
        )
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
