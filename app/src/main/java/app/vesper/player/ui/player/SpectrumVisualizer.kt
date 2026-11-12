package app.vesper.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import app.vesper.player.playback.SpectrumTap
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bars that follow the real sound (bass on the left, highs on the right): they jump up at once and
 * fall back smoothly, each with a small "peak" mark that hangs for a moment. Drawn behind the
 * player's controls in the colours of the current theme.
 */
@Composable
fun SpectrumVisualizer(modifier: Modifier = Modifier, playing: Boolean) {
    val c = Vesper.colors
    val square = Vesper.theme.ornament == Ornament.NEON || Vesper.theme.ornament == Ornament.MINIMAL
    val n = SpectrumTap.BANDS
    val levels = remember { FloatArray(n) }
    val peaks = remember { FloatArray(n) }
    val scratch = remember { FloatArray(n) }
    val tick = remember { mutableIntStateOf(0) }
    val isPlaying by rememberUpdatedState(playing)

    DisposableEffect(Unit) {
        SpectrumTap.acquire()
        onDispose { SpectrumTap.release() }
    }
    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            var moving = false
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val fresh = isPlaying && SpectrumTap.read(scratch)
                for (i in 0 until n) {
                    val target = if (fresh) scratch[i] else 0f
                    val v = levels[i]
                    // Quick rise, slow fall — the way level meters behave.
                    val k = if (target > v) minOf(1f, dt * 30f) else minOf(1f, dt * 7f)
                    levels[i] = v + (target - v) * k
                    peaks[i] = maxOf(levels[i], peaks[i] - dt * 0.4f)
                    if (peaks[i] > 0.004f) moving = true
                }
                if (moving) tick.intValue++
            }
            // Nothing to draw (pause, silence): don't wake up on every frame.
            if (!moving) {
                delay(120)
                last = 0L
            }
        }
    }

    val brush = remember(c.accentBright, c.accent) {
        Brush.verticalGradient(listOf(c.accentBright.copy(alpha = 0.50f), c.accent.copy(alpha = 0.10f)))
    }
    val peakColor = c.ornament.copy(alpha = 0.55f)
    Canvas(modifier) {
        tick.intValue // redraw on every new frame of data
        val w = size.width
        val h = size.height
        val slot = w / n
        val bar = slot * 0.62f
        val radius = if (square) CornerRadius.Zero else CornerRadius(bar / 2f, bar / 2f)
        for (i in 0 until n) {
            val x = i * slot + (slot - bar) / 2f
            val bh = levels[i] * h
            if (bh > 1f) drawRoundRect(brush, Offset(x, h - bh), Size(bar, bh + bar), radius)
            val ph = peaks[i] * h
            if (ph > 3f) drawRect(peakColor, Offset(x, h - ph - 4f), Size(bar, 2.5f))
        }
    }
}
