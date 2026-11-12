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
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Person
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
import app.vesper.player.ui.components.Appear
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.bouncyClick
import app.vesper.player.ui.components.themedShine
import app.vesper.player.ui.components.Artwork
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
    val online by vm.transfer.playlists.collectAsStateWithLifecycle(initialValue = emptyList())
    val player by vm.player.state.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    vm.permissionVersion.collectAsStateWithLifecycle().value
    val bottom = LocalBottomInset.current
    val libraryEmpty = lib.loaded && vids.loaded && lib.allTracks.isEmpty() && vids.all.isEmpty()
    val hasPermission = vm.hasAudioPermission() || vm.hasVideoPermission()

    val seen = remember { mutableSetOf<String>() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-checked every time the user comes back (e.g. from the notification settings).
    var notificationsOn by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsOn = playbackNotificationsOn(context)
        onPauseOrDispose { }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "header") { Appear(seen, "header", 0) { HomeHeader(lib.allTracks.size, vids.all.size) }}
        item(key = "search") { Appear(seen, "search", 1) {
            Row(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .clip(Vesper.shapes.medium)
                    .background(c.surfaceHigh)
                    .clickable { ui.open(Routes.SEARCH) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text("Песня, исполнитель или видео", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        }}
        if (!notificationsOn) {
            item(key = "notif") { Appear(seen, "notif", 2) { NotificationsBanner() } }
        }
        item(key = "ai") { Appear(seen, "ai", 2) { AiPickerCard() } }
        run {
            item(key = "shortcuts") { Appear(seen, "shortcuts", 3) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    item {
                        Shortcut(Icons.Rounded.Shuffle, "Перемешать") {
                            if (lib.allTracks.isEmpty()) vm.message("На телефоне пока нет музыки — найдите песню в поиске или включите волну")
                            else vm.player.playTracks(lib.allTracks, shuffle = true)
                        }
                    }
                    item {
                        Shortcut(Icons.Rounded.Favorite, "Избранное") {
                            if (lib.favorites.isEmpty()) vm.message("В избранном пока пусто — отмечайте треки сердцем")
                            else vm.player.playTracks(lib.favorites, 0, shuffle = false)
                        }
                    }
                    item { Shortcut(Icons.Rounded.Whatshot, "Припевы") { ui.open(Routes.CHORUS_FEED) } }
                    item { Shortcut(Icons.Rounded.ShowChart, "Энергия") { ui.open(Routes.ENERGY_CURVE) } }
                    item { Shortcut(Icons.Rounded.SwapHoriz, "Из Яндекса") { ui.open(Routes.YANDEX_IMPORT) } }
                    item { Shortcut(Icons.Rounded.Download, "По ссылке") { ui.goToTab(Routes.DOWNLOADS) } }
                    item { Shortcut(Icons.Rounded.Movie, "Кино") { ui.open(Routes.MOVIES) } }
                }
            }}
        }

        item(key = "wave") { Appear(seen, "wave", 2) { WaveSection() }}

        if (player.current != null && player.queueOwner != app.vesper.player.data.wave.WaveEngine.OWNER) {
            item(key = "now") { Appear(seen, "now", 5) { NowPlayingCard(onOpen = { ui.playerExpanded = true }) }}
        }

        // Playlists that play from the internet: transferred, from the account's cloud copy, saved picks.
        if (online.isNotEmpty()) {
            item(key = "onHeader") { Appear(seen, "onHeader", 6) { SectionHeader("Онлайн-плейлисты", actionLabel = "Все", onAction = { ui.playlistKind = 2; ui.goToTab(Routes.PLAYLISTS) }) }}
            item(key = "on") { Appear(seen, "on", 6) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(online.take(12), key = { it.id }) { p ->
                        Column(Modifier.width(132.dp).clip(Vesper.shapes.small).clickable { ui.open(Routes.onlinePlaylist(p.id)) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(Vesper.shapes.artwork).background(c.surfaceHigh)) {
                                if (p.coverUrl != null) {
                                    app.vesper.player.ui.components.RemoteImage(p.coverUrl, Modifier.fillMaxSize())
                                } else {
                                    Icon(Icons.Rounded.LibraryMusic, null, tint = c.textMuted, modifier = Modifier.align(Alignment.Center).size(36.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tracksCount(p.itemCount), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                }
            }}
        }

        if (libraryEmpty) {
            item(key = "empty") { Appear(seen, "empty", 7) {
                if (scanning) {
                    EmptyHint(Icons.Rounded.LibraryMusic, "Собираю библиотеку…", "Ищу музыку и видео на устройстве. Это займёт несколько секунд.")
                } else if (!hasPermission) {
                    EmptyHint(
                        Icons.Rounded.Lock,
                        "Музыка с телефона",
                        "Разрешите доступ, чтобы здесь появились песни и видео с устройства. Онлайн-музыка работает и без этого.",
                        "Разрешить доступ", actions.requestPermissions,
                        "Импортировать файлы", actions.importAudio,
                    )
                } else {
                    EmptyHint(
                        Icons.Rounded.LibraryAdd,
                        "На телефоне пока нет музыки",
                        "Слушайте онлайн через поиск и волну, скачивайте по ссылке — или добавьте свои файлы.",
                        "Импортировать файлы", actions.importAudio,
                        "Выбрать папку", actions.importFolder,
                    )
                }
            }}
            return@LazyColumn
        }

        if (lib.recentlyPlayed.isNotEmpty()) {
            item(key = "recentHeader") { Appear(seen, "recentHeader", 7) { SectionHeader("Недавно прослушанные", actionLabel = "Все", onAction = { ui.musicTab = 6; ui.goToTab(Routes.MUSIC) }) }}
            item(key = "recent") { Appear(seen, "recent", 8) {
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
            }}
        }

        if (lib.favorites.isNotEmpty()) {
            item(key = "favHeader") { Appear(seen, "favHeader", 9) { SectionHeader("Любимое") }}
            item(key = "fav") { Appear(seen, "fav", 10) {
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
            }}
        }

        if (playlists.isNotEmpty()) {
            item(key = "plHeader") { Appear(seen, "plHeader", 11) { SectionHeader("Плейлисты", actionLabel = "Все", onAction = { ui.goToTab(Routes.PLAYLISTS) }) }}
            item(key = "pl") { Appear(seen, "pl", 12) {
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
            }}
        }

        if (vids.recent.isNotEmpty()) {
            item(key = "vidHeader") { Appear(seen, "vidHeader", 13) { SectionHeader("Продолжить просмотр", actionLabel = "Видео", onAction = { ui.videoTab = 1; ui.goToTab(Routes.VIDEOS) }) }}
            item(key = "vid") { Appear(seen, "vid", 14) {
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
            }}
        }

        if (lib.albums.size > 1) {
            item(key = "albHeader") { Appear(seen, "albHeader", 15) { SectionHeader("Альбомы") }}
            item(key = "alb") { Appear(seen, "alb", 16) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(lib.albums.take(16), key = { it.key }) { a ->
                        MediaTile(a.title, a.subtitle, a.artUri, onClick = { ui.openGroup(GroupType.ALBUM, a.key) })
                    }
                }
            }}
        }

        if (lib.recentlyAdded.isNotEmpty()) {
            item(key = "addedHeader") { Appear(seen, "addedHeader", 17) { SectionHeader("Недавно добавленные", actionLabel = "Все", onAction = { ui.musicTab = 5; ui.goToTab(Routes.MUSIC) }) }}
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
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val ornament = Vesper.theme.ornament
    val session by vm.account.session.collectAsStateWithLifecycle()
    val sync by vm.cloud.status.collectAsStateWithLifecycle()
    val name = session?.let { s -> s.name.ifBlank { s.email.substringBefore('@') } }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OverlineText("${greeting()}, ${name ?: "Floyachieee"}", color = c.ornament)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (ornament == Ornament.NEON) "FLOYA" else "Floya",
                        style = MaterialTheme.typography.headlineLarge,
                        color = c.textPrimary,
                        modifier = Modifier.themedShine(),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${tracksCount(trackCount)} · ${videosCount(videoCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            HeaderIcon(Icons.Rounded.Insights, "Твой вайб") { ui.open(Routes.STATS) }
            Spacer(Modifier.width(4.dp))
            // Profile: initial of the account (with a small cloud-status dot), or a sign-in prompt.
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (session != null) c.accent else c.surfaceHigh)
                        .border(1.dp, if (session != null) c.ornament else c.outline, androidx.compose.foundation.shape.CircleShape)
                        .clickable { ui.open(Routes.ACCOUNT) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (name != null) {
                        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = c.onAccent)
                    } else {
                        Icon(Icons.Rounded.Person, "Войти в аккаунт", tint = c.textSecondary, modifier = Modifier.size(22.dp))
                    }
                }
                if (session != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(c.background)
                            .padding(2.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (sync.error != null) c.danger else c.accentBright),
                    )
                }
            }
        }
        if (ornament == Ornament.GOTHIC || ornament == Ornament.NEON) {
            OrnamentDivider(Modifier.padding(top = 6.dp, end = 8.dp))
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Vesper.colors
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = c.textSecondary, modifier = Modifier.size(22.dp))
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
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(Vesper.shapes.medium)
            .background(c.surface)
            .border(1.dp, c.outline, Vesper.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(current.uri, current.title, Modifier.size(52.dp), size = ArtSize.Small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(current.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(Vesper.shapes.chip).background(c.progressTrack)) {
                Box(Modifier.fillMaxWidth(progress.fraction).height(3.dp).background(c.accentBright))
            }
        }
        Spacer(Modifier.width(12.dp))
        PlayPauseButton(state.isPlaying, onClick = { vm.player.togglePlayPause() }, size = 42.dp)
    }
}

/** A compact note with up to two actions — unlike a full-screen empty state it leaves the rest of the screen usable. */
@Composable
private fun EmptyHint(
    icon: ImageVector,
    title: String,
    message: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = Vesper.colors
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(Vesper.shapes.medium)
            .background(c.surface)
            .border(1.dp, c.outline, Vesper.shapes.medium)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.ornament, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        }
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(12.dp))
            // One under another: two buttons side by side don't fit on a narrow phone.
            app.vesper.player.ui.components.PrimaryButton(primaryLabel, onClick = onPrimary, modifier = Modifier.fillMaxWidth())
            if (secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(8.dp))
                app.vesper.player.ui.components.SecondaryButton(secondaryLabel, onClick = onSecondary, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Entry to the AI picker of songs and videos, with a few ready requests. */
@Composable
private fun AiPickerCard() {
    val ui = LocalUi.current
    val c = Vesper.colors
    fun open(q: String?) {
        ui.assistantQuery = q
        ui.open(Routes.ASSISTANT)
    }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(Vesper.shapes.medium)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(c.highlight, c.surfaceHigh)))
            .border(1.dp, c.outline, Vesper.shapes.medium)
            .clickable { open(null) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = c.accentBright, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ИИ-подбор песен и видео", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                Text("Скажи, что хочется, — соберу из твоей музыки и найду новое", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(listOf("Для прогулки", "Похожее на этот трек", "Клипы под настроение", "Спокойное на вечер", "Для тренировки")) { q ->
                Text(
                    q,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textPrimary,
                    modifier = Modifier
                        .clip(Vesper.shapes.chip)
                        .background(c.surface)
                        .clickable { open(q) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Vesper.colors
    Row(
        Modifier
            .bouncyClick(onClick)
            .clip(Vesper.shapes.chip)
            .background(c.surfaceHigh)
            .border(1.dp, c.outline, Vesper.shapes.chip)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(themedLabel(label), style = MaterialTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1)
    }
}

/**
 * The player card in the phone's control panel / notification shade (and on the lock screen) is
 * built from Floya's playback notification — if notifications are off, the card can't appear.
 */
private fun playbackNotificationsOn(context: android.content.Context): Boolean {
    val nm = androidx.core.app.NotificationManagerCompat.from(context)
    if (!nm.areNotificationsEnabled()) return false
    val channel = nm.getNotificationChannel("default_channel_id")
    return channel == null || channel.importance != androidx.core.app.NotificationManagerCompat.IMPORTANCE_NONE
}

@Composable
private fun NotificationsBanner() {
    val c = Vesper.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .fillMaxWidth()
            .bouncyClick({
                val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }.onFailure {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + context.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            })
            .clip(Vesper.shapes.medium)
            .background(c.highlight)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.NotificationsOff, null, tint = c.accentBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Включите уведомления Floya", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(
                "Без них плеер не появится в панели управления, шторке и на экране блокировки. Нажмите, чтобы открыть настройки.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}
