package app.vesper.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// -------------------------------------------------------------------------------------------------
// Ornaments & headers
// -------------------------------------------------------------------------------------------------

/** Theme-specific hairline divider: diamond for Gothic, signal tick for Cyberpunk, plain otherwise. */
@Composable
fun OrnamentDivider(modifier: Modifier = Modifier) {
    val c = Vesper.colors
    val ornament = Vesper.theme.ornament
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val y = size.height / 2
        when (ornament) {
            Ornament.GOTHIC -> {
                val cx = size.width / 2
                val d = 4.dp.toPx()
                drawLine(c.ornament.copy(alpha = 0.55f), Offset(0f, y), Offset(cx - d * 3, y), 1f)
                drawLine(c.ornament.copy(alpha = 0.55f), Offset(cx + d * 3, y), Offset(size.width, y), 1f)
                val p = Path().apply { moveTo(cx, y - d); lineTo(cx + d, y); lineTo(cx, y + d); lineTo(cx - d, y); close() }
                drawPath(p, c.ornament)
            }
            Ornament.NEON -> {
                drawLine(c.outline, Offset(0f, y), Offset(size.width, y), 1f)
                drawRect(c.accentBright, Offset(0f, y - 1.5.dp.toPx()), Size(24.dp.toPx(), 3.dp.toPx()))
            }
            else -> drawLine(c.outline, Offset(0f, y), Offset(size.width, y), 1f)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Vesper.colors
    val ornament = Vesper.theme.ornament
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (ornament) {
            Ornament.GOTHIC -> Text("✦", color = c.ornament, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
            Ornament.NEON -> Box(Modifier.padding(end = 10.dp).size(width = 3.dp, height = 18.dp).background(c.accentBright))
            else -> Unit
        }
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = themedLabel(actionLabel),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = c.accentBright,
                modifier = Modifier
                    .clip(Vesper.shapes.chip)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun OverlineText(text: String, modifier: Modifier = Modifier, color: Color = Vesper.colors.textMuted) {
    Text(
        text = themedLabel(text),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
        maxLines = 1,
    )
}

// -------------------------------------------------------------------------------------------------
// Buttons
// -------------------------------------------------------------------------------------------------

/** Icon button with a soft press-scale micro-animation. */
@Composable
fun PressableIcon(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Vesper.colors.textPrimary,
    size: Dp = 24.dp,
    touchSize: Dp = 44.dp,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    Box(
        modifier
            .size(touchSize)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(bounded = false, radius = touchSize / 2),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(size).scale(scale),
        )
    }
}

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    glow: Boolean = Vesper.theme.ornament == Ornament.NEON,
) {
    val c = Vesper.colors
    val shape = Vesper.shapes.playButton
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "playScale")
    Box(
        modifier
            .size(size)
            .scale(scale)
            .then(
                if (glow) Modifier.shadow(18.dp, shape, ambientColor = c.accent, spotColor = c.accent) else Modifier,
            )
            .clip(shape)
            .background(c.accent)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(color = c.onAccent), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (scaleIn(tween(220), initialScale = 0.6f) + fadeIn(tween(180))) togetherWith
                    (scaleOut(tween(180), targetScale = 0.6f) + fadeOut(tween(140)))
            },
            label = "playPause",
        ) { playing ->
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Пауза" else "Воспроизвести",
                tint = c.onAccent,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

/** Heart with a spring "pop" and an expanding ring when added to favourites. */
@Composable
fun FavoriteButton(
    favorite: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    inactiveTint: Color = Vesper.colors.textSecondary,
) {
    val c = Vesper.colors
    val pop = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(favorite) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        if (favorite) {
            ring.snapTo(0f)
            pop.snapTo(0.6f)
            coroutineScope {
                launch { ring.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
                pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
            }
        } else {
            pop.snapTo(0.85f)
            pop.animateTo(1f, spring(dampingRatio = 0.6f))
        }
    }
    val tint by animateColorAsState(if (favorite) c.accentBright else inactiveTint, tween(250), label = "favTint")
    Box(
        modifier
            .size(44.dp)
            .drawBehind {
                val r = ring.value
                if (r > 0f && r < 1f) {
                    drawCircle(c.accentBright.copy(alpha = (1f - r) * 0.6f), radius = this.size.minDimension / 2f * (0.55f + r * 0.45f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() * (1f - r) + 0.5f))
                }
            }
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (favorite) "Убрать из избранного" else "В избранное",
            tint = tint,
            modifier = Modifier.size(size).scale(pop.value),
        )
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Vesper.colors
    Row(
        modifier
            .clip(Vesper.shapes.medium)
            .background(c.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(themedLabel(text), color = c.onAccent, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Vesper.colors
    Row(
        modifier
            .clip(Vesper.shapes.medium)
            .border(1.dp, c.outline, Vesper.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.textPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(themedLabel(text), color = c.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
    }
}

// -------------------------------------------------------------------------------------------------
// Slider / seek bar
// -------------------------------------------------------------------------------------------------

@Composable
fun VesperSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    trackHeight: Dp = 3.dp,
    restingThumb: Dp = 0.dp,
    activeColor: Color? = null,
) {
    val c = Vesper.colors
    var dragging by remember { mutableStateOf(false) }
    val thumb by animateDpAsState(if (dragging) 16.dp else restingThumb, spring(stiffness = 700f), label = "thumb")
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val fill = activeColor?.let { SolidColor(it) }
        ?: Brush.horizontalGradient(listOf(c.progressStart, c.progressEnd))
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { off ->
                    change((off.x / size.width).coerceIn(0f, 1f))
                    finished?.invoke()
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        change((off.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        dragging = false
                        finished?.invoke()
                    },
                    onDragCancel = {
                        dragging = false
                        finished?.invoke()
                    },
                    onHorizontalDrag = { ch, _ ->
                        ch.consume()
                        change((ch.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            },
    ) {
        val h = trackHeight.toPx() * if (dragging) 1.6f else 1f
        val cy = size.height / 2
        val v = value.coerceIn(0f, 1f)
        drawRoundRect(c.progressTrack, Offset(0f, cy - h / 2), Size(size.width, h), CornerRadius(h / 2))
        if (v > 0f) drawRoundRect(fill, Offset(0f, cy - h / 2), Size(size.width * v, h), CornerRadius(h / 2))
        val t = thumb.toPx()
        if (t > 0f) drawCircle(c.accentBright, radius = t / 2, center = Offset(size.width * v, cy))
    }
}

@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vesper.colors
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val live = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    val shown = dragFraction ?: live
    Column(modifier) {
        VesperSlider(
            value = shown,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            restingThumb = 10.dp,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                formatDuration(if (dragFraction != null) (shown * durationMs).toLong() else positionMs),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = if (dragFraction != null) c.accentBright else c.textMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "-" + formatDuration((durationMs - (shown * durationMs).toLong()).coerceAtLeast(0)),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = c.textMuted,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Chips, search
// -------------------------------------------------------------------------------------------------

@Composable
fun ChipRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(options) { index, label ->
            VesperChip(label, index == selected, onClick = { onSelect(index) })
        }
    }
}

@Composable
fun VesperChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    val bg by animateColorAsState(if (selected) c.highlight else Color.Transparent, tween(220), label = "chipBg")
    val fg by animateColorAsState(if (selected) c.accentBright else c.textSecondary, tween(220), label = "chipFg")
    val border by animateColorAsState(if (selected) c.accent.copy(alpha = 0.6f) else c.outline, tween(220), label = "chipBorder")
    Text(
        text = label,
        color = fg,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(Vesper.shapes.chip)
            .background(bg)
            .border(1.dp, border, Vesper.shapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Vesper.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(Vesper.shapes.medium)
            .background(c.surfaceHigh)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
            if (query.isEmpty()) {
                Text(placeholder, color = c.textMuted, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.merge(TextStyle(color = c.textPrimary)),
                cursorBrush = SolidColor(c.accentBright),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        PressableIcon(Icons.Rounded.Close, "Закрыть поиск", onClick = onClose, tint = c.textSecondary, size = 20.dp)
    }
}

// -------------------------------------------------------------------------------------------------
// Now-playing indicator
// -------------------------------------------------------------------------------------------------

@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Vesper.colors.accentBright) {
    val transition = rememberInfiniteTransition(label = "bars")
    val a by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "b1")
    val b by transition.animateFloat(1f, 0.25f, infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val d by transition.animateFloat(0.5f, 0.95f, infiniteRepeatable(tween(350, easing = LinearEasing), RepeatMode.Reverse), label = "b3")
    Canvas(modifier.size(16.dp)) {
        val w = size.width / 5
        val values = if (playing) listOf(a, b, d) else listOf(0.35f, 0.6f, 0.45f)
        values.forEachIndexed { i, v ->
            val h = size.height * v
            drawRoundRect(color, Offset(w * (i * 2), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}
