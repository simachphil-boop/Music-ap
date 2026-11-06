package app.vesper.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for internet tracks (keyed by the page, not by the short-lived stream link):
 * a track played once starts instantly the next time, even without resolving it again.
 */
object StreamCache {
    private const val MAX_BYTES = 300L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "stream-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Local files go straight to [local]; `floyastream://` items go through the cache first. */
    fun factory(context: Context, local: DataSource.Factory): DataSource.Factory {
        val online = CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(local)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { RoutingDataSource(local.createDataSource(), online.createDataSource()) }
    }

    /** True when the whole stream for [pageUrl] is already on disk. */
    fun isFullyCached(context: Context, pageUrl: String, length: Long): Boolean {
        if (length <= 0) return false
        val key = onlineKey(pageUrl)
        return runCatching { get(context).isCached(key, 0, length) }.getOrDefault(false)
    }

    fun onlineKey(pageUrl: String): String =
        Uri.Builder().scheme(STREAM_SCHEME).authority("resolve").appendQueryParameter("u", pageUrl).build().toString()
}

private class RoutingDataSource(
    private val local: DataSource,
    private val online: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        online.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == STREAM_SCHEME) online else local
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IllegalStateException("Источник не открыт")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
