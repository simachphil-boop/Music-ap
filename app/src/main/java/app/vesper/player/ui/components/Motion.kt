package app.vesper.player.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay

/**
 * Content slides up and fades in the first time it appears on a screen, one section after another.
 * [seen] is remembered by the screen, so sections scrolled back into view don't animate again.
 */
@Composable
fun Appear(seen: MutableSet<String>, key: String, order: Int, content: @Composable () -> Unit) {
    val anim = remember(key) { Animatable(if (key in seen) 1f else 0f) }
    LaunchedEffect(key) {
        if (anim.value < 1f) {
            seen += key
            delay(order.coerceIn(0, 8) * 55L)
            anim.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        }
    }
    Box(
        Modifier.graphicsLayer {
            alpha = anim.value
            translationY = (1f - anim.value) * 26.dp.toPx()
        },
    ) { content() }
}

/** Click that squashes the card a little under the finger and springs back — feels physical. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bouncyClick(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.45f, stiffness = 520f), label = "bouncy")
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = ripple(),
            onClick = onClick,
            onLongClick = onLongClick,
        )
}

/** Gentle floating motion for decorative icons (empty states, badges). */
fun Modifier.floating(amplitudeDp: Float = 5f, periodMs: Int = 2600): Modifier = composed {
    val inf = rememberInfiniteTransition(label = "float")
    val v = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "floatV")
    graphicsLayer { translationY = v.value * amplitudeDp.dp.toPx() }
}

/**
 * Theme-specific light on text: a gold gleam sliding across for Gothic, a flickering neon tube for
 * Cyberpunk, a slow sheen for the rest. Drawn over the glyphs only, never recomposes.
 */
fun Modifier.themedShine(): Modifier = composed {
    val ornament = Vesper.theme.ornament
    val c = Vesper.colors
    val inf = rememberInfiniteTransition(label = "shine")
    val p = inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "shineP")
    if (ornament == Ornament.NONE || ornament == Ornament.MINIMAL) return@composed this
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val t = p.value
            when (ornament) {
                Ornament.NEON -> {
                    // Neon tube: mostly steady, with quick flickers.
                    val f = (t * 13f) % 1f
                    val dim = if (f < 0.04f || (t > 0.62f && t < 0.64f)) 0.55f else 0f
                    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim), blendMode = BlendMode.DstOut)
                    drawRect(c.accentBright.copy(alpha = 0.18f), blendMode = BlendMode.SrcAtop)
                }
                else -> {
                    // A band of light passes across the letters, then pauses.
                    val x = (t * 2.2f - 0.6f) * size.width
                    val band = size.width * 0.35f
                    val light = if (ornament == Ornament.GOTHIC) c.ornament else c.accentBright
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, light.copy(alpha = 0.85f), Color.Transparent),
                            start = Offset(x - band, 0f),
                            end = Offset(x + band, size.height),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                }
            }
        }
}
