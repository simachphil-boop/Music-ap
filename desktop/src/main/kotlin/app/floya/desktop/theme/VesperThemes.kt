package app.floya.desktop.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Visual motif drawn by ornaments, placeholders and the player frame. */
enum class Ornament { GOTHIC, MIDNIGHT, NEON, MINIMAL, ROSE, FOREST, NONE }

@Immutable
data class VesperPalette(
    val isDark: Boolean,
    val background: Color,
    val backgroundAlt: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentBright: Color,
    val onAccent: Color,
    val ornament: Color,
    val highlight: Color,
    val progressTrack: Color,
    val progressStart: Color,
    val progressEnd: Color,
    val danger: Color,
)

@Immutable
data class VesperShapesSpec(
    val small: Int,
    val medium: Int,
    val large: Int,
    val artwork: Int,
    /** Cut (bevelled) corners instead of rounded ones — used by Cyberpunk. */
    val cut: Boolean = false,
    /** Circular play button vs. themed shape. */
    val roundPlayButton: Boolean = true,
)

@Immutable
data class VesperTypeSpec(
    val display: FontFamily,
    val displayWeight: FontWeight,
    val displayItalic: Boolean = false,
    val labelUppercase: Boolean = false,
    val labelTracking: Float = 0.04f,
    val displayTracking: Float = 0f,
    /** Optical size compensation — Cormorant has a small x-height. */
    val displayScale: Float = 1f,
)

@Immutable
data class VesperTheme(
    val id: String,
    val name: String,
    val tagline: String,
    val palette: VesperPalette,
    val shapes: VesperShapesSpec,
    val type: VesperTypeSpec,
    val ornament: Ornament,
)

object Fonts {
    @Suppress("DEPRECATION")
    private fun f(name: String, weight: FontWeight, style: FontStyle = FontStyle.Normal) =
        androidx.compose.ui.text.platform.Font("fonts/$name.ttf", weight, style)

    val manrope = FontFamily(
        f("manrope", FontWeight.Normal),
        f("manrope", FontWeight.Medium),
        f("manrope", FontWeight.SemiBold),
        f("manrope", FontWeight.Bold),
        f("manrope", FontWeight.ExtraBold),
    )
    val cormorant = FontFamily(
        f("cormorant", FontWeight.Medium),
        f("cormorant", FontWeight.SemiBold),
        f("cormorant", FontWeight.Bold),
        f("cormorant_italic", FontWeight.Medium, FontStyle.Italic),
        f("cormorant_italic", FontWeight.SemiBold, FontStyle.Italic),
    )
    val chakra = FontFamily(
        f("chakra_medium", FontWeight.Medium),
        f("chakra_semibold", FontWeight.SemiBold),
        f("chakra_semibold", FontWeight.Bold),
    )
    val dmSerif = FontFamily(
        f("dmserif", FontWeight.Normal),
        f("dmserif_italic", FontWeight.Normal, FontStyle.Italic),
    )
}

/**
 * Registry of all themes. To add a theme, append a [VesperTheme] to [all] — every screen
 * reads colours, shapes and type exclusively from the active theme.
 */
object ThemeRegistry {

    val gothic = VesperTheme(
        id = "gothic",
        name = "Gothic Dark",
        tagline = "Чёрный бархат, винный акцент, пергамент",
        palette = VesperPalette(
            isDark = true,
            background = Color(0xFF0A0708),
            backgroundAlt = Color(0xFF140C0F),
            surface = Color(0xFF161013),
            surfaceHigh = Color(0xFF21171B),
            outline = Color(0xFF33242A),
            textPrimary = Color(0xFFEDE3D4),
            textSecondary = Color(0xFFB8A897),
            textMuted = Color(0xFF7C6D66),
            accent = Color(0xFF9E2B40),
            accentBright = Color(0xFFD0687B),
            onAccent = Color(0xFFF6ECE2),
            ornament = Color(0xFFB59A68),
            highlight = Color(0xFF2A1319),
            progressTrack = Color(0xFF2D2024),
            progressStart = Color(0xFF7A1C2E),
            progressEnd = Color(0xFFB4364D),
            danger = Color(0xFFE0566B),
        ),
        shapes = VesperShapesSpec(small = 4, medium = 6, large = 10, artwork = 4),
        type = VesperTypeSpec(
            display = Fonts.cormorant,
            displayWeight = FontWeight.SemiBold,
            labelUppercase = true,
            labelTracking = 0.14f,
            displayTracking = 0.01f,
            displayScale = 1.16f,
        ),
        ornament = Ornament.GOTHIC,
    )

    val midnight = VesperTheme(
        id = "midnight",
        name = "Midnight",
        tagline = "Ночной город, холодный неон фонарей",
        palette = VesperPalette(
            isDark = true,
            background = Color(0xFF060A13),
            backgroundAlt = Color(0xFF0A1222),
            surface = Color(0xFF0E1628),
            surfaceHigh = Color(0xFF162138),
            outline = Color(0xFF1F2C47),
            textPrimary = Color(0xFFE7EDF8),
            textSecondary = Color(0xFF9CAAC4),
            textMuted = Color(0xFF5F6C87),
            accent = Color(0xFF3F7FF0),
            accentBright = Color(0xFF82B2FF),
            onAccent = Color(0xFFF2F6FF),
            ornament = Color(0xFF2E528F),
            highlight = Color(0xFF12203D),
            progressTrack = Color(0xFF1A2540),
            progressStart = Color(0xFF3F7FF0),
            progressEnd = Color(0xFF7CC4FF),
            danger = Color(0xFFFF6B7D),
        ),
        shapes = VesperShapesSpec(small = 8, medium = 12, large = 18, artwork = 10),
        type = VesperTypeSpec(display = Fonts.manrope, displayWeight = FontWeight.ExtraBold, displayTracking = -0.02f),
        ornament = Ornament.MIDNIGHT,
    )

    val cyberpunk = VesperTheme(
        id = "cyberpunk",
        name = "Cyberpunk",
        tagline = "Неоновый фиолет и бирюза на чёрном",
        palette = VesperPalette(
            isDark = true,
            background = Color(0xFF07060B),
            backgroundAlt = Color(0xFF0D0A17),
            surface = Color(0xFF110D1D),
            surfaceHigh = Color(0xFF1A1430),
            outline = Color(0xFF2B2148),
            textPrimary = Color(0xFFEFEAFF),
            textSecondary = Color(0xFFA79FCB),
            textMuted = Color(0xFF6B6392),
            accent = Color(0xFFB14CFF),
            accentBright = Color(0xFF23E6D3),
            onAccent = Color(0xFF0A0412),
            ornament = Color(0xFF23E6D3),
            highlight = Color(0xFF1E1338),
            progressTrack = Color(0xFF221A3B),
            progressStart = Color(0xFFB14CFF),
            progressEnd = Color(0xFF23E6D3),
            danger = Color(0xFFFF4D8B),
        ),
        shapes = VesperShapesSpec(small = 4, medium = 8, large = 12, artwork = 10, cut = true, roundPlayButton = false),
        type = VesperTypeSpec(
            display = Fonts.chakra,
            displayWeight = FontWeight.SemiBold,
            labelUppercase = true,
            labelTracking = 0.16f,
            displayTracking = 0.02f,
        ),
        ornament = Ornament.NEON,
    )

    val minimalLight = VesperTheme(
        id = "minimal_light",
        name = "Minimal Light",
        tagline = "Тёплая бумага, графит и тишина",
        palette = VesperPalette(
            isDark = false,
            background = Color(0xFFF6F4F0),
            backgroundAlt = Color(0xFFEFEBE4),
            surface = Color(0xFFFFFFFF),
            surfaceHigh = Color(0xFFEAE5DD),
            outline = Color(0xFFDDD7CD),
            textPrimary = Color(0xFF1C1B19),
            textSecondary = Color(0xFF5E5A53),
            textMuted = Color(0xFF98928A),
            accent = Color(0xFF2E2C29),
            accentBright = Color(0xFF2E2C29),
            onAccent = Color(0xFFFAF8F4),
            ornament = Color(0xFFB8B0A3),
            highlight = Color(0xFFEBE5DB),
            progressTrack = Color(0xFFDDD7CD),
            progressStart = Color(0xFF2E2C29),
            progressEnd = Color(0xFF5E5A53),
            danger = Color(0xFFC0392B),
        ),
        shapes = VesperShapesSpec(small = 8, medium = 12, large = 16, artwork = 8),
        type = VesperTypeSpec(display = Fonts.manrope, displayWeight = FontWeight.Bold, displayTracking = -0.02f),
        ornament = Ornament.MINIMAL,
    )

    val rose = VesperTheme(
        id = "rose",
        name = "Rose",
        tagline = "Пыльная роза, бархатная полночь",
        palette = VesperPalette(
            isDark = true,
            background = Color(0xFF181013),
            backgroundAlt = Color(0xFF22161B),
            surface = Color(0xFF26191F),
            surfaceHigh = Color(0xFF331F28),
            outline = Color(0xFF45303A),
            textPrimary = Color(0xFFF7E8EB),
            textSecondary = Color(0xFFD3B2BA),
            textMuted = Color(0xFF94747D),
            accent = Color(0xFFD48A9E),
            accentBright = Color(0xFFEBB0C0),
            onAccent = Color(0xFF2A0F18),
            ornament = Color(0xFFA35672),
            highlight = Color(0xFF3A2029),
            progressTrack = Color(0xFF3B2630),
            progressStart = Color(0xFFA35672),
            progressEnd = Color(0xFFEBB0C0),
            danger = Color(0xFFFF7A8A),
        ),
        shapes = VesperShapesSpec(small = 10, medium = 16, large = 24, artwork = 18),
        type = VesperTypeSpec(display = Fonts.dmSerif, displayWeight = FontWeight.Normal, displayItalic = true),
        ornament = Ornament.ROSE,
    )

    val forest = VesperTheme(
        id = "forest",
        name = "Forest",
        tagline = "Хвоя, мох и утренний туман",
        palette = VesperPalette(
            isDark = true,
            background = Color(0xFF0A110D),
            backgroundAlt = Color(0xFF0F1913),
            surface = Color(0xFF132019),
            surfaceHigh = Color(0xFF1A2B21),
            outline = Color(0xFF24392C),
            textPrimary = Color(0xFFE5EEE2),
            textSecondary = Color(0xFFA4B9A6),
            textMuted = Color(0xFF6B816E),
            accent = Color(0xFF6DAF7D),
            accentBright = Color(0xFF94D0A1),
            onAccent = Color(0xFF07130B),
            ornament = Color(0xFF8E7C52),
            highlight = Color(0xFF1A3123),
            progressTrack = Color(0xFF203427),
            progressStart = Color(0xFF4F8F60),
            progressEnd = Color(0xFF94D0A1),
            danger = Color(0xFFE57373),
        ),
        shapes = VesperShapesSpec(small = 8, medium = 14, large = 20, artwork = 14),
        type = VesperTypeSpec(display = Fonts.dmSerif, displayWeight = FontWeight.Normal),
        ornament = Ornament.FOREST,
    )

    val amoled = VesperTheme(
        id = "amoled",
        name = "AMOLED Black",
        tagline = "Абсолютный чёрный, ни одного лишнего пикселя",
        palette = VesperPalette(
            isDark = true,
            background = Color(0xFF000000),
            backgroundAlt = Color(0xFF000000),
            surface = Color(0xFF0B0B0B),
            surfaceHigh = Color(0xFF151515),
            outline = Color(0xFF1E1E1E),
            textPrimary = Color(0xFFF2F2F2),
            textSecondary = Color(0xFFA0A0A0),
            textMuted = Color(0xFF5E5E5E),
            accent = Color(0xFFEDEDED),
            accentBright = Color(0xFFFFFFFF),
            onAccent = Color(0xFF000000),
            ornament = Color(0xFF353535),
            highlight = Color(0xFF111111),
            progressTrack = Color(0xFF222222),
            progressStart = Color(0xFFEDEDED),
            progressEnd = Color(0xFFEDEDED),
            danger = Color(0xFFFF5252),
        ),
        shapes = VesperShapesSpec(small = 6, medium = 10, large = 14, artwork = 6),
        type = VesperTypeSpec(display = Fonts.manrope, displayWeight = FontWeight.Bold, displayTracking = -0.02f),
        ornament = Ornament.NONE,
    )

    val all: List<VesperTheme> = listOf(gothic, midnight, cyberpunk, minimalLight, rose, forest, amoled)

    fun byId(id: String?): VesperTheme = all.firstOrNull { it.id == id } ?: gothic
}
