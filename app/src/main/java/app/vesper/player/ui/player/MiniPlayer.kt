package app.vesper.player.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.theme.Vesper
import kotlin.math.abs

@Composable
fun MiniPlayer(onExpand: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val state by vm.player.state.collectAsStateWithLifecycle()
    val progress by vm.player.progress.collectAsStateWithLifecycle()
    val current = state.current ?: return
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(Vesper.shapes.medium)
            .background(c.surfaceHigh)
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        when {
                            dragY < -60f && abs(dragY) > abs(dragX) -> onExpand()
                            dragX < -120f -> vm.player.next()
                            dragX > 120f -> vm.player.previous()
                        }
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    },
                )
            },
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(200)) },
                contentKey = { it.key },
                label = "miniArt",
            ) { entry ->
                Artwork(entry.uri, entry.title, Modifier.size(48.dp))
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 4 } + fadeOut(tween(200)))
                },
                contentKey = { it.key },
                modifier = Modifier.weight(1f),
                label = "miniText",
            ) { entry ->
                Column {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textPrimary,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = 2),
                    )
                    Text(
                        entry.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            PressableIcon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) "Пауза" else "Воспроизвести",
                onClick = { vm.player.togglePlayPause() },
                size = 30.dp,
            )
            PressableIcon(
                Icons.Rounded.SkipNext,
                "Следующий трек",
                onClick = { vm.player.next() },
                enabled = state.hasNext,
                size = 26.dp,
            )
        }
        // Progress hairline
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(c.progressTrack))
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress.fraction)
                .height(2.dp)
                .background(c.accentBright),
        )
    }
}
