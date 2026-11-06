package app.vesper.player.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Living, theme-specific background: embers and bats for Gothic, stars and falling stars for
 * Midnight, digital rain and a neon grid for Cyberpunk, petals for Rose, fireflies and leaves for
 * Forest, slow light for Minimal. Everything is computed from time (no per-frame allocations),
 * redrawn ~30 times a second only while visible, and moves faster while music is playing.
 */
@Composable
fun ThemeAmbience(
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    intensity: Float = 1f,
) {
    val ornament = Vesper.theme.ornament
    if (ornament == Ornament.NONE) return
    val c = Vesper.colors
    val speed by animateFloatAsState(if (playing) 1.7f else 1f, tween(1200), label = "ambSpeed")
    val currentSpeed by rememberUpdatedState(speed)
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = System.nanoTime()
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                clock.floatValue += dt * currentSpeed
            }
            delay(26)
        }
    }
    Canvas(modifier) {
        val t = clock.floatValue
        when (ornament) {
            Ornament.GOTHIC -> gothic(t, c, intensity)
            Ornament.MIDNIGHT -> midnight(t, c, intensity)
            Ornament.NEON -> neon(t, c, intensity)
            Ornament.ROSE -> rose(t, c, intensity)
            Ornament.FOREST -> forest(t, c, intensity)
            Ornament.MINIMAL -> minimal(t, c, intensity)
            Ornament.NONE -> Unit
        }
    }
}

// ---- deterministic pseudo-random per particle ------------------------------------------------

private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7fffffff) / 2147483647f
}

private fun frac(v: Float) = v - floor(v)

// ---- Gothic: rising embers, drifting fog, bats crossing the night --------------------------

private fun DrawScope.gothic(t: Float, c: VesperPalette, k: Float) {
    val w = size.width
    val h = size.height
    // Candle-light glow in the corners, flickering.
    val flicker = 0.75f + 0.15f * sin(t * 7.3f) + 0.1f * sin(t * 13.1f + 1f)
    drawCircle(
        Brush.radialGradient(listOf(c.accent.copy(alpha = 0.16f * flicker * k), Color.Transparent), Offset(0f, h * 0.08f), w * 0.7f),
        w * 0.7f, Offset(0f, h * 0.08f),
    )
    drawCircle(
        Brush.radialGradient(listOf(c.ornament.copy(alpha = 0.09f * flicker * k), Color.Transparent), Offset(w, h * 0.02f), w * 0.55f),
        w * 0.55f, Offset(w, h * 0.02f),
    )
    // Fog banks along the bottom.
    for (i in 0 until 3) {
        val x = w * frac(rnd(i, 1) + t * (0.006f + 0.004f * i))
        val y = h * (0.82f + 0.08f * i)
        val r = w * (0.55f + 0.2f * rnd(i, 2))
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.08f * k), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.08f * k), Color.Transparent), Offset(x - w, y), r), r, Offset(x - w, y))
    }
    // Embers.
    val ember = lerp(c.accentBright, c.ornament, 0.4f)
    for (i in 0 until (28 * k).toInt().coerceAtMost(48)) {
        val sp = 0.025f + 0.04f * rnd(i, 3)
        val life = frac(rnd(i, 4) + t * sp)
        val x = w * rnd(i, 5) + sin(t * (0.6f + rnd(i, 6)) + i) * 18f * density
        val y = h * (1.05f - life * 1.1f)
        val fade = sin(life * PI.toFloat()).coerceAtLeast(0f)
        val tw = 0.6f + 0.4f * sin(t * (5f + 4f * rnd(i, 7)) + i)
        val r = (1f + 1.6f * rnd(i, 8)) * density
        drawCircle(ember.copy(alpha = 0.18f * fade * tw * k), r * 4f, Offset(x, y))
        drawCircle(ember.copy(alpha = 0.85f * fade * tw * k), r, Offset(x, y))
    }
    // Bats: each one flies across now and then on a wavy path.
    for (i in 0 until 3) {
        val period = 16f + 9f * rnd(i, 9)
        val p = frac(t / period + rnd(i, 10))
        if (p > 0.45f) continue // off-screen most of the time — rarer is spookier
        val q = p / 0.45f
        val dir = if (i % 2 == 0) 1f else -1f
        val x = if (dir > 0) -60f * density + q * (w + 120f * density) else w + 60f * density - q * (w + 120f * density)
        val y = h * (0.12f + 0.22f * rnd(i, 11)) + sin(q * 9f + i) * 26f * density
        val scale = (0.7f + 0.6f * rnd(i, 12)) * density
        bat(Offset(x, y), scale, flap = sin(t * 15f + i * 2f), color = c.ornament.copy(alpha = 0.55f * k))
    }
}

private fun DrawScope.bat(at: Offset, s: Float, flap: Float, color: Color) {
    val wing = 14f * s
    val lift = flap * 7f * s
    val path = Path().apply {
        moveTo(at.x, at.y)
        // left wing
        quadraticBezierTo(at.x - wing * 0.5f, at.y - 6f * s - lift, at.x - wing, at.y - 2f * s - lift * 1.4f)
        quadraticBezierTo(at.x - wing * 0.78f, at.y + 1f * s, at.x - wing * 0.62f, at.y + 2.5f * s - lift * 0.3f)
        quadraticBezierTo(at.x - wing * 0.42f, at.y + 0.5f * s, at.x - wing * 0.28f, at.y + 3f * s)
        quadraticBezierTo(at.x - wing * 0.14f, at.y + 1f * s, at.x, at.y + 3.5f * s)
        // right wing (mirror)
        quadraticBezierTo(at.x + wing * 0.14f, at.y + 1f * s, at.x + wing * 0.28f, at.y + 3f * s)
        quadraticBezierTo(at.x + wing * 0.42f, at.y + 0.5f * s, at.x + wing * 0.62f, at.y + 2.5f * s - lift * 0.3f)
        quadraticBezierTo(at.x + wing * 0.78f, at.y + 1f * s, at.x + wing, at.y - 2f * s - lift * 1.4f)
        quadraticBezierTo(at.x + wing * 0.5f, at.y - 6f * s - lift, at.x, at.y)
        close()
    }
    drawPath(path, color)
    // body and ears
    drawCircle(color, 2.6f * s, Offset(at.x, at.y + 1f * s))
    val ear = Path().apply {
        moveTo(at.x - 2f * s, at.y - 0.5f * s); lineTo(at.x - 1.6f * s, at.y - 4f * s); lineTo(at.x - 0.3f * s, at.y - 1f * s); close()
        moveTo(at.x + 2f * s, at.y - 0.5f * s); lineTo(at.x + 1.6f * s, at.y - 4f * s); lineTo(at.x + 0.3f * s, at.y - 1f * s); close()
    }
    drawPath(ear, color)
}

// ---- Midnight: twinkling stars, a soft moon, falling stars ---------------------------------

private fun DrawScope.midnight(t: Float, c: VesperPalette, k: Float) {
    val w = size.width
    val h = size.height
    val moon = Offset(w * 0.82f, h * 0.1f)
    drawCircle(Brush.radialGradient(listOf(c.accentBright.copy(alpha = 0.14f * k), Color.Transparent), moon, w * 0.45f), w * 0.45f, moon)
    for (i in 0 until (70 * k).toInt().coerceAtMost(110)) {
        val x = w * rnd(i, 1)
        val y = h * rnd(i, 2) * 0.85f
        val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (0.8f + 2.2f * rnd(i, 3)) + i * 1.7f))
        val r = (0.6f + 1.2f * rnd(i, 4) * rnd(i, 5)) * density
        drawCircle(c.textPrimary.copy(alpha = 0.7f * tw * k), r, Offset(x + sin(t * 0.05f + i) * 3f, y))
    }
    // A falling star roughly every 7 seconds.
    val period = 7f
    val n = floor(t / period).toInt()
    val p = frac(t / period)
    if (p < 0.18f) {
        val q = p / 0.18f
        val sx = w * (0.15f + 0.6f * rnd(n, 6))
        val sy = h * (0.05f + 0.25f * rnd(n, 7))
        val len = w * 0.35f
        val head = Offset(sx + len * q, sy + len * 0.45f * q)
        val tail = Offset(head.x - len * 0.35f, head.y - len * 0.16f)
        val alpha = sin(q * PI.toFloat()) * k
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, c.accentBright.copy(alpha = 0.9f * alpha)), tail, head),
            tail, head, strokeWidth = 2f * density, cap = StrokeCap.Round,
        )
        drawCircle(c.textPrimary.copy(alpha = alpha), 1.8f * density, head)
    }
}

// ---- Cyberpunk: digital rain, a moving scan line, a perspective grid -----------------------

private fun DrawScope.neon(t: Float, c: VesperPalette, k: Float) {
    val w = size.width
    val h = size.height
    val cols = 16
    for (i in 0 until cols) {
        val x = w * (i + 0.5f) / cols + (rnd(i, 1) - 0.5f) * 14f * density
        val sp = 0.08f + 0.14f * rnd(i, 2)
        val head = frac(rnd(i, 3) + t * sp) * (h * 1.3f) - h * 0.15f
        val len = h * (0.08f + 0.12f * rnd(i, 4))
        val color = if (i % 3 == 0) c.accent else c.accentBright
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.22f * k)), head - len, head),
            Offset(x, head - len), Offset(x, head), strokeWidth = 1.5f * density,
        )
        drawRect(color.copy(alpha = 0.5f * k), Offset(x - 1.5f * density, head - 2f * density), Size(3f * density, 3f * density))
    }
    // Scan line.
    val sy = frac(t * 0.07f) * h
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, c.accentBright.copy(alpha = 0.07f * k), Color.Transparent), sy - 40f * density, sy + 40f * density),
        Offset(0f, sy - 40f * density), Size(w, 80f * density),
    )
    // Synthwave grid near the bottom.
    val horizon = h * 0.78f
    val gridColor = c.accent.copy(alpha = 0.16f * k)
    for (j in 0 until 8) {
        val z = frac(j / 8f + t * 0.12f)
        val y = horizon + (h - horizon) * z * z
        drawLine(gridColor.copy(alpha = gridColor.alpha * z), Offset(0f, y), Offset(w, y), strokeWidth = 1f * density)
    }
    for (j in -6..6) {
        val bx = w / 2 + j * w * 0.16f
        drawLine(gridColor, Offset(w / 2 + j * w * 0.03f, horizon), Offset(bx + j * w * 0.12f, h), strokeWidth = 1f * density)
    }
    // Rare glitch bar.
    if (frac(t * 0.37f) < 0.03f) {
        val gy = h * rnd(floor(t * 0.37f).toInt(), 9)
        drawRect(c.accentBright.copy(alpha = 0.12f * k), Offset(0f, gy), Size(w, 3f * density))
    }
}

// ---- Rose: falling, tumbling petals and a few sparkles -------------------------------------

private fun DrawScope.rose(t: Float, c: VesperPalette, k: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until (18 * k).toInt().coerceAtMost(30)) {
        val sp = 0.03f + 0.035f * rnd(i, 1)
        val life = frac(rnd(i, 2) + t * sp)
        val x = w * rnd(i, 3) + sin(t * (0.5f + 0.6f * rnd(i, 4)) + i) * 40f * density
        val y = -30f * density + life * (h + 60f * density)
        val s = (6f + 6f * rnd(i, 5)) * density
        val spin = t * (40f + 70f * rnd(i, 6)) + i * 37f
        val squash = 0.45f + 0.55f * abs(cos(t * (1.2f + rnd(i, 7)) + i))
        val color = (if (i % 2 == 0) c.accent else c.accentBright).copy(alpha = 0.42f * k)
        rotate(spin, Offset(x, y)) {
            translate(x, y) {
                val petal = Path().apply {
                    moveTo(0f, -s)
                    cubicTo(s * 0.9f * squash, -s * 0.6f, s * 0.7f * squash, s * 0.7f, 0f, s)
                    cubicTo(-s * 0.7f * squash, s * 0.7f, -s * 0.9f * squash, -s * 0.6f, 0f, -s)
                    close()
                }
                drawPath(petal, color)
            }
        }
    }
    for (i in 0 until 10) {
        val tw = (sin(t * (1.5f + rnd(i, 8) * 2f) + i * 2f) * 0.5f + 0.5f)
        val p = Offset(w * rnd(i, 9), h * rnd(i, 10))
        val r = 3f * density * tw
        drawLine(c.ornament.copy(alpha = 0.6f * tw * k), Offset(p.x - r, p.y), Offset(p.x + r, p.y), 1.2f * density)
        drawLine(c.ornament.copy(alpha = 0.6f * tw * k), Offset(p.x, p.y - r), Offset(p.x, p.y + r), 1.2f * density)
    }
}

// ---- Forest: wandering fireflies, falling leaves, ground mist ------------------------------

private fun DrawScope.forest(t: Float, c: VesperPalette, k: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 2) {
        val y = h * (0.88f + 0.06f * i)
        val x = w * frac(rnd(i, 1) + t * 0.008f)
        val r = w * 0.6f
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.07f * k), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
    }
    val glow = c.accentBright
    for (i in 0 until (22 * k).toInt().coerceAtMost(36)) {
        val cx = w * rnd(i, 2)
        val cy = h * (0.25f + 0.7f * rnd(i, 3))
        val x = cx + sin(t * (0.21f + 0.2f * rnd(i, 4)) + i) * 40f * density + sin(t * 0.9f + i * 3f) * 6f * density
        val y = cy + cos(t * (0.17f + 0.2f * rnd(i, 5)) + i * 2f) * 30f * density
        val pulse = (sin(t * (1.1f + rnd(i, 6) * 1.5f) + i * 1.3f) * 0.5f + 0.5f).let { it * it }
        drawCircle(glow.copy(alpha = 0.2f * pulse * k), 9f * density, Offset(x, y))
        drawCircle(glow.copy(alpha = 0.9f * pulse * k), 1.8f * density, Offset(x, y))
    }
    for (i in 0 until 7) {
        val life = frac(rnd(i, 7) + t * (0.025f + 0.02f * rnd(i, 8)))
        val x = w * rnd(i, 9) + sin(t * 0.7f + i) * 50f * density
        val y = -20f * density + life * (h + 40f * density)
        val s = (5f + 4f * rnd(i, 10)) * density
        rotate(t * 60f * (if (i % 2 == 0) 1 else -1) + i * 50f, Offset(x, y)) {
            drawOval(c.ornament.copy(alpha = 0.35f * k), Offset(x - s, y - s * 0.45f), Size(s * 2, s * 0.9f))
        }
    }
}

// ---- Minimal: slow soft light ---------------------------------------------------------------

private fun DrawScope.minimal(t: Float, c: VesperPalette, k: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until 3) {
        val x = w * (0.5f + 0.4f * sin(t * 0.05f * (i + 1) + i * 2f))
        val y = h * (0.3f + 0.3f * i + 0.08f * cos(t * 0.04f * (i + 1)))
        val r = w * 0.7f
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.05f * k), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
    }
}
