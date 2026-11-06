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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
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
 * YouTube links go through [YouTubeFast] (one HTTP request); everything else, or a failed fast
 * path, goes through yt-dlp. Different tracks resolve in parallel, and a request for a track that
 * is already being resolved (e.g. by prefetch) simply waits for that result instead of starting over.
 */
object StreamResolver {
    private data class Resolved(val url: String, val headers: Map<String, String>, val expiresAt: Long, val fast: YouTubeFast.Stream?)

    private val cache = ConcurrentHashMap<String, Resolved>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Resolved>>()
    private val pool = Executors.newFixedThreadPool(4)
    private const val YTDLP_TTL_MS = 60L * 60 * 1000

    fun resolveSpec(context: Context, spec: DataSpec): DataSpec {
        if (spec.uri.scheme != STREAM_SCHEME) return spec
        val page = spec.uri.getQueryParameter("u") ?: throw IOException("Нет адреса страницы")
        val r = resolve(context, page)
        return spec.withUri(Uri.parse(r.url)).withAdditionalHeaders(r.headers)
    }

    /** Warms the cache for a track so it starts without a pause. [allowSlow] = also via yt-dlp. */
    fun prefetch(context: Context, pageUrl: String, allowSlow: Boolean = true) {
        if (fresh(pageUrl) != null || inFlight.containsKey(pageUrl)) return
        if (!allowSlow && YouTubeFast.videoId(pageUrl) == null) return
        start(context.applicationContext, pageUrl, allowSlow)
    }

    /** Direct YouTube audio link if one is known or can be fetched quickly (used by the downloader). */
    fun fastAudio(context: Context, pageUrl: String): YouTubeFast.Stream? {
        if (YouTubeFast.videoId(pageUrl) == null) return null
        return runCatching { resolve(context, pageUrl, allowSlow = false).fast }.getOrNull()
    }

    private fun fresh(pageUrl: String): Resolved? =
        cache[pageUrl]?.takeIf { System.currentTimeMillis() < it.expiresAt }

    private fun resolve(context: Context, pageUrl: String, allowSlow: Boolean = true): Resolved {
        fresh(pageUrl)?.let { return it }
        val future = start(context, pageUrl, allowSlow)
        try {
            return future.get()
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            // A fast-only prefetch may have failed — retry with the full path for real playback.
            if (allowSlow && cause is FastOnlyMiss) return resolve(context, pageUrl, true)
            throw cause as? IOException ?: IOException(cause.message ?: "Не удалось получить поток", cause)
        }
    }

    /** Starts resolving (or joins a run already in progress) without blocking the caller. */
    private fun start(context: Context, pageUrl: String, allowSlow: Boolean): CompletableFuture<Resolved> {
        inFlight[pageUrl]?.let { return it }
        val created = CompletableFuture<Resolved>()
        val existing = inFlight.putIfAbsent(pageUrl, created)
        if (existing != null) return existing
        pool.execute {
            // Leave the in-flight map before waking waiters, so a retry starts a fresh run.
            val result = runCatching { doResolve(context, pageUrl, allowSlow) }
            inFlight.remove(pageUrl, created)
            result.fold({ created.complete(it) }, { created.completeExceptionally(it) })
        }
        return created
    }

    private class FastOnlyMiss : IOException("Быстрый способ не сработал")

    private fun doResolve(context: Context, pageUrl: String, allowSlow: Boolean): Resolved {
        YouTubeFast.audio(pageUrl)?.let { s ->
            return Resolved(s.url, s.headers, s.expiresAt, s).also { cache[pageUrl] = it }
        }
        if (!allowSlow) throw FastOnlyMiss()
        val ready = runBlocking { FfmpegTool.init(context) }
        if (!ready) throw IOException("Движок не запустился: ${FfmpegTool.lastError}")
        try {
            val request = YoutubeDLRequest(normalizePage(pageUrl)).apply {
                addOption("--no-playlist")
                // Direct (non-manifest) audio so ExoPlayer can stream it progressively.
                addOption("-f", "bestaudio[protocol^=http][ext=m4a]/bestaudio[protocol^=http]/best[protocol^=http]/best")
                YtDlpTuning.apply(context, this, skipManifests = true)
                if (CookieStore.hasAny(context)) addOption("--cookies", CookieStore.file(context).absolutePath)
            }
            val info = YoutubeDL.getInstance().getInfo(request)
            val url = info.url ?: throw IOException("Поток не найден")
            val r = Resolved(url, info.httpHeaders.orEmpty(), System.currentTimeMillis() + YTDLP_TTL_MS, null)
            cache[pageUrl] = r
            return r
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "Не удалось получить поток", e)
        }
    }

    /** music.youtube.com pages make yt-dlp do extra work; the regular watch page is equivalent. */
    fun normalizePage(pageUrl: String): String =
        YouTubeFast.videoId(pageUrl)?.let { "https://www.youtube.com/watch?v=$it" } ?: pageUrl
}

/** Options that make every yt-dlp run noticeably faster. */
object YtDlpTuning {
    fun apply(context: Context, request: YoutubeDLRequest, skipManifests: Boolean) {
        // Keep YouTube's player code and signature solutions between runs instead of downloading them every time.
        val dir = java.io.File(context.cacheDir, "yt-dlp").apply { mkdirs() }
        request.addOption("--cache-dir", dir.absolutePath)
        request.addOption("--no-warnings")
        if (skipManifests) request.addOption("--extractor-args", "youtube:skip=hls,dash")
    }
}

fun MediaItem.pageUrl(): String? = mediaMetadata.extras?.getString(EXTRA_PAGE_URL)
