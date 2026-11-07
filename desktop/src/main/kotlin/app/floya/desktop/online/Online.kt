package app.floya.desktop.online

import app.floya.desktop.Paths
import app.floya.desktop.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class SearchSource { YOUTUBE }

data class SearchResult(
    val url: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val thumbnail: String?,
    val source: SearchSource,
) {
    fun toTrack(): Track {
        val parts = author.split(" · ")
        return Track(
            id = url,
            title = title,
            artist = parts.getOrNull(0).orEmpty(),
            album = parts.getOrNull(1).orEmpty(),
            durationMs = durationSec * 1000L,
            url = url,
            thumbnail = thumbnail,
        )
    }
}

/** Internet search: YouTube Music songs and YouTube videos through their own web API (no yt-dlp). */
object WebSearch {
    suspend fun songs(query: String): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Result.success(emptyList())
        runCatching { ytMusic(q, SONGS_PARAMS) }.mapCatching { if (it.isEmpty()) ytVideos("$q audio") else it }
    }

    suspend fun videos(query: String): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext Result.success(emptyList())
        runCatching { ytVideos(q) }
    }

    private fun ytVideos(query: String): List<SearchResult> {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB")
                        .put("clientVersion", WEB_CLIENT_VERSION)
                        .put("hl", "ru")
                        .put("gl", "RU"),
                ),
            )
            .put("query", query)
            .put("params", VIDEOS_PARAMS)
        val conn = (URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://www.youtube.com")
            setRequestProperty("X-Youtube-Client-Name", "1")
            setRequestProperty("X-Youtube-Client-Version", WEB_CLIENT_VERSION)
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        }
        val text = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return emptyList()
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val renderers = ArrayList<JSONObject>()
        collect(JSONObject(text), "videoRenderer", renderers)
        return renderers.mapNotNull { r ->
            val id = r.optString("videoId").takeIf { it.length == 11 } ?: return@mapNotNull null
            fun runsText(o: JSONObject?): String {
                if (o == null) return ""
                o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
                val runs = o.optJSONArray("runs") ?: return ""
                return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }
            val title = runsText(r.optJSONObject("title")).ifBlank { return@mapNotNull null }
            val length = runsText(r.optJSONObject("lengthText"))
            if (length.isBlank()) return@mapNotNull null // live streams and upcoming premieres
            val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            SearchResult(
                url = "https://www.youtube.com/watch?v=$id",
                title = title,
                author = runsText(r.optJSONObject("ownerText")).ifBlank { runsText(r.optJSONObject("longBylineText")) },
                durationSec = toSeconds(length),
                thumbnail = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")?.substringBefore('?')
                    ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                source = SearchSource.YOUTUBE,
            )
        }.distinctBy { it.url }
    }

    private fun ytMusic(query: String, params: String): List<SearchResult> {
        val body = JSONObject()
            .put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "ru")
                        .put("gl", "RU"),
                ),
            )
            .put("query", query)
            .put("params", params)
        val conn = (URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("X-Youtube-Client-Name", "67")
            setRequestProperty("X-Youtube-Client-Version", CLIENT_VERSION)
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
        }
        val text = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return emptyList()
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val renderers = ArrayList<JSONObject>()
        collect(JSONObject(text), "musicResponsiveListItemRenderer", renderers)
        return renderers.mapNotNull { parseItem(it) }.distinctBy { it.url }
    }

    private fun parseItem(r: JSONObject): SearchResult? {
        val videoId = r.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: findString(r, "videoId")
            ?: return null
        val flex = r.optJSONArray("flexColumns") ?: return null
        fun columnText(i: Int): String {
            val runs = flex.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
            return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }
        val title = columnText(0).ifBlank { return null }
        val parts = columnText(1).split(" • ", " · ").map { it.trim() }.filter { it.isNotBlank() }
        val typeWords = setOf("песня", "song", "видео", "video", "трек", "track", "сингл", "single", "клип")
        val durationRegex = Regex("""^\d{1,2}(:\d{2}){1,2}$""")
        val duration = parts.lastOrNull { durationRegex.matches(it) }
        val rest = parts.filter { it.lowercase() !in typeWords && it != duration && !it.contains("просмотр") && !it.contains("views") }
        val artist = rest.getOrNull(0).orEmpty()
        val album = rest.getOrNull(1).orEmpty()
        val thumbs = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return SearchResult(
            url = "https://music.youtube.com/watch?v=$videoId",
            title = title,
            author = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · "),
            durationSec = duration?.let { toSeconds(it) } ?: 0,
            thumbnail = thumb,
            source = SearchSource.YOUTUBE,
        )
    }

    private fun toSeconds(s: String): Int = s.split(":").fold(0) { acc, p -> acc * 60 + (p.toIntOrNull() ?: 0) }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out += it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k != key) collect(node.opt(k), key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun findString(node: Any?, key: String): String? {
        when (node) {
            is JSONObject -> {
                node.optString(key).takeIf { it.isNotBlank() && node.opt(key) is String }?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) findString(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findString(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private const val CLIENT_VERSION = "1.20241023.01.00"
    private const val WEB_CLIENT_VERSION = "2.20250101.01.00"
    private const val VIDEOS_PARAMS = "EgIQAQ=="
    private const val SONGS_PARAMS = "EgWKAQIIAWoKEAoQAxAEEAkQBQ=="
}

/**
 * Turns a YouTube page into a playable stream: YouTube's player API first (one request),
 * yt-dlp.exe as a fallback. Results are cached until shortly before the link expires.
 */
object StreamResolver {
    data class Resolved(val url: String, val headers: Map<String, String>, val expiresAt: Long, val fast: YouTubeFast.Stream?)

    private val cache = ConcurrentHashMap<String, Resolved>()

    fun cached(page: String): Resolved? = cache[page]?.takeIf { System.currentTimeMillis() < it.expiresAt }

    fun resolve(page: String): Resolved {
        cached(page)?.let { return it }
        YouTubeFast.audio(page)?.let { s ->
            return Resolved(s.url, s.headers, s.expiresAt, s).also { cache[page] = it }
        }
        val url = YtDlp.directUrl(page) ?: throw java.io.IOException("Не удалось получить поток")
        return Resolved(url, emptyMap(), System.currentTimeMillis() + 60 * 60 * 1000L, null).also { cache[page] = it }
    }
}

/** The bundled yt-dlp.exe — for sites and cases the fast paths don't cover. */
object YtDlp {
    fun directUrl(page: String): String? = runCatching {
        val p = ytDlpProcess(listOf(
            Paths.ytDlp, "--no-playlist", "--no-warnings", "-f", "bestaudio[ext=m4a]/bestaudio/best", "-g", page,
        )).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        out.lines().firstOrNull { it.startsWith("http") }
    }.getOrNull()
}

/** yt-dlp with UTF-8 output, so Cyrillic titles and paths come through intact on Windows. */
fun ytDlpProcess(args: List<String>): ProcessBuilder = ProcessBuilder(args).apply {
    environment()["PYTHONIOENCODING"] = "utf-8"
    environment()["PYTHONUTF8"] = "1"
}
