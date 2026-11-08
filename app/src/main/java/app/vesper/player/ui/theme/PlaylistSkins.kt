package app.vesper.player.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which living picture is drawn at the top of a themed playlist. */
enum class SceneKind { DARK_FANTASY, NATURE, OCEAN, COSMOS, SAKURA, SYNTHWAVE, NOIR, WINTER, FIRE, DESERT }

/**
 * A playlist's own look, independent of the app theme: palette, fonts and shapes (as a full
 * [VesperTheme], so every list row and button follows it), plus an animated scene and particles.
 */
data class PlaylistSkin(
    val id: String,
    val name: String,
    val tagline: String,
    val scene: SceneKind,
    val theme: VesperTheme,
)

object PlaylistSkins {

    private fun palette(
        bg: Long, surface: Long, text: Long, accent: Long, accentBright: Long, ornament: Long,
        dark: Boolean = true,
    ): VesperPalette {
        val b = Color(bg)
        val s = Color(surface)
        val t = Color(text)
        val a = Color(accent)
        val ab = Color(accentBright)
        return VesperPalette(
            isDark = dark,
            background = b,
            backgroundAlt = lerp(b, s, 0.5f),
            surface = s,
            surfaceHigh = lerp(s, t, if (dark) 0.07f else 0.05f),
            outline = lerp(s, t, 0.16f),
            textPrimary = t,
            textSecondary = lerp(t, b, 0.3f),
            textMuted = lerp(t, b, 0.52f),
            accent = a,
            accentBright = ab,
            onAccent = if (dark) Color(0xFFFFF8F0) else Color.White,
            ornament = Color(ornament),
            highlight = lerp(b, a, 0.2f),
            progressTrack = lerp(s, t, 0.12f),
            progressStart = a,
            progressEnd = ab,
            danger = Color(0xFFE5566A),
        )
    }

    private fun skin(
        id: String, name: String, tagline: String, scene: SceneKind, base: VesperTheme,
        palette: VesperPalette, ornament: Ornament, type: VesperTypeSpec = base.type,
    ) = PlaylistSkin(id, name, tagline, scene, base.copy(id = "pl_$id", name = name, palette = palette, ornament = ornament, type = type))

    val darkFantasy = skin(
        "dark_fantasy", "Тёмное фэнтези", "Замок под луной, вороны и угли",
        SceneKind.DARK_FANTASY, ThemeRegistry.gothic,
        palette(0xFF0B070C, 0xFF171018, 0xFFEDE1CF, 0xFF8E1F36, 0xFFD25A70, 0xFFC9A45C),
        Ornament.GOTHIC,
    )
    val nature = skin(
        "nature", "Природа", "Горы, сосновый лес и рассвет",
        SceneKind.NATURE, ThemeRegistry.forest,
        palette(0xFF0C1410, 0xFF15211A, 0xFFE6EFE2, 0xFF3F8A55, 0xFF8FD18A, 0xFFE8C77A),
        Ornament.FOREST,
    )
    val ocean = skin(
        "ocean", "Океан", "Волны, глубина и лунная дорожка",
        SceneKind.OCEAN, ThemeRegistry.midnight,
        palette(0xFF061219, 0xFF0D1F2A, 0xFFE0F2F7, 0xFF1C7C9A, 0xFF5FD1E8, 0xFF9FE6F2),
        Ornament.MINIMAL,
    )
    val cosmos = skin(
        "cosmos", "Космос", "Туманности, планеты и звездопад",
        SceneKind.COSMOS, ThemeRegistry.midnight,
        palette(0xFF07061A, 0xFF120F2B, 0xFFE9E6FF, 0xFF6A4BD8, 0xFFB59BFF, 0xFF8FD3FF),
        Ornament.MIDNIGHT,
    )
    val sakura = skin(
        "sakura", "Сакура", "Розовое небо и летящие лепестки",
        SceneKind.SAKURA, ThemeRegistry.rose,
        palette(0xFF1A0E14, 0xFF27161F, 0xFFFBE9F0, 0xFFD45C8A, 0xFFFFA6C6, 0xFFF4D7A6),
        Ornament.ROSE,
    )
    val synthwave = skin(
        "synthwave", "Синтвейв", "Ретро-закат, неон и бесконечная сетка",
        SceneKind.SYNTHWAVE, ThemeRegistry.cyberpunk,
        palette(0xFF0D0618, 0xFF1A0E2C, 0xFFF8E9FF, 0xFFE0379C, 0xFF35E0F0, 0xFFFFC857),
        Ornament.NEON,
    )
    val noir = skin(
        "noir", "Дождливый нуар", "Ночной город, ливень и тёплые окна",
        SceneKind.NOIR, ThemeRegistry.midnight,
        palette(0xFF0B0C0F, 0xFF16181D, 0xFFE4E2DC, 0xFFB5833B, 0xFFE8B865, 0xFF8C96A8),
        Ornament.MINIMAL,
    )
    val winter = skin(
        "winter", "Зима", "Снегопад над тихими холмами",
        SceneKind.WINTER, ThemeRegistry.midnight,
        palette(0xFF0C1420, 0xFF162132, 0xFFEEF5FF, 0xFF4F86C6, 0xFFA9D2FF, 0xFFDDE9F7),
        Ornament.MIDNIGHT,
    )
    val fire = skin(
        "fire", "Пламя", "Вулкан, лава и летящие искры",
        SceneKind.FIRE, ThemeRegistry.gothic,
        palette(0xFF110705, 0xFF1F0E09, 0xFFFFEDDC, 0xFFD1431B, 0xFFFF9A3C, 0xFFFFC75A),
        Ornament.GOTHIC,
        type = ThemeRegistry.gothic.type.copy(displayWeight = FontWeight.Bold),
    )
    val desert = skin(
        "desert", "Пустыня", "Дюны на закате и первые звёзды",
        SceneKind.DESERT, ThemeRegistry.rose,
        palette(0xFF160C10, 0xFF241419, 0xFFFFEEDD, 0xFFD9772F, 0xFFFFB766, 0xFFF2CF8D),
        Ornament.MINIMAL,
    )

    val all: List<PlaylistSkin> = listOf(darkFantasy, nature, ocean, cosmos, sakura, synthwave, noir, winter, fire, desert)

    fun byId(id: String?): PlaylistSkin? = all.firstOrNull { it.id == id }

    // ---- per-playlist choice (stored on the device, no database change needed) --------------

    private const val PREFS = "floya_playlist_skins"
    private val _choices = MutableStateFlow<Map<Long, String>>(emptyMap())
    val choices: StateFlow<Map<Long, String>> = _choices.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs(PREFS), Context.MODE_PRIVATE)
        _choices.value = p.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> (v as? String)?.let { id to it } } }.toMap()
    }

    /** Current choices (loads them if needed). */
    fun all(context: Context): Map<Long, String> {
        load(context)
        return _choices.value
    }

    /** Replaces every choice at once (used when the account's cloud copy is applied). */
    fun replaceAll(context: Context, values: Map<Long, String>) {
        loaded = true
        context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().clear()
            .apply { values.forEach { (k, v) -> putString(k.toString(), v) } }.apply()
        _choices.value = values
    }

    fun set(context: Context, playlistId: Long, skinId: String?) {
        val p = context.getSharedPreferences(app.vesper.player.data.account.Profile.prefs(PREFS), Context.MODE_PRIVATE)
        p.edit().apply { if (skinId == null) remove(playlistId.toString()) else putString(playlistId.toString(), skinId) }.apply()
        _choices.value = _choices.value.toMutableMap().apply { if (skinId == null) remove(playlistId) else put(playlistId, skinId) }
    }
}
