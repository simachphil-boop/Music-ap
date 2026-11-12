package app.vesper.player.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vesper_settings")

enum class SortField { TITLE, ARTIST, DATE_ADDED, DURATION, ALBUM }

data class SortOrder(val field: SortField = SortField.TITLE, val ascending: Boolean = true) {
    fun encode(): String = "${field.name}:${if (ascending) 1 else 0}"

    companion object {
        fun decode(raw: String?, default: SortOrder): SortOrder {
            if (raw.isNullOrBlank()) return default
            val parts = raw.split(":")
            val field = runCatching { SortField.valueOf(parts[0]) }.getOrNull() ?: return default
            return SortOrder(field, parts.getOrNull(1) != "0")
        }
    }
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: Int = -1,
    val bandLevels: List<Int> = emptyList(),
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    /** Extra loudness on top of the system volume, in millibels (0 = off, 1500 = +15 dB). Works even with the EQ off. */
    val volumeBoost: Int = 0,
)

data class AppSettings(
    val themeId: String = "gothic",
    /** 0 = always the chosen theme, 1 = follow the system (light by day, chosen dark theme at night), 2 = always light. */
    val themeMode: Int = 0,
    /** Folders (as shown in the "Папки" tab) the user switched off — their tracks are hidden, never deleted. */
    val excludedFolders: Set<String> = emptySet(),
    /** Show one copy when the same song exists several times (same title, artist, length). */
    val hideDuplicates: Boolean = true,
    val musicSort: SortOrder = SortOrder(),
    val videoSort: SortOrder = SortOrder(SortField.DATE_ADDED, ascending = false),
    val pauseOnDisconnect: Boolean = true,
    val resumeOnConnect: Boolean = false,
    val skipShortAudio: Boolean = true,
    val artworkTint: Boolean = true,
    val autoRescan: Boolean = true,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val onboardingDone: Boolean = false,
    val videoSpeed: Float = 1f,
    /** Animated themed background (embers and bats, stars, petals…). */
    val ambience: Boolean = true,
    val visualizer: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val themeMode = intPreferencesKey("theme_mode")
        val excludedFolders = androidx.datastore.preferences.core.stringSetPreferencesKey("excluded_folders")
        val hideDuplicates = booleanPreferencesKey("hide_duplicates")
        val musicSort = stringPreferencesKey("music_sort")
        val videoSort = stringPreferencesKey("video_sort")
        val pauseOnDisconnect = booleanPreferencesKey("pause_on_disconnect")
        val resumeOnConnect = booleanPreferencesKey("resume_on_connect")
        val skipShort = booleanPreferencesKey("skip_short_audio")
        val artworkTint = booleanPreferencesKey("artwork_tint")
        val autoRescan = booleanPreferencesKey("auto_rescan")
        val shuffle = booleanPreferencesKey("shuffle")
        val repeatMode = intPreferencesKey("repeat_mode")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = intPreferencesKey("eq_preset")
        val eqBands = stringPreferencesKey("eq_bands")
        val bassBoost = intPreferencesKey("bass_boost")
        val virtualizer = intPreferencesKey("virtualizer")
        val volumeBoost = intPreferencesKey("volume_boost")
        val ambience = booleanPreferencesKey("ambience")
        val visualizer = booleanPreferencesKey("visualizer")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val videoSpeed = stringPreferencesKey("video_speed")
        val queueIndex = intPreferencesKey("queue_index")
        val queuePosition = longPreferencesKey("queue_position")
        val originalOrder = stringPreferencesKey("shuffle_original_order")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeId = p[Keys.theme] ?: "gothic",
            themeMode = p[Keys.themeMode] ?: 0,
            excludedFolders = p[Keys.excludedFolders] ?: emptySet(),
            hideDuplicates = p[Keys.hideDuplicates] ?: true,
            musicSort = SortOrder.decode(p[Keys.musicSort], SortOrder()),
            videoSort = SortOrder.decode(p[Keys.videoSort], SortOrder(SortField.DATE_ADDED, false)),
            pauseOnDisconnect = p[Keys.pauseOnDisconnect] ?: true,
            resumeOnConnect = p[Keys.resumeOnConnect] ?: false,
            skipShortAudio = p[Keys.skipShort] ?: true,
            artworkTint = p[Keys.artworkTint] ?: true,
            autoRescan = p[Keys.autoRescan] ?: true,
            shuffle = p[Keys.shuffle] ?: false,
            repeatMode = p[Keys.repeatMode] ?: 0,
            equalizer = EqualizerSettings(
                enabled = p[Keys.eqEnabled] ?: false,
                preset = p[Keys.eqPreset] ?: -1,
                bandLevels = p[Keys.eqBands]?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList(),
                bassBoost = p[Keys.bassBoost] ?: 0,
                virtualizer = p[Keys.virtualizer] ?: 0,
                volumeBoost = p[Keys.volumeBoost] ?: 0,
            ),
            ambience = p[Keys.ambience] ?: true,
            visualizer = p[Keys.visualizer] ?: true,
            onboardingDone = p[Keys.onboarding] ?: false,
            videoSpeed = p[Keys.videoSpeed]?.toFloatOrNull() ?: 1f,
        )
    }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setTheme(id: String) = context.dataStore.edit { it[Keys.theme] = id }
    suspend fun setThemeMode(mode: Int) = context.dataStore.edit { it[Keys.themeMode] = mode }
    suspend fun setFolderIncluded(folder: String, included: Boolean) = context.dataStore.edit {
        val cur = it[Keys.excludedFolders] ?: emptySet()
        it[Keys.excludedFolders] = if (included) cur - folder else cur + folder
    }
    suspend fun setHideDuplicates(v: Boolean) = context.dataStore.edit { it[Keys.hideDuplicates] = v }
    suspend fun setMusicSort(order: SortOrder) = context.dataStore.edit { it[Keys.musicSort] = order.encode() }
    suspend fun setVideoSort(order: SortOrder) = context.dataStore.edit { it[Keys.videoSort] = order.encode() }
    suspend fun setPauseOnDisconnect(v: Boolean) = context.dataStore.edit { it[Keys.pauseOnDisconnect] = v }
    suspend fun setResumeOnConnect(v: Boolean) = context.dataStore.edit { it[Keys.resumeOnConnect] = v }
    suspend fun setSkipShortAudio(v: Boolean) = context.dataStore.edit { it[Keys.skipShort] = v }
    suspend fun setArtworkTint(v: Boolean) = context.dataStore.edit { it[Keys.artworkTint] = v }
    suspend fun setAutoRescan(v: Boolean) = context.dataStore.edit { it[Keys.autoRescan] = v }
    suspend fun setShuffle(v: Boolean) = context.dataStore.edit { it[Keys.shuffle] = v }
    suspend fun setRepeatMode(v: Int) = context.dataStore.edit { it[Keys.repeatMode] = v }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboarding] = true }
    suspend fun setVideoSpeed(v: Float) = context.dataStore.edit { it[Keys.videoSpeed] = v.toString() }

    suspend fun setEqualizerEnabled(v: Boolean) = context.dataStore.edit { it[Keys.eqEnabled] = v }
    suspend fun setEqualizerPreset(preset: Int, levels: List<Int>) = context.dataStore.edit {
        it[Keys.eqPreset] = preset
        it[Keys.eqBands] = levels.joinToString(",")
    }
    suspend fun setBassBoost(v: Int) = context.dataStore.edit { it[Keys.bassBoost] = v }
    suspend fun setVirtualizer(v: Int) = context.dataStore.edit { it[Keys.virtualizer] = v }
    suspend fun setVolumeBoost(v: Int) = context.dataStore.edit { it[Keys.volumeBoost] = v }
    suspend fun setAmbience(v: Boolean) = context.dataStore.edit { it[Keys.ambience] = v }
    suspend fun setVisualizer(v: Boolean) = context.dataStore.edit { it[Keys.visualizer] = v }

    // --- Queue position (written frequently by the playback service) ---
    suspend fun saveQueuePosition(index: Int, positionMs: Long) = context.dataStore.edit {
        it[Keys.queueIndex] = index
        it[Keys.queuePosition] = positionMs
    }

    suspend fun queuePosition(): Pair<Int, Long> {
        val p = context.dataStore.data.first()
        return (p[Keys.queueIndex] ?: 0) to (p[Keys.queuePosition] ?: 0L)
    }

    suspend fun setOriginalOrder(ids: List<Long>) = context.dataStore.edit {
        it[Keys.originalOrder] = ids.joinToString(",")
    }

    suspend fun originalOrder(): List<Long> =
        context.dataStore.data.first()[Keys.originalOrder]
            ?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
}
