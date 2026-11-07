package app.floya.desktop.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Plain HTTP downloads split into ranges that are fetched in parallel. */
object ParallelHttp {
    private const val CHUNK = 1L shl 20 // 1 MB — small enough that YouTube never throttles a request
    private const val WORKERS = 4

    /**
     * Downloads [url] into [target]. Returns false on any failure (caller falls back to another method).
     * [onProgress] gets (bytes done, total bytes, bytes per second).
     */
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        knownLength: Long,
        target: File,
        canceled: () -> Boolean,
        onProgress: (Long, Long, Long) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val total = if (knownLength > 0) knownLength else contentLength(url, headers)
        if (total <= 0) return@withContext false
        val chunks = ((total + CHUNK - 1) / CHUNK).toInt()
        val next = AtomicInteger(0)
        val done = AtomicLong(0)
        val started = System.currentTimeMillis()
        var lastReport = 0L
        RandomAccessFile(target, "rw").use { it.setLength(total) }
        try {
            coroutineScope {
                (0 until WORKERS).map {
                    async {
                        RandomAccessFile(target, "rw").use { file ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                if (canceled()) throw InterruptedException("canceled")
                                val index = next.getAndIncrement()
                                if (index >= chunks) break
                                val from = index * CHUNK
                                val to = minOf(total, from + CHUNK) - 1
                                var attempt = 0
                                while (true) {
                                    try {
                                        val got = fetchRange(url, headers, from, to, file, buf)
                                        done.addAndGet(got)
                                        break
                                    } catch (e: java.io.IOException) {
                                        if (++attempt >= 3) throw e
                                        Thread.sleep(300L * attempt)
                                    }
                                }
                                val now = System.currentTimeMillis()
                                synchronized(this@ParallelHttp) {
                                    if (now - lastReport > 250) {
                                        lastReport = now
                                        val elapsed = (now - started).coerceAtLeast(1)
                                        onProgress(done.get(), total, done.get() * 1000 / elapsed)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            done.get() == total
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    /** Fetches one byte range and writes it at its place in the file. Returns bytes written. */
    private fun fetchRange(url: String, headers: Map<String, String>, from: Long, to: Long, file: RandomAccessFile, buf: ByteArray): Long {
        val conn = open(url, headers).apply { setRequestProperty("Range", "bytes=$from-$to") }
        try {
            if (conn.responseCode != 206) throw java.io.IOException("HTTP ${conn.responseCode}")
            var pos = from
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(file) {
                        file.seek(pos)
                        file.write(buf, 0, n)
                    }
                    pos += n
                }
            }
            val expected = to - from + 1
            if (pos - from != expected) throw java.io.IOException("Обрыв: ${pos - from} из $expected")
            return expected
        } finally {
            conn.disconnect()
        }
    }

    private fun contentLength(url: String, headers: Map<String, String>): Long {
        val conn = open(url, headers).apply { setRequestProperty("Range", "bytes=0-0") }
        return try {
            if (conn.responseCode != 206) return -1
            conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1
        } catch (e: Exception) {
            -1
        } finally {
            conn.disconnect()
        }
    }

    /** Small file (a cover image). True only on HTTP 200 with a non-empty body. */
    fun fetchSmall(url: String, target: File): Boolean = runCatching {
        val conn = open(url, emptyMap())
        try {
            if (conn.responseCode != 200) return@runCatching false
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            target.length() > 1024
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
}
