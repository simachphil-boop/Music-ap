package app.vesper.player.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class StudioPreset(
    val title: String,
    val subtitle: String,
    val speed: Float,
    val pitch: Float,
    val reverb: Float,
    val eightD: Boolean,
) {
    NORMAL("Обычный", "Как записано", 1f, 1f, 0f, false),
    SLOWED("Slowed + Reverb", "Медленнее, ниже, с эхом", 0.82f, 0.82f, 0.45f, false),
    NIGHTCORE("Nightcore", "Быстрее и выше", 1.25f, 1.25f, 0f, false),
    SPEED_UP("Speed Up", "Быстрее, тон почти тот же", 1.18f, 1.06f, 0f, false),
    EIGHT_D("8D", "Звук кружит вокруг головы", 1f, 1f, 0.2f, true),
    DREAMY("Dreamy", "Медленно, глубоко и в 8D", 0.88f, 0.9f, 0.55f, true),
}

data class StudioState(
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val reverb: Float = 0f,
    val eightD: Boolean = false,
    val preset: StudioPreset? = StudioPreset.NORMAL,
) {
    val isNormal: Boolean get() = speed == 1f && pitch == 1f && reverb == 0f && !eightD
    val label: String get() = preset?.takeIf { it != StudioPreset.NORMAL }?.title ?: if (isNormal) "" else "Свой эффект"
}

/** Real-time sound effects for music: speed/pitch (player), reverb and 8D (custom processor). */
class AudioStudio {
    val processor = StudioProcessor()
    private val _state = MutableStateFlow(StudioState())
    val state: StateFlow<StudioState> = _state.asStateFlow()

    fun applyPreset(p: StudioPreset) = set(StudioState(p.speed, p.pitch, p.reverb, p.eightD, p))

    fun set(s: StudioState) {
        _state.value = s
        processor.reverbMix = s.reverb.coerceIn(0f, 1f)
        processor.eightD = s.eightD
    }

    fun update(transform: (StudioState) -> StudioState) = set(transform(_state.value).copy(preset = null))

    /** FFmpeg audio filter that reproduces the current effect when saving to a file. */
    fun ffmpegFilter(s: StudioState): String {
        val parts = mutableListOf("aresample=44100")
        if (s.pitch != 1f) {
            parts += "asetrate=${(44100 * s.pitch).roundToInt()}"
            parts += "aresample=44100"
        }
        val tempo = s.speed / s.pitch
        if (tempo != 1f) parts += "atempo=${"%.4f".format(java.util.Locale.US, tempo.coerceIn(0.5f, 2f))}"
        if (s.reverb > 0f) {
            val d = (0.18f + s.reverb * 0.4f).coerceAtMost(0.6f)
            parts += "aecho=0.85:0.8:43|71|113|157:${"%.2f|%.2f|%.2f|%.2f".format(java.util.Locale.US, d, d * 0.8f, d * 0.6f, d * 0.45f)}"
        }
        if (s.eightD) parts += "apulsator=hz=0.125:amount=0.85"
        return parts.joinToString(",")
    }
}

/**
 * Lightweight PCM16 stereo processor: Schroeder reverb (4 combs + 2 all-passes per channel)
 * and an "8D" constant-power auto-panner. Pass-through when both are off.
 */
class StudioProcessor : BaseAudioProcessor() {
    @Volatile var reverbMix: Float = 0f
    @Volatile var eightD: Boolean = false

    private var sampleRate = 44100
    private var phase = 0.0
    private var combL: Array<Comb> = emptyArray()
    private var combR: Array<Comb> = emptyArray()
    private var apL: Array<AllPass> = emptyArray()
    private var apR: Array<AllPass> = emptyArray()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        buildFilters()
        return inputAudioFormat
    }

    private fun buildFilters() {
        fun ms(v: Double) = (v * sampleRate / 1000.0).toInt().coerceAtLeast(1)
        combL = arrayOf(Comb(ms(29.7)), Comb(ms(37.1)), Comb(ms(41.1)), Comb(ms(43.7)))
        combR = arrayOf(Comb(ms(30.9)), Comb(ms(36.3)), Comb(ms(42.2)), Comb(ms(44.9)))
        apL = arrayOf(AllPass(ms(5.0)), AllPass(ms(1.7)))
        apR = arrayOf(AllPass(ms(5.3)), AllPass(ms(1.9)))
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (SpectrumTap.active) SpectrumTap.feed(inputBuffer, sampleRate)
        val out = replaceOutputBuffer(remaining)
        val mix = reverbMix
        val spin = eightD
        if (mix <= 0f && !spin) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        out.order(ByteOrder.nativeOrder())
        val step = 2.0 * PI * 0.11 / sampleRate
        val wet = mix * 0.9f
        val dry = 1f - mix * 0.35f
        while (input.remaining() >= 4) {
            var l = input.short / 32768f
            var r = input.short / 32768f
            if (mix > 0f) {
                val inL = l * 0.3f
                val inR = r * 0.3f
                var revL = 0f
                var revR = 0f
                for (c in combL) revL += c.process(inL)
                for (c in combR) revR += c.process(inR)
                for (a in apL) revL = a.process(revL)
                for (a in apR) revR = a.process(revR)
                l = l * dry + revL * wet
                r = r * dry + revR * wet
            }
            if (spin) {
                val pan = sin(phase).toFloat()
                phase += step
                if (phase > 2 * PI) phase -= 2 * PI
                val mono = (l + r) * 0.5f
                val gl = sqrt((1f - pan) / 2f) * 1.25f
                val gr = sqrt((1f + pan) / 2f) * 1.25f
                l = mono * gl * 0.75f + l * 0.25f
                r = mono * gr * 0.75f + r * 0.25f
            }
            out.putShort((l.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.putShort((r.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        // Skip a dangling odd byte, if any.
        if (input.hasRemaining()) input.position(input.limit())
        out.flip()
    }

    override fun onFlush() {
        combL.forEach { it.clear() }
        combR.forEach { it.clear() }
        apL.forEach { it.clear() }
        apR.forEach { it.clear() }
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size)
        private var i = 0
        private var store = 0f
        fun process(x: Float): Float {
            val y = buf[i]
            store = y * 0.8f + store * 0.2f
            buf[i] = x + store * 0.8f
            i = (i + 1) % buf.size
            return y
        }
        fun clear() {
            buf.fill(0f)
            store = 0f
        }
    }

    private class AllPass(size: Int) {
        private val buf = FloatArray(size)
        private var i = 0
        fun process(x: Float): Float {
            val b = buf[i]
            val y = -x + b
            buf[i] = x + b * 0.5f
            i = (i + 1) % buf.size
            return y
        }
        fun clear() = buf.fill(0f)
    }
}

/** Inserts [StudioProcessor] into ExoPlayer's audio chain. */
class StudioRenderersFactory(context: Context, private val processor: StudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(processor))
            .build()
}
