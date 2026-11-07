package app.floya.desktop.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import app.floya.desktop.theme.Ornament
import app.floya.desktop.theme.Vesper
import app.floya.desktop.theme.VesperPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated values shared by the glow behind the artwork and the ornaments drawn over it.
 * Backed by State and read only while drawing, so the animation never recomposes the screen.
 */
class FrameMotion(
    private val phaseState: State<Float>,
    private val flickerState: State<Float>,
    private val energyState: State<Float>,
) {
    val phase: Float get() = phaseState.value
    val flicker: Float get() = flickerState.value
    val energy: Float get() = energyState.value
}

@Composable
fun rememberFrameMotion(playing: Boolean): FrameMotion {
    val inf = rememberInfiniteTransition(label = "frame")
    val phase = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "phase")
    val flicker = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Reverse), label = "flicker")
    val energy = animateFloatAsState(if (playing) 1f else 0.35f, tween(900), label = "energy")
    return remember(phase, flicker, energy) { FrameMotion(phase, flicker, energy) }
}

/** Soft theme-coloured halo behind the cover; breathes with the music. */
@Composable
fun FrameGlow(ornament: Ornament, motion: FrameMotion, modifier: Modifier) {
    if (ornament == Ornament.NONE || ornament == Ornament.MINIMAL) return
    val c = Vesper.colors
    Canvas(modifier) {
        val color = when (ornament) {
            Ornament.GOTHIC -> c.accent
            Ornament.NEON -> c.accentBright
            else -> c.accent
        }
        val candle = 0.75f + 0.25f * sin(motion.flicker * PI.toFloat() * 2f + motion.phase * 40f)
        val a = (if (ornament == Ornament.GOTHIC) 0.42f * candle else 0.32f) * motion.energy
        val r = size.minDimension * (0.72f + 0.04f * sin(motion.phase * PI.toFloat() * 4f))
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center, r), r, center)
    }
}

/** Ornaments over the cover: gothic corners and glints, neon glitch, an aurora sweep, sparkles… */
@Composable
fun FrameOverlay(ornament: Ornament, motion: FrameMotion, modifier: Modifier) {
    val c = Vesper.colors
    val corner = Vesper.shapes.artwork
    Canvas(modifier) {
        val radius = corner.topStart.toPx(size, this) // approximate the theme's artwork rounding
        when (ornament) {
            Ornament.GOTHIC -> gothicFrame(c, motion, radius)
            Ornament.NEON -> neonFrame(c, motion, radius)
            Ornament.MIDNIGHT -> auroraFrame(c, motion, radius)
            Ornament.ROSE -> sparkleFrame(motion, c.ornament)
            Ornament.FOREST -> sparkleFrame(motion, c.accentBright)
            Ornament.MINIMAL, Ornament.NONE -> Unit
        }
    }
}

private fun DrawScope.gothicFrame(c: VesperPalette, m: FrameMotion, radius: Float) {
    val inset = 3.dp.toPx()
    val breathe = 0.35f + 0.35f * (0.5f + 0.5f * sin(m.phase * PI.toFloat() * 2f))
    drawRoundRect(
        c.ornament.copy(alpha = breathe),
        Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2),
        CornerRadius(radius), style = Stroke(1.dp.toPx()),
    )
    // A glint of light travelling around the frame.
    val perimeter = 2 * (size.width + size.height)
    val d = m.phase * perimeter
    val glint = when {
        d < size.width -> Offset(d, inset)
        d < size.width + size.height -> Offset(size.width - inset, d - size.width)
        d < 2 * size.width + size.height -> Offset(size.width - (d - size.width - size.height), size.height - inset)
        else -> Offset(inset, size.height - (d - 2 * size.width - size.height))
    }
    drawCircle(Brush.radialGradient(listOf(c.ornament.copy(alpha = 0.9f), Color.Transparent), glint, 18.dp.toPx()), 18.dp.toPx(), glint)
    // Fleur corners.
    val s = 14.dp.toPx()
    val corners = listOf(Offset(0f, 0f) to 0f, Offset(size.width, 0f) to 90f, Offset(size.width, size.height) to 180f, Offset(0f, size.height) to 270f)
    for ((p, angle) in corners) {
        rotate(angle, p) { fleur(p, s, c.ornament.copy(alpha = 0.6f + 0.4f * m.energy)) }
    }
}

/** A small gothic corner piece: two curling arms and a diamond. Drawn for the top-left corner. */
private fun DrawScope.fleur(at: Offset, s: Float, color: Color) {
    val stroke = Stroke(1.4.dp.toPx())
    val arms = Path().apply {
        moveTo(at.x + s * 1.6f, at.y + s * 0.15f)
        cubicTo(at.x + s * 0.9f, at.y + s * 0.15f, at.x + s * 0.15f, at.y + s * 0.4f, at.x + s * 0.15f, at.y + s * 1.6f)
        moveTo(at.x + s * 1.1f, at.y + s * 0.15f)
        quadraticBezierTo(at.x + s * 0.75f, at.y + s * 0.75f, at.x + s * 1.05f, at.y + s * 0.95f)
        moveTo(at.x + s * 0.15f, at.y + s * 1.1f)
        quadraticBezierTo(at.x + s * 0.75f, at.y + s * 0.75f, at.x + s * 0.95f, at.y + s * 1.05f)
    }
    drawPath(arms, color, style = stroke)
    val dx = at.x + s * 0.55f
    val dy = at.y + s * 0.55f
    val r = s * 0.2f
    val diamond = Path().apply { moveTo(dx, dy - r); lineTo(dx + r, dy); lineTo(dx, dy + r); lineTo(dx - r, dy); close() }
    drawPath(diamond, color)
}

private fun DrawScope.neonFrame(c: VesperPalette, m: FrameMotion, radius: Float) {
    val pulse = 0.55f + 0.45f * m.flicker
    drawRoundRect(c.accentBright.copy(alpha = 0.5f * pulse * m.energy + 0.2f), Offset.Zero, size, CornerRadius(radius), style = Stroke(2.dp.toPx()))
    // A short RGB-split glitch a few times per cycle.
    val g = (m.phase * 9f) % 1f
    if (g < 0.05f) {
        val shift = 4.dp.toPx() * (if (g < 0.025f) 1f else -1f)
        drawRoundRect(Color(0xFFFF2BD6).copy(alpha = 0.6f), Offset(shift, 0f), size, CornerRadius(radius), style = Stroke(1.5.dp.toPx()))
        drawRoundRect(Color(0xFF00F0FF).copy(alpha = 0.6f), Offset(-shift, 0f), size, CornerRadius(radius), style = Stroke(1.5.dp.toPx()))
        val y = size.height * ((m.phase * 37f) % 1f)
        drawRect(c.accentBright.copy(alpha = 0.35f), Offset(0f, y), Size(size.width, 3.dp.toPx()))
    }
}

private fun DrawScope.auroraFrame(c: VesperPalette, m: FrameMotion, radius: Float) {
    // Sweep of light rotating around the cover, like a moon passing by.
    val brush = Brush.sweepGradient(
        listOf(Color.Transparent, c.accentBright.copy(alpha = 0.75f * (0.4f + 0.6f * m.energy)), Color.Transparent, Color.Transparent),
        center,
    )
    rotate(m.phase * 360f, center) {
        drawRoundRect(brush, Offset.Zero, size, CornerRadius(radius), style = Stroke(2.dp.toPx()))
    }
    // Small moon orbiting the cover.
    val a = m.phase * PI.toFloat() * 2f
    val r = size.minDimension * 0.55f
    val p = Offset(center.x + cos(a) * r, center.y + sin(a) * r * 0.92f)
    drawCircle(Brush.radialGradient(listOf(c.textPrimary.copy(alpha = 0.8f), Color.Transparent), p, 7.dp.toPx()), 7.dp.toPx(), p)
}

private fun DrawScope.sparkleFrame(m: FrameMotion, color: Color) {
    for (i in 0 until 6) {
        val a = (m.phase + i / 6f) * PI.toFloat() * 2f
        val wob = 0.53f + 0.03f * sin(m.phase * 20f + i)
        val p = Offset(center.x + cos(a) * size.width * wob, center.y + sin(a) * size.height * wob)
        val tw = 0.5f + 0.5f * sin(m.flicker * PI.toFloat() + i * 1.3f)
        val r = (3f + 2f * tw) * density
        val col = color.copy(alpha = (0.4f + 0.5f * tw) * (0.5f + 0.5f * m.energy))
        drawLine(col, Offset(p.x - r, p.y), Offset(p.x + r, p.y), 1.3f * density)
        drawLine(col, Offset(p.x, p.y - r), Offset(p.x, p.y + r), 1.3f * density)
        drawCircle(col.copy(alpha = col.alpha * 0.3f), r * 1.6f, p)
    }
}
