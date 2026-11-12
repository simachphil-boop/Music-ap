package app.vesper.player.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real spectrum of the music that is playing, taken from the app's own audio stream on its way to
 * the speaker — so no microphone / "record audio" permission is needed. Works only while a
 * visualizer is on screen ([acquire]/[release]); otherwise it costs nothing.
 */
object SpectrumTap {
    const val BANDS = 28
    private const val N = 1024
    /** The sound reaches the ear a little after it passes through here (output buffer). */
    private const val LATENCY_MS = 160L
    private const val QUEUE = 16

    private val users = AtomicInteger(0)
    val active: Boolean get() = users.get() > 0

    fun acquire() { users.incrementAndGet() }
    fun release() { users.decrementAndGet() }

    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2.0 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2.0 * PI * it / N).toFloat() }
    private val samples = FloatArray(N)
    private var filled = 0
    private var decim = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private var edges = IntArray(0)
    private var edgesFor = 0

    // Ring of finished frames with the time they should be shown.
    private val frames = Array(QUEUE) { FloatArray(BANDS) }
    private val showAt = LongArray(QUEUE)
    @Volatile private var head = 0

    /** Called from the audio thread with interleaved 16-bit stereo. Does not move the buffer's position. */
    fun feed(buffer: ByteBuffer, sampleRate: Int) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        // About 22 kHz is plenty for a picture: take every second frame of 44.1/48 kHz audio.
        val step = if (sampleRate >= 32_000) 2 else 1
        while (b.remaining() >= 4) {
            val l = b.short
            val r = b.short
            if (decim++ % step != 0) continue
            samples[filled++] = (l + r) / 65536f
            if (filled == N) {
                analyse(sampleRate / step)
                // Half-overlapping windows: twice as many frames, smoother motion.
                System.arraycopy(samples, N / 2, samples, 0, N / 2)
                filled = N / 2
            }
        }
    }

    private fun analyse(rate: Int) {
        for (i in 0 until N) {
            re[i] = samples[i] * window[i]
            im[i] = 0f
        }
        fft()
        if (edgesFor != rate) {
            // Logarithmic bands from 50 Hz to 10 kHz — the way pitch is heard.
            val top = minOf(10_000.0, rate / 2.0 - 1)
            edges = IntArray(BANDS + 1) { k ->
                val f = 50.0 * (top / 50.0).pow(k.toDouble() / BANDS)
                (f * N / rate).toInt().coerceIn(1, N / 2 - 1)
            }
            edgesFor = rate
        }
        val next = (head + 1) % QUEUE
        val out = frames[next]
        for (k in 0 until BANDS) {
            val from = edges[k]
            val to = maxOf(edges[k + 1], from + 1)
            var peak = 0f
            for (i in from until to) {
                val m = re[i] * re[i] + im[i] * im[i]
                if (m > peak) peak = m
            }
            // Decibel-like scale: quiet details stay visible, loud parts don't clip. Highs get a lift.
            val mag = sqrt(peak) / (N / 4f) * (1f + k * 0.09f)
            out[k] = ((ln(mag.toDouble() + 1e-5) + 6.2) / 6.2).toFloat().coerceIn(0f, 1f)
        }
        showAt[next] = android.os.SystemClock.elapsedRealtime() + LATENCY_MS
        head = next
    }

    /** In-place radix-2 FFT. */
    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2
            val stride = N / len
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * stride]
                    val wi = -sinT[k * stride]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Copies the frame that matches what is audible right now into [into].
     * Returns false when nothing fresh is available (paused, another format) — the caller fades out.
     */
    fun read(into: FloatArray): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        var i = head
        repeat(QUEUE) {
            val t = showAt[i]
            if (t != 0L && t <= now) {
                if (now - t > 400) return false
                System.arraycopy(frames[i], 0, into, 0, BANDS)
                return true
            }
            i = (i - 1 + QUEUE) % QUEUE
        }
        return false
    }
}
