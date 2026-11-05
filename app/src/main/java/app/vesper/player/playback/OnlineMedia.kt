package app.vesper.player.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSpec
import app.vesper.player.data.download.CookieStore
import app.vesper.player.data.edit.FfmpegTool
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

const val STREAM_SCHEME = "floyastream"
const val EXTRA_PAGE_URL = "floya.page_url"
const val ONLINE_ID_PREFIX = "web:"

/** A track/video page on the internet that can be streamed (resolved via yt-dlp at play time). */
data class OnlineItem(
    val pageUrl: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val thumbnail: String?,
)

fun OnlineItem.toMediaItem(): MediaItem {
    val streamUri = Uri.Builder().scheme(STREAM_SCHEME).authority("resolve").appendQueryParameter("u", pageUrl).build()
    val extras = Bundle().apply {
        // EXTRA_TRACK_URI doubles as the artwork source in the UI; for online items it is the thumbnail.
        putString(EXTRA_TRACK_URI, thumbnail.orEmpty())
        putLong(EXTRA_DURATION, durationSec * 1000L)
        putString(EXTRA_PAGE_URL, pageUrl)
    }
    return MediaItem.Builder()
        .setMediaId(ONLINE_ID_PREFIX + pageUrl)
        .setUri(streamUri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(streamUri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifBlank { "Онлайн" })
                .setArtworkUri(thumbnail?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(extras)
                .build(),
        )
        .build()
}

/**
 * Turns `floyastream://resolve?u=<page>` into the real media URL right before playback.
 * Results are cached for an hour (YouTube stream links expire after a few hours).
 */
object StreamResolver {
    private data class Resolved(val url: String, val headers: Map<String, String>, val at: Long)

    private val cache = ConcurrentHashMap<String, Resolved>()
    private val prefetcher = Executors.newSingleThreadExecutor()
    private const val TTL_MS = 60L * 60 * 1000

    fun resolveSpec(context: Context, spec: DataSpec): DataSpec {
        if (spec.uri.scheme != STREAM_SCHEME) return spec
        val page = spec.uri.getQueryParameter("u") ?: throw IOException("Нет адреса страницы")
        val r = resolve(context, page)
        return spec.withUri(Uri.parse(r.url)).withAdditionalHeaders(r.headers)
    }

    /** Warms the cache for the next track so it starts without a pause. */
    fun prefetch(context: Context, pageUrl: String) {
        if (cache[pageUrl]?.let { System.currentTimeMillis() - it.at < TTL_MS } == true) return
        prefetcher.execute { runCatching { resolve(context, pageUrl) } }
    }

    @Synchronized
    private fun resolve(context: Context, pageUrl: String): Resolved {
        cache[pageUrl]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it }
        val ready = runBlocking { FfmpegTool.init(context) }
        if (!ready) throw IOException("Движок не запустился: ${FfmpegTool.lastError}")
        try {
            val request = YoutubeDLRequest(pageUrl).apply {
                addOption("--no-playlist")
                // Direct (non-manifest) audio so ExoPlayer can stream it progressively.
                addOption("-f", "bestaudio[protocol^=http][ext=m4a]/bestaudio[protocol^=http]/best[protocol^=http]/best")
                if (CookieStore.hasAny(context)) addOption("--cookies", CookieStore.file(context).absolutePath)
            }
            val info = YoutubeDL.getInstance().getInfo(request)
            val url = info.url ?: throw IOException("Поток не найден")
            val r = Resolved(url, info.httpHeaders.orEmpty(), System.currentTimeMillis())
            cache[pageUrl] = r
            return r
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Не удалось получить поток", e)
        }
    }
}

fun MediaItem.pageUrl(): String? = mediaMetadata.extras?.getString(EXTRA_PAGE_URL)
