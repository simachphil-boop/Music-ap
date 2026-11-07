package app.vesper.player.data.download

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * A link that points straight at a media file (…/song.mp3, a file-hosting "download" link, etc.).
 * Such files need no site analysis at all — they're fetched like on an old-school download site:
 * one plain HTTP request (here split into parallel parts), no yt-dlp, no re-encoding.
 */
object DirectLink {
    data class Info(val url: String, val fileName: String, val mime: String, val length: Long) {
        val isAudio: Boolean get() = mime.startsWith("audio/") || ext in AUDIO
        val isVideo: Boolean get() = mime.startsWith("video/") || ext in VIDEO
        val ext: String get() = fileName.substringAfterLast('.', "").lowercase()
    }

    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "amr", "aiff", "alac")
    private val VIDEO = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts")
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    val headers = mapOf("User-Agent" to UA)

    private val known = ConcurrentHashMap<String, Info>()

    fun cached(url: String): Info? = known[url]

    /** Checks the link with one tiny request. Null = it's a web page, let yt-dlp handle it. */
    fun probe(url: String): Info? {
        known[url]?.let { return it }
        val lower = url.lowercase()
        // Video sites serve pages, not files — don't even ask.
        if (listOf("youtube.", "youtu.be", "vk.com", "vkvideo", "tiktok.", "instagram.", "rutube.", "soundcloud.", "twitter.", "x.com/").any { it in lower }) return null
        val info = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@runCatching null
                val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val finalUrl = conn.url.toString()
                val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val name = fileName(conn.getHeaderField("Content-Disposition"), finalUrl, mime)
                val candidate = Info(finalUrl, name, mime, total)
                if (candidate.isAudio || candidate.isVideo) candidate else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (info != null) known[url] = info
        return info
    }

    private fun fileName(disposition: String?, url: String, mime: String): String {
        val fromHeader = disposition?.let {
            Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.let { n -> URLDecoder.decode(n, "UTF-8") }
                ?: Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val fromUrl = runCatching { URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8") }.getOrNull()
        var name = (fromHeader ?: fromUrl).orEmpty().trim().ifBlank { "file" }
        if (!name.contains('.')) {
            name += "." + when {
                mime.contains("mpeg") && mime.startsWith("audio") -> "mp3"
                mime.contains("mp4") && mime.startsWith("audio") -> "m4a"
                mime.contains("ogg") -> "ogg"
                mime.contains("flac") -> "flac"
                mime.contains("wav") -> "wav"
                mime.contains("webm") -> "webm"
                mime.startsWith("video") -> "mp4"
                else -> "bin"
            }
        }
        return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
    }
}
