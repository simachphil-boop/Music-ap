package app.floya.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.floya.desktop.formatTime
import app.floya.desktop.theme.FrameGlow
import app.floya.desktop.theme.FrameOverlay
import app.floya.desktop.theme.ThemeAmbience
import app.floya.desktop.theme.Vesper
import app.floya.desktop.theme.rememberFrameMotion
import androidx.compose.runtime.collectAsState
import kotlin.math.pow
import kotlin.math.roundToInt

/** Controls at the bottom of the window. */
@Composable
fun PlayerBar(onExpand: () -> Unit) {
    val app = LocalApp.current
    val c = Vesper.colors
    val track by app.player.current.collectAsState()
    val playing by app.player.engine.playing.collectAsState()
    val loading by app.player.engine.loading.collectAsState()
    val pos by app.player.engine.position.collectAsState()
    val settings by app.settings.collectAsState()
    var scrub by remember { mutableStateOf<Float?>(null) }
    val dur = track?.durationMs ?: 0L

    Row(
        Modifier.fillMaxWidth().height(88.dp).background(c.surface.copy(alpha = 0.96f)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: what's playing.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                Cover(track, Modifier.size(58.dp).clickable(onClick = onExpand).pointerHoverIcon(PointerIcon.Hand), Vesper.shapes.small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track!!.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track!!.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text("Выберите трек", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        }
        // Center: transport + progress.
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Rounded.Shuffle, "Перемешать очередь", { app.player.shuffleQueue() }, 18.dp)
                IconBtn(Icons.Rounded.SkipPrevious, "Назад", { app.player.previous() }, 26.dp, c.textPrimary)
                PlayButton(playing, loading) { app.player.toggle() }
                IconBtn(Icons.Rounded.SkipNext, "Вперёд", { app.player.next() }, 26.dp, c.textPrimary)
                IconBtn(
                    if (settings.repeat == 2) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор",
                    { app.player.cycleRepeat() }, 18.dp, if (settings.repeat > 0) c.accentBright else c.textSecondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = scrub?.let { (it * dur).toLong() } ?: pos
                Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                Spacer(Modifier.width(10.dp))
                FloyaSlider(
                    value = scrub ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    onChange = { scrub = it },
                    onFinished = {
                        scrub?.let { if (dur > 0) app.player.seek((it * dur).toLong()) }
                        scrub = null
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(if (dur > 0) formatTime(dur) else "", style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.width(48.dp))
            }
        }
        // Right: volume with boost zone, expand.
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            VolumeControl(settings.volume, settings.boost, Modifier.width(190.dp))
            IconBtn(Icons.Rounded.OpenInFull, "Развернуть плеер", onExpand, 18.dp)
        }
    }
}

@Composable
fun PlayButton(playing: Boolean, loading: Boolean, big: Boolean = false, onClick: () -> Unit) {
    val c = Vesper.colors
    val s = if (big) 68.dp else 46.dp
    Box(
        Modifier.padding(horizontal = 8.dp).size(s).clip(Vesper.shapes.playButton)
            .background(Brush.linearGradient(listOf(c.progressStart, c.progressEnd)))
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(s * 0.45f), color = c.onAccent, strokeWidth = 2.dp)
        else Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Играть", tint = c.onAccent, modifier = Modifier.size(s * 0.55f))
    }
}

/**
 * One slider: left of the mark is normal volume, right of it is Floya's extra boost
 * (up to ≈ 280 % loudness, soft-limited). Red past ~200 % — careful with your ears.
 */
@Composable
fun VolumeControl(volume: Float, boost: Float, modifier: Modifier) {
    val app = LocalApp.current
    val c = Vesper.colors
    val maxBoost = 2.8f
    val pos = if (boost > 1.001f) 0.75f + 0.25f * (boost - 1f) / (maxBoost - 1f) else volume * 0.75f
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VolumeDown, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            FloyaSlider(
                value = pos,
                onChange = { p ->
                    if (p <= 0.75f) {
                        app.player.setVolume(p / 0.75f)
                        if (boost > 1f) app.player.setBoost(1f)
                    } else {
                        if (volume < 1f) app.player.setVolume(1f)
                        app.player.setBoost(1f + (p - 0.75f) / 0.25f * (maxBoost - 1f))
                    }
                },
                modifier = Modifier.weight(1f),
                extraFrom = 0.75f,
            )
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.VolumeUp, null, tint = if (boost > 1f) c.accentBright else c.textMuted, modifier = Modifier.size(18.dp))
        }
        if (boost > 1.01f) {
            // Loudness by ear: +10 dB ≈ twice as loud.
            val db = 20 * kotlin.math.log10(boost)
            val percent = (100 * 2.0.pow(db / 10.0)).roundToInt()
            Text(
                "Усиление $percent%" + if (percent > 200) " · берегите слух" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (percent > 200) c.danger else c.accentBright,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Full-window player: big living cover, controls and the queue. */
@Composable
fun NowPlaying(visible: Boolean, onClose: () -> Unit) {
    val app = LocalApp.current
    val c = Vesper.colors
    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
        val track by app.player.current.collectAsState()
        val playing by app.player.engine.playing.collectAsState()
        val loading by app.player.engine.loading.collectAsState()
        val queue by app.player.queue.collectAsState()
        val index by app.player.index.collectAsState()
        val level by app.player.engine.level.collectAsState()
        val settings by app.settings.collectAsState()
        val ornament = Vesper.theme.ornament
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.highlight, c.background, c.background)))) {
            if (settings.ambience) ThemeAmbience(Modifier.fillMaxSize(), playing = playing, intensity = 1.2f)
            Row(Modifier.fillMaxSize().padding(36.dp)) {
                Column(Modifier.weight(1.2f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val motion = rememberFrameMotion(playing)
                    Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        FrameGlow(ornament, motion, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.35f; scaleY = 1.35f })
                        // The cover pulses a little with the actual loudness of the music.
                        Box(Modifier.fillMaxSize().graphicsLayer { val k = 1f + 0.025f * level; scaleX = k; scaleY = k }) {
                            Cover(track, Modifier.fillMaxSize())
                            FrameOverlay(ornament, motion, Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(track?.title ?: "", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(track?.artist ?: "", style = MaterialTheme.typography.titleMedium, color = c.textSecondary, maxLines = 1)
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Icons.Rounded.SkipPrevious, "Назад", { app.player.previous() }, 32.dp, c.textPrimary)
                        PlayButton(playing, loading, big = true) { app.player.toggle() }
                        IconBtn(Icons.Rounded.SkipNext, "Вперёд", { app.player.next() }, 32.dp, c.textPrimary)
                    }
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(0.8f).fillMaxHeight().clip(Vesper.shapes.large).background(c.surface.copy(alpha = 0.55f)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Overline("Очередь · ${queue.size}", Modifier.weight(1f))
                        IconBtn(Icons.Rounded.ExpandMore, "Свернуть", onClose, 24.dp)
                    }
                    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
                    LazyColumn(state = state) {
                        itemsIndexed(queue, key = { i, t -> "$i:${t.id}" }) { i, t ->
                            TrackRow(
                                t, i, current = i == index, playing = playing,
                                onPlay = { app.player.playAt(i) },
                                menu = trackMenu(t, listOf(ContextMenuItem("Убрать из очереди") { app.player.removeFromQueue(i) })),
                            )
                        }
                    }
                }
            }
        }
    }
}
