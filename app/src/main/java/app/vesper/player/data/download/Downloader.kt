package app.vesper.player.data.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import app.vesper.player.data.LibraryRepository
import app.vesper.player.data.media.ProbeResult
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class DownloadKind { VIDEO, AUDIO }

enum class DownloadStatus { QUEUED, DOWNLOADING, PROCESSING, DONE, FAILED, CANCELED }

data class LinkInfo(
    val url: String,
    val title: String,
    val uploader: String,
    val durationSec: Int,
    val thumbnail: String?,
    val source: String,
    /** Distinct video heights offered by the site, highest first. */
    val heights: List<Int>,
)

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val kind: DownloadKind,
    val maxHeight: Int?,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val error: String? = null,
    val mediaId: Long? = null,
) {
    val active: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PROCESSING
}

data class EngineState(
    val ready: Boolean = false,
    val preparing: Boolean = false,
    val updating: Boolean = false,
    val version: String? = null,
)

/**
 * Downloads video/audio from a link (direct files and sites supported by yt-dlp),
 * then publishes the file to the shared Movies/Music folder and adds it to the library.
 */
class Downloader(
    private val context: Context,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val initLock = Mutex()
    private val prefs = context.getSharedPreferences("floya_downloader", Context.MODE_PRIVATE)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _engine = MutableStateFlow(EngineState())
    val engine: StateFlow<EngineState> = _engine.asStateFlow()

    private suspend fun ensureEngine(): Boolean = initLock.withLock {
        if (_engine.value.ready) return@withLock true
        _engine.update { it.copy(preparing = true) }
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
            }
            val version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
            _engine.update { it.copy(ready = true, version = version) }
            true
        } catch (e: Exception) {
            false
        } finally {
            _engine.update { it.copy(preparing = false) }
        }
    }

    /** Sites change often; refresh yt-dlp at most once a week, silently. */
    private suspend fun autoUpdateIfStale() {
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last < 7L * 24 * 3600 * 1000) return
        runCatching { updateEngineInternal() }
    }

    private suspend fun updateEngineInternal(): String = withContext(Dispatchers.IO) {
        _engine.update { it.copy(updating = true) }
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            val version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
            _engine.update { it.copy(version = version) }
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "Движок загрузки уже последней версии"
            else "Движок загрузки обновлён${version?.let { " до $it" } ?: ""}"
        } finally {
            _engine.update { it.copy(updating = false) }
        }
    }

    suspend fun updateEngine(): String {
        if (!ensureEngine()) return "Не удалось запустить движок загрузки"
        return runCatching { updateEngineInternal() }
            .getOrElse { "Не удалось обновить: проверьте подключение к интернету" }
    }

    suspend fun fetchInfo(rawUrl: String): Result<LinkInfo> {
        val url = extractUrl(rawUrl) ?: return Result.failure(IllegalArgumentException("Это не похоже на ссылку"))
        if (!ensureEngine()) return Result.failure(IllegalStateException("Не удалось запустить движок загрузки"))
        autoUpdateIfStale()
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = YoutubeDLRequest(url).apply { addOption("--no-playlist") }
                val info = YoutubeDL.getInstance().getInfo(request)
                val heights = info.formats.orEmpty()
                    .filter { it.vcodec != null && it.vcodec != "none" && it.height > 0 }
                    .map { it.height }
                    .distinct()
                    .sortedDescending()
                LinkInfo(
                    url = url,
                    title = info.title?.takeIf { it.isNotBlank() } ?: "Без названия",
                    uploader = info.uploader.orEmpty(),
                    durationSec = info.duration,
                    thumbnail = info.thumbnail,
                    source = info.extractorKey.orEmpty(),
                    heights = heights,
                )
            }.recoverCatching { e -> throw IllegalStateException(friendlyError(e)) }
        }
    }

    fun download(info: LinkInfo, kind: DownloadKind, maxHeight: Int?) {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = info.url,
            title = info.title,
            thumbnail = info.thumbnail,
            kind = kind,
            maxHeight = maxHeight,
        )
        _tasks.update { listOf(task) + it }
        DownloadService.start(context)
        scope.launch { run(task) }
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        updateTask(id) { it.copy(status = DownloadStatus.CANCELED) }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active } }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun run(task: DownloadTask) = withContext(Dispatchers.IO) {
        val workDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/${task.id}").apply { mkdirs() }
        try {
            if (!ensureEngine()) error("Не удалось запустить движок загрузки")
            updateTask(task.id) { it.copy(status = DownloadStatus.DOWNLOADING) }
            val request = YoutubeDLRequest(task.url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("-o", workDir.absolutePath + "/%(title).120B.%(ext)s")
                if (task.kind == DownloadKind.AUDIO) {
                    addOption("-f", "bestaudio/best")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                } else {
                    val h = task.maxHeight
                    val format = if (h != null) {
                        "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b"
                    } else {
                        "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
                    }
                    addOption("-f", format)
                    addOption("--merge-output-format", "mp4")
                    addOption("--embed-metadata")
                }
            }
            YoutubeDL.getInstance().execute(request, task.id) { progress, eta, line ->
                val processing = line.contains("[Merger]") || line.contains("[ExtractAudio]") ||
                    line.contains("[EmbedThumbnail]") || line.contains("[Metadata]") || line.contains("[VideoConvertor]")
                updateTask(task.id) {
                    if (it.status == DownloadStatus.CANCELED) it
                    else it.copy(
                        status = if (processing) DownloadStatus.PROCESSING else DownloadStatus.DOWNLOADING,
                        progress = if (progress >= 0f) (progress / 100f).coerceIn(0f, 1f) else it.progress,
                        etaSec = eta,
                    )
                }
            }
            updateTask(task.id) { it.copy(status = DownloadStatus.PROCESSING, progress = 1f) }

            val skip = setOf("part", "ytdl", "jpg", "jpeg", "png", "webp", "json", "temp", "tmp")
            val file = workDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() !in skip && !it.name.endsWith(".part") }
                ?.maxByOrNull { it.length() }
                ?: error("Файл не получен — возможно, ролик недоступен")

            val uri = publish(file, task.kind)
            val mediaId = when (val r = library.importSingle(uri)) {
                is ProbeResult.Audio -> r.track.id
                is ProbeResult.Video -> r.video.id
                is ProbeResult.Unsupported -> null
            }
            updateTask(task.id) { it.copy(status = DownloadStatus.DONE, progress = 1f, mediaId = mediaId) }
        } catch (e: YoutubeDL.CanceledException) {
            updateTask(task.id) { it.copy(status = DownloadStatus.CANCELED) }
        } catch (e: Exception) {
            val current = _tasks.value.firstOrNull { it.id == task.id }
            if (current?.status != DownloadStatus.CANCELED) {
                updateTask(task.id) { it.copy(status = DownloadStatus.FAILED, error = friendlyError(e)) }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Moves the finished file into shared storage (Movies/Floya or Music/Floya) so it
     * survives app removal and is visible to other apps.
     */
    private fun publish(file: File, kind: DownloadKind): Uri {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (kind == DownloadKind.AUDIO) "audio/mpeg" else "video/mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (kind == DownloadKind.AUDIO) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (kind == DownloadKind.AUDIO) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Floya",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("Не удалось сохранить файл")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("Не удалось сохранить файл")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            // Same URI form the library scanner produces, so a later rescan doesn't duplicate it.
            val id = ContentUris.parseId(uri)
            return ContentUris.withAppendedId(
                if (kind == DownloadKind.AUDIO) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                id,
            )
        }
        // Android 9: keep the file in the app's media folder (no storage permission needed).
        val dirType = if (kind == DownloadKind.AUDIO) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val dir = File(ContextCompat.getExternalFilesDirs(context, dirType).firstOrNull() ?: context.filesDir, "Floya").apply { mkdirs() }
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) target = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        file.copyTo(target)
        return Uri.fromFile(target)
    }

    companion object {
        private const val KEY_LAST_UPDATE = "last_engine_update"
        private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

        /** Pulls the first link out of shared text like "Смотри: https://youtu.be/…". */
        fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', '»') }

        fun friendlyError(e: Throwable): String {
            val raw = (e.message ?: "").lines().lastOrNull { it.contains("ERROR") } ?: e.message.orEmpty()
            return when {
                raw.contains("Unsupported URL", true) -> "Этот сайт не поддерживается"
                raw.contains("Private video", true) || raw.contains("Sign in", true) || raw.contains("login", true) ->
                    "Видео закрыто или требует входа в аккаунт"
                raw.contains("not available", true) || raw.contains("unavailable", true) -> "Видео недоступно"
                raw.contains("Unable to resolve host", true) || raw.contains("network", true) || raw.contains("timed out", true) ->
                    "Нет подключения к интернету"
                raw.contains("HTTP Error 403", true) -> "Сайт отклонил загрузку — попробуйте обновить движок в настройках"
                raw.isBlank() -> "Не удалось скачать"
                else -> raw.removePrefix("ERROR:").trim().take(160)
            }
        }
    }
}
