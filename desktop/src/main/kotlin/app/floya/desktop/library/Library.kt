package app.floya.desktop.library

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.floya.desktop.Paths
import app.floya.desktop.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Music on this computer: walks the chosen folders, reads tags (title, artist, album, length)
 * and remembers them in a cache file so the next start is instant.
 */
class Library {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val cacheFile = File(Paths.data, "library.json")
    /** path → (lastModified, track) */
    private var known = HashMap<String, Pair<Long, Track>>()

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        loadCache()
    }

    private fun loadCache() {
        runCatching {
            val a = JSONArray(cacheFile.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val t = Track.fromJson(o.getJSONObject("t"))
                known[t.path ?: continue] = o.getLong("m") to t
            }
            _tracks.value = known.values.map { it.second }.sortedByDescending { it.addedAt }
        }
    }

    private fun saveCache() {
        runCatching {
            val a = JSONArray()
            known.values.forEach { (m, t) -> a.put(JSONObject().put("m", m).put("t", t.toJson())) }
            cacheFile.writeText(a.toString())
        }
    }

    suspend fun scan(folders: List<String>) = withContext(Dispatchers.IO) {
        if (_scanning.value) return@withContext
        _scanning.value = true
        try {
            val files = folders.map { File(it) }.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }.toList()
            }.distinctBy { it.absolutePath }
            val next = HashMap<String, Pair<Long, Track>>()
            var changed = false
            var sinceUpdate = 0
            for (f in files) {
                val path = f.absolutePath
                val m = f.lastModified()
                val old = known[path]
                if (old != null && old.first == m) {
                    next[path] = old
                    continue
                }
                next[path] = m to read(f)
                changed = true
                if (++sinceUpdate >= 40) {
                    sinceUpdate = 0
                    _tracks.value = (known + next).values.map { it.second }.sortedByDescending { it.addedAt }
                }
            }
            if (next.size != known.size) changed = true
            known = next
            _tracks.value = known.values.map { it.second }.sortedByDescending { it.addedAt }
            if (changed) saveCache()
        } finally {
            _scanning.value = false
        }
    }

    /** Adds one new file right away (after a download). */
    fun add(file: File) {
        val t = read(file)
        known[file.absolutePath] = file.lastModified() to t
        _tracks.value = listOf(t) + _tracks.value.filter { it.id != t.id }
        saveCache()
    }

    private fun read(f: File): Track {
        val base = Track(
            id = f.absolutePath,
            title = f.nameWithoutExtension,
            artist = "Неизвестный исполнитель",
            path = f.absolutePath,
            addedAt = f.lastModified(),
        )
        return runCatching {
            val af = AudioFileIO.read(f)
            val tag = af.tag
            fun field(k: FieldKey) = runCatching { tag?.getFirst(k) }.getOrNull()?.trim().orEmpty()
            val title = field(FieldKey.TITLE)
            val artist = field(FieldKey.ARTIST).ifBlank { field(FieldKey.ALBUM_ARTIST) }
            base.copy(
                title = title.ifBlank { guessTitle(f.nameWithoutExtension) },
                artist = artist.ifBlank { guessArtist(f.nameWithoutExtension) ?: base.artist },
                album = field(FieldKey.ALBUM),
                durationMs = af.audioHeader.trackLength * 1000L,
            )
        }.getOrElse {
            base.copy(title = guessTitle(f.nameWithoutExtension), artist = guessArtist(f.nameWithoutExtension) ?: base.artist)
        }
    }

    /** "Artist - Title" file names are common; use them when tags are missing. */
    private fun guessArtist(name: String) = name.split(" - ", " – ", " — ").takeIf { it.size >= 2 }?.first()?.trim()
    private fun guessTitle(name: String) = name.split(" - ", " – ", " — ").let { if (it.size >= 2) it.drop(1).joinToString(" - ").trim() else name }

    companion object {
        val EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "alac", "mp4")
    }
}

/** Covers: embedded pictures of local files and thumbnails of internet tracks, cached in memory and on disk. */
object Artwork {
    private val memory = object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 300
    }
    private val pool = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val diskDir = File(Paths.cache, "art").apply { mkdirs() }

    fun peek(track: Track): ImageBitmap? = synchronized(memory) { memory[track.id] }

    suspend fun load(track: Track): ImageBitmap? {
        synchronized(memory) { if (memory.containsKey(track.id)) return memory[track.id] }
        val img = withContext(pool) {
            runCatching {
                val bytes = if (track.path != null) {
                    AudioFileIO.read(File(track.path)).tag?.firstArtwork?.binaryData
                        ?: File(track.path).parentFile?.listFiles()?.firstOrNull {
                            it.name.lowercase() in setOf("cover.jpg", "folder.jpg", "cover.png", "folder.png", "front.jpg")
                        }?.readBytes()
                } else {
                    track.thumbnail?.let { remote(it) }
                }
                bytes?.let { decode(it) }
            }.getOrNull()
        }
        synchronized(memory) { memory[track.id] = img }
        return img
    }

    private fun remote(url: String): ByteArray? {
        val key = Integer.toHexString(url.hashCode()) + "_" + url.length
        val f = File(diskDir, key)
        if (f.isFile && f.length() > 0) return f.readBytes()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return try {
            if (conn.responseCode != 200) null else conn.inputStream.readBytes().also { runCatching { f.writeBytes(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap {
        val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        // Keep memory low: covers are shown at most ~600 px.
        val max = 600
        if (img.width <= max && img.height <= max) return img.toComposeImageBitmap()
        val k = max.toFloat() / maxOf(img.width, img.height)
        val w = (img.width * k).toInt().coerceAtLeast(1)
        val h = (img.height * k).toInt().coerceAtLeast(1)
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }
}
