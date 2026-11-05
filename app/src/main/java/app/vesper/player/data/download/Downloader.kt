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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class DownloadKind { VIDEO, AUDIO }

class DownloadError(message: String, val needsLogin: Boolean) : Exception(message)

enum class SearchSource(val title: String, val note: String) {
    YOUTUBE("YouTube", "Музыка, клипы, видео"),
    SOUNDCLOUD("SoundCloud", "Треки музыкантов"),
    ARCHIVE("Internet Archive", "Бесплатно и легально: общественное достояние и CC"),
}

data class SearchResult(
    val url: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val thumbnail: String?,
    val source: SearchSource,
)

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
    val needsLogin: Boolean = false,
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
    private val slots = kotlinx.coroutines.sync.Semaphore(2)

    suspend fun prepare(): Boolean = ensureEngine()
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
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addCookies(this)
                }
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
            }.recoverCatching { e -> throw describe(e) }
        }
    }

    /** Searches a source and returns pages that the regular download flow can open. */
    suspend fun search(query: String, source: SearchSource): Result<List<SearchResult>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        return withContext(Dispatchers.IO) {
            runCatching {
                when (source) {
                    SearchSource.ARCHIVE -> searchArchive(q)
                    else -> {
                        if (!ensureEngine()) throw DownloadError("Не удалось запустить движок загрузки (${FfmpegTool.lastError})", false)
                        autoUpdateIfStale()
                        val prefix = if (source == SearchSource.YOUTUBE) "ytsearch25" else "scsearch25"
                        val request = YoutubeDLRequest("$prefix:$q").apply {
                            addOption("--flat-playlist")
                            addOption("--dump-single-json")
                            addOption("--no-warnings")
                            addCookies(this)
                        }
                        val out = YoutubeDL.getInstance().execute(request).out
                        parseFlatPlaylist(out, source)
                    }
                }
            }.recoverCatching { e -> throw if (e is DownloadError) e else describe(e) }
        }
    }

    private fun parseFlatPlaylist(json: String, source: SearchSource): List<SearchResult> {
        val start = json.indexOf('{')
        if (start < 0) return emptyList()
        val root = org.json.JSONObject(json.substring(start))
        val entries = root.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<SearchResult>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val url = e.optString("webpage_url").ifBlank { e.optString("url") }
            if (url.isBlank() || !url.startsWith("http")) continue
            val thumbs = e.optJSONArray("thumbnails")
            val thumb = e.optString("thumbnail").ifBlank {
                if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
            }
            out += SearchResult(
                url = url,
                title = e.optString("title").ifBlank { "Без названия" },
                author = e.optString("channel").ifBlank { e.optString("uploader") },
                durationSec = e.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                thumbnail = thumb.ifBlank { null },
                source = source,
            )
        }
        return out
    }

    /** archive.org advanced search — free public-domain / Creative Commons audio and video. */
    private fun searchArchive(q: String): List<SearchResult> {
        val query = "(${q}) AND mediatype:(audio OR movies)"
        val url = "https://archive.org/advancedsearch.php?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=mediatype&fl%5B%5D=runtime" +
            "&sort%5B%5D=downloads+desc&rows=30&page=1&output=json"
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val docs = org.json.JSONObject(body).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<SearchResult>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optString("identifier")
            if (id.isBlank()) continue
            val creator = d.optJSONArray("creator")?.optString(0) ?: d.optString("creator")
            out += SearchResult(
                url = "https://archive.org/details/$id",
                title = d.optJSONArray("title")?.optString(0) ?: d.optString("title").ifBlank { id },
                author = creator.orEmpty(),
                durationSec = parseRuntime(d.optString("runtime")),
                thumbnail = "https://archive.org/services/img/$id",
                source = SearchSource.ARCHIVE,
            )
        }
        return out
    }

    private fun parseRuntime(raw: String): Int {
        if (raw.isBlank()) return 0
        val parts = raw.trim().split(":").mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toInt()
            2 -> (parts[0] * 60 + parts[1]).toInt()
            1 -> parts[0].toInt()
            else -> 0
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
        // At most two downloads at once — a whole playlist can be queued safely.
        scope.launch { slots.withPermit { if (_tasks.value.firstOrNull { it.id == task.id }?.status != DownloadStatus.CANCELED) run(task) } }
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        updateTask(id) { it.copy(status = DownloadStatus.CANCELED) }
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active } }
    }

    private fun addCookies(request: YoutubeDLRequest) {
        val f = CookieStore.file(context)
        if (CookieStore.hasAny(context)) request.addOption("--cookies", f.absolutePath)
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
                addCookies(this)
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
                is ProbeResult.Audio -> r.track.id.also { library.markDownloaded(it) }
                is ProbeResult.Video -> r.video.id
                is ProbeResult.Unsupported -> null
            }
            updateTask(task.id) { it.copy(status = DownloadStatus.DONE, progress = 1f, mediaId = mediaId) }
        } catch (e: YoutubeDL.CanceledException) {
            updateTask(task.id) { it.copy(status = DownloadStatus.CANCELED) }
        } catch (e: Exception) {
            val current = _tasks.value.firstOrNull { it.id == task.id }
            if (current?.status != DownloadStatus.CANCELED) {
                val d = describe(e)
                updateTask(task.id) { it.copy(status = DownloadStatus.FAILED, error = d.message, needsLogin = d.needsLogin) }
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

        /** Turns yt-dlp output into a short Russian message plus the original line for diagnostics. */
        fun describe(e: Throwable): DownloadError {
            val msg = e.message.orEmpty()
            if (msg.startsWith("Не удалось запустить движок")) return DownloadError(msg, needsLogin = false)
            val raw = msg.lines().lastOrNull { it.contains("ERROR") }?.removePrefix("ERROR:")?.trim()
                ?: msg.lines().lastOrNull { it.isNotBlank() }.orEmpty()
            val detail = raw.take(220)
            fun has(vararg keys: String) = keys.any { raw.contains(it, ignoreCase = true) }
            return when {
                has("not a bot", "confirm you") ->
                    DownloadError("YouTube просит подтвердить, что вы не бот. Войдите в аккаунт YouTube ниже и повторите.\n$detail", needsLogin = true)
                has("age-restricted", "confirm your age", "inappropriate for some users") ->
                    DownloadError("Видео 18+: войдите в аккаунт, чтобы скачать его.\n$detail", needsLogin = true)
                has("Private video", "private") ->
                    DownloadError("Это закрытое видео. Скачать можно, только если у вашего аккаунта есть доступ — войдите ниже.\n$detail", needsLogin = true)
                has("--cookies", "Sign in", "log in", "login required", "authentication") ->
                    DownloadError("Сайт требует вход в аккаунт. Войдите ниже и повторите.\n$detail", needsLogin = true)
                has("Unsupported URL") -> DownloadError("Этот сайт не поддерживается", needsLogin = false)
                has("Unable to resolve host", "Failed to resolve", "timed out", "Network is unreachable") ->
                    DownloadError("Нет подключения к интернету", needsLogin = false)
                has("HTTP Error 403") ->
                    DownloadError("Сайт отклонил загрузку. Обновите движок ниже или войдите в аккаунт.\n$detail", needsLogin = true)
                has("not available", "unavailable", "removed") -> DownloadError("Видео недоступно.\n$detail", needsLogin = false)
                raw.isBlank() -> DownloadError("Не удалось скачать", needsLogin = false)
                else -> DownloadError(detail, needsLogin = false)
            }
        }
    }
}
