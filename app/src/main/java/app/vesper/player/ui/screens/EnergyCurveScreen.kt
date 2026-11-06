package app.vesper.player.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.wave.CurveBuilder
import app.vesper.player.data.wave.CurvePick
import app.vesper.player.data.wave.CurvePreset
import app.vesper.player.data.wave.EnergyCurve
import app.vesper.player.data.wave.SoundProfile
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val durations = listOf(30, 45, 60, 90, 120)

/**
 * "Кривая энергии": draw with a finger how intense the music should be over the next N minutes
 * (warm-up → peak → cool-down, a slow fade to sleep, anything) and get a queue that follows the line.
 */
@Composable
fun EnergyCurveScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val bottom = LocalBottomInset.current
    val lib by vm.library.collectAsStateWithLifecycle()

    var presetIndex by remember { mutableIntStateOf(0) }
    var durationIndex by remember { mutableIntStateOf(2) }
    val points = remember { mutableStateListOf<Float>().apply { addAll(EnergyCurve.preset(CurvePreset.WORKOUT, 60).points) } }
    var drawVersion by remember { mutableIntStateOf(0) }
    var seed by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<Map<Long, SoundProfile>?>(null) }
    var picks by remember { mutableStateOf<List<CurvePick>>(emptyList()) }

    LaunchedEffect(lib.allTracks.size) { profiles = vm.soundProfiles() }
    // Rebuild after every finished stroke, preset, length or reshuffle.
    LaunchedEffect(drawVersion, durationIndex, seed, profiles) {
        val p = profiles ?: return@LaunchedEffect
        val curve = EnergyCurve(points.toList(), durations[durationIndex])
        picks = withContext(Dispatchers.Default) { CurveBuilder.build(curve, lib.allTracks, p, seed) }
    }

    fun applyPreset(i: Int) {
        presetIndex = i
        val preset = CurvePreset.entries[i]
        points.clear()
        points.addAll(EnergyCurve.preset(preset, durations[durationIndex]).points)
        drawVersion++
    }

    val totalMs = durations[durationIndex] * 60_000L
    val title = if (presetIndex >= 0) CurvePreset.entries[presetIndex].title else "Своя кривая"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                OverlineText("Кривая энергии", color = c.ornament)
            }
            Text(
                "Нарисуй пальцем, какой должна быть музыка: снизу — тихо и спокойно, сверху — громко и быстро.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            ChipRow(CurvePreset.entries.map { it.title }, presetIndex, onSelect = { applyPreset(it) })
            Spacer(Modifier.height(8.dp))
            ChipRow(durations.map { if (it < 60) "$it мин" else if (it % 60 == 0) "${it / 60} ч" else "${it / 60} ч ${it % 60} мин" }, durationIndex, onSelect = { durationIndex = it })
            Spacer(Modifier.height(14.dp))
            CurveCanvas(
                points = points,
                picks = picks,
                totalMs = totalMs,
                onDrawn = {
                    presetIndex = -1
                    drawVersion++
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatClock(0), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Text(formatClock(totalMs / 2), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Text(formatClock(totalMs), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    "Включить",
                    onClick = { vm.player.playTracks(picks.map { it.track }, 0, shuffle = false) },
                    icon = Icons.Rounded.PlayArrow,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    "В плейлист",
                    onClick = { vm.createPlaylist("Кривая · $title", PlaylistKind.MUSIC, picks.map { it.track.id }) },
                    icon = Icons.Rounded.PlaylistAdd,
                    modifier = Modifier.weight(1f),
                )
                PressableIcon(Icons.Rounded.Refresh, "Другие треки", onClick = { seed++ })
            }
            Spacer(Modifier.height(16.dp))
            if (profiles != null && picks.isEmpty()) {
                EmptyState(Icons.Rounded.PlayArrow, "Не из чего собрать", "Нужны песни длиннее минуты в библиотеке. Чем больше треков проанализировано, тем точнее кривая.")
            } else if (picks.isNotEmpty()) {
                val minutes = (picks.sumOf { it.track.durationMs } / 60_000).toInt()
                OverlineText("${picks.size} треков · $minutes мин", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
        }
        itemsIndexed(picks, key = { _, p -> p.track.id }) { i, p ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatClock(p.startMs), style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.width(48.dp))
                Artwork(p.track.uri, p.track.title, modifier = Modifier.size(44.dp), size = ArtSize.Small)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.track.title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.track.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                // Little meter: how intense this track is.
                Box(Modifier.width(36.dp).height(6.dp).clip(Vesper.shapes.chip).background(c.progressTrack)) {
                    Box(Modifier.fillMaxWidth(p.level.coerceIn(0.05f, 1f)).height(6.dp).background(Brush.horizontalGradient(listOf(c.progressStart, c.progressEnd))))
                }
                PressableIcon(
                    Icons.Rounded.PlayArrow,
                    "Играть отсюда",
                    onClick = { vm.player.playTracks(picks.map { it.track }, i, shuffle = false) },
                    tint = c.textMuted,
                    size = 20.dp,
                )
            }
        }
    }
}

@Composable
private fun CurveCanvas(
    points: MutableList<Float>,
    picks: List<CurvePick>,
    totalMs: Long,
    onDrawn: () -> Unit,
) {
    val c = Vesper.colors
    var lastIndex by remember { mutableIntStateOf(-1) }
    var lastValue by remember { mutableStateOf(0f) }

    fun paint(pos: Offset, width: Float, height: Float, connect: Boolean) {
        val n = points.size
        val idx = ((pos.x / width) * (n - 1)).toInt().coerceIn(0, n - 1)
        val value = (1f - pos.y / height).coerceIn(0.02f, 0.98f)
        if (connect && lastIndex >= 0 && lastIndex != idx) {
            // Fill the gap between two finger positions so fast strokes leave no holes.
            val step = if (idx > lastIndex) 1 else -1
            var i = lastIndex
            while (i != idx) {
                val f = (i - lastIndex).toFloat() / (idx - lastIndex)
                points[i] = lastValue + (value - lastValue) * f
                i += step
            }
        }
        points[idx] = value
        lastIndex = idx
        lastValue = value
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 20.dp)
            .clip(Vesper.shapes.medium)
            .background(c.surfaceHigh)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    lastIndex = -1
                    paint(pos, size.width.toFloat(), size.height.toFloat(), connect = false)
                    onDrawn()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        lastIndex = -1
                        paint(pos, size.width.toFloat(), size.height.toFloat(), connect = false)
                    },
                    onDragEnd = { onDrawn() },
                    onDragCancel = { onDrawn() },
                ) { change, _ ->
                    change.consume()
                    paint(change.position, size.width.toFloat(), size.height.toFloat(), connect = true)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // Guide lines: calm / medium / intense.
        for (k in 1..3) {
            val y = h * k / 4f
            drawLine(c.outline.copy(alpha = 0.5f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (points.size < 2) return@Canvas
        val stepX = w / (points.size - 1)
        val line = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = h * (1f - v)
            if (i == 0) {
                line.moveTo(x, y)
            } else {
                val px = (i - 1) * stepX
                val py = h * (1f - points[i - 1])
                line.cubicTo((px + x) / 2f, py, (px + x) / 2f, y, x, y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.45f), c.accent.copy(alpha = 0.03f))))
        drawPath(line, c.accentBright, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        // Where each chosen track lands: dot at its start time and actual intensity.
        if (totalMs > 0) {
            for (p in picks) {
                val x = (p.startMs.toFloat() / totalMs).coerceIn(0f, 1f) * w
                val y = h * (1f - p.level)
                drawCircle(c.textPrimary, radius = 3.5.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return "$h:${m.toString().padStart(2, '0')}"
}
