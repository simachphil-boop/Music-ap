package app.vesper.player.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.appContainer
import app.vesper.player.data.lyrics.LyricsResult
import app.vesper.player.playback.QueueEntry
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.theme.Vesper

/** Full-screen lyrics with karaoke highlighting for synced lyrics. */
@Composable
fun LyricsView(entry: QueueEntry, onClose: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    val progress by vm.player.progress.collectAsStateWithLifecycle()
    var result by remember(entry.key) { mutableStateOf<LyricsResult?>(null) }
    var attempt by remember(entry.key) { mutableIntStateOf(0) }

    LaunchedEffect(entry.key, attempt) {
        result = null
        result = context.appContainer.lyrics.get(
            rawTitle = entry.title,
            rawArtist = entry.artist,
            durationSec = (entry.durationMs / 1000).toInt(),
            online = entry.online,
            refresh = attempt > 0,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PressableIcon(Icons.Rounded.KeyboardArrowDown, "Закрыть текст", onClick = onClose, size = 30.dp)
            Column(Modifier.weight(1f)) {
                OverlineText("Текст песни", color = c.ornament)
                Text(entry.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        when (val r = result) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            is LyricsResult.NotFound, is LyricsResult.Error -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(60.dp))
                Text(
                    if (r is LyricsResult.Error) r.message else "Текст для этой песни не найден",
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Попробовать ещё раз", onClick = { attempt++ }, icon = Icons.Rounded.Refresh)
            }
            is LyricsResult.Found -> {
                val lyrics = r.lyrics
                if (lyrics.synced.isNotEmpty()) {
                    val lines = lyrics.synced
                    val pos = progress.positionMs + 250
                    val currentIndex = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)
                    val listState = rememberLazyListState()
                    LaunchedEffect(currentIndex) {
                        listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 260.dp),
                    ) {
                        itemsIndexed(lines) { i, line ->
                            val active = i == currentIndex
                            val color by animateColorAsState(
                                when {
                                    active -> c.textPrimary
                                    i < currentIndex -> c.textMuted
                                    else -> c.textSecondary.copy(alpha = 0.65f)
                                },
                                tween(300),
                                label = "lyricColor",
                            )
                            val scale by animateFloatAsState(if (active) 1.04f else 1f, tween(300), label = "lyricScale")
                            Text(
                                line.text.ifBlank { "♪" },
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (active) c.accentBright else color,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.player.seekTo(line.timeMs) }
                                    .padding(vertical = 8.dp)
                                    .scale(scale),
                            )
                        }
                    }
                } else {
                    Text(
                        lyrics.plain,
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textPrimary,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
}
