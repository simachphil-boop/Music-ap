package app.vesper.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Reads a long HTTP stream as a series of short ranged requests. Video hosts (YouTube first of
 * all) slow down one long open-ended request to about playback speed; a few-megabyte range is
 * served at full speed, so the start, seeking and buffering ahead are much faster.
 */
class ChunkedDataSource(
    private val upstream: DataSource,
    private val total: Long,
    private val chunk: Long = CHUNK,
) : DataSource {
    private var base: DataSpec? = null
    private var pos = 0L
    private var end = 0L
    private var chunkLeft = 0L
    private var upstreamOpen = false
    private var emptyReads = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        base = dataSpec
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(total, pos + dataSpec.length) else total
        if (pos > end) throw IOException("Позиция за концом файла")
        if (pos < end) openChunk()
        return end - pos
    }

    private fun openChunk() {
        val spec = base ?: throw IOException("Источник не открыт")
        val len = minOf(chunk, end - pos)
        upstream.open(spec.buildUpon().setPosition(pos).setLength(len).build())
        upstreamOpen = true
        chunkLeft = len
    }

    private fun closeUpstream() {
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            if (chunkLeft <= 0L || !upstreamOpen) {
                closeUpstream()
                openChunk()
            }
            val n = upstream.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
            if (n == C.RESULT_END_OF_INPUT) {
                // The server ended this range early — ask again from where we stopped.
                closeUpstream()
                chunkLeft = 0
                if (++emptyReads > 3) throw IOException("Сервер обрывает передачу")
                continue
            }
            emptyReads = 0
            pos += n
            chunkLeft -= n
            return n
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: base?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            closeUpstream()
        } finally {
            base = null
        }
    }

    companion object {
        const val CHUNK = 4L * 1024 * 1024
    }
}
