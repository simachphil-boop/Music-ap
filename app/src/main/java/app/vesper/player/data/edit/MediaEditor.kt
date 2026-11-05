package app.vesper.player.data.edit

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import app.vesper.player.data.LibraryRepository
import app.vesper.player.data.media.ProbeResult
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Frame shapes offered for video cropping. */
enum class CropAspect(val label: String, val w: Int, val h: Int) {
    SQUARE("1:1", 1, 1),
    VERTICAL("9:16", 9, 16),
    PORTRAIT("4:5", 4, 5),
    WIDE("16:9", 16, 9),
    CLASSIC("4:3", 4, 3),
    CINEMA("21:9", 21, 9),
    ;

    val ratio: Float get() = w.toFloat() / h
}

/**
 * Trim (start/end in ms; endMs = 0 means "to the end") and optional crop.
 * [offset] positions the crop window along the free axis: 0 = left/top, 1 = right/bottom.
 */
data class EditSpec(
    val startMs: Long = 0,
    val endMs: Long = 0,
    val crop: CropAspect? = null,
    val offset: Float = 0.5f,
) {
    val trims: Boolean get() = startMs > 0 || endMs > 0
    val isNoop: Boolean get() = !trims && crop == null
}

/** Runs the FFmpeg binary bundled with youtubedl-android. */
object FfmpegTool {
    private val lock = Mutex()
    @Volatile private var ready = false

    /** Human-readable reason of the last failed start, shown to the user. */
    @Volatile var lastError: String? = null
        private set

    suspend fun init(context: Context): Boolean = lock.withLock {
        if (ready) return@withLock true
        withContext(Dispatchers.IO) {
            runCatching {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
                lastError = null
            }.onFailure { e ->
                var root: Throwable = e
                while (root.cause != null && root.cause !== root) root = root.cause!!
                lastError = "${root.javaClass.simpleName}: ${root.message ?: e.message ?: "без описания"}".take(200)
            }.isSuccess
        }
    }

    /** Returns exit code and the tail of the log. Kills the process if the coroutine is cancelled. */
    suspend fun run(context: Context, args: List<String>, totalMs: Long, onProgress: (Float) -> Unit): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] =
                File(packages, "ffmpeg/usr/lib").absolutePath + ":" + File(packages, "python/usr/lib").absolutePath
            val process = pb.start()
            val handle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) process.destroy() }
            val tail = ArrayDeque<String>()
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val us = when {
                        line.startsWith("out_time_us=") -> line.substringAfter('=').toLongOrNull()
                        line.startsWith("out_time_ms=") -> line.substringAfter('=').toLongOrNull()
                        else -> null
                    }
                    if (us != null && totalMs > 0) onProgress((us / 1000f / totalMs).coerceIn(0f, 1f))
                    if (!line.contains('=') || line.contains("rror")) {
                        tail.addLast(line)
                        if (tail.size > 12) tail.removeFirst()
                    }
                }
                process.waitFor() to tail.joinToString("\n")
            } finally {
                handle?.dispose()
            }
        }

    fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}

/** Saves a finished file into shared storage (Movies/Floya or Music/Floya). */
object MediaPublisher {
    fun publish(context: Context, file: File, audio: Boolean): Uri {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (audio) "audio/mpeg" else "video/mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = if (audio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Floya")
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
            // Same URI form the library scanner produces, so a rescan doesn't duplicate it.
            return ContentUris.withAppendedId(
                if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentUris.parseId(uri),
            )
        }
        val dirType = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val dir = File(ContextCompat.getExternalFilesDirs(context, dirType).firstOrNull() ?: context.filesDir, "Floya").apply { mkdirs() }
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) target = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        file.copyTo(target)
        return Uri.fromFile(target)
    }
}

enum class RingtoneKind(val title: String, val folder: String, val manager: Int, val column: String) {
    CALL("Звонок", Environment.DIRECTORY_RINGTONES, android.media.RingtoneManager.TYPE_RINGTONE, MediaStore.Audio.AudioColumns.IS_RINGTONE),
    NOTIFICATION("Уведомления", Environment.DIRECTORY_NOTIFICATIONS, android.media.RingtoneManager.TYPE_NOTIFICATION, MediaStore.Audio.AudioColumns.IS_NOTIFICATION),
    ALARM("Будильник", Environment.DIRECTORY_ALARMS, android.media.RingtoneManager.TYPE_ALARM, MediaStore.Audio.AudioColumns.IS_ALARM),
}

/** Puts a sound into the system ringtone folders and makes it the default. */
object RingtonePublisher {
    fun canWriteSettings(context: Context): Boolean = android.provider.Settings.System.canWrite(context)

    suspend fun publishAndSet(context: Context, file: File, kind: RingtoneKind): Uri = withContext(Dispatchers.IO) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, kind.folder + "/Floya")
                put(kind.column, true)
                put(MediaStore.Audio.AudioColumns.IS_MUSIC, false)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val u = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("Не удалось сохранить рингтон")
            resolver.openOutputStream(u)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Не удалось сохранить рингтон")
            resolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            u
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(kind.folder), "Floya").apply { mkdirs() }
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("audio/mpeg")) { _, scanned ->
                    if (scanned != null) cont.resumeWith(Result.success(scanned)) else cont.resumeWith(Result.failure(IllegalStateException("Файл не проиндексирован")))
                }
            }
        }
        android.media.RingtoneManager.setActualDefaultRingtoneUri(context, kind.manager, uri)
        uri
    }
}

data class EditJob(
    val title: String,
    val progress: Float = 0f,
    val running: Boolean = true,
    val stage: String = "Подготовка…",
    val error: String? = null,
    val resultId: Long? = null,
    val audio: Boolean = false,
)

/** Trims and crops library items into new files; the original file is never modified. */
class MediaEditor(
    private val context: Context,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val _job = MutableStateFlow<EditJob?>(null)
    val job: StateFlow<EditJob?> = _job.asStateFlow()
    private var running: Job? = null

    fun start(sourceUri: String, audio: Boolean, title: String, durationMs: Long, spec: EditSpec) {
        if (running?.isActive == true) return
        _job.value = EditJob(title = title, audio = audio)
        running = scope.launch {
            val work = File(context.cacheDir, "edit").apply { deleteRecursively(); mkdirs() }
            try {
                if (!FfmpegTool.init(context)) error("Не удалось запустить обработчик (${FfmpegTool.lastError})")
                _job.update { it?.copy(stage = "Читаю файл…") }
                val input = copyToCache(Uri.parse(sourceUri), work)
                _job.update { it?.copy(stage = "Обработка…") }
                val output = File(work, safeName(title) + (if (audio) " (фрагмент)" else " (обрезка)") + if (audio) ".mp3" else ".mp4")
                render(context, input, output, audio, spec, durationMs) { p -> _job.update { it?.copy(progress = p) } }
                _job.update { it?.copy(stage = "Сохраняю…", progress = 1f) }
                val uri = MediaPublisher.publish(context, output, audio)
                val id = when (val r = library.importSingle(uri)) {
                    is ProbeResult.Audio -> r.track.id
                    is ProbeResult.Video -> r.video.id
                    is ProbeResult.Unsupported -> null
                }
                _job.update { it?.copy(running = false, stage = "Готово", resultId = id) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _job.value = null
                throw e
            } catch (e: Exception) {
                _job.update { it?.copy(running = false, error = e.message ?: "Не удалось обработать файл") }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    /** Saves a library track with a Studio effect (slowed, nightcore, 8D…) as a new MP3. */
    fun startEffectExport(sourceUri: String, title: String, durationMs: Long, filter: String, label: String, speed: Float) {
        if (running?.isActive == true) return
        _job.value = EditJob(title = "$title ($label)", audio = true)
        running = scope.launch {
            val work = File(context.cacheDir, "edit").apply { deleteRecursively(); mkdirs() }
            try {
                if (!FfmpegTool.init(context)) error("Не удалось запустить обработчик (${FfmpegTool.lastError})")
                _job.update { it?.copy(stage = "Читаю файл…") }
                val input = copyToCache(Uri.parse(sourceUri), work)
                _job.update { it?.copy(stage = "Накладываю эффект…") }
                val output = File(work, safeName("$title ($label)") + ".mp3")
                val total = (durationMs / speed.coerceAtLeast(0.1f)).toLong()
                val variants = listOf(
                    listOf("-map", "0:a:0", "-map", "0:v:0?", "-af", filter, "-c:a", "libmp3lame", "-q:a", "2", "-c:v", "copy", "-id3v2_version", "3", "-map_metadata", "0"),
                    listOf("-map", "0:a:0", "-af", filter, "-c:a", "libmp3lame", "-q:a", "2", "-map_metadata", "0"),
                )
                var ok = false
                var log = ""
                for (v in variants) {
                    output.delete()
                    val args = listOf("-y", "-hide_banner", "-nostats", "-progress", "pipe:1", "-i", input.absolutePath) + v + output.absolutePath
                    val (code, tail) = FfmpegTool.run(context, args, total) { p -> _job.update { it?.copy(progress = p) } }
                    if (code == 0 && output.length() > 0) { ok = true; break }
                    log = tail
                }
                if (!ok) error("Не удалось наложить эффект" + log.lines().lastOrNull { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty())
                _job.update { it?.copy(stage = "Сохраняю…", progress = 1f) }
                val uri = MediaPublisher.publish(context, output, true)
                val id = when (val r = library.importSingle(uri)) {
                    is ProbeResult.Audio -> r.track.id.also { library.markDownloaded(it) }
                    else -> null
                }
                _job.update { it?.copy(running = false, stage = "Готово", resultId = id) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _job.value = null
                throw e
            } catch (e: Exception) {
                _job.update { it?.copy(running = false, error = e.message ?: "Не удалось обработать файл") }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    /** Cuts a fragment and sets it as the phone's ringtone / notification / alarm sound. */
    fun startRingtone(sourceUri: String, title: String, durationMs: Long, spec: EditSpec, kind: RingtoneKind) {
        if (running?.isActive == true) return
        _job.value = EditJob(title = "$title — ${kind.title.lowercase()}", audio = true)
        running = scope.launch {
            val work = File(context.cacheDir, "edit").apply { deleteRecursively(); mkdirs() }
            try {
                if (!FfmpegTool.init(context)) error("Не удалось запустить обработчик (${FfmpegTool.lastError})")
                val input = copyToCache(Uri.parse(sourceUri), work)
                _job.update { it?.copy(stage = "Вырезаю фрагмент…") }
                val output = File(work, safeName(title) + " (рингтон).mp3")
                render(context, input, output, true, spec.copy(crop = null), durationMs) { p -> _job.update { it?.copy(progress = p) } }
                _job.update { it?.copy(stage = "Устанавливаю…", progress = 1f) }
                RingtonePublisher.publishAndSet(context, output, kind)
                _job.update { it?.copy(running = false, stage = "Готово", resultId = -1L) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _job.value = null
                throw e
            } catch (e: Exception) {
                _job.update { it?.copy(running = false, error = e.message ?: "Не удалось установить рингтон") }
            } finally {
                work.deleteRecursively()
            }
        }
    }

    fun cancel() {
        running?.cancel()
        _job.value = null
    }

    fun dismiss() {
        if (running?.isActive != true) _job.value = null
    }

    private suspend fun copyToCache(uri: Uri, dir: File): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(uri.path!!)
        var name = "source"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        val ext = name.substringAfterLast('.', "").ifBlank { "bin" }
        val target = File(dir, "source.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: error("Файл недоступен")
        target
    }

    companion object {
        fun safeName(title: String): String =
            title.replace(Regex("""[\\/:*?"<>|\n\r]"""), " ").trim().take(80).ifBlank { "Floya" }

        /**
         * Renders [spec] from [input] to [output] with FFmpeg, trying encoders the device build
         * provides. Trim-only video uses stream copy (fast, cuts on the nearest keyframe).
         */
        suspend fun render(
            context: Context,
            input: File,
            output: File,
            audio: Boolean,
            spec: EditSpec,
            sourceDurationMs: Long,
            onProgress: (Float) -> Unit,
        ) {
            val end = if (spec.endMs > 0) spec.endMs else sourceDurationMs
            val total = (end - spec.startMs).coerceAtLeast(1)
            val attempts: List<List<String>> = if (audio) {
                listOf(
                    listOf("-map", "0:a:0", "-map", "0:v:0?", "-c:a", "libmp3lame", "-q:a", "2", "-c:v", "copy", "-id3v2_version", "3", "-map_metadata", "0"),
                    listOf("-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2", "-map_metadata", "0"),
                )
            } else if (spec.crop == null) {
                listOf(
                    listOf("-map", "0:v:0", "-map", "0:a:0?", "-c", "copy", "-movflags", "+faststart"),
                    listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
                )
            } else {
                val r = "${spec.crop.w}/${spec.crop.h}"
                val o = String.format(Locale.US, "%.3f", spec.offset.coerceIn(0f, 1f))
                val vf = "crop=w='min(iw,ih*$r)':h='min(ih,iw/($r))':x='(iw-ow)*$o':y='(ih-oh)*$o',scale='trunc(iw/2)*2':'trunc(ih/2)*2'"
                listOf(
                    listOf("-map", "0:v:0", "-map", "0:a:0?", "-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
                    listOf("-map", "0:v:0", "-map", "0:a:0?", "-vf", vf, "-c:v", "mpeg4", "-q:v", "3", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart"),
                )
            }
            var lastLog = ""
            for (codec in attempts) {
                val args = buildList {
                    addAll(listOf("-y", "-hide_banner", "-nostats", "-progress", "pipe:1"))
                    if (spec.startMs > 0) addAll(listOf("-ss", FfmpegTool.seconds(spec.startMs)))
                    if (spec.endMs > 0) addAll(listOf("-to", FfmpegTool.seconds(spec.endMs)))
                    addAll(listOf("-i", input.absolutePath))
                    addAll(codec)
                    add(output.absolutePath)
                }
                output.delete()
                val (code, log) = FfmpegTool.run(context, args, total, onProgress)
                if (code == 0 && output.exists() && output.length() > 0) return
                lastLog = log
            }
            error("Не удалось обработать файл" + lastLog.lines().lastOrNull { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty())
        }
    }
}
