package app.vesper.player.data.transfer

import app.vesper.player.data.db.OnlinePlaylistEntity
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.data.download.Downloader
import app.vesper.player.playback.OnlineItem
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TransferredPlaylist(
    val name: String,
    val source: String,
    val sourceUrl: String,
    val coverUrl: String?,
    val items: List<OnlineItem>,
)

class TransferException(message: String) : Exception(message)

/**
 * Brings playlists over from Spotify, Yandex Music, Deezer, YouTube and SoundCloud.
 * Catalogue services only give "artist — title"; those tracks are found on YouTube
 * when played or downloaded (via a `ytsearch1:` address).
 */
class PlaylistTransfer(
    private val db: VesperDatabase,
    private val downloader: Downloader,
) {
    private val dao = db.onlinePlaylists()

    val playlists: Flow<List<OnlinePlaylistEntity>> = dao.observeAll()
    fun playlist(id: Long): Flow<OnlinePlaylistEntity?> = dao.observe(id)

    suspend fun fetch(rawUrl: String): TransferredPlaylist = withContext(Dispatchers.IO) {
        val url = Downloader.extractUrl(rawUrl) ?: throw TransferException("Вставьте ссылку на плейлист или альбом")
        val u = url.lowercase()
        val result = try {
            when {
                "spotify.com" in u -> spotify(url)
                "music.yandex" in u -> yandex(url)
                "deezer.com" in u || "deezer.page.link" in u -> deezer(url)
                "vk.com" in u || "vk.ru" in u -> throw TransferException("VK не открывает плейлисты сторонним приложениям — перенос из VK невозможен")
                else -> ytdlp(url)
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferException("Не удалось прочитать плейлист: ${e.message?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(140) ?: "неизвестная ошибка"}")
        }
        if (result.items.isEmpty()) throw TransferException("В плейлисте не нашлось треков (возможно, он закрыт)")
        result
    }

    suspend fun save(p: TransferredPlaylist): Long = dao.insert(
        OnlinePlaylistEntity(
            name = p.name,
            source = p.source,
            sourceUrl = p.sourceUrl,
            coverUrl = p.coverUrl,
            itemsJson = encode(p.items),
            itemCount = p.items.size,
            createdAt = System.currentTimeMillis(),
        ),
    )

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    // ---------------------------------------------------------------------------------------------
    // Spotify — public embed page carries the track list as JSON (__NEXT_DATA__).
    // ---------------------------------------------------------------------------------------------

    private fun spotify(url: String): TransferredPlaylist {
        val m = Regex("""spotify\.com/(?:intl-[a-z]+/)?(playlist|album)/([A-Za-z0-9]+)""").find(url)
            ?: throw TransferException("Нужна ссылка на плейлист или альбом Spotify")
        val (kind, id) = m.destructured
        val html = http("https://open.spotify.com/embed/$kind/$id")
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw TransferException("Spotify не отдал список треков")
        val root = JSONObject(json)
        val entity = findObjectWithArray(root, "trackList") ?: throw TransferException("Плейлист Spotify пуст или закрыт")
        val list = entity.getJSONArray("trackList")
        val items = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title")
            val artist = t.optString("subtitle")
            if (title.isBlank()) null else searchItem(artist, title, (t.optLong("duration") / 1000).toInt(), null)
        }
        val name = entity.optString("name").ifBlank { entity.optString("title") }.ifBlank { "Плейлист Spotify" }
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        return TransferredPlaylist(name, "Spotify", url, cover, items)
    }

    private fun findObjectWithArray(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.optJSONArray(key) != null) return node
                val names = node.keys()
                while (names.hasNext()) findObjectWithArray(node.opt(names.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findObjectWithArray(node.opt(i), key)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Yandex Music — public API for public playlists and albums.
    // ---------------------------------------------------------------------------------------------

    private fun yandex(url: String): TransferredPlaylist {
        val user = Regex("""users/([^/]+)/playlists/(\d+)""").find(url)
        val album = Regex("""album/(\d+)""").find(url)
        val uuid = Regex("""playlists/([0-9a-fA-F-]{20,})""").find(url)
        val api = "https://api.music.yandex.net"
        return when {
            user != null -> {
                val (owner, kind) = user.destructured
                val r = JSONObject(http("$api/users/$owner/playlists/$kind?rich-tracks=true")).getJSONObject("result")
                yandexPlaylist(r, url)
            }
            uuid != null -> {
                val r = JSONObject(http("$api/playlist/${uuid.groupValues[1]}?rich-tracks=true")).getJSONObject("result")
                yandexPlaylist(r, url)
            }
            album != null -> {
                val r = JSONObject(http("$api/albums/${album.groupValues[1]}/with-tracks")).getJSONObject("result")
                val tracks = ArrayList<JSONObject>()
                val volumes = r.optJSONArray("volumes") ?: JSONArray()
                for (v in 0 until volumes.length()) {
                    val vol = volumes.optJSONArray(v) ?: continue
                    for (i in 0 until vol.length()) vol.optJSONObject(i)?.let { tracks += it }
                }
                TransferredPlaylist(
                    name = r.optString("title").ifBlank { "Альбом" },
                    source = "Яндекс Музыка",
                    sourceUrl = url,
                    coverUrl = yandexCover(r.optString("coverUri")),
                    items = tracks.mapNotNull { yandexTrack(it) },
                )
            }
            else -> throw TransferException("Нужна ссылка на плейлист или альбом Яндекс Музыки")
        }
    }

    private fun yandexPlaylist(r: JSONObject, url: String): TransferredPlaylist {
        val arr = r.optJSONArray("tracks") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            yandexTrack(o.optJSONObject("track") ?: o)
        }
        return TransferredPlaylist(
            name = r.optString("title").ifBlank { "Плейлист" },
            source = "Яндекс Музыка",
            sourceUrl = url,
            coverUrl = yandexCover(r.optJSONObject("cover")?.optString("uri") ?: r.optString("ogImage")),
            items = items,
        )
    }

    private fun yandexTrack(t: JSONObject): OnlineItem? {
        val title = t.optString("title").takeIf { it.isNotBlank() } ?: return null
        val artists = t.optJSONArray("artists")
        val artist = if (artists != null) (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }.joinToString(", ") else ""
        return searchItem(artist, title, (t.optLong("durationMs") / 1000).toInt(), yandexCover(t.optString("coverUri")))
    }

    private fun yandexCover(uri: String?): String? =
        uri?.takeIf { it.isNotBlank() && it != "null" }?.let { "https://" + it.removePrefix("https://").replace("%%", "200x200") }

    // ---------------------------------------------------------------------------------------------
    // Deezer — open API.
    // ---------------------------------------------------------------------------------------------

    private fun deezer(url: String): TransferredPlaylist {
        var target = url
        if ("deezer.page.link" in url) target = resolveRedirect(url)
        val m = Regex("""deezer\.com/(?:[a-z]{2}/)?(playlist|album)/(\d+)""").find(target)
            ?: throw TransferException("Нужна ссылка на плейлист или альбом Deezer")
        val (kind, id) = m.destructured
        val r = JSONObject(http("https://api.deezer.com/$kind/$id"))
        r.optJSONObject("error")?.let { throw TransferException("Deezer: ${it.optString("message")}") }
        val data = r.optJSONObject("tracks")?.optJSONArray("data") ?: JSONArray()
        val items = (0 until data.length()).mapNotNull { i ->
            val t = data.optJSONObject(i) ?: return@mapNotNull null
            searchItem(
                t.optJSONObject("artist")?.optString("name").orEmpty(),
                t.optString("title"),
                t.optInt("duration"),
                t.optJSONObject("album")?.optString("cover_medium") ?: r.optString("cover_medium").ifBlank { null },
            )
        }
        return TransferredPlaylist(
            name = r.optString("title").ifBlank { "Deezer" },
            source = "Deezer",
            sourceUrl = url,
            coverUrl = r.optString("picture_medium").ifBlank { r.optString("cover_medium") }.ifBlank { null },
            items = items,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // YouTube / YouTube Music / SoundCloud / anything yt-dlp understands as a playlist.
    // ---------------------------------------------------------------------------------------------

    private suspend fun ytdlp(url: String): TransferredPlaylist {
        if (!downloader.prepare()) throw TransferException("Не удалось запустить движок загрузки")
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
        }
        val out = YoutubeDL.getInstance().execute(request).out
        val root = JSONObject(out.substring(out.indexOf('{')))
        val entries = root.optJSONArray("entries") ?: throw TransferException("По ссылке нет плейлиста")
        val items = (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val page = e.optString("webpage_url").ifBlank { e.optString("url") }
            if (!page.startsWith("http")) return@mapNotNull null
            val thumbs = e.optJSONArray("thumbnails")
            OnlineItem(
                pageUrl = page,
                title = e.optString("title").ifBlank { "Без названия" },
                artist = e.optString("channel").ifBlank { e.optString("uploader") },
                durationSec = e.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                thumbnail = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url"),
            )
        }
        val source = when {
            "soundcloud" in url -> "SoundCloud"
            "youtu" in url -> "YouTube"
            else -> root.optString("extractor_key").ifBlank { "Интернет" }
        }
        return TransferredPlaylist(root.optString("title").ifBlank { "Плейлист" }, source, url, null, items)
    }

    // ---------------------------------------------------------------------------------------------

    private fun searchItem(artist: String, title: String, durationSec: Int, cover: String?) = OnlineItem(
        pageUrl = "ytsearch1:" + listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ") + " audio",
        title = title,
        artist = artist,
        durationSec = durationSec,
        thumbnail = cover,
    )

    private fun http(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "ru,en;q=0.8")
        }
        try {
            if (conn.responseCode == 404) throw TransferException("Плейлист не найден или закрыт")
            if (conn.responseCode >= 400) throw TransferException("Сервис ответил ошибкой ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveRedirect(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true }
        return try {
            conn.inputStream.close()
            conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun encode(items: List<OnlineItem>): String = JSONArray().apply {
            items.forEach {
                put(JSONObject().put("u", it.pageUrl).put("t", it.title).put("a", it.artist).put("d", it.durationSec).put("th", it.thumbnail ?: ""))
            }
        }.toString()

        fun decode(json: String): List<OnlineItem> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    OnlineItem(it.optString("u"), it.optString("t"), it.optString("a"), it.optInt("d"), it.optString("th").ifBlank { null })
                }
            }
        }.getOrDefault(emptyList())
    }
}
