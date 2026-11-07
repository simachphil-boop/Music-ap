package app.floya.desktop.audio

import app.floya.desktop.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Plays any file or internet stream FFmpeg can read (MP3, M4A, FLAC, OGG/Opus, WAV, YouTube links…).
 * FFmpeg decodes to raw PCM, Floya writes it to the sound card itself — so it can apply its own
 * volume *above* 100 % (with a soft limiter instead of harsh clipping) and measure the loudness
 * that drives the animations.
 *
 * Pause stops the decoder and remembers the position; resume starts it again from there.
 * That keeps internet streams from timing out during long pauses.
 */
class AudioEngine(
    private val onEnded: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    /** Smoothed loudness 0..1 of what's playing right now — for visual effects. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile var volume: Float = 0.8f
    /** 1.0 = normal, up to ~2.8 = +15 dB. */
    @Volatile var boost: Float = 1f

    private var source: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var session: Session? = null
    private val lock = Any()

    fun load(src: String, hdrs: Map<String, String>, startMs: Long, autoplay: Boolean) {
        synchronized(lock) {
            session?.stop()
            session = null
            source = src
            headers = hdrs
            _position.value = startMs
            if (autoplay) start(startMs) else _playing.value = false
        }
    }

    fun play() = synchronized(lock) {
        if (source != null && session == null) start(_position.value)
    }

    fun pause() = synchronized(lock) {
        session?.stop()
        session = null
        _playing.value = false
        _loading.value = false
        _level.value = 0f
    }

    fun toggle() = if (_playing.value || session != null) pause() else play()

    fun seek(ms: Long) = synchronized(lock) {
        val target = ms.coerceAtLeast(0)
        _position.value = target
        if (session != null) {
            session?.stop()
            start(target)
        }
    }

    fun stop() = synchronized(lock) {
        session?.stop()
        session = null
        source = null
        _playing.value = false
        _loading.value = false
        _position.value = 0
        _level.value = 0f
    }

    private fun start(fromMs: Long) {
        val src = source ?: return
        val s = Session(src, headers, fromMs)
        session = s
        _playing.value = true
        _loading.value = true
        s.thread.start()
    }

    private inner class Session(val src: String, val hdrs: Map<String, String>, val fromMs: Long) {
        @Volatile var stopped = false
        private var process: Process? = null
        private var line: SourceDataLine? = null

        val thread = Thread({ run() }, "floya-audio").apply { isDaemon = true }

        fun stop() {
            stopped = true
            runCatching { process?.destroyForcibly() }
            runCatching { line?.stop(); line?.flush() }
        }

        private fun run() {
            val online = src.startsWith("http")
            val cmd = buildList {
                add(Paths.ffmpeg)
                addAll(listOf("-hide_banner", "-loglevel", "error", "-nostdin"))
                if (online) {
                    addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "4"))
                    hdrs["User-Agent"]?.let { addAll(listOf("-user_agent", it)) }
                    val extra = hdrs.filterKeys { it != "User-Agent" }
                    if (extra.isNotEmpty()) addAll(listOf("-headers", extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }))
                }
                if (fromMs > 0) addAll(listOf("-ss", "%.3f".format(java.util.Locale.US, fromMs / 1000.0)))
                addAll(listOf("-i", src, "-vn", "-sn", "-ac", "2", "-ar", RATE.toString(), "-f", "s16le", "pipe:1"))
            }
            var produced = false
            try {
                val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start()
                process = p
                val errText = StringBuilder()
                Thread {
                    runCatching { p.errorStream.bufferedReader().forEachLine { if (errText.length < 2000) errText.appendLine(it) } }
                }.apply { isDaemon = true }.start()

                val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
                val l = AudioSystem.getSourceDataLine(format)
                l.open(format, RATE * 4 / 4) // ~250 ms buffer: quick reaction to pause/seek
                line = l
                l.start()
                val input = p.inputStream
                val buf = ByteArray(8192)
                var smooth = 0f
                while (!stopped) {
                    var n = input.read(buf)
                    if (n < 0) break
                    if (n % 4 != 0) {
                        // keep whole stereo frames
                        val extra = 4 - n % 4
                        var got = 0
                        while (got < extra) {
                            val r = input.read(buf, n + got, extra - got)
                            if (r < 0) break
                            got += r
                        }
                        n += got
                    }
                    if (!produced) {
                        produced = true
                        _loading.value = false
                    }
                    val rms = process(buf, n)
                    smooth = smooth * 0.8f + rms * 0.2f
                    _level.value = smooth.coerceIn(0f, 1f)
                    l.write(buf, 0, n - n % 4)
                    _position.value = fromMs + l.longFramePosition * 1000L / RATE
                }
                if (!stopped) {
                    l.drain()
                    _position.value = fromMs + l.longFramePosition * 1000L / RATE
                }
                l.close()
                val code = runCatching { p.waitFor() }.getOrDefault(0)
                if (!stopped) {
                    synchronized(lock) { if (session === this) session = null }
                    _playing.value = false
                    _loading.value = false
                    _level.value = 0f
                    if (!produced && code != 0) {
                        onError(errText.toString().lines().lastOrNull { it.isNotBlank() } ?: "Не удалось воспроизвести")
                    } else {
                        onEnded()
                    }
                }
            } catch (e: Exception) {
                if (!stopped) {
                    synchronized(lock) { if (session === this) session = null }
                    _playing.value = false
                    _loading.value = false
                    onError(
                        if (e is java.io.IOException && e.message?.contains("ffmpeg", true) == true) "Не найден ffmpeg — переустановите Floya"
                        else e.message ?: "Ошибка воспроизведения",
                    )
                }
            } finally {
                runCatching { process?.destroyForcibly() }
                runCatching { line?.close() }
            }
        }

        /** Applies volume and boost in place, returns loudness 0..1 of the chunk. */
        private fun process(buf: ByteArray, n: Int): Float {
            val gain = volume.coerceIn(0f, 1f).pow(1.7f) * boost.coerceIn(1f, 3f)
            val limit = gain > 1f
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort()
                var v = s / 32768f
                sum += (v * v).toDouble()
                v *= gain
                if (limit && abs(v) > KNEE) {
                    // Soft limiter: loud peaks are rounded off instead of cracking.
                    val sign = if (v < 0) -1f else 1f
                    v = sign * (KNEE + (1f - KNEE) * tanh((abs(v) - KNEE) / (1f - KNEE)))
                }
                val out = (v * 32767f).toInt().coerceIn(-32768, 32767)
                buf[i] = (out and 0xff).toByte()
                buf[i + 1] = ((out shr 8) and 0xff).toByte()
                i += 2
            }
            val samples = n / 2
            return if (samples == 0) 0f else (sqrt(sum / samples) * 3.2).toFloat()
        }
    }

    companion object {
        private const val RATE = 44100
        private const val KNEE = 0.8f
    }
}
