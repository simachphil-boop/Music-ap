package app.vesper.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.ui.GroupType
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.Routes
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.MediaTile
import app.vesper.player.ui.components.OrnamentDivider
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PlayPauseButton
import app.vesper.player.ui.components.PlaylistArt
import app.vesper.player.ui.components.SectionHeader
import app.vesper.player.ui.components.TrackRow
import app.vesper.player.ui.components.VideoThumb
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.components.greeting
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.components.videosCount
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel

@Composable
fun HomeScreen() {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val previews by vm.playlistPreview.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    vm.permissionVersion.collectAsStateWithLifecycle().value
    val bottom = LocalBottomInset.current
    val libraryEmpty = lib.loaded && vids.loaded && lib.allTracks.isEmpty() && vids.all.isEmpty()
    val hasPermission = vm.hasAudioPermission() || vm.hasVideoPermission()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "header") { HomeHeader(lib.allTracks.size, vids.all.size) }
        item(key = "search") {
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(Vesper.shapes.medium)
                    .background(c.surfaceHigh)
                    .clickable { ui.open(Routes.SEARCH) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Найти песню, исполнителя или видео", style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
            }
        }
        item(key = "wave") { WaveSection() }

        if (libraryEmpty) {
            item(key = "empty") {
                if (scanning) {
                    EmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "Собираю библиотеку…",
                        message = "Ищу музыку и видео на устройстве. Это займёт несколько секунд.",
                    )
                } else if (!hasPermission) {
                    EmptyState(
                        icon = Icons.Rounded.Lock,
                        title = "Нужен доступ к медиатеке",
                        message = "Разрешите Floya Music читать музыку и видео на устройстве — или импортируйте отдельные файлы и папки вручную. Всё работает офлайн.",
                        primaryLabel = "Разрешить доступ",
                        onPrimary = actions.requestPermissions,
                        secondaryLabel = "Импортировать файлы",
                        onSecondary = actions.importAudio,
                    )
                } else {
                    EmptyState(
                        icon = Icons.Rounded.LibraryAdd,
                        title = "Библиотека пуста",
                        message = "На устройстве не нашлось аудио и видео. Импортируйте файлы или целую папку — Floya Music запомнит их без копирования.",
                        primaryLabel = "Импортировать файлы",
                        onPrimary = actions.importAudio,
                        secondaryLabel = "Выбрать папку",
                        onSecondary = actions.importFolder,
                    )
                }
            }
            return@LazyColumn
        }

        if (player.current != null && player.queueOwner != app.vesper.player.data.wave.WaveEngine.OWNER) {
            item(key = "now") { NowPlayingCard(onOpen = { ui.playerExpanded = true }) }
        }

        item(key = "quick") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickAction(Icons.Rounded.Shuffle, "Перемешать всё", Modifier.weight(1f)) {
                    vm.player.playTracks(lib.allTracks, shuffle = true)
                }
                QuickAction(Icons.Rounded.Favorite, "Избранное", Modifier.weight(1f)) {
                    if (lib.favorites.isEmpty()) vm.message("В избранном пока пусто — отмечайте треки сердцем")
                    else vm.player.playTracks(lib.favorites, 0, shuffle = false)
                }
                QuickAction(Icons.Rounded.Download, "По ссылке", Modifier.weight(1f)) { ui.goToTab(Routes.DOWNLOADS) }
            }
        }

        if (lib.recentlyPlayed.isNotEmpty()) {
            item(key = "recentHeader") { SectionHeader("Недавно прослушанные", actionLabel = "Все", onAction = { ui.musicTab = 6; ui.goToTab(Routes.MUSIC) }) }
            item(key = "recent") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.recentlyPlayed.take(15), key = { it.id }) { t ->
                        MediaTile(
                            title = t.title,
                            subtitle = t.artist,
                            artUri = t.uri,
                            highlighted = player.current?.trackId == t.id,
                            onClick = {
                                val list = lib.recentlyPlayed.take(50)
                                vm.player.playTracks(list, list.indexOf(t), shuffle = false)
                            },
                            onLongClick = { ui.showTrackOptions(t) },
                        )
                    }
                }
            }
        }

        if (lib.favorites.isNotEmpty()) {
            item(key = "favHeader") { SectionHeader("Любимое") }
            item(key = "fav") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.favorites.take(20), key = { it.id }) { t ->
                        MediaTile(
                            title = t.title,
                            subtitle = t.artist,
                            artUri = t.uri,
                            highlighted = player.current?.trackId == t.id,
                            onClick = { vm.player.playTracks(lib.favorites, lib.favorites.indexOf(t), shuffle = false) },
                            onLongClick = { ui.showTrackOptions(t) },
                        )
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item(key = "plHeader") { SectionHeader("Плейлисты", actionLabel = "Все", onAction = { ui.goToTab(Routes.PLAYLISTS) }) }
            item(key = "pl") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(playlists.take(12), key = { it.id }) { p ->
                        Column(
                            Modifier.width(132.dp).clip(Vesper.shapes.small).clickable { ui.openPlaylist(p.id) },
                        ) {
                            PlaylistArt(p, previews[p.id].orEmpty(), lib, vids, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(8.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (p.kind == PlaylistKind.VIDEO) videosCount(p.itemCount) else tracksCount(p.itemCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (vids.recent.isNotEmpty()) {
            item(key = "vidHeader") { SectionHeader("Продолжить просмотр", actionLabel = "Видео", onAction = { ui.videoTab = 1; ui.goToTab(Routes.VIDEOS) }) }
            item(key = "vid") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(vids.recent.take(12), key = { it.id }) { v ->
                        Column(
                            Modifier
                                .width(220.dp)
                                .clip(Vesper.shapes.small)
                                .clickable { actions.openVideos(vids.recent, vids.recent.indexOf(v)) },
                        ) {
                            VideoThumb(v, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(v.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "Осталось ${formatDuration((v.durationMs - v.lastPositionMs).coerceAtLeast(0))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (lib.albums.size > 1) {
            item(key = "albHeader") { SectionHeader("Альбомы") }
            item(key = "alb") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.albums.take(16), key = { it.key }) { a ->
                        MediaTile(a.title, a.subtitle, a.artUri, onClick = { ui.openGroup(GroupType.ALBUM, a.key) })
                    }
                }
            }
        }

        if (lib.recentlyAdded.isNotEmpty()) {
            item(key = "addedHeader") { SectionHeader("Недавно добавленные", actionLabel = "Все", onAction = { ui.musicTab = 5; ui.goToTab(Routes.MUSIC) }) }
            val added = lib.recentlyAdded.take(6)
            items(added, key = { "added-${it.id}" }) { t ->
                TrackRow(
                    track = t,
                    isCurrent = player.current?.trackId == t.id,
                    isPlaying = player.isPlaying,
                    onClick = { vm.player.playTracks(lib.recentlyAdded, lib.recentlyAdded.indexOf(t), shuffle = false) },
                    onMore = { ui.showTrackOptions(t) },
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(trackCount: Int, videoCount: Int) {
    val c = Vesper.colors
    val ornament = Vesper.theme.ornament
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlineText("${greeting()}, Floyachieee", color = c.ornament, modifier = Modifier.weight(1f))
            val ui = LocalUi.current
            Text(
                "✦ Твой вайб",
                style = MaterialTheme.typography.labelMedium,
                color = c.accentBright,
                modifier = Modifier
                    .clip(Vesper.shapes.chip)
                    .background(c.highlight)
                    .clickable { ui.open(Routes.STATS) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (ornament == Ornament.NEON) "FLOYA" else "Floya",
                style = MaterialTheme.typography.displayMedium,
                color = c.textPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (ornament == Ornament.NEON) "MUSIC" else "music",
                style = MaterialTheme.typography.headlineSmall,
                color = c.accentBright,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            "${tracksCount(trackCount)}  ·  ${videosCount(videoCount)}",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
        )
        if (ornament == Ornament.GOTHIC || ornament == Ornament.NEON) {
            OrnamentDivider(Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun NowPlayingCard(onOpen: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val state by vm.player.state.collectAsStateWithLifecycle()
    val progress by vm.player.progress.collectAsStateWithLifecycle()
    val current = state.current ?: return
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(Vesper.shapes.large)
            .background(c.surface)
            .border(1.dp, c.outline, Vesper.shapes.large)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(current.uri, current.title, Modifier.size(84.dp), size = ArtSize.Medium)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            OverlineText(if (state.isPlaying) "Сейчас играет" else "Продолжить", color = c.accentBright)
            Spacer(Modifier.height(4.dp))
            Text(current.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(Vesper.shapes.chip).background(c.progressTrack)) {
                Box(Modifier.fillMaxWidth(progress.fraction).height(3.dp).background(c.accentBright))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatDuration(progress.positionMs)} / ${formatDuration(progress.durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        PlayPauseButton(state.isPlaying, onClick = { vm.player.togglePlayPause() }, size = 52.dp)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Vesper.colors
    Column(
        modifier
            .clip(Vesper.shapes.medium)
            .background(c.surface)
            .border(1.dp, c.outline, Vesper.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            themedLabel(label),
            style = MaterialTheme.typography.labelMedium,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
