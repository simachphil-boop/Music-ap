package app.vesper.player.data.media

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import app.vesper.player.data.db.MediaSource
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity

enum class MediaKind { AUDIO, VIDEO }

sealed interface ProbeResult {
    data class Audio(val track: TrackEntity) : ProbeResult
    data class Video(val video: VideoEntity) : ProbeResult
    data class Unsupported(val name: String, val reason: String) : ProbeResult
}

/**
 * Imports user-picked documents via the Storage Access Framework.
 * Files are never copied — we persist a read grant on the URI and store a reference.
 */
object MediaImporter {

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mka", "3gp", "mid", "midi", "weba",
    )
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "ts", "avi", "flv")

    /** Formats Android/ExoPlayer cannot decode out of the box. */
    private val unsupportedMimes = setOf(
        "audio/x-ms-wma", "audio/x-ms-wax", "video/x-ms-wmv", "video/x-ms-asf", "audio/ape", "audio/x-ape",
        "audio/x-aiff", "audio/aiff", "video/x-ms-asx",
    )
    private val unsupportedExtensions = setOf("wma", "wmv", "asf", "ape", "aiff", "aif", "rm", "rmvb", "dsf", "dff")

    fun takePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Recursively lists audio/video documents in a picked folder tree. */
    fun listTree(context: Context, treeUri: Uri, maxFiles: Int = 5000): List<Pair<Uri, String>> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = ArrayList<Pair<Uri, String>>()
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 10 || out.size >= maxFiles) return
            for (child in dir.listFiles()) {
                if (out.size >= maxFiles) return
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else {
                    val name = child.name.orEmpty()
                    val mime = child.type.orEmpty()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (mime.startsWith("audio/") || mime.startsWith("video/") ||
                        ext in audioExtensions || ext in videoExtensions || ext in unsupportedExtensions
                    ) {
                        out += child.uri to name
                    }
                }
            }
        }
        walk(root, 0)
        return out
    }

    fun probe(context: Context, uri: Uri, knownName: String? = null, folder: String = "Импорт"): ProbeResult {
        val resolver = context.contentResolver
        var name = knownName
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        if (name == null && !c.isNull(0)) name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
        }
        val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = (runCatching { resolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "").lowercase()

        if (mime in unsupportedMimes || ext in unsupportedExtensions) {
            return ProbeResult.Unsupported(fileName, "Формат не поддерживается")
        }
        val kind = when {
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime.startsWith("video/") -> MediaKind.VIDEO
            ext in audioExtensions && ext != "3gp" -> MediaKind.AUDIO
            ext in videoExtensions -> MediaKind.VIDEO
            else -> return ProbeResult.Unsupported(fileName, "Это не аудио и не видео")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = runCatching { retriever.extractMetadata(key) }.getOrNull().orEmpty()
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0L
            val hasVideo = meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!hasVideo && !hasAudio && duration <= 0L) {
                return ProbeResult.Unsupported(fileName, "Файл повреждён или не читается")
            }
            val now = System.currentTimeMillis()
            val baseTitle = fileName.substringBeforeLast('.')
            return if (kind == MediaKind.VIDEO || (hasVideo && mime.startsWith("video/"))) {
                ProbeResult.Video(
                    VideoEntity(
                        uri = uri.toString(),
                        title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { baseTitle },
                        durationMs = duration,
                        width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toIntOrNull() ?: 0,
                        height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toIntOrNull() ?: 0,
                        sizeBytes = size,
                        mimeType = mime.ifBlank { "video/$ext" },
                        dateAdded = now,
                        source = MediaSource.IMPORTED,
                        folder = folder,
                    ),
                )
            } else {
                val trackNo = meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    .substringBefore('/').trim().toIntOrNull() ?: 0
                val year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR).take(4).toIntOrNull() ?: 0
                ProbeResult.Audio(
                    TrackEntity(
                        uri = uri.toString(),
                        title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { baseTitle },
                        artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            .ifBlank { meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) }
                            .cleanTag(UNKNOWN_ARTIST),
                        album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM).cleanTag(UNKNOWN_ALBUM),
                        genre = meta(MediaMetadataRetriever.METADATA_KEY_GENRE).cleanTag(UNKNOWN_GENRE),
                        durationMs = duration,
                        trackNumber = trackNo,
                        year = year,
                        mimeType = mime.ifBlank { "audio/$ext" },
                        sizeBytes = size,
                        dateAdded = now,
                        source = MediaSource.IMPORTED,
                        folder = folder,
                        displayName = fileName,
                    ),
                )
            }
        } catch (e: Exception) {
            return ProbeResult.Unsupported(fileName, "Не удалось прочитать файл")
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Extra technical details for the track-info sheet. */
    fun technicalInfo(context: Context, uri: Uri): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let {
                out["Битрейт"] = "${it / 1000} кбит/с"
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let {
                    out["Частота"] = "%.1f кГц".format(it / 1000f)
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.let {
                    if (it > 0) out["Разрядность"] = "$it бит"
                }
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.takeIf { it.isNotBlank() }?.let {
                out["Композитор"] = it
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.takeIf { it.isNotBlank() }?.let {
                out["Исполнитель альбома"] = it
            }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        return out
    }
}
