package app.vesper.player.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User's own folders for playlists ("Тренировки", "Для мамы"…): playlist id → folder name. */
object PlaylistFolders {
    private const val PREFS = "floya_playlist_folders"
    private val _map = MutableStateFlow<Map<Long, String>>(emptyMap())
    val map: StateFlow<Map<Long, String>> = _map.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs(PREFS), Context.MODE_PRIVATE)
        _map.value = p.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> (v as? String)?.let { id to it } } }.toMap()
    }

    /** Current choices (loads them if needed). */
    fun all(context: Context): Map<Long, String> {
        load(context)
        return _map.value
    }

    /** Replaces every choice at once (used when the account's cloud copy is applied). */
    fun replaceAll(context: Context, values: Map<Long, String>) {
        loaded = true
        context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().clear()
            .apply { values.forEach { (k, v) -> putString(k.toString(), v) } }.apply()
        _map.value = values
    }

    fun set(context: Context, playlistId: Long, folder: String?) {
        val name = folder?.trim()?.takeIf { it.isNotEmpty() }
        context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs(PREFS), Context.MODE_PRIVATE).edit()
            .apply { if (name == null) remove(playlistId.toString()) else putString(playlistId.toString(), name) }.apply()
        _map.value = _map.value.toMutableMap().apply { if (name == null) remove(playlistId) else put(playlistId, name) }
    }
}
