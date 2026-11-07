package app.floya.desktop.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Seconds since the scene appeared; advanced ~30×/s only while on screen. Frozen when [animate] is false. */
@Composable
fun rememberSceneClock(animate: Boolean = true, start: Float = 7.5f): State<Float> {
    val clock = remember { mutableFloatStateOf(start) }
    if (animate) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (last != 0L) clock.floatValue += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                }
                delay(28)
            }
        }
    }
    return clock
}

/**
 * The illustrated, animated picture of a playlist theme (castle under the moon, mountains at dawn,
 * waves, a ringed planet…). Fades into [fadeTo] at the bottom so the list continues seamlessly.
 */
@Composable
fun SkinScene(scene: SceneKind, modifier: Modifier, fadeTo: Color, animate: Boolean = true) {
    val clock = rememberSceneClock(animate)
    Canvas(modifier) {
        val t = clock.value
        when (scene) {
            SceneKind.DARK_FANTASY -> darkFantasy(t)
            SceneKind.NATURE -> nature(t)
            SceneKind.OCEAN -> ocean(t)
            SceneKind.COSMOS -> cosmos(t)
            SceneKind.SAKURA -> sakura(t)
            SceneKind.SYNTHWAVE -> synthwave(t)
            SceneKind.NOIR -> noir(t)
            SceneKind.WINTER -> winter(t)
            SceneKind.FIRE -> fire(t)
            SceneKind.DESERT -> desert(t)
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, fadeTo), size.height * 0.55f, size.height))
    }
}

/** Light particles over the whole playlist screen: crows' embers, snow, rain, sparks, bubbles… */
@Composable
fun SkinParticles(scene: SceneKind, modifier: Modifier) {
    val clock = rememberSceneClock()
    Canvas(modifier) {
        val t = clock.value
        when (scene) {
            SceneKind.DARK_FANTASY -> risers(t, 22, Color(0xFFFFB25C), 0.03f, 1.6f)
            SceneKind.FIRE -> risers(t, 36, Color(0xFFFF8A2A), 0.06f, 2f)
            SceneKind.NATURE -> fireflies(t, Color(0xFFE6F59A))
            SceneKind.OCEAN -> bubbles(t)
            SceneKind.COSMOS, SceneKind.DESERT -> twinkles(t, if (scene == SceneKind.COSMOS) 60 else 28)
            SceneKind.SAKURA -> petals(t)
            SceneKind.SYNTHWAVE -> scanline(t)
            SceneKind.NOIR -> rain(t, 0.22f)
            SceneKind.WINTER -> snow(t)
        }
    }
}

// ---- helpers -------------------------------------------------------------------------------

private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0x7fffffff) / 2147483647f
}

private fun frac(v: Float) = v - floor(v)

private fun DrawScope.sky(colors: List<Color>) = drawRect(Brush.verticalGradient(colors))

private fun DrawScope.glow(center: Offset, radius: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, radius), radius, center)

/** A jagged ridge across the width; [seed] varies the shape, [rough] the height of peaks. */
private fun DrawScope.ridge(base: Float, rough: Float, seed: Int, color: Color, peaks: Int = 7, sharp: Boolean = true, shift: Float = 0f) {
    val w = size.width
    val path = Path().apply {
        moveTo(0f, size.height)
        val n = peaks * 2
        for (i in 0..n) {
            val x = w * i / n + shift
            val up = if (i % 2 == 1) rough * (0.5f + 0.5f * rnd(i, seed)) else rough * 0.15f * rnd(i, seed + 1)
            val y = base - up
            if (i == 0) lineTo(0f, y) else if (sharp) lineTo(x, y) else {
                val px = w * (i - 1) / n + shift
                quadraticBezierTo((px + x) / 2f, y - rough * 0.12f, x, y)
            }
        }
        lineTo(w, size.height)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.pine(x: Float, baseY: Float, h: Float, color: Color) {
    val w = h * 0.42f
    val path = Path().apply {
        moveTo(x, baseY - h)
        for (k in 1..3) {
            val y = baseY - h + h * k / 3.3f
            lineTo(x + w * k / 3f, y)
            lineTo(x + w * (k - 0.6f) / 3f, y)
        }
        lineTo(x + w * 0.12f, baseY)
        lineTo(x - w * 0.12f, baseY)
        for (k in 3 downTo 1) {
            val y = baseY - h + h * k / 3.3f
            lineTo(x - w * (k - 0.6f) / 3f, y)
            lineTo(x - w * k / 3f, y)
        }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.stars(t: Float, count: Int, maxY: Float, color: Color = Color.White) {
    for (i in 0 until count) {
        val tw = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * (0.7f + 2f * rnd(i, 3)) + i * 1.9f))
        drawCircle(color.copy(alpha = 0.85f * tw), (0.5f + 1.1f * rnd(i, 4) * rnd(i, 5)) * density, Offset(size.width * rnd(i, 1), size.height * maxY * rnd(i, 2)))
    }
}

private fun DrawScope.bird(at: Offset, s: Float, flap: Float, color: Color) {
    val lift = flap * 4f * s
    val p = Path().apply {
        moveTo(at.x - 9f * s, at.y - 2f * s - lift)
        quadraticBezierTo(at.x - 4f * s, at.y - 5f * s - lift * 0.5f, at.x, at.y)
        quadraticBezierTo(at.x + 4f * s, at.y - 5f * s - lift * 0.5f, at.x + 9f * s, at.y - 2f * s - lift)
    }
    drawPath(p, color, style = Stroke(1.6f * s, cap = StrokeCap.Round))
}

private fun DrawScope.flock(t: Float, count: Int, color: Color, yFrom: Float, yTo: Float, speed: Float = 0.035f) {
    for (i in 0 until count) {
        val p = frac(rnd(i, 30) + t * speed * (0.7f + 0.6f * rnd(i, 31)))
        val x = -30f * density + p * (size.width + 60f * density)
        val y = size.height * (yFrom + (yTo - yFrom) * rnd(i, 32)) + sin(t * 1.3f + i) * 6f * density
        bird(Offset(x, y), density * (0.7f + 0.5f * rnd(i, 33)), sin(t * 9f + i * 2f), color)
    }
}

// ---- scenes ----------------------------------------------------------------------------------

private fun DrawScope.darkFantasy(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF0E0716), Color(0xFF2A0F25), Color(0xFF4B1426)))
    stars(t, 40, 0.55f, Color(0xFFF3E6D0))
    val moon = Offset(w * 0.74f, h * 0.27f)
    glow(moon, w * 0.42f, Color(0xFFE9C98A).copy(alpha = 0.28f))
    drawCircle(Color(0xFFF2E3C4), w * 0.11f, moon)
    drawCircle(Color(0xFFD9C49C).copy(alpha = 0.55f), w * 0.025f, moon + Offset(-w * 0.03f, -w * 0.02f))
    drawCircle(Color(0xFFD9C49C).copy(alpha = 0.45f), w * 0.018f, moon + Offset(w * 0.04f, w * 0.03f))
    ridge(h * 0.8f, h * 0.18f, 3, Color(0xFF240B1A), peaks = 5)
    // Castle.
    val castle = Color(0xFF0C060B)
    val baseY = h * 0.86f
    val cx = w * 0.36f
    fun tower(x: Float, tw: Float, th: Float) {
        drawRect(castle, Offset(x - tw / 2, baseY - th), Size(tw, th))
        val roof = Path().apply { moveTo(x - tw * 0.7f, baseY - th); lineTo(x, baseY - th - tw * 1.6f); lineTo(x + tw * 0.7f, baseY - th); close() }
        drawPath(roof, castle)
        drawLine(castle, Offset(x, baseY - th - tw * 1.6f), Offset(x, baseY - th - tw * 2.2f), 1.5f * density)
    }
    drawRect(castle, Offset(cx - w * 0.2f, baseY - h * 0.2f), Size(w * 0.4f, h * 0.2f))
    for (k in 0 until 9) drawRect(castle, Offset(cx - w * 0.2f + k * w * 0.046f, baseY - h * 0.225f), Size(w * 0.025f, h * 0.03f))
    tower(cx - w * 0.2f, w * 0.07f, h * 0.36f)
    tower(cx + w * 0.2f, w * 0.07f, h * 0.33f)
    tower(cx, w * 0.09f, h * 0.5f)
    tower(cx - w * 0.09f, w * 0.05f, h * 0.29f)
    tower(cx + w * 0.1f, w * 0.05f, h * 0.27f)
    // Flickering windows.
    val windows = listOf(Offset(cx, baseY - h * 0.38f), Offset(cx - w * 0.2f, baseY - h * 0.27f), Offset(cx + w * 0.2f, baseY - h * 0.24f), Offset(cx + w * 0.06f, baseY - h * 0.12f), Offset(cx - w * 0.1f, baseY - h * 0.1f))
    windows.forEachIndexed { i, p ->
        val f = 0.65f + 0.35f * sin(t * (6f + i) + i * 2f)
        glow(p, 12f * density, Color(0xFFFFB347).copy(alpha = 0.45f * f))
        drawRect(Color(0xFFFFC56B).copy(alpha = 0.9f * f), p - Offset(2.5f * density, 4f * density), Size(5f * density, 8f * density))
    }
    ridge(h * 0.95f, h * 0.08f, 9, Color(0xFF07040A), peaks = 9, sharp = false)
    // Mist and crows.
    for (i in 0 until 3) {
        val x = w * frac(rnd(i, 7) + t * 0.01f * (i + 1))
        glow(Offset(x, h * 0.88f), w * 0.45f, Color(0xFF8E5A7A).copy(alpha = 0.12f))
    }
    flock(t, 4, Color(0xFF050305), 0.12f, 0.45f, 0.028f)
}

private fun DrawScope.nature(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF7FB4D8), Color(0xFFF2C9A0), Color(0xFFF6A97A)))
    val sun = Offset(w * 0.68f, h * 0.48f)
    rotate(t * 3f, sun) {
        for (k in 0 until 12) {
            val a = k * PI.toFloat() / 6f
            drawLine(Color(0xFFFFF0C2).copy(alpha = 0.22f), sun, sun + Offset(cos(a), sin(a)) * (w * 0.6f), 10f * density)
        }
    }
    glow(sun, w * 0.35f, Color(0xFFFFE6A8).copy(alpha = 0.7f))
    drawCircle(Color(0xFFFFF4D6), w * 0.08f, sun)
    ridge(h * 0.6f, h * 0.32f, 1, Color(0xFF8C9DB8), peaks = 4)
    // snow caps
    ridge(h * 0.68f, h * 0.3f, 2, Color(0xFF5F7E86), peaks = 5)
    ridge(h * 0.8f, h * 0.18f, 4, Color(0xFF3B5E4A), peaks = 6, sharp = false)
    for (i in 0 until 18) {
        val x = w * (i + 0.3f * rnd(i, 5)) / 17f
        pine(x, h * (0.93f + 0.04f * rnd(i, 6)), h * (0.2f + 0.14f * rnd(i, 7)), Color(0xFF1C3526))
    }
    drawRect(Color(0xFF15281D), Offset(0f, h * 0.94f), Size(w, h * 0.06f))
    flock(t, 5, Color(0xFF2B3A40), 0.15f, 0.4f, 0.03f)
}

private fun DrawScope.ocean(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF0B1E3A), Color(0xFF1D4F7A), Color(0xFF3C86A8)))
    stars(t, 25, 0.35f)
    val moon = Offset(w * 0.3f, h * 0.24f)
    glow(moon, w * 0.3f, Color(0xFFCDEFFF).copy(alpha = 0.3f))
    drawCircle(Color(0xFFEAF8FF), w * 0.07f, moon)
    val horizon = h * 0.52f
    val layers = listOf(Color(0xFF1B5F86), Color(0xFF14496B), Color(0xFF0E3854), Color(0xFF0A2A40), Color(0xFF071E2E))
    layers.forEachIndexed { k, col ->
        val y0 = horizon + (h - horizon) * k / layers.size
        val amp = (3f + k * 4f) * density
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w + 10f) {
                lineTo(x, y0 + sin(x / (40f + k * 18f) + t * (0.8f + k * 0.25f) * (if (k % 2 == 0) 1 else -1)) * amp)
                x += 8f
            }
            lineTo(w, h)
            close()
        }
        drawPath(path, col)
    }
    // Moon path on the water.
    for (i in 0 until 14) {
        val y = horizon + (h - horizon) * i / 14f
        val wob = sin(t * 2f + i) * 10f * density
        drawRect(Color(0xFFDDF6FF).copy(alpha = 0.35f * (1f - i / 14f)), Offset(moon.x - (20f + i * 4f) * density / 2 + wob, y), Size((20f + i * 4f) * density, 2f * density))
    }
}

private fun DrawScope.cosmos(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF05041A), Color(0xFF120A33), Color(0xFF1C0D3D)))
    glow(Offset(w * 0.25f, h * 0.35f), w * 0.55f, Color(0xFF7A3DD8).copy(alpha = 0.35f))
    glow(Offset(w * 0.7f, h * 0.6f), w * 0.5f, Color(0xFF2E7BD8).copy(alpha = 0.3f))
    glow(Offset(w * 0.55f + sin(t * 0.1f) * 20f, h * 0.3f), w * 0.3f, Color(0xFFE05AB8).copy(alpha = 0.22f))
    stars(t, 90, 1f)
    // Ringed planet.
    val p = Offset(w * 0.68f, h * 0.48f)
    val r = w * 0.14f
    drawCircle(Brush.linearGradient(listOf(Color(0xFFF2B880), Color(0xFFB65A7A), Color(0xFF3B1E55)), p - Offset(r, r), p + Offset(r, r)), r, p)
    rotate(-18f, p) {
        drawOval(Color(0xFFF7D9B0).copy(alpha = 0.7f), p - Offset(r * 1.9f, r * 0.38f), Size(r * 3.8f, r * 0.76f), style = Stroke(3f * density))
        drawOval(Color(0xFFF7D9B0).copy(alpha = 0.35f), p - Offset(r * 2.2f, r * 0.48f), Size(r * 4.4f, r * 0.96f), style = Stroke(1.5f * density))
    }
    // Small moon orbiting.
    val a = t * 0.4f
    drawCircle(Color(0xFFCFD8FF), w * 0.02f, p + Offset(cos(a) * r * 2.6f, sin(a) * r * 0.7f))
    // Shooting star.
    val per = 6f
    val q = frac(t / per)
    if (q < 0.15f) {
        val n = floor(t / per).toInt()
        val k = q / 0.15f
        val s = Offset(w * (0.1f + 0.5f * rnd(n, 8)), h * 0.1f)
        val head = s + Offset(w * 0.4f * k, h * 0.25f * k)
        drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White), s, head), s + (head - s) * 0.5f, head, 2f * density, cap = StrokeCap.Round)
    }
}

private fun DrawScope.sakura(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFFF6C7D8), Color(0xFFF3A9C2), Color(0xFFE48AAE)))
    val sun = Offset(w * 0.62f, h * 0.45f)
    glow(sun, w * 0.4f, Color(0xFFFFF0F4).copy(alpha = 0.6f))
    drawCircle(Color(0xFFFFE4EC), w * 0.13f, sun)
    ridge(h * 0.78f, h * 0.22f, 12, Color(0xFFC9779A), peaks = 3, sharp = false)
    ridge(h * 0.9f, h * 0.1f, 13, Color(0xFF9E4F72), peaks = 5, sharp = false)
    // Branch from the left with blossoms, swaying.
    val sway = sin(t * 0.8f) * 4f * density
    val branch = Path().apply {
        moveTo(-10f, h * 0.18f)
        cubicTo(w * 0.2f, h * 0.15f + sway, w * 0.32f, h * 0.3f + sway, w * 0.5f, h * 0.26f + sway)
    }
    drawPath(branch, Color(0xFF4A2433), style = Stroke(7f * density, cap = StrokeCap.Round))
    val twig = Path().apply { moveTo(w * 0.22f, h * 0.2f + sway); quadraticBezierTo(w * 0.3f, h * 0.06f + sway, w * 0.42f, h * 0.08f + sway) }
    drawPath(twig, Color(0xFF4A2433), style = Stroke(4f * density, cap = StrokeCap.Round))
    for (i in 0 until 26) {
        val along = rnd(i, 40)
        val x = w * 0.5f * along + (rnd(i, 41) - 0.5f) * 30f * density
        val y = h * (0.12f + 0.16f * rnd(i, 42)) + sway
        val r = (4f + 4f * rnd(i, 43)) * density
        drawCircle(Color(0xFFFFD6E4), r, Offset(x, y))
        drawCircle(Color(0xFFF58CB0), r * 0.35f, Offset(x, y))
    }
    flock(t, 2, Color(0xFF7A3A57), 0.3f, 0.5f, 0.02f)
}

private fun DrawScope.synthwave(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF14062B), Color(0xFF4A0D5E), Color(0xFFB0256E)))
    stars(t, 30, 0.4f)
    val horizon = h * 0.64f
    val sun = Offset(w * 0.5f, horizon - h * 0.04f)
    val r = w * 0.22f
    glow(sun, r * 2f, Color(0xFFFF5FA2).copy(alpha = 0.35f))
    drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFE066), Color(0xFFFF4F9A)), sun.y - r, sun.y + r), r, sun)
    // Stripes cut through the sun.
    for (k in 0 until 6) {
        val y = sun.y + r * (0.05f + k * 0.16f) + frac(t * 0.3f) * r * 0.16f
        drawRect(Color(0xFF4A0D5E), Offset(sun.x - r, y), Size(r * 2, (1.5f + k * 1.6f) * density))
    }
    ridge(horizon + 2f, h * 0.12f, 21, Color(0xFF1B0730), peaks = 6)
    drawRect(Color(0xFF12041F), Offset(0f, horizon), Size(w, h - horizon))
    val grid = Color(0xFF35E0F0)
    for (j in 0 until 10) {
        val z = frac(j / 10f + t * 0.25f)
        val y = horizon + (h - horizon) * z * z
        drawLine(grid.copy(alpha = 0.7f * z), Offset(0f, y), Offset(w, y), 1.5f * density)
    }
    for (j in -10..10) {
        drawLine(grid.copy(alpha = 0.5f), Offset(w / 2 + j * w * 0.04f, horizon), Offset(w / 2 + j * w * 0.22f, h), 1.2f * density)
    }
}

private fun DrawScope.noir(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF0D1015), Color(0xFF1C222C), Color(0xFF2A2F37)))
    // Lightning now and then.
    val f = frac(t / 9f)
    if (f < 0.03f || (f > 0.045f && f < 0.06f)) drawRect(Color.White.copy(alpha = 0.18f))
    glow(Offset(w * 0.5f, h * 0.9f), w * 0.7f, Color(0xFFB5833B).copy(alpha = 0.18f))
    for (layer in 0 until 2) {
        val col = if (layer == 0) Color(0xFF20252E) else Color(0xFF0E1116)
        var x = -10f
        var i = layer * 50
        while (x < w) {
            val bw = (28f + 40f * rnd(i, 1)) * density
            val bh = h * (0.25f + 0.45f * rnd(i, 2)) * (if (layer == 0) 1.1f else 0.85f)
            drawRect(col, Offset(x, h - bh), Size(bw - 3f * density, bh))
            if (layer == 1) {
                // Windows: some lit warm, some off; a few switch on and off.
                var wy = h - bh + 8f * density
                var row = 0
                while (wy < h - 6f * density) {
                    var wx = x + 5f * density
                    var col2 = 0
                    while (wx < x + bw - 10f * density) {
                        val id = i * 97 + row * 13 + col2
                        val lit = rnd(id, 3) > 0.62f && (rnd(id, 4) > 0.15f || sin(t * 0.7f + id) > 0f)
                        if (lit) drawRect(Color(0xFFE8B865).copy(alpha = 0.75f), Offset(wx, wy), Size(4f * density, 5f * density))
                        wx += 9f * density
                        col2++
                    }
                    wy += 11f * density
                    row++
                }
            }
            x += bw
            i++
        }
    }
    rain(t, 0.45f)
}

private fun DrawScope.winter(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF1B2D4A), Color(0xFF4F6E93), Color(0xFFA9C0D8)))
    stars(t, 20, 0.3f)
    val moon = Offset(w * 0.78f, h * 0.22f)
    glow(moon, w * 0.3f, Color.White.copy(alpha = 0.25f))
    drawCircle(Color(0xFFF4F8FF), w * 0.06f, moon)
    ridge(h * 0.66f, h * 0.22f, 31, Color(0xFFC9D8EA), peaks = 4, sharp = false)
    ridge(h * 0.78f, h * 0.12f, 32, Color(0xFFE3ECF6), peaks = 5, sharp = false)
    for (i in 0 until 9) {
        val x = w * (0.05f + 0.9f * rnd(i, 34))
        val by = h * (0.84f + 0.06f * rnd(i, 35))
        val th = h * (0.14f + 0.1f * rnd(i, 36))
        pine(x, by, th, Color(0xFF2D4A5E))
        pine(x, by - th * 0.05f, th * 0.55f, Color(0xFFEAF2FA).copy(alpha = 0.7f))
    }
    drawRect(Color(0xFFF1F6FB), Offset(0f, h * 0.9f), Size(w, h * 0.1f))
    // Warm window of a small hut.
    val hut = Offset(w * 0.22f, h * 0.88f)
    drawRect(Color(0xFF3A2A25), hut - Offset(18f * density, 20f * density), Size(36f * density, 20f * density))
    val roof = Path().apply { moveTo(hut.x - 24f * density, hut.y - 20f * density); lineTo(hut.x, hut.y - 38f * density); lineTo(hut.x + 24f * density, hut.y - 20f * density); close() }
    drawPath(roof, Color(0xFFF4F8FF))
    val f = 0.8f + 0.2f * sin(t * 5f)
    glow(hut - Offset(0f, 10f * density), 22f * density, Color(0xFFFFC36B).copy(alpha = 0.5f * f))
    drawRect(Color(0xFFFFD27A), hut - Offset(5f * density, 15f * density), Size(10f * density, 8f * density))
    snow(t)
}

private fun DrawScope.fire(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF1A0505), Color(0xFF4A0E07), Color(0xFF9A2A0C)))
    val pulse = 0.75f + 0.25f * sin(t * 1.7f)
    glow(Offset(w * 0.5f, h * 0.55f), w * 0.7f, Color(0xFFFF6A1F).copy(alpha = 0.35f * pulse))
    // Volcano.
    val v = Path().apply {
        moveTo(w * 0.05f, h); lineTo(w * 0.4f, h * 0.42f); lineTo(w * 0.47f, h * 0.45f); lineTo(w * 0.53f, h * 0.43f); lineTo(w * 0.6f, h * 0.42f); lineTo(w * 0.95f, h); close()
    }
    drawPath(v, Color(0xFF1C0907))
    glow(Offset(w * 0.5f, h * 0.43f), w * 0.18f, Color(0xFFFFB347).copy(alpha = 0.8f * pulse))
    // Lava rivers.
    for (k in 0 until 3) {
        val path = Path().apply {
            moveTo(w * (0.46f + 0.04f * k), h * 0.45f)
            var y = h * 0.45f
            var x = w * (0.46f + 0.04f * k)
            while (y < h) {
                y += h * 0.06f
                x += sin(y / 30f + k) * 8f * density + (k - 1) * 6f * density
                lineTo(x, y)
            }
        }
        drawPath(path, Color(0xFFFF7A1A).copy(alpha = 0.6f + 0.3f * sin(t * 2f + k)), style = Stroke((3f - k * 0.6f) * density, cap = StrokeCap.Round))
    }
    // Smoke.
    for (i in 0 until 5) {
        val life = frac(rnd(i, 50) + t * 0.05f)
        glow(Offset(w * 0.5f + sin(t * 0.4f + i) * 30f * density + life * 40f * density, h * (0.4f - life * 0.4f)), w * (0.08f + life * 0.15f), Color(0xFF3A2320).copy(alpha = 0.5f * (1f - life)))
    }
    ridge(h * 0.97f, h * 0.06f, 51, Color(0xFF0E0403), peaks = 8)
    risers(t, 30, Color(0xFFFFA040), 0.08f, 2f)
}

private fun DrawScope.desert(t: Float) {
    val w = size.width
    val h = size.height
    sky(listOf(Color(0xFF2B1640), Color(0xFF8C3B5A), Color(0xFFF08A4B), Color(0xFFFFC271)))
    stars(t, 18, 0.25f)
    val sun = Offset(w * 0.45f, h * 0.6f)
    glow(sun, w * 0.45f, Color(0xFFFFD08A).copy(alpha = 0.5f))
    drawCircle(Color(0xFFFFE2A8), w * 0.15f, sun)
    // Heat shimmer: thin bands drifting across the sun.
    for (k in 0 until 4) {
        val y = sun.y - w * 0.05f + k * w * 0.04f + sin(t * 1.5f + k) * 2f * density
        drawRect(Color(0xFFF08A4B).copy(alpha = 0.25f), Offset(sun.x - w * 0.16f, y), Size(w * 0.32f, 1.5f * density))
    }
    ridge(h * 0.72f, h * 0.1f, 61, Color(0xFFC9673E), peaks = 3, sharp = false)
    ridge(h * 0.82f, h * 0.12f, 62, Color(0xFF9E4B33), peaks = 3, sharp = false, shift = -w * 0.1f)
    ridge(h * 0.94f, h * 0.1f, 63, Color(0xFF5F2A24), peaks = 2, sharp = false)
    // Cactus and a caravan walking slowly.
    val cx = w * 0.84f
    val cb = h * 0.9f
    val cactus = Color(0xFF2D1518)
    drawRoundRect(cactus, Offset(cx - 5f * density, cb - 50f * density), Size(10f * density, 50f * density), androidx.compose.ui.geometry.CornerRadius(5f * density))
    drawRoundRect(cactus, Offset(cx - 18f * density, cb - 36f * density), Size(8f * density, 20f * density), androidx.compose.ui.geometry.CornerRadius(4f * density))
    drawRoundRect(cactus, Offset(cx + 10f * density, cb - 42f * density), Size(8f * density, 18f * density), androidx.compose.ui.geometry.CornerRadius(4f * density))
    val p = frac(t * 0.01f)
    for (k in 0 until 3) {
        val x = w * (p * 1.3f - 0.15f) - k * 26f * density
        val y = h * 0.735f
        val bob = abs(sin(t * 3f + k)) * 1.5f * density
        drawOval(Color(0xFF3A1A1E), Offset(x - 9f * density, y - 9f * density - bob), Size(18f * density, 8f * density))
        drawCircle(Color(0xFF3A1A1E), 3f * density, Offset(x + 10f * density, y - 12f * density - bob))
        drawLine(Color(0xFF3A1A1E), Offset(x - 6f * density, y - 2f * density), Offset(x - 6f * density, y + 4f * density), 1.5f * density)
        drawLine(Color(0xFF3A1A1E), Offset(x + 6f * density, y - 2f * density), Offset(x + 6f * density, y + 4f * density), 1.5f * density)
    }
}

// ---- particles -------------------------------------------------------------------------------

private fun DrawScope.risers(t: Float, count: Int, color: Color, speed: Float, size0: Float) {
    for (i in 0 until count) {
        val life = frac(rnd(i, 3) + t * speed * (0.6f + rnd(i, 4)))
        val x = size.width * rnd(i, 5) + sin(t * (0.6f + rnd(i, 6)) + i) * 16f * density
        val y = size.height * (1.05f - life * 1.1f)
        val fade = sin(life * PI.toFloat())
        val r = (0.8f + size0 * rnd(i, 7)) * density
        drawCircle(color.copy(alpha = 0.2f * fade), r * 4f, Offset(x, y))
        drawCircle(color.copy(alpha = 0.9f * fade), r, Offset(x, y))
    }
}

private fun DrawScope.fireflies(t: Float, color: Color) {
    for (i in 0 until 20) {
        val x = size.width * rnd(i, 1) + sin(t * (0.2f + 0.2f * rnd(i, 2)) + i) * 40f * density
        val y = size.height * (0.2f + 0.75f * rnd(i, 3)) + cos(t * (0.18f + 0.2f * rnd(i, 4)) + i) * 30f * density
        val p = (0.5f + 0.5f * sin(t * (1f + rnd(i, 5)) + i)).let { it * it }
        drawCircle(color.copy(alpha = 0.22f * p), 9f * density, Offset(x, y))
        drawCircle(color.copy(alpha = 0.9f * p), 1.8f * density, Offset(x, y))
    }
}

private fun DrawScope.bubbles(t: Float) {
    for (i in 0 until 18) {
        val life = frac(rnd(i, 1) + t * (0.03f + 0.03f * rnd(i, 2)))
        val x = size.width * rnd(i, 3) + sin(t * 1.4f + i) * 8f * density
        val y = size.height * (1.05f - life * 1.1f)
        val r = (2f + 5f * rnd(i, 4)) * density
        drawCircle(Color(0xFF9FE6F2).copy(alpha = 0.35f * sin(life * PI.toFloat())), r, Offset(x, y), style = Stroke(1.2f * density))
    }
}

private fun DrawScope.twinkles(t: Float, count: Int) {
    for (i in 0 until count) {
        val tw = (0.5f + 0.5f * sin(t * (0.8f + 2f * rnd(i, 3)) + i * 1.7f))
        drawCircle(Color.White.copy(alpha = 0.55f * tw * tw), (0.6f + 1.2f * rnd(i, 4)) * density, Offset(size.width * rnd(i, 1), size.height * rnd(i, 2)))
    }
}

private fun DrawScope.petals(t: Float) {
    for (i in 0 until 16) {
        val life = frac(rnd(i, 2) + t * (0.03f + 0.03f * rnd(i, 1)))
        val x = size.width * rnd(i, 3) + sin(t * (0.5f + 0.6f * rnd(i, 4)) + i) * 40f * density
        val y = -30f * density + life * (size.height + 60f * density)
        val s = (5f + 5f * rnd(i, 5)) * density
        val squash = 0.45f + 0.55f * abs(cos(t * (1.2f + rnd(i, 7)) + i))
        rotate(t * (40f + 60f * rnd(i, 6)) + i * 37f, Offset(x, y)) {
            drawOval(Color(0xFFFFB3CC).copy(alpha = 0.7f), Offset(x - s * squash, y - s * 0.55f), Size(s * 2 * squash, s * 1.1f))
        }
    }
}

private fun DrawScope.scanline(t: Float) {
    val y = frac(t * 0.08f) * size.height
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF35E0F0).copy(alpha = 0.06f), Color.Transparent), y - 50f * density, y + 50f * density),
        Offset(0f, y - 50f * density), Size(size.width, 100f * density),
    )
}

private fun DrawScope.rain(t: Float, strength: Float) {
    val len = 16f * density
    for (i in 0 until (70 * strength / 0.45f).toInt()) {
        val sp = 0.9f + 0.6f * rnd(i, 2)
        val y = frac(rnd(i, 1) + t * sp) * (size.height + len) - len
        val x = size.width * rnd(i, 3) + y * 0.12f
        drawLine(Color(0xFFB8C4D6).copy(alpha = strength * (0.5f + 0.5f * rnd(i, 4))), Offset(x, y), Offset(x + len * 0.12f, y + len), 1f * density)
    }
}

private fun DrawScope.snow(t: Float) {
    for (i in 0 until 60) {
        val sp = 0.025f + 0.04f * rnd(i, 2)
        val y = frac(rnd(i, 1) + t * sp) * (size.height + 20f) - 10f
        val x = size.width * rnd(i, 3) + sin(t * (0.6f + rnd(i, 4)) + i) * 14f * density
        val r = (1f + 2.2f * rnd(i, 5)) * density
        drawCircle(Color.White.copy(alpha = 0.55f + 0.4f * rnd(i, 6)), r, Offset(x, y))
    }
}
