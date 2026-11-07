package app.floya.desktop.online

import app.floya.desktop.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

enum class DlStatus { QUEUED, DOWNLOADING, PROCESSING, DONE, FAILED, CANCELED }

data class DlTask(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val video: Boolean,
    val status: DlStatus = DlStatus.QUEUED,
    val progress: Float = 0f,
    val speed: String = "",
    val error: String? = null,
    val file: String? = null,
)

data class LinkInfo(val url: String, val title: String, val author: String, val durationSec: Int, val thumbnail: String?, val source: String)

/**
 * Downloads, fastest way first:
 *  1. a direct file link → plain parallel HTTP (like an old download site);
 *  2. YouTube audio → direct link from YouTube's API, 4 parallel parts, FFmpeg only adds cover/tags;
 *  3. anything else → the bundled yt-dlp.exe.
 */
class Downloads(
    private val dirProvider: () -> File,
    private val mp3Provider: () -> Boolean,
    private val onFinished: (File, Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks.asStateFlow()
    private val processes = HashMap<String, Process>()

    suspend fun info(url: String): Result<LinkInfo> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            DirectLink.probe(url)?.let { d ->
                return@runCatching LinkInfo(url, d.fileName.substringBeforeLast('.'), "", 0, null, "Прямая ссылка · " + d.ext.uppercase())
            }
            YouTubeFast.details(url)?.let { d ->
                return@runCatching LinkInfo(url, d.title, d.author, d.durationSec, d.thumbnail, "YouTube")
            }
            // Other sites: ask yt-dlp for the title only.
            val p = ytDlpProcess(listOf(Paths.ytDlp, "--no-playlist", "--no-warnings", "--print", "%(title)s\n%(uploader)s\n%(duration)s\n%(thumbnail)s", url)).start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            if (lines.isEmpty()) error("Не удалось открыть ссылку")
            LinkInfo(url, lines[0], lines.getOrNull(1).orEmpty().takeIf { it != "NA" }.orEmpty(), lines.getOrNull(2)?.toDoubleOrNull()?.toInt() ?: 0, lines.getOrNull(3)?.takeIf { it.startsWith("http") }, "Сайт")
        }
    }

    fun enqueue(url: String, title: String, artist: String, thumbnail: String?, video: Boolean) {
        val task = DlTask(UUID.randomUUID().toString(), url, title, artist, thumbnail, video)
        _tasks.value = listOf(task) + _tasks.value
        scope.launch { gate.withPermit { run(task) } }
    }

    fun cancel(id: String) {
        update(id) { it.copy(status = DlStatus.CANCELED) }
        synchronized(processes) { processes.remove(id)?.destroyForcibly() }
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.status == DlStatus.QUEUED || it.status == DlStatus.DOWNLOADING || it.status == DlStatus.PROCESSING }
    }

    private fun canceled(id: String) = _tasks.value.firstOrNull { it.id == id }?.status == DlStatus.CANCELED

    private fun update(id: String, f: (DlTask) -> DlTask) {
        synchronized(this) { _tasks.value = _tasks.value.map { if (it.id == id) f(it) else it } }
    }

    private suspend fun run(task: DlTask) {
        if (canceled(task.id)) return
        val out = dirProvider().apply { mkdirs() }
        val work = File(Paths.cache, "dl/${task.id}").apply { mkdirs() }
        update(task.id) { it.copy(status = DlStatus.DOWNLOADING) }
        try {
            val file = direct(task, out, work) ?: youtubeAudio(task, out, work) ?: ytDlp(task, out)
            if (canceled(task.id)) return
            update(task.id) { it.copy(status = DlStatus.DONE, progress = 1f, file = file.absolutePath, speed = "") }
            onFinished(file, task.video)
        } catch (e: Exception) {
            if (!canceled(task.id)) update(task.id) { it.copy(status = DlStatus.FAILED, error = e.message ?: "Ошибка", speed = "") }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun progress(id: String): (Long, Long, Long) -> Unit = { done, total, bps ->
        update(id) {
            if (it.status == DlStatus.CANCELED) it
            else it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f), speed = speedText(bps))
        }
    }

    private suspend fun direct(task: DlTask, out: File, work: File): File? {
        val d = DirectLink.cached(task.url) ?: DirectLink.probe(task.url) ?: return null
        val target = unique(File(out, d.fileName))
        val tmp = File(work, "part")
        if (!ParallelHttp.download(d.url, DirectLink.headers, d.length, tmp, { canceled(task.id) }, progress(task.id))) {
            // Server without range support: one plain stream.
            java.net.URL(d.url).openStream().use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        }
        tmp.copyTo(target, overwrite = true)
        return target
    }

    private suspend fun youtubeAudio(task: DlTask, out: File, work: File): File? {
        if (task.video || YouTubeFast.videoId(task.url) == null) return null
        val s = runCatching { StreamResolver.resolve(task.url).fast }.getOrNull() ?: return null
        val raw = File(work, "source.${s.ext}")
        if (!ParallelHttp.download(s.url, s.headers, s.contentLength, raw, { canceled(task.id) }, progress(task.id))) return null
        update(task.id) { it.copy(status = DlStatus.PROCESSING, progress = 1f, speed = "") }
        val title = task.title.ifBlank { s.title }
        val artist = task.artist.ifBlank { s.author }
        val mp3 = mp3Provider()
        val cover = File(work, "cover.jpg").takeIf { f ->
            val id = YouTubeFast.videoId(task.url)
            listOfNotNull(
                task.thumbnail?.takeIf { it.contains("googleusercontent") }?.replace(Regex("=w\\d+-h\\d+.*$"), "=w600-h600-l90-rj"),
                "https://i.ytimg.com/vi/$id/maxresdefault.jpg",
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            ).any { ParallelHttp.fetchSmall(it, f) }
        }
        val name = safe(if (artist.isNotBlank() && !title.contains(artist, true)) "$artist - $title" else title)
        val target = unique(File(out, name + if (mp3) ".mp3" else ".m4a"))
        val args = buildList {
            addAll(listOf(Paths.ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", raw.absolutePath))
            if (cover != null) addAll(listOf("-i", cover.absolutePath))
            addAll(listOf("-map", "0:a"))
            if (cover != null) addAll(listOf("-map", "1:v"))
            when {
                mp3 -> addAll(listOf("-c:a", "libmp3lame", "-q:a", "0", "-id3v2_version", "3"))
                s.ext != "m4a" -> addAll(listOf("-c:a", "aac", "-b:a", "192k"))
                else -> addAll(listOf("-c:a", "copy"))
            }
            if (cover != null) {
                // Square the cover (video frames are 16:9).
                if (cover.length() > 0) addAll(listOf("-vf", "crop='min(iw,ih)':'min(iw,ih)'"))
                addAll(listOf("-c:v", "mjpeg", "-disposition:v:0", "attached_pic"))
            }
            addAll(listOf("-metadata", "title=$title"))
            if (artist.isNotBlank()) addAll(listOf("-metadata", "artist=$artist"))
            add(target.absolutePath)
        }
        val code = exec(task.id, args)
        if (code != 0 || !target.isFile) {
            target.delete()
            // Retry without the cover — music matters more than the picture.
            val plain = args.toMutableList().apply {
                if (cover != null) {
                    val i = indexOf(cover.absolutePath); removeAt(i); removeAt(i - 1)
                    val m = indexOf("1:v"); removeAt(m); removeAt(m - 1)
                    val vf = indexOf("-vf"); if (vf >= 0) { removeAt(vf + 1); removeAt(vf) }
                    val cv = indexOf("-c:v"); removeAt(cv + 1); removeAt(cv)
                    val d = indexOf("-disposition:v:0"); removeAt(d + 1); removeAt(d)
                }
            }
            if (exec(task.id, plain) != 0 || !target.isFile) return null
        }
        return target
    }

    private fun ytDlp(task: DlTask, out: File): File {
        if (canceled(task.id)) error("Отменено")
        val template = File(out, "%(title)s.%(ext)s").absolutePath
        val args = buildList {
            addAll(listOf(Paths.ytDlp, "--no-playlist", "--newline", "--no-warnings", "--no-mtime", "--ffmpeg-location", Paths.ffmpeg, "-o", template, "--print", "after_move:filepath"))
            addAll(listOf("--concurrent-fragments", "4", "--embed-metadata", "--embed-thumbnail"))
            if (task.video) {
                addAll(listOf("-f", "bv*[height<=1080]+ba/b", "--merge-output-format", "mp4"))
            } else {
                add("-x")
                if (mp3Provider()) addAll(listOf("--audio-format", "mp3", "--audio-quality", "0"))
                else addAll(listOf("-f", "bestaudio[ext=m4a]/bestaudio/best", "--audio-format", "best"))
            }
            add(task.url)
        }
        val p = ytDlpProcess(args).redirectErrorStream(true).start()
        synchronized(processes) { processes[task.id] = p }
        var result: String? = null
        val tail = ArrayDeque<String>()
        val pct = Regex("""\[download\]\s+([\d.]+)%.*?(?:at\s+(\S+))?""")
        p.inputStream.bufferedReader().forEachLine { line ->
            tail.addLast(line)
            if (tail.size > 6) tail.removeFirst()
            pct.find(line)?.let { m ->
                val v = m.groupValues[1].toFloatOrNull()?.div(100f) ?: return@let
                update(task.id) { it.copy(progress = v, speed = m.groupValues.getOrNull(2)?.replace("MiB/s", " МБ/с")?.replace("KiB/s", " КБ/с").orEmpty()) }
            }
            if (line.contains("[ExtractAudio]") || line.contains("[Merger]") || line.contains("[EmbedThumbnail]")) {
                update(task.id) { it.copy(status = DlStatus.PROCESSING, speed = "") }
            }
            if (File(line.trim()).isAbsolute && File(line.trim()).isFile) result = line.trim()
        }
        val code = p.waitFor()
        synchronized(processes) { processes.remove(task.id) }
        if (canceled(task.id)) error("Отменено")
        val file = result?.let { File(it) }
        if (code != 0 || file == null || !file.isFile) {
            error(tail.lastOrNull { it.contains("ERROR") }?.substringAfter("ERROR:")?.trim() ?: "Не удалось скачать")
        }
        return file
    }

    private fun exec(id: String, args: List<String>): Int {
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        synchronized(processes) { processes[id] = p }
        p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        synchronized(processes) { processes.remove(id) }
        return code
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        var i = 2
        while (true) {
            val c = File(f.parentFile, "${f.nameWithoutExtension} ($i).${f.extension}")
            if (!c.exists()) return c
            i++
        }
    }

    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(150).ifBlank { "track" }

    private fun speedText(bps: Long): String = when {
        bps <= 0 -> ""
        bps > 1024 * 1024 -> "%.1f МБ/с".format(bps / 1048576.0)
        else -> "${bps / 1024} КБ/с"
    }
}
