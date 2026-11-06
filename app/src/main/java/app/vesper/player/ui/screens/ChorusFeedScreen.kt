package app.vesper.player.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.wave.ChorusFinder
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.FavoriteButton
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * "Лента припевов": the user's own library as a vertical feed of hooks.
 * Each page plays the most memorable ~25 s of a song (found by [ChorusFinder]);
 * swipe up for the next one, like it, or switch to the whole track.
 * Rarely played and long-forgotten songs come first — it's a way to rediscover your music.
 */
@Composable
fun ChorusFeedScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val context = LocalContext.current
    val bottom = LocalBottomInset.current
    val lib by vm.library.collectAsStateWithLifecycle()

    val feed = remember(lib.loaded) { buildFeed(lib.allTracks) }

    if (feed.isEmpty()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack, modifier = Modifier.padding(8.dp))
            EmptyState(Icons.Rounded.MusicNote, "Пока нечего листать", "Добавьте в библиотеку песни длиннее минуты — и здесь появятся их припевы.")
        }
        return
    }

    val snippet = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
        }
    }
    DisposableEffect(Unit) {
        vm.player.pause()
        onDispose { snippet.release() }
    }

    val pager = rememberPagerState { feed.size }
    var paused by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    // Play the hook of the page that has settled; warm up the next ones meanwhile.
    LaunchedEffect(pager.settledPage) {
        val page = pager.settledPage
        val track = feed[page]
        paused = false
        progress = 0f
        launch { for (i in 1..2) feed.getOrNull(page + i)?.let { ChorusFinder.find(context, it) } }
        val start = ChorusFinder.find(context, track)
        snippet.setMediaItem(MediaItem.fromUri(track.uri), start)
        snippet.volume = 0f
        snippet.prepare()
        snippet.play()
        // Soft fade-in, then follow the clock; at the end fade out and move on.
        for (i in 1..10) {
            snippet.volume = i / 10f
            delay(60)
        }
        while (true) {
            delay(120)
            val played = (snippet.currentPosition - start).coerceAtLeast(0)
            progress = (played.toFloat() / ChorusFinder.SNIPPET_MS).coerceIn(0f, 1f)
            val ended = snippet.playbackState == androidx.media3.common.Player.STATE_ENDED
            if (played >= ChorusFinder.SNIPPET_MS || ended) {
                for (i in 9 downTo 0) {
                    snippet.volume = i / 10f
                    delay(50)
                }
                snippet.pause()
                if (page + 1 < feed.size) pager.animateScrollToPage(page + 1)
                break
            }
        }
    }
    LaunchedEffect(paused) { if (paused) snippet.pause() else if (snippet.playbackState != androidx.media3.common.Player.STATE_IDLE) snippet.play() }

    Box(Modifier.fillMaxSize().background(c.background)) {
        app.vesper.player.ui.theme.ThemeAmbience(Modifier.fillMaxSize(), playing = !paused)
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { feed[it].id }) { page ->
            val track = lib.trackById[feed[page].id] ?: feed[page]
            ChorusPage(
                track = track,
                active = page == pager.settledPage,
                progress = if (page == pager.settledPage) progress else 0f,
                paused = paused,
                bottom = bottom,
                onTogglePause = { paused = !paused },
                onFavorite = { vm.toggleFavorite(track) },
                onPlayWhole = {
                    snippet.pause()
                    vm.player.playTracks(listOf(track), 0, shuffle = false)
                    ui.playerExpanded = true
                },
                onQueue = { vm.player.addToQueue(listOf(track)) },
                isLast = page == feed.lastIndex,
            )
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            OverlineText("Лента припевов · ${pager.currentPage + 1} из ${feed.size}", color = c.ornament)
        }
    }
}

@Composable
private fun ChorusPage(
    track: TrackEntity,
    active: Boolean,
    progress: Float,
    paused: Boolean,
    bottom: androidx.compose.ui.unit.Dp,
    onTogglePause: () -> Unit,
    onFavorite: () -> Unit,
    onPlayWhole: () -> Unit,
    onQueue: () -> Unit,
    isLast: Boolean,
) {
    val c = Vesper.colors
    val artScale by animateFloatAsState(if (active && !paused) 1f else 0.9f, tween(500), label = "art")
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.highlight.copy(alpha = 0.85f), Color.Transparent, Color.Transparent)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTogglePause),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp, start = 28.dp, end = 28.dp, bottom = bottom + 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.4f))
            Box(contentAlignment = Alignment.Center) {
                Artwork(
                    uri = track.uri,
                    seed = track.title,
                    size = ArtSize.Large,
                    modifier = Modifier
                        .fillMaxWidth(artScale)
                        .aspectRatio(1f),
                )
                if (paused && active) {
                    Box(Modifier.size(72.dp).clip(Vesper.shapes.large).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Продолжить", tint = Color.White, modifier = Modifier.size(44.dp))
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(track.title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(track.artist, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(Vesper.shapes.chip).background(c.progressTrack)) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(Brush.horizontalGradient(listOf(c.progressStart, c.progressEnd))),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FavoriteButton(track.favorite, onFavorite, size = 30.dp)
                PressableIcon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "Пауза", onClick = onTogglePause, size = 34.dp)
                PressableIcon(Icons.Rounded.PlaylistAdd, "В очередь", onClick = onQueue, size = 28.dp)
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Слушать целиком", onClick = onPlayWhole, icon = Icons.Rounded.PlayArrow)
            Spacer(Modifier.weight(0.6f))
            if (!isLast) {
                Row(Modifier.alpha(0.6f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.KeyboardArrowUp, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Свайп вверх — следующий припев", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                }
            }
        }
    }
}

/** Forgotten and rarely played songs first, favourites sprinkled in, everything a bit shuffled. */
private fun buildFeed(tracks: List<TrackEntity>): List<TrackEntity> {
    val now = System.currentTimeMillis()
    return tracks
        .filter { it.durationMs >= 60_000 && !it.uri.startsWith("http") }
        .map { t ->
            val daysSince = if (t.lastPlayedAt > 0) (now - t.lastPlayedAt) / 86_400_000f else 365f
            val forgotten = (daysSince / 60f).coerceAtMost(1f)
            val rare = 1f / (1 + t.playCount)
            val score = 0.45f * forgotten + 0.35f * rare + (if (t.favorite) 0.15f else 0f) + Random.nextFloat() * 0.5f
            t to score
        }
        .sortedByDescending { it.second }
        .take(300)
        .map { it.first }
}
