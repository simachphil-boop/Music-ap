package app.vesper.player.data.wave

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.vesper.player.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Finds the "hook" of a track — usually the chorus — without decoding the whole file:
 * it samples a short slice every few seconds, builds a loudness map and picks the loudest
 * ~20-second stretch in the body of the song, preferring the moment the energy jumps up.
 * Results are cached, so each track is analysed once (≈0.3–1 s).
 */
object ChorusFinder {
    const val SNIPPET_MS = 25_000L
    private const val WINDOW_MS = 20_000L
    private const val PROBE_MS = 350L
    private const val TIME_BUDGET_MS = 4_000L

    private fun prefs(context: Context) = context.getSharedPreferences("floya_chorus", Context.MODE_PRIVATE)

    fun cached(context: Context, track: TrackEntity): Long? =
        prefs(context).getLong(key(track), -1L).takeIf { it >= 0 }

    suspend fun find(context: Context, track: TrackEntity): Long = withContext(Dispatchers.IO) {
        cached(context, track)?.let { return@withContext it }
        val start = runCatching { analyze(context, Uri.parse(track.uri), track.durationMs) }.getOrNull()
            ?: fallback(track.durationMs)
        prefs(context).edit().putLong(key(track), start).apply()
        start
    }

    private fun key(t: TrackEntity) = "${t.id}_${t.durationMs}"

    private fun fallback(durationMs: Long): Long =
        if (durationMs < 60_000) 0L else (durationMs * 0.33).toLong()

    private fun analyze(context: Context, uri: Uri, durationMs: Long): Long? {
        if (durationMs < 60_000) return 0L
        val curve = loudnessMap(context, uri, durationMs) ?: return null
        val times = curve.first
        val loud = curve.second
        if (loud.size < 12) return null
        val max = loud.max().coerceAtLeast(1e-5f)
        for (i in loud.indices) loud[i] /= max

        val step = (times[1] - times[0]).coerceAtLeast(1L)
        val win = (WINDOW_MS / step).toInt().coerceAtLeast(2)
        val lookBack = (6_000L / step).toInt().coerceAtLeast(1)
        val first = loud.indices.firstOrNull { times[it] >= durationMs * 0.15 } ?: return null
        val last = loud.indices.lastOrNull { times[it] + WINDOW_MS <= durationMs * 0.85 } ?: return null
        if (last <= first) return null

        var best = first
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in first..last) {
            val end = (i + win).coerceAtMost(loud.size)
            var sum = 0f
            for (j in i until end) sum += loud[j]
            val mean = sum / (end - i)
            var before = 0f
            val from = (i - lookBack).coerceAtLeast(0)
            for (j in from until i) before += loud[j]
            val prev = if (i > from) before / (i - from) else mean
            // Loud and sustained, and noticeably louder than what came right before.
            val score = mean + 0.6f * (mean - prev).coerceAtLeast(0f)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return (times[best] - 400L).coerceAtLeast(0L)
    }

    /** (timestamps in ms, RMS loudness) sampled across the whole track. */
    private fun loudnessMap(context: Context, uri: Uri, durationMs: Long): Pair<LongArray, FloatArray>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    format = f
                    break
                }
            }
            val fmt = format ?: return null
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(fmt, null, null, 0)
            decoder.start()
            var floatPcm = false

            val step = (durationMs / 80).coerceIn(2_000L, 5_000L)
            val points = ((durationMs - PROBE_MS) / step).toInt()
            val times = LongArray(points)
            val values = FloatArray(points)
            val info = MediaCodec.BufferInfo()
            val started = System.currentTimeMillis()

            for (p in 0 until points) {
                if (System.currentTimeMillis() - started > TIME_BUDGET_MS) return null
                val at = p * step
                times[p] = at
                extractor.seekTo(at * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decoder.flush()
                var sumSq = 0.0
                var count = 0
                val need = (sampleRate * PROBE_MS / 1000).toInt()
                var inputDone = false
                var guard = 0
                while (count < need && guard++ < 400) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(5_000)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)
                            val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(info, 5_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = decoder.outputFormat
                            if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        outIndex >= 0 -> {
                            val out = decoder.getOutputBuffer(outIndex)
                            // Skip audio decoded before the requested point (seek lands on the previous sync frame).
                            if (out != null && info.size > 0 && info.presentationTimeUs >= at * 1000L - 50_000L) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val ordered = out.slice().order(ByteOrder.nativeOrder())
                                if (floatPcm) {
                                    val fb = ordered.asFloatBuffer()
                                    while (fb.remaining() >= channels && count < need) {
                                        var s = 0f
                                        for (c in 0 until channels) s += fb.get()
                                        s /= channels
                                        sumSq += (s * s).toDouble()
                                        count++
                                    }
                                } else {
                                    val sb = ordered.asShortBuffer()
                                    while (sb.remaining() >= channels && count < need) {
                                        var s = 0f
                                        for (c in 0 until channels) s += sb.get() / 32768f
                                        s /= channels
                                        sumSq += (s * s).toDouble()
                                        count++
                                    }
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                values[p] = if (count > 0) sqrt(sumSq / count).toFloat() else 0f
            }
            return times to values
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
