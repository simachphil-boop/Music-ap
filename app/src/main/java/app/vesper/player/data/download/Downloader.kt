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
import app.vesper.player.data.edit.EditSpec
import app.vesper.player.data.edit.FfmpegTool
import app.vesper.player.data.edit.MediaEditor
import app.vesper.player.data.edit.MediaPublisher
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
    val edit: EditSpec? = null,
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
            if (!FfmpegTool.init(context)) error("init failed")
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
        if (!ensureEngine()) return "Не удалось запустить движок загрузки (${FfmpegTool.lastError})"
        return runCatching { updateEngineInternal() }
            .getOrElse { "Не удалось обновить: проверьте подключение к интернету" }
    }

    suspend fun fetchInfo(rawUrl: String): Result<LinkInfo> {
        val url = extractUrl(rawUrl) ?: return Result.failure(IllegalArgumentException("Это не похоже на ссылку"))
        if (!ensureEngine()) return Result.failure(IllegalStateException("Не удалось запустить движок загрузки (${FfmpegTool.lastError})"))
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

    fun download(info: LinkInfo, kind: DownloadKind, maxHeight: Int?, edit: EditSpec? = null) {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = info.url,
            title = info.title,
            thumbnail = info.thumbnail,
            kind = kind,
            maxHeight = maxHeight,
            edit = edit?.takeIf { !it.isNoop },
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
            if (!ensureEngine()) error("Не удалось запустить движок загрузки (${FfmpegTool.lastError})")
            updateTask(task.id) { it.copy(status = DownloadStatus.DOWNLOADING) }
            val request = YoutubeDLRequest(task.url).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                task.edit?.takeIf { it.trims }?.let { e ->
                    // Fetch only the chosen fragment — saves traffic and time.
                    val end = if (e.endMs > 0) FfmpegTool.seconds(e.endMs) else "inf"
                    addOption("--download-sections", "*${FfmpegTool.seconds(e.startMs)}-$end")
                }
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
            var file = workDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() !in skip && !it.name.endsWith(".part") }
                ?.maxByOrNull { it.length() }
                ?: error("Файл не получен — возможно, ролик недоступен")

            val crop = task.edit?.crop
            if (task.kind == DownloadKind.VIDEO && crop != null) {
                updateTask(task.id) { it.copy(status = DownloadStatus.PROCESSING, progress = 0f) }
                val cropped = File(workDir, "cropped_" + file.nameWithoutExtension.take(100) + ".mp4")
                val duration = runCatching {
                    android.media.MediaMetadataRetriever().run {
                        setDataSource(file.absolutePath)
                        val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        release()
                        d
                    }
                }.getOrDefault(0L)
                MediaEditor.render(context, file, cropped, false, EditSpec(crop = crop, offset = task.edit?.offset ?: 0.5f), duration) { p ->
                    updateTask(task.id) { it.copy(progress = p) }
                }
                file.delete()
                file = File(workDir, file.nameWithoutExtension.take(100) + ".mp4").also { cropped.renameTo(it) }
            }

            val uri = MediaPublisher.publish(context, file, task.kind == DownloadKind.AUDIO)
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

    companion object {
        private const val KEY_LAST_UPDATE = "last_engine_update"
        private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

        /** Pulls the first link out of shared text like "Смотри: https://youtu.be/…". */
        fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', '»') }

        fun friendlyError(e: Throwable): String {
            if (e.message?.startsWith("Не удалось запустить движок") == true) return e.message!!
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
