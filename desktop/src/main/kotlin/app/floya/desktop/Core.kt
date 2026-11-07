package app.floya.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where Floya keeps its data and finds its helper programs. */
object Paths {
    val data: File = File(System.getenv("APPDATA") ?: (System.getProperty("user.home") + "/.config"), "Floya").apply { mkdirs() }
    val cache: File = File(data, "cache").apply { mkdirs() }
    val music: File = File(System.getProperty("user.home"), "Music")
    private val resources: File? = System.getProperty("compose.application.resources.dir")?.let { File(it) }

    private fun tool(name: String): String {
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name
        resources?.let { File(it, exe) }?.takeIf { it.isFile }?.let { return it.absolutePath }
        File(System.getProperty("user.dir"), exe).takeIf { it.isFile }?.let { return it.absolutePath }
        return name // from PATH
    }

    val ffmpeg: String by lazy { tool("ffmpeg") }
    val ytDlp: String by lazy { tool("yt-dlp") }
}

/** One playable thing: a file on disk or an internet track (YouTube etc.). */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val path: String? = null,
    val url: String? = null,
    val thumbnail: String? = null,
    val addedAt: Long = 0,
) {
    val isOnline: Boolean get() = path == null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("artist", artist).put("album", album)
        .put("durationMs", durationMs).put("path", path ?: JSONObject.NULL).put("url", url ?: JSONObject.NULL)
        .put("thumbnail", thumbnail ?: JSONObject.NULL).put("addedAt", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            id = o.getString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("durationMs"),
            path = o.optString("path").takeIf { o.has("path") && !o.isNull("path") },
            url = o.optString("url").takeIf { o.has("url") && !o.isNull("url") },
            thumbnail = o.optString("thumbnail").takeIf { o.has("thumbnail") && !o.isNull("thumbnail") },
            addedAt = o.optLong("addedAt"),
        )
    }
}

data class Playlist(val id: Long, val name: String, val tracks: List<Track>, val createdAt: Long)

data class Settings(
    val themeId: String = "gothic",
    val volume: Float = 0.8f,
    /** Extra loudness: 1.0 = off, up to 2.8 (≈ +15 dB) like on the phone. */
    val boost: Float = 1f,
    val folders: List<String> = listOf(Paths.music.absolutePath),
    val downloadDir: String = File(Paths.music, "Floya").absolutePath,
    val ambience: Boolean = true,
    val mp3: Boolean = false,
    val skins: Map<Long, String> = emptyMap(),
    val shuffle: Boolean = false,
    val repeat: Int = 0,
)

/** Small JSON files in %APPDATA%\Floya — settings, playlists, listening history. */
object Store {
    private val settingsFile = File(Paths.data, "settings.json")
    private val playlistsFile = File(Paths.data, "playlists.json")
    private val historyFile = File(Paths.data, "history.json")

    fun loadSettings(): Settings = runCatching {
        val o = JSONObject(settingsFile.readText())
        val d = Settings()
        Settings(
            themeId = o.optString("themeId", d.themeId),
            volume = o.optDouble("volume", d.volume.toDouble()).toFloat(),
            boost = o.optDouble("boost", 1.0).toFloat(),
            folders = o.optJSONArray("folders")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: d.folders,
            downloadDir = o.optString("downloadDir", d.downloadDir),
            ambience = o.optBoolean("ambience", true),
            mp3 = o.optBoolean("mp3", false),
            skins = o.optJSONObject("skins")?.let { s -> s.keySet().associate { it.toLong() to s.getString(it) } } ?: emptyMap(),
            shuffle = o.optBoolean("shuffle", false),
            repeat = o.optInt("repeat", 0),
        )
    }.getOrDefault(Settings())

    fun saveSettings(s: Settings) {
        val o = JSONObject()
            .put("themeId", s.themeId).put("volume", s.volume.toDouble()).put("boost", s.boost.toDouble())
            .put("folders", JSONArray(s.folders)).put("downloadDir", s.downloadDir)
            .put("ambience", s.ambience).put("mp3", s.mp3)
            .put("skins", JSONObject().apply { s.skins.forEach { (k, v) -> put(k.toString(), v) } })
            .put("shuffle", s.shuffle).put("repeat", s.repeat)
        write(settingsFile, o.toString(2))
    }

    fun loadPlaylists(): List<Playlist> = runCatching {
        val a = JSONArray(playlistsFile.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val t = o.getJSONArray("tracks")
            Playlist(o.getLong("id"), o.getString("name"), (0 until t.length()).map { Track.fromJson(t.getJSONObject(it)) }, o.optLong("createdAt"))
        }
    }.getOrDefault(emptyList())

    fun savePlaylists(list: List<Playlist>) {
        val a = JSONArray()
        list.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("createdAt", p.createdAt).put("tracks", JSONArray().apply { p.tracks.forEach { put(it.toJson()) } }))
        }
        write(playlistsFile, a.toString())
    }

    /** Recently played (newest first) and play counts. */
    fun loadHistory(): Pair<List<Track>, Map<String, Int>> = runCatching {
        val o = JSONObject(historyFile.readText())
        val r = o.getJSONArray("recent")
        val c = o.getJSONObject("counts")
        (0 until r.length()).map { Track.fromJson(r.getJSONObject(it)) } to c.keySet().associateWith { c.getInt(it) }
    }.getOrDefault(emptyList<Track>() to emptyMap())

    fun saveHistory(recent: List<Track>, counts: Map<String, Int>) {
        val o = JSONObject()
            .put("recent", JSONArray().apply { recent.forEach { put(it.toJson()) } })
            .put("counts", JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } })
        write(historyFile, o.toString())
    }

    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
