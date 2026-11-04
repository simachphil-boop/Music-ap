package app.vesper.player.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.ui.GroupType
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OrnamentDivider
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.formatTotalDuration
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.theme.Vesper

@Composable
fun GroupDetailScreen(type: GroupType, key: String, onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val flow = remember(type, key) { vm.group(type, key) }
    val group by flow.collectAsState(initial = null)
    val player by vm.player.state.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current
    val g = group

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (g != null) {
                    PressableIcon(
                        Icons.Rounded.PlaylistAdd, "Всё в плейлист",
                        onClick = { ui.showAddToPlaylist(g.tracks.map { it.id }, PlaylistKind.MUSIC) },
                    )
                }
            }
        }
        if (g == null) {
            if (lib.loaded) {
                item { EmptyState(Icons.Rounded.Search, "Не найдено", "Похоже, эти треки больше не в библиотеке.") }
            }
            return@LazyColumn
        }
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(if (type == GroupType.ALBUM) 220.dp else 180.dp)) {
                    Artwork(
                        g.artUri, g.title, Modifier.fillMaxSize(),
                        size = ArtSize.Large,
                        shape = if (type == GroupType.ARTIST) CircleShape else Vesper.shapes.artwork,
                    )
                }
                Spacer(Modifier.height(18.dp))
                OverlineText(type.label(), color = c.ornament)
                Text(
                    g.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = c.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${g.subtitle} · ${formatTotalDuration(g.totalDurationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    textAlign = TextAlign.Center,
                )
                OrnamentDivider(Modifier.padding(top = 14.dp))
            }
        }
        item(key = "bar") {
            PlayShuffleBar(
                count = g.tracks.size,
                onPlay = { vm.player.playTracks(g.tracks, 0, shuffle = false) },
                onShuffle = { vm.player.playTracks(g.tracks, shuffle = true) },
            )
        }
        trackItems(
            g.tracks,
            player.current?.trackId,
            player.isPlaying,
            onPlay = { vm.player.playTracks(g.tracks, it, shuffle = null, keepStart = true) },
            onMore = { ui.showTrackOptions(it) },
            numbered = type == GroupType.ALBUM,
        )
        item(key = "footer") {
            Text(
                tracksCount(g.tracks.size),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }
    }
}
