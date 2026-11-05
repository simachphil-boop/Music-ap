package app.vesper.player.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Loads embedded cover art / video frames straight from the media URI and keeps
 * a memory cache. Nothing is downloaded — works fully offline.
 */
object ArtworkLoader {

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val misses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val gate = Semaphore(4)

    private fun key(uri: String, sizePx: Int) = "$uri@${bucket(sizePx)}"
    private fun bucket(sizePx: Int) = when {
        sizePx <= 192 -> 192
        sizePx <= 480 -> 480
        else -> 1024
    }

    fun cached(uri: String, sizePx: Int): Bitmap? = cache.get(key(uri, sizePx))

    fun isKnownMissing(uri: String, sizePx: Int): Boolean = misses.contains(key(uri, sizePx))

    suspend fun load(context: Context, uri: String, isVideo: Boolean, sizePx: Int): Bitmap? {
        val k = key(uri, sizePx)
        cache.get(k)?.let { return it }
        if (misses.contains(k)) return null
        return gate.withPermit {
            cache.get(k) ?: withContext(Dispatchers.IO) {
                val bmp = runCatching { decode(context.applicationContext, Uri.parse(uri), isVideo, bucket(sizePx)) }
                    .getOrNull()
                if (bmp != null) cache.put(k, bmp) else misses.add(k)
                bmp
            }
        }
    }

    /** Blocking variant used by the playback service bitmap loader. */
    fun loadBlocking(context: Context, uri: String, sizePx: Int): Bitmap? {
        val k = key(uri, sizePx)
        cache.get(k)?.let { return it }
        val bmp = runCatching { decode(context.applicationContext, Uri.parse(uri), false, bucket(sizePx)) }.getOrNull()
        if (bmp != null) cache.put(k, bmp)
        return bmp
    }

    fun invalidate(uri: String) {
        listOf(192, 480, 1024).forEach {
            cache.remove("$uri@$it")
            misses.remove("$uri@$it")
        }
    }

    private fun decode(context: Context, uri: Uri, isVideo: Boolean, size: Int): Bitmap? {
        if (uri.scheme == "http" || uri.scheme == "https") {
            val conn = (java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            return try {
                decodeSampled(conn.inputStream.use { it.readBytes() }, size)
            } finally {
                conn.disconnect()
            }
        }
        if (uri.toString().isBlank()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            runCatching {
                return context.contentResolver.loadThumbnail(uri, Size(size, size), null)
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (isVideo) {
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: size
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: size
                val scale = size.toFloat() / maxOf(w, h, 1)
                val tw = (w * scale).toInt().coerceAtLeast(1)
                val th = (h * scale).toInt().coerceAtLeast(1)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val at = if (duration > 10_000) 3_000_000L else 0L
                return retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
            }
            val bytes = retriever.embeddedPicture ?: return null
            return decodeSampled(bytes, size)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampled(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
