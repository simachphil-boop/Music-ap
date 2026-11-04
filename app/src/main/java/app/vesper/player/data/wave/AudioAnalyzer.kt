package app.vesper.player.data.wave

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

data class AudioFeatures(
    /** Loudness, 0 (quiet) .. 1 (loud/compressed). */
    val energy: Float,
    /** Variation of loudness, 0 (flat) .. 1 (very dynamic). */
    val dynamics: Float,
    /** Zero-crossing based brightness, 0 (dark/bassy) .. 1 (bright). */
    val brightness: Float,
    /** Estimated tempo in BPM. */
    val bpm: Float,
    /** 0..1, how periodic the onset envelope is. */
    val tempoConfidence: Float,
)

/**
 * Lightweight on-device audio analysis: decodes ~20 s from the middle of a track and
 * derives loudness, dynamics, brightness and tempo. No network, no ML models.
 */
object AudioAnalyzer {

    private const val FRAME = 1024
    private const val ANALYSIS_SECONDS = 20
    private const val TIME_BUDGET_MS = 9_000L

    fun analyze(context: Context, uri: Uri, durationMs: Long): AudioFeatures? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIndex)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            if (durationMs > 60_000) {
                extractor.seekTo(durationMs * 1000L * 35 / 100, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            var floatPcm = false
            val rms = ArrayList<Float>(1200)
            val zcr = ArrayList<Float>(1200)
            var frameSumSq = 0.0
            var frameCrossings = 0
            var frameCount = 0
            var lastSample = 0f
            var targetFrames = sampleRate * ANALYSIS_SECONDS / FRAME

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val started = System.currentTimeMillis()

            fun pushSample(s: Float) {
                frameSumSq += (s * s).toDouble()
                if ((s >= 0f) != (lastSample >= 0f)) frameCrossings++
                lastSample = s
                frameCount++
                if (frameCount == FRAME) {
                    rms += sqrt(frameSumSq / FRAME).toFloat()
                    zcr += frameCrossings.toFloat() / FRAME
                    frameSumSq = 0.0
                    frameCrossings = 0
                    frameCount = 0
                }
            }

            while (!outputDone && rms.size < targetFrames) {
                if (System.currentTimeMillis() - started > TIME_BUDGET_MS) break
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
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
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = decoder.outputFormat
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        targetFrames = sampleRate * ANALYSIS_SECONDS / FRAME
                    }
                    outIndex >= 0 -> {
                        val out = decoder.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.slice().order(ByteOrder.nativeOrder())
                            if (floatPcm) {
                                val fb = ordered.asFloatBuffer()
                                while (fb.remaining() >= channels) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get()
                                    pushSample(sum / channels)
                                }
                            } else {
                                val sb = ordered.asShortBuffer()
                                while (sb.remaining() >= channels) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += sb.get() / 32768f
                                    pushSample(sum / channels)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (rms.size < 40) return null
            return compute(rms, zcr, sampleRate.toFloat() / FRAME)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun compute(rms: List<Float>, zcr: List<Float>, frameRate: Float): AudioFeatures {
        // Ignore near-silent frames (intros/gaps) for loudness statistics.
        val loud = rms.filter { it > 0.003f }.ifEmpty { rms }
        val mean = loud.average().toFloat()
        val std = sqrt(loud.map { (it - mean) * (it - mean) }.average()).toFloat()
        val db = 20f * log10(mean.coerceAtLeast(1e-5f))
        val energy = ((db + 38f) / 30f).coerceIn(0f, 1f)          // ~-38 dBFS → 0, ~-8 dBFS → 1
        val dynamics = ((std / mean.coerceAtLeast(1e-5f)) / 1.1f).coerceIn(0f, 1f)
        val brightness = ((zcr.average().toFloat() - 0.015f) / 0.16f).coerceIn(0f, 1f)

        // Tempo: autocorrelation of the onset (positive energy flux) envelope.
        val onset = FloatArray(rms.size)
        for (i in 1 until rms.size) onset[i] = (rms[i] - rms[i - 1]).coerceAtLeast(0f)
        val om = onset.average().toFloat()
        for (i in onset.indices) onset[i] -= om
        var acf0 = 0.0
        for (v in onset) acf0 += (v * v).toDouble()
        var bestLag = -1
        var bestScore = 0.0
        var bestRaw = 0.0
        val minLag = (60f * frameRate / 190f).toInt().coerceAtLeast(2)
        val maxLag = (60f * frameRate / 65f).toInt().coerceAtMost(onset.size / 2)
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until onset.size) s += (onset[i] * onset[i - lag]).toDouble()
            val bpm = 60f * frameRate / lag
            // Mild prior around 120 BPM reduces half/double-tempo errors.
            val prior = exp(-0.5 * (ln(bpm / 120.0) / 0.45).let { it * it })
            val weighted = s * prior
            if (weighted > bestScore) {
                bestScore = weighted
                bestLag = lag
                bestRaw = s
            }
        }
        val bpm = if (bestLag > 0) 60f * frameRate / bestLag else 110f
        val confidence = if (acf0 > 0) (bestRaw / acf0).toFloat().coerceIn(0f, 1f) else 0f
        return AudioFeatures(energy, dynamics, brightness, bpm, abs(confidence))
    }
}
