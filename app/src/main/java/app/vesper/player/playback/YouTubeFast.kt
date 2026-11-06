package app.vesper.player.playback

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fast path for YouTube / YouTube Music: asks YouTube's own player API for a direct audio link.
 * One HTTP request (~0.3–1 s) instead of starting yt-dlp's Python runtime (~3–8 s).
 * Mobile/VR clients return plain URLs (no signature deciphering). If YouTube changes
 * something and this stops working, callers fall back to yt-dlp automatically.
 */
object YouTubeFast {

    data class Stream(
        val url: String,
        val userAgent: String,
        val mimeType: String,
        val contentLength: Long,
        val expiresAt: Long,
        val title: String,
        val author: String,
        val durationSec: Int,
    ) {
        val ext: String get() = if (mimeType.contains("webm")) "webm" else "m4a"
        val headers: Map<String, String> get() = mapOf("User-Agent" to userAgent)
    }

    private data class Client(
        val name: String,
        val id: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any>,
    )

    private val clients = listOf(
        Client(
            name = "ANDROID_VR",
            id = "28",
            version = "1.62.27",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            extra = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "androidSdkVersion" to 32,
                "osName" to "Android",
                "osVersion" to "12L",
            ),
        ),
        Client(
            name = "IOS",
            id = "5",
            version = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82",
            ),
        ),
    )

    private val idPatterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"""),
    )

    fun videoId(pageUrl: String): String? {
        val lower = pageUrl.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) return null
        return idPatterns.firstNotNullOfOrNull { it.find(pageUrl)?.groupValues?.get(1) }
    }

    /** Returns a working direct audio stream or null (caller then uses yt-dlp). */
    fun audio(pageUrl: String): Stream? {
        val id = videoId(pageUrl) ?: return null
        for (client in clients) {
            val s = runCatching { request(id, client) }.getOrNull() ?: continue
            if (probe(s)) return s
        }
        return null
    }

    private fun request(videoId: String, c: Client): Stream? {
        val clientJson = JSONObject()
            .put("clientName", c.name)
            .put("clientVersion", c.version)
            .put("hl", "ru")
            .put("gl", "RU")
        c.extra.forEach { (k, v) -> clientJson.put(k, v) }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientJson))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", c.userAgent)
            setRequestProperty("X-YouTube-Client-Name", c.id)
            setRequestProperty("X-YouTube-Client-Version", c.version)
            setRequestProperty("Origin", "https://www.youtube.com")
        }
        val text = try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val root = JSONObject(text)
        if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val streaming = root.optJSONObject("streamingData") ?: return null
        val formats = streaming.optJSONArray("adaptiveFormats") ?: return null
        var best: JSONObject? = null
        var bestScore = -1L
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            if (!f.has("url")) continue // ciphered — leave to yt-dlp
            if (f.has("isDrc") && f.optBoolean("isDrc")) continue
            // Prefer AAC/M4A: plays everywhere and can be saved without re-encoding.
            val score = f.optLong("bitrate") + if (mime.contains("mp4")) 1_000_000L else 0L
            if (score > bestScore) {
                bestScore = score
                best = f
            }
        }
        val f = best ?: return null
        val expires = streaming.optLong("expiresInSeconds", 6 * 3600L).coerceAtLeast(600L)
        val details = root.optJSONObject("videoDetails")
        return Stream(
            url = f.getString("url"),
            userAgent = c.userAgent,
            mimeType = f.optString("mimeType"),
            contentLength = f.optString("contentLength").toLongOrNull() ?: -1L,
            // Refresh well before YouTube's own expiry.
            expiresAt = System.currentTimeMillis() + expires * 1000L / 2,
            title = details?.optString("title").orEmpty(),
            author = details?.optString("author").orEmpty().removeSuffix(" - Topic"),
            durationSec = details?.optString("lengthSeconds")?.toIntOrNull() ?: 0,
        )
    }

    /** One tiny ranged request — makes sure YouTube actually serves this link. */
    private fun probe(s: Stream): Boolean = runCatching {
        val conn = (URL(s.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", s.userAgent)
            setRequestProperty("Range", "bytes=0-1")
        }
        try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
