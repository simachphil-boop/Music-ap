package app.vesper.player.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

@Immutable
data class VesperShapes(
    val small: CornerBasedShape,
    val medium: CornerBasedShape,
    val large: CornerBasedShape,
    val artwork: CornerBasedShape,
    val playButton: CornerBasedShape,
    val chip: CornerBasedShape,
)

val LocalVesperTheme = staticCompositionLocalOf { ThemeRegistry.gothic }
val LocalPalette = compositionLocalOf { ThemeRegistry.gothic.palette }
val LocalVesperShapes = staticCompositionLocalOf { shapesFor(ThemeRegistry.gothic.shapes) }

/** Convenient accessors: `Vesper.colors.accent`, `Vesper.shapes.artwork`, ... */
object Vesper {
    val theme: VesperTheme
        @Composable get() = LocalVesperTheme.current
    val colors: VesperPalette
        @Composable get() = LocalPalette.current
    val shapes: VesperShapes
        @Composable get() = LocalVesperShapes.current
}

fun shapesFor(spec: VesperShapesSpec): VesperShapes {
    fun s(r: Int): CornerBasedShape = if (spec.cut) CutCornerShape(r.dp) else RoundedCornerShape(r.dp)
    return VesperShapes(
        small = s(spec.small),
        medium = s(spec.medium),
        large = s(spec.large),
        artwork = s(spec.artwork),
        playButton = if (spec.roundPlayButton) CircleShape else CutCornerShape(30),
        chip = if (spec.cut) CutCornerShape(6.dp) else RoundedCornerShape(50),
    )
}

fun typographyFor(spec: VesperTypeSpec): Typography {
    val body = Fonts.manrope
    val displayStyle = if (spec.displayItalic) FontStyle.Italic else FontStyle.Normal
    fun d(size: Float, line: Float) = TextStyle(
        fontFamily = spec.display,
        fontWeight = spec.displayWeight,
        fontStyle = displayStyle,
        fontSize = (size * spec.displayScale).sp,
        lineHeight = (line * spec.displayScale).sp,
        letterSpacing = (spec.displayTracking * size).sp,
    )
    fun b(size: Float, line: Float, weight: FontWeight, tracking: Float = 0f) = TextStyle(
        fontFamily = body,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = (tracking * size).sp,
    )
    return Typography(
        displayLarge = d(46f, 52f),
        displayMedium = d(38f, 44f),
        displaySmall = d(32f, 38f),
        headlineLarge = d(30f, 36f),
        headlineMedium = d(26f, 32f),
        headlineSmall = d(22f, 28f),
        titleLarge = b(19f, 25f, FontWeight.Bold, -0.01f),
        titleMedium = b(16f, 22f, FontWeight.SemiBold),
        titleSmall = b(14f, 20f, FontWeight.SemiBold),
        bodyLarge = b(16f, 24f, FontWeight.Normal),
        bodyMedium = b(14f, 20f, FontWeight.Medium),
        bodySmall = b(12.5f, 17f, FontWeight.Medium),
        labelLarge = b(14f, 18f, FontWeight.SemiBold, 0.01f),
        labelMedium = b(12f, 16f, FontWeight.SemiBold, 0.02f),
        labelSmall = b(10.5f, 14f, FontWeight.Bold, spec.labelTracking),
    )
}

private fun colorSchemeFor(p: VesperPalette): ColorScheme =
    if (p.isDark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.highlight,
            onPrimaryContainer = p.textPrimary,
            secondary = p.accentBright,
            onSecondary = p.onAccent,
            secondaryContainer = p.surfaceHigh,
            onSecondaryContainer = p.textPrimary,
            tertiary = p.ornament,
            background = p.background,
            onBackground = p.textPrimary,
            surface = p.surface,
            onSurface = p.textPrimary,
            surfaceVariant = p.surfaceHigh,
            onSurfaceVariant = p.textSecondary,
            surfaceTint = Color.Transparent,
            outline = p.outline,
            outlineVariant = p.outline,
            error = p.danger,
            inverseSurface = p.textPrimary,
            inverseOnSurface = p.background,
            surfaceContainerLowest = p.background,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surfaceHigh,
            surfaceContainerHighest = p.surfaceHigh,
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = p.onAccent,
            primaryContainer = p.highlight,
            onPrimaryContainer = p.textPrimary,
            secondary = p.accentBright,
            onSecondary = p.onAccent,
            secondaryContainer = p.surfaceHigh,
            onSecondaryContainer = p.textPrimary,
            tertiary = p.ornament,
            background = p.background,
            onBackground = p.textPrimary,
            surface = p.surface,
            onSurface = p.textPrimary,
            surfaceVariant = p.surfaceHigh,
            onSurfaceVariant = p.textSecondary,
            surfaceTint = Color.Transparent,
            outline = p.outline,
            outlineVariant = p.outline,
            error = p.danger,
            inverseSurface = p.textPrimary,
            inverseOnSurface = p.background,
            surfaceContainerLowest = p.surface,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surfaceHigh,
            surfaceContainerHighest = p.surfaceHigh,
        )
    }

/** Smoothly cross-fades every colour of the palette when the theme changes. */
@Composable
private fun animatedPalette(target: VesperPalette): VesperPalette {
    val spec = tween<Color>(durationMillis = 550, easing = FastOutSlowInEasing)
    @Composable
    fun a(c: Color, label: String): Color = animateColorAsState(c, spec, label = label).value
    return VesperPalette(
        isDark = target.isDark,
        background = a(target.background, "bg"),
        backgroundAlt = a(target.backgroundAlt, "bgAlt"),
        surface = a(target.surface, "surface"),
        surfaceHigh = a(target.surfaceHigh, "surfaceHigh"),
        outline = a(target.outline, "outline"),
        textPrimary = a(target.textPrimary, "text1"),
        textSecondary = a(target.textSecondary, "text2"),
        textMuted = a(target.textMuted, "text3"),
        accent = a(target.accent, "accent"),
        accentBright = a(target.accentBright, "accentBright"),
        onAccent = a(target.onAccent, "onAccent"),
        ornament = a(target.ornament, "ornament"),
        highlight = a(target.highlight, "highlight"),
        progressTrack = a(target.progressTrack, "track"),
        progressStart = a(target.progressStart, "p0"),
        progressEnd = a(target.progressEnd, "p1"),
        danger = a(target.danger, "danger"),
    )
}

/**
 * Root theme. [animate] = false is used by theme previews so they render instantly.
 */
@Composable
fun VesperThemeProvider(
    theme: VesperTheme,
    animate: Boolean = true,
    applySystemBars: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (animate) animatedPalette(theme.palette) else theme.palette
    val shapes = remember(theme.shapes) { shapesFor(theme.shapes) }
    val typography = remember(theme.type) { typographyFor(theme.type) }
    val scheme = colorSchemeFor(palette)

    if (applySystemBars) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !theme.palette.isDark
                controller.isAppearanceLightNavigationBars = !theme.palette.isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalVesperTheme provides theme,
        LocalPalette provides palette,
        LocalVesperShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(
                extraSmall = shapes.small,
                small = shapes.small,
                medium = shapes.medium,
                large = shapes.large,
                extraLarge = shapes.large,
            ),
            content = content,
        )
    }
}

/** Section/overline label text honouring the theme's case & tracking. */
@Composable
fun themedLabel(text: String): String =
    if (Vesper.theme.type.labelUppercase) text.uppercase() else text
