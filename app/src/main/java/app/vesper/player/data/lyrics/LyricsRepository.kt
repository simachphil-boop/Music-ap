package app.vesper.player.data.lyrics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    /** Time-coded lines (karaoke mode); empty if only plain text is known. */
    val synced: List<LyricLine>,
    val plain: String,
) {
    val isEmpty: Boolean get() = synced.isEmpty() && plain.isBlank()
}

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object NotFound : LyricsResult
    data class Error(val message: String) : LyricsResult
}

/**
 * Song lyrics from LRCLIB (free, no key). Every answer — including "not found" — is cached
 * on the device, so lyrics keep working offline after the first lookup.
 */
class LyricsRepository(private val context: Context) {

    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }

    suspend fun get(rawTitle: String, rawArtist: String, durationSec: Int, online: Boolean, refresh: Boolean = false): LyricsResult =
        withContext(Dispatchers.IO) {
            val (artist, title) = clean(rawArtist, rawTitle, online)
            val cacheFile = File(dir, key(artist, title) + ".json")
            if (!refresh && cacheFile.exists()) {
                val cached = runCatching { fromJson(JSONObject(cacheFile.readText())) }.getOrNull()
                val notFoundFresh = cached == null && System.currentTimeMillis() - cacheFile.lastModified() < 3L * 24 * 3600 * 1000
                if (cached != null) return@withContext LyricsResult.Found(cached)
                if (notFoundFresh) return@withContext LyricsResult.NotFound
            }
            try {
                val found = fetchExact(artist, title, durationSec) ?: fetchSearch(artist, title, durationSec)
                cacheFile.writeText(found?.let { toJson(it).toString() } ?: "{}")
                if (found == null) LyricsResult.NotFound else LyricsResult.Found(found)
            } catch (e: Exception) {
                LyricsResult.Error("Нет подключения к интернету — тексты загружаются один раз и потом доступны офлайн")
            }
        }

    private fun fetchExact(artist: String, title: String, durationSec: Int): Lyrics? {
        val q = buildString {
            append("https://lrclib.net/api/get?artist_name=").append(enc(artist))
            append("&track_name=").append(enc(title))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val body = http(q) ?: return null
        return parseRecord(JSONObject(body))
    }

    private fun fetchSearch(artist: String, title: String, durationSec: Int): Lyrics? {
        val url = if (artist.isNotBlank()) {
            "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}"
        } else {
            "https://lrclib.net/api/search?q=${enc(title)}"
        }
        val body = http(url) ?: return null
        val arr = JSONArray(body)
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hasSynced = o.optString("syncedLyrics").isNotBlank()
            val hasPlain = o.optString("plainLyrics").isNotBlank()
            if (!hasSynced && !hasPlain) continue
            val diff = if (durationSec > 0) kotlin.math.abs(o.optDouble("duration", 0.0) - durationSec) else 0.0
            val score = diff + if (hasSynced) 0.0 else 5.0
            if (score < bestScore) {
                bestScore = score
                best = o
            }
        }
        return best?.let { parseRecord(it) }
    }

    private fun parseRecord(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental")) return Lyrics(emptyList(), "♪ Инструментальная композиция")
        val synced = parseLrc(o.optString("syncedLyrics"))
        val plain = o.optString("plainLyrics").takeIf { it != "null" }.orEmpty()
        val l = Lyrics(synced, plain.ifBlank { synced.joinToString("\n") { it.text } })
        return l.takeIf { !it.isEmpty }
    }

    private fun http(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "FloyaMusic/1.0 (Android music player)")
        }
        return try {
            when (conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IllegalStateException("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun toJson(l: Lyrics) = JSONObject().apply {
        put("plain", l.plain)
        put("synced", JSONArray().apply { l.synced.forEach { put(JSONObject().put("t", it.timeMs).put("x", it.text)) } })
    }

    private fun fromJson(o: JSONObject): Lyrics? {
        if (!o.has("plain")) return null
        val arr = o.optJSONArray("synced") ?: JSONArray()
        val lines = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { LyricLine(it.optLong("t"), it.optString("x")) }
        }
        return Lyrics(lines, o.optString("plain"))
    }

    companion object {
        private val tagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

        fun parseLrc(lrc: String?): List<LyricLine> {
            if (lrc.isNullOrBlank() || lrc == "null") return emptyList()
            val out = ArrayList<LyricLine>()
            lrc.lineSequence().forEach { line ->
                val tags = tagRegex.findAll(line).toList()
                if (tags.isEmpty()) return@forEach
                val text = line.substring(tags.last().range.last + 1).trim()
                for (t in tags) {
                    val min = t.groupValues[1].toLong()
                    val sec = t.groupValues[2].toLong()
                    val frac = t.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    out += LyricLine(min * 60_000 + sec * 1000 + ms, text)
                }
            }
            return out.sortedBy { it.timeMs }
        }

        /** Strips YouTube-style noise: "Artist - Title (Official Video) [HD] ft. X" → (Artist, Title). */
        fun clean(rawArtist: String, rawTitle: String, online: Boolean): Pair<String, String> {
            var artist = rawArtist.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "").replace("VEVO", "", true).trim()
            var title = rawTitle
            val noise = Regex(
                """[(\[](official|lyric|lyrics|audio|video|music video|клип|текст|hd|hq|4k|remaster(ed)?|visualizer|премьера|official audio|official video|mv)[^)\]]*[)\]]""",
                RegexOption.IGNORE_CASE,
            )
            title = title.replace(noise, "")
            if (title.contains(" - ") || title.contains(" — ")) {
                val parts = title.split(" - ", " — ", limit = 2)
                val looksLikeChannel = artist.isBlank() || artist.startsWith("Неизвестн") ||
                    parts[0].contains(artist, true) || artist.contains(parts[0].trim(), true) || artist.length > 30
                if (online || looksLikeChannel) {
                    artist = parts[0].trim()
                    title = parts[1]
                }
            }
            title = title.replace(Regex("""\s+(ft\.?|feat\.?|при уч\.).*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""[|].*$"""), "")
                .trim().trim('"', '«', '»')
            if (artist.startsWith("Неизвестн")) artist = ""
            return artist to title
        }

        private fun key(artist: String, title: String): String =
            (artist.lowercase() + "|" + title.lowercase()).hashCode().toUInt().toString(16)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
