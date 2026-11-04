package app.vesper.player.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.playback.QueueEntry
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.PlayingBars
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.VesperChip
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.components.formatTotalDuration
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val state by vm.player.state.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf(state.queue) }
    LaunchedEffect(state.queue) { items = state.queue }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.currentIndex - 1).coerceAtLeast(0))
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = from.index
        val t = to.index
        if (f in items.indices && t in items.indices) {
            items = items.toMutableList().apply { add(t, removeAt(f)) }
            vm.player.moveQueueItem(f, t)
        }
    }

    VesperSheet(onDismiss) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Очередь", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                Text(
                    "${tracksCount(items.size)} · ${formatTotalDuration(items.sumOf { it.durationMs })}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Text(
                themedLabel("Очистить"),
                style = MaterialTheme.typography.labelMedium,
                color = c.danger,
                modifier = Modifier
                    .clip(Vesper.shapes.chip)
                    .clickable { dismiss { vm.player.clearQueue() } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Text(
            "Удерживайте ≡ и перетаскивайте, чтобы изменить порядок",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(items, key = { it.key }) { entry ->
                ReorderableItem(reorderState, key = entry.key) { dragging ->
                    val index = items.indexOf(entry)
                    val isCurrent = index == state.currentIndex
                    QueueRow(
                        entry = entry,
                        isCurrent = isCurrent,
                        playing = isCurrent && state.isPlaying,
                        dragging = dragging,
                        onClick = { vm.player.skipToQueueIndex(index) },
                        onRemove = { vm.player.removeQueueItem(index) },
                        handle = {
                            Icon(
                                Icons.Rounded.DragHandle,
                                "Перетащить",
                                tint = c.textMuted,
                                modifier = Modifier.draggableHandle().padding(12.dp).size(22.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    playing: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    handle: @Composable () -> Unit,
) {
    val c = Vesper.colors
    val bg by animateColorAsState(
        when {
            dragging -> c.surfaceHigh
            isCurrent -> c.highlight
            else -> c.surface
        },
        tween(200),
        label = "queueBg",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (dragging) Modifier.shadow(10.dp) else Modifier)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp)) {
            Artwork(entry.uri, entry.title, Modifier.size(44.dp))
            if (isCurrent) {
                Box(
                    Modifier.size(44.dp).clip(Vesper.shapes.artwork).background(Color(0x88000000)),
                    contentAlignment = Alignment.Center,
                ) { PlayingBars(playing, color = Color.White) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) c.accentBright else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.artist} · ${formatDuration(entry.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!isCurrent) PressableIcon(Icons.Rounded.Close, "Убрать из очереди", onClick = onRemove, tint = c.textMuted, size = 18.dp)
        handle()
    }
}

@Composable
fun SleepTimerSheet(onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val sleep by vm.sleepTimer.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep) {
        while (sleep.endsAtMillis > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val presets = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    VesperSheet(onDismiss) { dismiss ->
        Text("Таймер сна", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        Text(
            when {
                sleep.endOfTrack -> "Музыка остановится после текущего трека"
                sleep.endsAtMillis > 0 -> "Остановка через ${formatDuration(sleep.remainingMs(now))} — с плавным затуханием"
                else -> "Музыка плавно затихнет и встанет на паузу"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (sleep.active) c.accentBright else c.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { m ->
                VesperChip(
                    label = if (m >= 60 && m % 60 == 0) "${m / 60} ч" else if (m > 60) "${m / 60} ч ${m % 60} мин" else "$m мин",
                    selected = false,
                    onClick = { dismiss { vm.sleepTimer.start(m) } },
                )
            }
            VesperChip(
                label = "До конца трека",
                selected = sleep.endOfTrack,
                onClick = { dismiss { vm.sleepTimer.startEndOfTrack() } },
            )
        }
        if (sleep.active) {
            Spacer(Modifier.height(16.dp))
            Text(
                themedLabel("Выключить таймер"),
                style = MaterialTheme.typography.labelLarge,
                color = c.danger,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .clip(Vesper.shapes.chip)
                    .clickable { dismiss { vm.sleepTimer.cancel() } }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}
