package app.vesper.player.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.vesper.player.data.media.ArtworkLoader
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper
import kotlin.math.abs

enum class ArtSize(val px: Int) { Small(160), Medium(420), Large(1000) }

/**
 * Cover art / video thumbnail loaded from the media file itself. Falls back to a themed,
 * deterministic placeholder (monogram + the theme's motif) instead of a generic icon.
 */
@Composable
fun Artwork(
    uri: String?,
    seed: String,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    size: ArtSize = ArtSize.Small,
    shape: Shape = Vesper.shapes.artwork,
    onBitmap: ((Bitmap?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(
        initialValue = uri?.let { ArtworkLoader.cached(it, size.px) },
        uri, size,
    ) {
        val cached = uri?.let { ArtworkLoader.cached(it, size.px) }
        value = cached
        if (cached == null && uri != null) value = ArtworkLoader.load(context, uri, isVideo, size.px)
        onBitmap?.invoke(value)
    }
    Box(modifier.clip(shape)) {
        Crossfade(targetState = bitmap, animationSpec = tween(280), label = "artwork") { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = remember(bmp) { bmp.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ArtPlaceholder(seed = seed, isVideo = isVideo, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun ArtPlaceholder(seed: String, isVideo: Boolean, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    val theme = Vesper.theme
    val hash = abs(seed.hashCode())
    val tint = (hash % 100) / 100f
    val base = lerp(c.surfaceHigh, c.accent, 0.06f + tint * 0.12f)
    val motif = c.ornament.copy(alpha = if (c.isDark) 0.45f else 0.6f)
    BoxWithConstraints(modifier.background(base), contentAlignment = Alignment.Center) {
        val w = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val s = size.minDimension
            when (theme.ornament) {
                Ornament.GOTHIC -> {
                    val inset = s * 0.07f
                    drawRect(motif, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(width = s * 0.006f + 0.6f))
                    val inset2 = inset + s * 0.025f
                    drawRect(motif.copy(alpha = motif.alpha * 0.5f), Offset(inset2, inset2), Size(size.width - inset2 * 2, size.height - inset2 * 2), style = Stroke(width = s * 0.004f + 0.5f))
                    val d = s * 0.035f
                    val cx = size.width / 2
                    val path = Path().apply {
                        moveTo(cx, inset - d); lineTo(cx + d, inset); lineTo(cx, inset + d); lineTo(cx - d, inset); close()
                    }
                    drawPath(path, motif)
                }
                Ornament.NEON -> {
                    val l = s * 0.18f
                    val st = Stroke(width = s * 0.012f + 0.8f)
                    val p = s * 0.08f
                    val a = Path().apply { moveTo(p, p + l); lineTo(p, p); lineTo(p + l, p) }
                    val b = Path().apply {
                        moveTo(size.width - p, size.height - p - l); lineTo(size.width - p, size.height - p); lineTo(size.width - p - l, size.height - p)
                    }
                    drawPath(a, c.accentBright.copy(alpha = 0.7f), style = st)
                    drawPath(b, c.accent.copy(alpha = 0.8f), style = st)
                }
                Ornament.MIDNIGHT -> {
                    for (i in 0 until 5) {
                        val y = size.height * (0.62f + i * 0.08f)
                        drawLine(motif.copy(alpha = 0.18f + i * 0.08f), Offset(0f, y), Offset(size.width, y), strokeWidth = s * 0.004f + 0.5f)
                    }
                }
                Ornament.ROSE -> {
                    for (i in 1..3) {
                        drawCircle(motif.copy(alpha = 0.32f / i), radius = s * (0.22f + i * 0.13f), style = Stroke(width = s * 0.006f + 0.5f))
                    }
                }
                Ornament.FOREST -> {
                    for (i in 0 until 6) {
                        val x = size.width * (0.12f + i * 0.15f)
                        drawLine(
                            motif.copy(alpha = 0.35f),
                            Offset(x, size.height),
                            Offset(x + s * 0.06f, size.height * (0.70f - (i % 3) * 0.06f)),
                            strokeWidth = s * 0.006f + 0.5f,
                        )
                    }
                }
                Ornament.MINIMAL, Ornament.NONE -> Unit
            }
        }
        if (isVideo) {
            Icon(Icons.Rounded.Movie, null, tint = c.textMuted, modifier = Modifier.size(w * 0.3f))
        } else {
            val letter = seed.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "♪"
            val fontSize = with(LocalDensity.current) { (w * 0.42f).toSp() }
            Text(
                text = letter,
                style = TextStyle(
                    fontFamily = theme.type.display,
                    fontWeight = theme.type.displayWeight,
                    fontStyle = if (theme.type.displayItalic) FontStyle.Italic else FontStyle.Normal,
                    fontSize = fontSize,
                    color = lerp(c.textMuted, c.textSecondary, 0.35f).copy(alpha = 0.9f),
                ),
            )
        }
    }
}

/** Darkened overlay for text on top of artwork. */
val ScrimColor = Color(0x99000000)
