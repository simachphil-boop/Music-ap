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
import app.vesper.player.playback.StreamResolver
import app.vesper.player.playback.YouTubeFast
import app.vesper.player.playback.YtDlpTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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

class DownloadError(message: String, val needsLogin: Boolean, val network: Boolean = false) : Exception(message)

/** The connection dropped after part of the file arrived — retry continues from the saved parts. */
class NetworkInterrupted : java.io.IOException("Connection reset: network interrupted")

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
    val artist: String = "",
    val maxHeight: Int?,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val etaSec: Long = 0,
    val error: String? = null,
    val mediaId: Long? = null,
    val edit: EditSpec? = null,
    val needsLogin: Boolean = false,
    /** Current speed in bytes per second (0 = unknown). */
    val speedBps: Long = 0,
    /** 0 = best available, 1 = economy (~128 kbit/s, smaller files). */
    val audioQuality: Int = 0,
    /** Automatic retry number after a network failure (0 = first try). */
    val attempt: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
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
    private val prefs = context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs("floya_downloader"), Context.MODE_PRIVATE)

    /** false (default) = keep the original AAC/M4A — no re-encoding, several times faster. */
    var audioAsMp3: Boolean
        get() = prefs.getBoolean(KEY_MP3, false)
        set(value) = prefs.edit().putBoolean(KEY_MP3, value).apply()

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
        // Fast answers first: a direct file link, or YouTube's own API — both without starting yt-dlp.
        withContext(Dispatchers.IO) { DirectLink.probe(url) }?.let { d ->
            return Result.success(
                LinkInfo(
                    url = url,
                    title = d.fileName.substringBeforeLast('.'),
                    uploader = "",
                    durationSec = 0,
                    thumbnail = null,
                    source = "Прямая ссылка · " + d.ext.uppercase(),
                    heights = emptyList(),
                ),
            )
        }
        withContext(Dispatchers.IO) { runCatching { YouTubeFast.details(url) }.getOrNull() }?.let { d ->
            return Result.success(
                LinkInfo(
                    url = url,
                    title = d.title.ifBlank { "Без названия" },
                    uploader = d.author,
                    durationSec = d.durationSec,
                    thumbnail = d.thumbnail,
                    source = "Youtube",
                    heights = d.heights,
                ),
            )
        }
        if (!ensureEngine()) return Result.failure(IllegalStateException("Не удалось запустить движок загрузки (${FfmpegTool.lastError})"))
        autoUpdateIfStale()
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = YoutubeDLRequest(StreamResolver.normalizePage(url)).apply {
                    addOption("--no-playlist")
                    addOption("--dump-single-json")
                    addOption("--skip-download")
                    YtDlpTuning.apply(context, this, skipManifests = false)
                    addCookies(this)
                }
                val out = YoutubeDL.getInstance().execute(request).out
                val json = out.substring(out.indexOf('{').coerceAtLeast(0))
                val info = org.json.JSONObject(json)
                // The download reuses this instead of asking the site a second time.
                runCatching { infoFile(url).apply { parentFile?.mkdirs() }.writeText(json) }
                val formats = info.optJSONArray("formats")
                val heights = buildList {
                    if (formats != null) for (i in 0 until formats.length()) {
                        val f = formats.optJSONObject(i) ?: continue
                        val vcodec = f.optString("vcodec", "none")
                        val h = f.optInt("height", 0)
                        if (vcodec != "none" && vcodec != "null" && h > 0) add(h)
                    }
                }.distinct().sortedDescending()
                fun str(key: String) = info.optString(key).takeIf { it.isNotBlank() && it != "null" }
                LinkInfo(
                    url = url,
                    title = str("title") ?: "Без названия",
                    uploader = str("artist") ?: str("uploader") ?: str("channel") ?: "",
                    durationSec = info.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                    thumbnail = str("thumbnail"),
                    source = str("extractor_key") ?: "",
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
                            YtDlpTuning.apply(context, this, skipManifests = true)
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

    fun download(info: LinkInfo, kind: DownloadKind, maxHeight: Int?, edit: EditSpec? = null, audioQuality: Int = 0) {
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = info.url,
            title = info.title,
            thumbnail = info.thumbnail,
            kind = kind,
            artist = info.uploader,
            maxHeight = maxHeight,
            edit = edit?.takeIf { !it.isNoop },
            audioQuality = audioQuality,
        )
        _tasks.update { listOf(task) + it }
        persist()
        launchTask(task)
    }

    /** Runs again with the same id, so a half-downloaded file in its work folder is continued, not restarted. */
    fun retry(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.active) return
        val fresh = task.copy(status = DownloadStatus.QUEUED, error = null, needsLogin = false, attempt = 0, speedBps = 0, etaSec = 0)
        _tasks.update { list -> list.map { if (it.id == id) fresh else it } }
        persist()
        launchTask(fresh)
    }

    fun remove(id: String) {
        _tasks.update { list -> list.filter { it.id != id || it.active } }
        workDirOf(id).deleteRecursively()
        persist()
    }

    private fun launchTask(task: DownloadTask) {
        DownloadService.start(context)
        // At most two downloads at once — a whole playlist can be queued safely.
        scope.launch {
            slots.withPermit {
                var attempt = 0
                while (true) {
                    if (isCanceled(task.id)) break
                    val current = _tasks.value.firstOrNull { it.id == task.id } ?: break
                    run(current)
                    val after = _tasks.value.firstOrNull { it.id == task.id } ?: break
                    // Network hiccup: wait a little and continue from where it stopped (up to 3 times).
                    if (after.status == DownloadStatus.FAILED && networkFailures.remove(task.id) && attempt < AUTO_RETRIES) {
                        attempt++
                        val wait = longArrayOf(3, 8, 20)[attempt - 1]
                        for (left in wait downTo 1) {
                            if (isCanceled(task.id)) break
                            updateTask(task.id) {
                                it.copy(status = DownloadStatus.QUEUED, attempt = attempt, error = "Сеть прервалась — повтор через $left с (попытка $attempt из $AUTO_RETRIES)")
                            }
                            kotlinx.coroutines.delay(1000)
                        }
                        continue
                    }
                    break
                }
            }
        }
    }

    // ---- history (kept between app launches) -------------------------------------------------

    private val networkFailures = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun workDirOf(id: String) = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads/$id")

    private fun persist() {
        val snapshot = _tasks.value.take(HISTORY_LIMIT)
        scope.launch(Dispatchers.IO) {
            val arr = org.json.JSONArray()
            snapshot.forEach { t ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", t.id).put("url", t.url).put("title", t.title).put("thumb", t.thumbnail ?: "")
                        .put("kind", t.kind.name).put("artist", t.artist).put("h", t.maxHeight ?: 0)
                        .put("status", t.status.name).put("error", t.error ?: "").put("media", t.mediaId ?: 0L)
                        .put("q", t.audioQuality).put("at", t.createdAt).put("login", t.needsLogin),
                )
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
        }
    }

    private fun loadHistory(): List<DownloadTask> = runCatching {
        val arr = org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val status = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.FAILED)
            val interrupted = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PROCESSING
            DownloadTask(
                id = o.getString("id"),
                url = o.getString("url"),
                title = o.optString("title"),
                thumbnail = o.optString("thumb").ifBlank { null },
                kind = runCatching { DownloadKind.valueOf(o.getString("kind")) }.getOrDefault(DownloadKind.AUDIO),
                artist = o.optString("artist"),
                maxHeight = o.optInt("h").takeIf { it > 0 },
                status = if (interrupted) DownloadStatus.FAILED else status,
                error = if (interrupted) "Загрузка прервалась, когда приложение закрылось. Нажмите «Повторить» — продолжим с того же места." else o.optString("error").ifBlank { null },
                mediaId = o.optLong("media").takeIf { it > 0 },
                audioQuality = o.optInt("q"),
                createdAt = o.optLong("at", System.currentTimeMillis()),
                needsLogin = o.optBoolean("login"),
                progress = if (status == DownloadStatus.DONE) 1f else 0f,
            )
        }
    }.getOrDefault(emptyList())

    init {
        _tasks.value = loadHistory()
    }

    fun cancel(id: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
        updateTask(id) { it.copy(status = DownloadStatus.CANCELED) }
    }

    fun clearFinished() {
        val gone = _tasks.value.filter { !it.active }
        _tasks.update { list -> list.filter { it.active } }
        scope.launch(Dispatchers.IO) { gone.forEach { workDirOf(it.id).deleteRecursively() } }
        persist()
    }

    private fun addCookies(request: YoutubeDLRequest) {
        val f = CookieStore.file(context)
        if (CookieStore.hasAny(context)) request.addOption("--cookies", f.absolutePath)
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        var statusChanged = false
        _tasks.update { list ->
            list.map {
                if (it.id == id) transform(it).also { n -> if (n.status != it.status) statusChanged = true } else it
            }
        }
        // History is saved on status changes only, not on every progress tick.
        if (statusChanged) persist()
    }

    private suspend fun run(task: DownloadTask) = withContext(Dispatchers.IO) {
        val workDir = workDirOf(task.id).apply { mkdirs() }
        try {
            updateTask(task.id) { it.copy(status = DownloadStatus.DOWNLOADING) }
            val direct = directFile(task, workDir)
            if (direct != null) {
                finish(task, direct)
                return@withContext
            }
            if (isCanceled(task.id)) return@withContext
            if (!ensureEngine()) error("Не удалось запустить движок загрузки (${FfmpegTool.lastError})")
            val fast = try {
                fastYouTubeAudio(task, workDir)
            } catch (e: NetworkInterrupted) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fast != null) {
                finish(task, fast)
                return@withContext
            }
            if (isCanceled(task.id)) return@withContext
            // Leave yt-dlp's own .part files for resuming; drop only leftovers of the fast path.
            workDir.listFiles()?.filter { it.name.startsWith("source.") || it.name.startsWith("cover") || it.name.endsWith(".chunks") }?.forEach { it.delete() }
            val info = infoFile(task.url).takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < INFO_TTL_MS }
            val request = YoutubeDLRequest(StreamResolver.normalizePage(task.url)).apply {
                addOption("--no-playlist")
                addOption("--no-mtime")
                // Already looked the link up when the user opened it — skip the second lookup.
                if (info != null) addOption("--load-info-json", info.absolutePath)
                addOption("--concurrent-fragments", "4")
                // Ride out short network drops instead of failing the whole download.
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--socket-timeout", "20")
                addOption("--continue")
                YtDlpTuning.apply(context, this, skipManifests = task.kind == DownloadKind.AUDIO)
                addCookies(this)
                task.edit?.takeIf { it.trims }?.let { e ->
                    // Fetch only the chosen fragment — saves traffic and time.
                    val end = if (e.endMs > 0) FfmpegTool.seconds(e.endMs) else "inf"
                    addOption("--download-sections", "*${FfmpegTool.seconds(e.startMs)}-$end")
                }
                addOption("-o", workDir.absolutePath + "/%(title).120B.%(ext)s")
                if (task.kind == DownloadKind.AUDIO) {
                    addOption("-x")
                    val economy = task.audioQuality == 1
                    if (audioAsMp3) {
                        addOption("-f", if (economy) "bestaudio[abr<=140]/worstaudio/bestaudio/best" else "bestaudio/best")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", if (economy) "5" else "0")
                    } else if (economy) {
                        addOption("-f", "bestaudio[abr<=140][ext=m4a]/bestaudio[abr<=140]/worstaudio[ext=m4a]/worstaudio/bestaudio/best")
                        addOption("--audio-format", "best")
                    } else {
                        // Take a format that can be saved as is: AAC → .m4a, MP3 → .mp3, no re-encoding.
                        addOption("-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio[ext=mp3]/bestaudio/best")
                        addOption("--audio-format", "best")
                    }
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
                val speed = parseSpeed(line)
                updateTask(task.id) {
                    if (it.status == DownloadStatus.CANCELED) it
                    else it.copy(
                        status = if (processing) DownloadStatus.PROCESSING else DownloadStatus.DOWNLOADING,
                        progress = if (progress >= 0f) (progress / 100f).coerceIn(0f, 1f) else it.progress,
                        etaSec = eta,
                        speedBps = if (processing) 0 else speed ?: it.speedBps,
                        error = null,
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

            finish(task, file)
        } catch (e: YoutubeDL.CanceledException) {
            updateTask(task.id) { it.copy(status = DownloadStatus.CANCELED) }
        } catch (e: Exception) {
            val current = _tasks.value.firstOrNull { it.id == task.id }
            if (current?.status != DownloadStatus.CANCELED) {
                val d = describe(e)
                if (d.network) networkFailures += task.id
                updateTask(task.id) { it.copy(status = DownloadStatus.FAILED, error = d.message, needsLogin = d.needsLogin, speedBps = 0) }
            }
        } finally {
            // Keep partial data after a failure so «Повторить» continues instead of starting over.
            val st = _tasks.value.firstOrNull { it.id == task.id }?.status
            if (st == DownloadStatus.DONE || st == DownloadStatus.CANCELED) workDir.deleteRecursively()
        }
    }

    private suspend fun finish(task: DownloadTask, file: File) {
        val uri = MediaPublisher.publish(context, file, task.kind == DownloadKind.AUDIO)
        val mediaId = when (val r = library.importSingle(uri)) {
            is ProbeResult.Audio -> r.track.id.also { library.markDownloaded(it) }
            is ProbeResult.Video -> r.video.id
            is ProbeResult.Unsupported -> null
        }
        updateTask(task.id) { it.copy(status = DownloadStatus.DONE, progress = 1f, mediaId = mediaId) }
    }

    /** Direct file links: plain parallel HTTP download, saved as is — the "old download site" speed. */
    private suspend fun directFile(task: DownloadTask, workDir: File): File? {
        if (task.edit != null) return null
        val d = DirectLink.cached(task.url) ?: DirectLink.probe(task.url) ?: return null
        val fits = (task.kind == DownloadKind.AUDIO && d.isAudio) || (task.kind == DownloadKind.VIDEO && d.isVideo)
        if (!fits) return null
        val target = File(workDir, d.fileName)
        val ok = ParallelHttp.download(d.url, DirectLink.headers, d.length, target, { isCanceled(task.id) }) { done, total, bps ->
            updateTask(task.id) {
                if (it.status == DownloadStatus.CANCELED) it
                else it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f), etaSec = if (bps > 0) (total - done) / bps else 0, speedBps = bps, error = null)
            }
        }
        if (ok) return target
        if (File(target.path + ".chunks").isFile && !isCanceled(task.id)) throw NetworkInterrupted()
        // Server doesn't allow parts (no Range support): one ordinary stream, still without yt-dlp.
        if (isCanceled(task.id)) return null
        return runCatching {
            val conn = (java.net.URL(d.url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                DirectLink.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastUi = 0L
                        while (true) {
                            if (isCanceled(task.id)) return@runCatching null
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastUi > 300) {
                                lastUi = now
                                updateTask(task.id) { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                }
                target.takeIf { it.length() > 0 }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun isCanceled(id: String) = _tasks.value.firstOrNull { it.id == id }?.status == DownloadStatus.CANCELED

    private fun infoFile(url: String): File =
        File(context.cacheDir, "link-info/" + Integer.toHexString(url.hashCode()) + "_" + url.length + ".json")

    /**
     * YouTube audio without yt-dlp: a direct link from [StreamResolver] (often already known because the
     * track is playing), downloaded in 4 parallel parts, then FFmpeg only packs cover and tags — no re-encoding.
     * Returns null to let the regular yt-dlp path take over.
     */
    private suspend fun fastYouTubeAudio(task: DownloadTask, workDir: File): File? = coroutineScope {
        if (task.kind != DownloadKind.AUDIO || task.edit != null) return@coroutineScope null
        val id = YouTubeFast.videoId(task.url) ?: return@coroutineScope null
        val stream = (if (task.audioQuality == 1) YouTubeFast.audio(task.url, economy = true) else StreamResolver.fastAudio(context, task.url))
            ?: return@coroutineScope null
        val raw = File(workDir, "source.${stream.ext}")
        if (!ParallelHttp.download(stream.url, stream.headers, stream.contentLength, raw, { isCanceled(task.id) }) { done, total, bytesPerSec ->
                updateTask(task.id) {
                    if (it.status == DownloadStatus.CANCELED) it
                    else it.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = (done.toFloat() / total).coerceIn(0f, 1f),
                        etaSec = if (bytesPerSec > 0) (total - done) / bytesPerSec else 0,
                        speedBps = bytesPerSec,
                        error = null,
                    )
                }
            }
        ) {
            if (isCanceled(task.id)) return@coroutineScope null
            // Part of the file is already here — keep it and let the retry continue.
            if (File(raw.path + ".chunks").isFile) throw NetworkInterrupted()
            return@coroutineScope null
        }
        updateTask(task.id) { it.copy(status = DownloadStatus.PROCESSING, progress = 1f, speedBps = 0) }

        val cover = fetchCover(id, task.thumbnail, workDir)
        val title = task.title.ifBlank { stream.title }
        val artist = task.artist.ifBlank { stream.author }.removeSuffix(" - Topic")
        val mp3 = audioAsMp3
        val needsEncode = mp3 || stream.ext != "m4a"
        val outName = MediaEditor.safeName(if (artist.isNotBlank() && !title.contains(artist, true)) "$artist - $title" else title)
        val out = File(workDir, outName + if (mp3) ".mp3" else ".m4a")
        val args = buildList {
            add("-y"); add("-i"); add(raw.absolutePath)
            if (cover != null) { add("-i"); add(cover.absolutePath) }
            add("-map"); add("0:a")
            if (cover != null) { add("-map"); add("1:v") }
            when {
                mp3 -> { add("-c:a"); add("libmp3lame"); add("-q:a"); add(if (task.audioQuality == 1) "5" else "0"); add("-id3v2_version"); add("3") }
                needsEncode -> { add("-c:a"); add("aac"); add("-b:a"); add("192k") }
                else -> { add("-c:a"); add("copy") }
            }
            if (cover != null) { add("-c:v"); add(if (mp3) "mjpeg" else "copy"); add("-disposition:v:0"); add("attached_pic") }
            add("-metadata"); add("title=$title")
            if (artist.isNotBlank()) { add("-metadata"); add("artist=$artist") }
            if (!mp3) { add("-movflags"); add("+faststart") }
            add(out.absolutePath)
        }
        val durationMs = stream.durationSec * 1000L
        var (code, _) = FfmpegTool.run(context, args, durationMs) {}
        if (code != 0 && cover != null) {
            // Some covers can't be embedded — keep the music anyway.
            val noCover = args.toMutableList().apply {
                val i = indexOf(cover.absolutePath); removeAt(i); removeAt(i - 1)
                val m = indexOf("1:v"); removeAt(m); removeAt(m - 1)
                val c = indexOf("-c:v"); removeAt(c + 1); removeAt(c)
                val d = indexOf("-disposition:v:0"); removeAt(d + 1); removeAt(d)
            }
            code = FfmpegTool.run(context, noCover, durationMs) {}.first
        }
        if (code != 0 || !out.isFile || out.length() == 0L) return@coroutineScope null
        raw.delete()
        out
    }

    /** Square cover: YouTube Music art in high resolution, or the video frame cropped to a square. */
    private suspend fun fetchCover(videoId: String, thumbnail: String?, workDir: File): File? {
        val candidates = buildList {
            if (thumbnail != null && thumbnail.contains("googleusercontent")) {
                add(thumbnail.replace(Regex("=w\\d+-h\\d+.*$"), "=w600-h600-l90-rj"))
            }
            add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
            add("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
        }
        val raw = File(workDir, "cover_src.jpg")
        val ok = candidates.firstOrNull { url -> ParallelHttp.fetchSmall(url, raw) } ?: return null
        val square = File(workDir, "cover.jpg")
        val crop = if (ok.contains("i.ytimg.com")) listOf("-vf", "crop='min(iw,ih)':'min(iw,ih)'") else emptyList()
        val code = FfmpegTool.run(context, listOf("-y", "-i", raw.absolutePath) + crop + listOf("-q:v", "2", square.absolutePath), 0) {}.first
        return if (code == 0 && square.isFile && square.length() > 0) square else raw
    }

    companion object {
        private const val KEY_LAST_UPDATE = "last_engine_update"
        private const val KEY_MP3 = "audio_as_mp3"
        private const val KEY_HISTORY = "history_v1"
        private const val HISTORY_LIMIT = 150
        private const val AUTO_RETRIES = 3

        /** "[download]  42.0% of 5.31MiB at 1.20MiB/s ETA 00:03" → bytes per second. */
        fun parseSpeed(line: String): Long? {
            val m = Regex("""at\s+([\d.]+)\s*([KMG]?i?B)/s""").find(line) ?: return null
            val v = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2].uppercase().firstOrNull()) {
                'K' -> 1024.0
                'M' -> 1024.0 * 1024
                'G' -> 1024.0 * 1024 * 1024
                else -> 1.0
            }
            return (v * mult).toLong()
        }
        private const val INFO_TTL_MS = 30L * 60 * 1000
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
                has("Unable to resolve host", "Failed to resolve", "Network is unreachable", "No address associated") ->
                    DownloadError("Нет подключения к интернету. Повторим автоматически, когда связь вернётся.", needsLogin = false, network = true)
                has("timed out", "timeout", "Connection reset", "connection abort", "SocketException", "Broken pipe", "IncompleteRead", "HTTP Error 5", "Connection refused", "unexpected end of stream") ->
                    DownloadError("Связь прервалась во время загрузки. Повторяем и продолжаем с того же места.", needsLogin = false, network = true)
                has("No space left") -> DownloadError("Не хватает места на телефоне. Освободите память и нажмите «Повторить».", needsLogin = false)
                has("Requested format is not available") -> DownloadError("Выбранное качество недоступно для этого ролика — выберите «Лучшее» и повторите.", needsLogin = false)
                has("HTTP Error 403") ->
                    DownloadError("Сайт отклонил загрузку. Обновите движок ниже или войдите в аккаунт.\n$detail", needsLogin = true)
                has("not available", "unavailable", "removed") -> DownloadError("Видео недоступно.\n$detail", needsLogin = false)
                raw.isBlank() -> DownloadError("Не удалось скачать", needsLogin = false)
                else -> DownloadError(detail, needsLogin = false)
            }
        }
    }
}
