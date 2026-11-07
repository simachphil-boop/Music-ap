package app.floya.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.floya.desktop.AppState
import app.floya.desktop.Track
import app.floya.desktop.formatTime
import app.floya.desktop.library.Artwork
import app.floya.desktop.theme.Vesper
import app.floya.desktop.theme.themedLabel

val LocalApp = androidx.compose.runtime.staticCompositionLocalOf<AppState> { error("no app") }

/** Lets the window know a text field is focused, so Space types instead of pausing. */
fun Modifier.trackTextFocus(): Modifier = this.onFocusChanged { app.floya.desktop.textFocused = it.isFocused }

/** Cover of a track: embedded picture / thumbnail, or a themed gradient with a note while loading. */
@Composable
fun Cover(track: Track?, modifier: Modifier, shape: Shape = Vesper.shapes.artwork) {
    val c = Vesper.colors
    val img by produceState<ImageBitmap?>(track?.let { Artwork.peek(it) }, track?.id) {
        value = track?.let { Artwork.load(it) }
    }
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(c.highlight, c.surfaceHigh))), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) {
            Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, null, tint = c.textMuted, modifier = Modifier.fillMaxSize(0.4f))
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, hint: String, onClick: () -> Unit, size: Dp = 22.dp, tint: Color = Vesper.colors.textSecondary, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        modifier
            .clip(CircleShape)
            .hoverable(src)
            .background(if (hovered) Vesper.colors.surfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, hint, tint = if (hovered) Vesper.colors.textPrimary else tint, modifier = Modifier.size(size))
    }
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Row(
        modifier
            .clip(Vesper.shapes.chip)
            .background(Brush.horizontalGradient(listOf(c.progressStart, c.progressEnd)))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.onAccent)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Row(
        modifier
            .clip(Vesper.shapes.chip)
            .border(1.dp, c.outline, Vesper.shapes.chip)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.textPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    Box(
        Modifier
            .clip(Vesper.shapes.chip)
            .background(if (selected) c.accent else c.surfaceHigh)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onAccent else c.textSecondary)
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Vesper.colors.ornament) {
    Text(themedLabel(text), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Vesper.colors.textPrimary, modifier = modifier)
}

/** Thin themed slider; [extraFrom] (0..1) marks where the "boost" zone begins (drawn in accent). */
@Composable
fun FloyaSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
    extraFrom: Float? = null,
    height: Dp = 4.dp,
) {
    val c = Vesper.colors
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val thumb by animateFloatAsState(if (hovered || dragging) 1f else 0f, tween(150))
    BoxWithConstraints(
        modifier
            .height(18.dp)
            .hoverable(src)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)); onFinished() }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true; onChange((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { dragging = false; onFinished() },
                    onDragCancel = { dragging = false; onFinished() },
                ) { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(height).clip(CircleShape).background(c.progressTrack))
        Box(
            Modifier.width(w * value.coerceIn(0f, 1f)).height(height).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(c.progressStart, if (extraFrom != null && value > extraFrom) c.danger else c.progressEnd))),
        )
        if (extraFrom != null) {
            Box(Modifier.offset(x = w * extraFrom - 1.dp).width(2.dp).height(10.dp).background(c.textMuted))
        }
        val d = 12.dp * (0.5f + 0.5f * thumb)
        Box(Modifier.offset(x = w * value.coerceIn(0f, 1f) - d / 2).size(d).clip(CircleShape).background(c.textPrimary))
    }
}

/** A row in any track list: cover, title, artist, album, length; right-click for more. */
@Composable
fun TrackRow(
    track: Track,
    index: Int?,
    current: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    menu: () -> List<ContextMenuItem>,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Vesper.colors
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    ContextMenuArea(items = menu) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Vesper.shapes.small)
                .hoverable(src)
                .background(if (current) c.highlight.copy(alpha = 0.8f) else if (hovered) c.surfaceHigh.copy(alpha = 0.7f) else Color.Transparent)
                .clickable(onClick = onPlay)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    if (current && playing) Icon(Icons.Rounded.Equalizer, null, tint = c.accentBright, modifier = Modifier.size(18.dp))
                    else Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                Spacer(Modifier.width(6.dp))
            }
            Cover(track, Modifier.size(42.dp), Vesper.shapes.small)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1.4f)) {
                Text(
                    track.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (current) c.accentBright else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(track.album, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            if (track.isOnline) {
                Text("онлайн", style = MaterialTheme.typography.labelSmall, color = c.ornament, modifier = Modifier.padding(end = 10.dp))
            }
            Text(if (track.durationMs > 0) formatTime(track.durationMs) else "", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.width(48.dp))
            if (trailing != null) trailing()
        }
    }
}

/** Standard right-click menu for a track. */
@Composable
fun trackMenu(track: Track, extra: List<ContextMenuItem> = emptyList()): () -> List<ContextMenuItem> {
    val app = LocalApp.current
    return {
        val playlists = app.playlists.value
        buildList {
            add(ContextMenuItem("Играть следующим") { app.player.addToQueue(listOf(track), next = true) })
            add(ContextMenuItem("Добавить в очередь") { app.player.addToQueue(listOf(track)) })
            playlists.forEach { p -> add(ContextMenuItem("В плейлист «${p.name}»") { app.addToPlaylist(p.id, listOf(track)) }) }
            add(ContextMenuItem("В новый плейлист") { app.createPlaylist(track.title, listOf(track)); app.say("Плейлист создан") })
            if (track.url != null) add(ContextMenuItem("Скачать") { app.downloads.enqueue(track.url, track.title, track.artist, track.thumbnail, video = false); app.say("Скачиваю «${track.title}»") })
            if (track.path != null) add(ContextMenuItem("Показать в папке") {
                runCatching { Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", track.path)) }
            })
            addAll(extra)
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val c = Vesper.colors
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Column(Modifier.width(380.dp).clip(Vesper.shapes.large).background(c.surface).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).trackTextFocus(),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Отмена", color = c.textSecondary) }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(confirm, onClick = { onConfirm(text.trim()) })
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
fun fieldColors() = Vesper.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary,
        focusedBorderColor = c.accentBright,
        unfocusedBorderColor = c.outline,
        cursorColor = c.accentBright,
        focusedContainerColor = c.surface.copy(alpha = 0.6f),
        unfocusedContainerColor = c.surface.copy(alpha = 0.6f),
        focusedPlaceholderColor = c.textMuted,
        unfocusedPlaceholderColor = c.textMuted,
    )
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val c = Vesper.colors
    Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.floating().size(84.dp).clip(Vesper.shapes.large).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
