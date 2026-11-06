package app.vesper.player.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import app.vesper.player.data.prefs.EqualizerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqualizerInfo(
    val bandCount: Int,
    val centerFrequenciesHz: List<Int>,
    val minLevel: Int,
    val maxLevel: Int,
    val presets: List<String>,
    val bassBoostSupported: Boolean,
    val virtualizerSupported: Boolean,
)

/**
 * Wraps the platform audio effects attached to the music player's audio session.
 * The playback service attaches it; the settings UI reads [info] and writes via SettingsRepository.
 */
class AudioEffects {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var sessionId: Int = 0
    private var lastSettings: EqualizerSettings = EqualizerSettings()

    private val _info = MutableStateFlow<EqualizerInfo?>(null)
    val info: StateFlow<EqualizerInfo?> = _info.asStateFlow()

    @Synchronized
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        // Loudness boost is independent of the equalizer, so it is created even if the EQ isn't available.
        enhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        applyBoost(lastSettings.volumeBoost)
        try {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            val presets = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
            bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
            virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()
            _info.value = EqualizerInfo(
                bandCount = bands,
                centerFrequenciesHz = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 },
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                presets = presets,
                bassBoostSupported = bassBoost?.strengthSupported == true,
                virtualizerSupported = virtualizer?.strengthSupported == true,
            )
            apply(lastSettings)
        } catch (e: Throwable) {
            Log.w("AudioEffects", "Equalizer unavailable", e)
            runCatching { equalizer?.release() }
            runCatching { bassBoost?.release() }
            runCatching { virtualizer?.release() }
            equalizer = null
            bassBoost = null
            virtualizer = null
            _info.value = null
        }
    }

    /** Band levels for a built-in preset (used to show the curve when a preset is picked). */
    @Synchronized
    fun presetLevels(preset: Int): List<Int>? {
        val eq = equalizer ?: return null
        return runCatching {
            val wasEnabled = eq.enabled
            eq.usePreset(preset.toShort())
            val levels = (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }
            eq.setEnabled(wasEnabled)
            levels
        }.getOrNull()
    }

    /** Instant boost change while the user drags the slider. */
    @Synchronized
    fun setBoost(millibels: Int) {
        lastSettings = lastSettings.copy(volumeBoost = millibels)
        applyBoost(millibels)
    }

    private fun applyBoost(millibels: Int) {
        val e = enhancer ?: return
        runCatching {
            val gain = millibels.coerceIn(0, MAX_BOOST_MB)
            e.setTargetGain(gain)
            e.setEnabled(gain > 0)
        }
    }

    @Synchronized
    fun apply(settings: EqualizerSettings) {
        lastSettings = settings
        applyBoost(settings.volumeBoost)
        val eq = equalizer ?: return
        runCatching {
            eq.setEnabled(settings.enabled)
            if (settings.enabled) {
                if (settings.bandLevels.size == eq.numberOfBands.toInt()) {
                    settings.bandLevels.forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level.toShort()) }
                } else if (settings.preset >= 0 && settings.preset < eq.numberOfPresets) {
                    eq.usePreset(settings.preset.toShort())
                }
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                bb.setEnabled(settings.enabled && settings.bassBoost > 0)
                if (bb.strengthSupported) bb.setStrength(settings.bassBoost.coerceIn(0, 1000).toShort())
            }
        }
        virtualizer?.let { v ->
            runCatching {
                v.setEnabled(settings.enabled && settings.virtualizer > 0)
                if (v.strengthSupported) v.setStrength(settings.virtualizer.coerceIn(0, 1000).toShort())
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { enhancer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        enhancer = null
        sessionId = 0
    }

    companion object {
        /** +15 dB ≈ 2.8× as loud by ear. More than that mostly adds distortion. */
        const val MAX_BOOST_MB = 1500
    }
}
