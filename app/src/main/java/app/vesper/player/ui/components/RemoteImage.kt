package app.vesper.player.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.net.URL

private val remoteCache = object : LruCache<String, Bitmap>(24 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
}

/** At most six pictures load at once, so a long list of results doesn't choke the connection. */
private val remoteGate = Semaphore(6)

/**
 * Pictures from the internet are kept on disk (up to ~60 MB, oldest removed first): covers of
 * online playlists and search results appear at once the second time and are visible offline.
 */
private object ThumbDisk {
    private const val MAX_BYTES = 60L * 1024 * 1024
    private val writes = AtomicInteger(0)

    private fun dir(context: Context) = File(context.cacheDir, "thumbs").apply { mkdirs() }

    fun file(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), hash)
    }

    fun save(context: Context, url: String, bytes: ByteArray) {
        runCatching {
            val target = file(context, url)
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(target)
            if (writes.incrementAndGet() % 50 == 0) trim(context)
        }
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var size = files.sumOf { it.length() }
        for (f in files) {
            if (size <= MAX_BYTES) break
            size -= f.length()
            f.delete()
        }
    }
}

private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Small network image (covers, link previews). Falls back to a plain surface when it can't be loaded. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState(url?.let { remoteCache.get(it) }, url) {
        if (url == null) {
            value = null
            return@produceState
        }
        remoteCache.get(url)?.let {
            value = it
            return@produceState
        }
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val cached = ThumbDisk.file(context, url)
                if (cached.isFile && cached.length() > 0) {
                    decodeSampled(cached.readBytes())?.let {
                        cached.setLastModified(System.currentTimeMillis())
                        return@runCatching it
                    }
                }
                remoteGate.withPermit {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        instanceFollowRedirects = true
                    }
                    try {
                        if (conn.responseCode !in 200..299) return@withPermit null
                        val bytes = conn.inputStream.use { it.readBytes() }
                        decodeSampled(bytes)?.also { ThumbDisk.save(context, url, bytes) }
                    } finally {
                        conn.disconnect()
                    }
                }
            }.getOrNull()?.also { remoteCache.put(url, it) }
        }
    }
    Box(modifier.background(Vesper.colors.surfaceHigh)) {
        val b = bitmap
        if (b != null) {
            Image(remember(b) { b.asImageBitmap() }, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
