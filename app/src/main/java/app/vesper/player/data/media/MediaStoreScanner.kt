package app.vesper.player.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import app.vesper.player.data.db.MediaSource
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity

const val UNKNOWN_ARTIST = "Неизвестный исполнитель"
const val UNKNOWN_ALBUM = "Неизвестный альбом"
const val UNKNOWN_GENRE = "Без жанра"

/** Reads the device media library through MediaStore (no file copying). */
object MediaStoreScanner {

    fun scanAudio(context: Context, minDurationMs: Long): List<TrackEntity> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(minDurationMs.toString())
        val legacyGenres = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) legacyGenreMap(context) else emptyMap()

        val result = ArrayList<TrackEntity>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.str(MediaStore.Audio.Media.DISPLAY_NAME)
                val rawTitle = c.str(MediaStore.Audio.Media.TITLE)
                val genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.str(MediaStore.Audio.Media.GENRE)
                } else {
                    legacyGenres[id].orEmpty()
                }
                result += TrackEntity(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = rawTitle.ifBlank { displayName.substringBeforeLast('.') },
                    artist = c.str(MediaStore.Audio.Media.ARTIST).cleanTag(UNKNOWN_ARTIST),
                    album = c.str(MediaStore.Audio.Media.ALBUM).cleanTag(UNKNOWN_ALBUM),
                    genre = genre.cleanTag(UNKNOWN_GENRE),
                    durationMs = c.long(MediaStore.Audio.Media.DURATION),
                    trackNumber = c.int(MediaStore.Audio.Media.TRACK) % 1000,
                    year = c.int(MediaStore.Audio.Media.YEAR),
                    mimeType = c.str(MediaStore.Audio.Media.MIME_TYPE),
                    sizeBytes = c.long(MediaStore.Audio.Media.SIZE),
                    dateAdded = c.long(MediaStore.Audio.Media.DATE_ADDED) * 1000L,
                    source = MediaSource.MEDIA_STORE,
                    folder = c.folder(),
                    displayName = displayName,
                )
            }
        }
        return result
    }

    fun scanVideo(context: Context): List<VideoEntity> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val result = ArrayList<VideoEntity>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.str(MediaStore.Video.Media.DISPLAY_NAME)
                val title = c.str(MediaStore.Video.Media.TITLE).ifBlank { displayName.substringBeforeLast('.') }
                result += VideoEntity(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    title = title,
                    durationMs = c.long(MediaStore.Video.Media.DURATION),
                    width = c.int(MediaStore.Video.Media.WIDTH),
                    height = c.int(MediaStore.Video.Media.HEIGHT),
                    sizeBytes = c.long(MediaStore.Video.Media.SIZE),
                    mimeType = c.str(MediaStore.Video.Media.MIME_TYPE),
                    dateAdded = c.long(MediaStore.Video.Media.DATE_ADDED) * 1000L,
                    source = MediaSource.MEDIA_STORE,
                    folder = c.str(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
                )
            }
        }
        return result
    }

    /** Genres before Android 11 live in a separate table. */
    private fun legacyGenreMap(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        runCatching {
            @Suppress("DEPRECATION")
            val genresUri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                genresUri,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null, null, null,
            )?.use { g ->
                while (g.moveToNext()) {
                    val genreId = g.getLong(0)
                    val name = g.getString(1) ?: continue
                    @Suppress("DEPRECATION")
                    val members = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    context.contentResolver.query(
                        members,
                        arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                        null, null, null,
                    )?.use { m ->
                        while (m.moveToNext()) map[m.getLong(0)] = name
                    }
                }
            }
        }
        return map
    }

    private fun Cursor.str(column: String): String {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i).orEmpty() else ""
    }

    private fun Cursor.long(column: String): Long {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getLong(i) else 0L
    }

    private fun Cursor.int(column: String): Int {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getInt(i) else 0
    }

    private fun Cursor.folder(): String {
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            str(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            str(MediaStore.Audio.Media.DATA).substringBeforeLast('/')
        }
        return path.trimEnd('/').substringAfterLast('/')
    }
}

internal fun String.cleanTag(fallback: String): String {
    val t = trim()
    return if (t.isEmpty() || t.equals("<unknown>", ignoreCase = true) || t.equals("unknown", ignoreCase = true)) fallback else t
}
