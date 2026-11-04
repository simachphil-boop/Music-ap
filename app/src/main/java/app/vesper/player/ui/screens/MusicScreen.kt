package app.vesper.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.prefs.SortField
import app.vesper.player.data.prefs.SortOrder
import app.vesper.player.ui.GroupType
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.TrackGroup
import app.vesper.player.ui.components.ActionSheet
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.ChoiceSheet
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.MediaTile
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.ScreenHeader
import app.vesper.player.ui.components.SearchField
import app.vesper.player.ui.components.SheetAction
import app.vesper.player.ui.components.TrackRow
import app.vesper.player.ui.components.formatTotalDuration
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel

private val musicTabs = listOf("Песни", "Альбомы", "Исполнители", "Жанры", "Избранное", "Добавленные", "История")

val musicSortOptions = listOf(
    "Название · А → Я" to SortOrder(SortField.TITLE, true),
    "Название · Я → А" to SortOrder(SortField.TITLE, false),
    "Исполнитель" to SortOrder(SortField.ARTIST, true),
    "Сначала новые" to SortOrder(SortField.DATE_ADDED, false),
    "Сначала старые" to SortOrder(SortField.DATE_ADDED, true),
    "Длительность" to SortOrder(SortField.DURATION, false),
)

@Composable
fun MusicScreen() {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val lib by vm.library.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    vm.permissionVersion.collectAsStateWithLifecycle().value
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortSheet by remember { mutableStateOf(false) }
    var importSheet by remember { mutableStateOf(false) }
    val bottom = LocalBottomInset.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Музыка",
            subtitle = "${tracksCount(lib.allTracks.size)} · ${formatTotalDuration(lib.allTracks.sumOf { it.durationMs })}",
        ) {
            PressableIcon(Icons.Rounded.Search, "Поиск", onClick = { searching = true })
            PressableIcon(Icons.Rounded.Sort, "Сортировка", onClick = { sortSheet = true })
            PressableIcon(Icons.Rounded.Add, "Добавить музыку", onClick = { importSheet = true })
        }
        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp),
                color = c.accentBright,
                trackColor = c.progressTrack,
            )
        }
        if (searching) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Треки, исполнители, альбомы",
                onClose = {
                    searching = false
                    query = ""
                },
            )
            Spacer(Modifier.height(8.dp))
        } else {
            ChipRow(musicTabs, ui.musicTab, onSelect = { ui.musicTab = it })
            Spacer(Modifier.height(6.dp))
        }

        if (!vm.hasAudioPermission() && lib.allTracks.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Lock,
                title = "Нет доступа к музыке",
                message = "Разрешите доступ к аудио на устройстве или импортируйте файлы через системный выбор файлов.",
                primaryLabel = "Разрешить доступ",
                onPrimary = actions.requestPermissions,
                secondaryLabel = "Импортировать файлы",
                onSecondary = actions.importAudio,
            )
            return@Column
        }

        if (searching) {
            val q = query.trim()
            val results = remember(q, lib.allTracks) {
                if (q.isEmpty()) emptyList()
                else lib.sortedTracks.filter {
                    it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) || it.genre.contains(q, true)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                if (q.isNotEmpty() && results.isEmpty()) {
                    item { EmptyState(Icons.Rounded.Search, "Ничего не найдено", "По запросу «$q» нет совпадений в вашей библиотеке.") }
                }
                trackItems(results, player.current?.trackId, player.isPlaying, onPlay = { i -> vm.player.playTracks(results, i, shuffle = false) }, onMore = { ui.showTrackOptions(it) })
            }
            return@Column
        }

        AnimatedContent(
            targetState = ui.musicTab,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "musicTab",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            when (tab) {
                0 -> TrackListTab(
                    tracks = lib.sortedTracks,
                    empty = { LibraryEmpty(onImport = { importSheet = true }) },
                )
                1 -> GroupGrid(lib.albums, empty = { EmptyState(Icons.Rounded.Album, "Альбомов нет", "Альбомы появятся, когда в библиотеке будет музыка с тегами.") })
                2 -> GroupList(lib.artists, round = true, empty = { EmptyState(Icons.Rounded.Person, "Исполнителей нет", "Исполнители берутся из тегов ваших аудиофайлов.") })
                3 -> GroupList(lib.genres, round = false, empty = { EmptyState(Icons.Rounded.Category, "Жанров нет", "Жанры берутся из тегов ваших аудиофайлов.") })
                4 -> TrackListTab(
                    tracks = lib.favorites,
                    empty = { EmptyState(Icons.Rounded.Favorite, "Избранное пусто", "Нажмите на сердце в плеере или в меню трека, чтобы добавить его сюда.") },
                )
                5 -> TrackListTab(
                    tracks = lib.recentlyAdded,
                    empty = { LibraryEmpty(onImport = { importSheet = true }) },
                )
                else -> TrackListTab(
                    tracks = lib.recentlyPlayed,
                    showShuffle = false,
                    empty = { EmptyState(Icons.Rounded.History, "История пуста", "Здесь появятся треки, которые вы слушали.") },
                )
            }
        }
    }

    if (sortSheet) {
        ChoiceSheet(
            title = "Сортировка",
            options = musicSortOptions.map { it.first },
            selected = musicSortOptions.indexOfFirst { it.second == settings.musicSort },
            onDismiss = { sortSheet = false },
            onSelect = { vm.setMusicSort(musicSortOptions[it].second) },
        )
    }
    if (importSheet) ImportSheet(onDismiss = { importSheet = false })
}

@Composable
private fun LibraryEmpty(onImport: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.LibraryMusic,
        title = "Здесь пока тихо",
        message = "Музыка на устройстве не найдена. Добавьте файлы MP3, FLAC, M4A, OGG, WAV или целую папку.",
        primaryLabel = "Добавить музыку",
        onPrimary = onImport,
    )
}

@Composable
fun ImportSheet(onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    ActionSheet(
        onDismiss = onDismiss,
        actions = listOf(
            SheetAction(Icons.Rounded.Refresh, "Сканировать устройство") {
                if (vm.hasAudioPermission() || vm.hasVideoPermission()) vm.rescan() else actions.requestPermissions()
            },
            SheetAction(Icons.Rounded.Download, "Скачать по ссылке") { ui.open(app.vesper.player.ui.Routes.DOWNLOADS) },
            SheetAction(Icons.Rounded.MusicNote, "Импортировать аудиофайлы") { actions.importAudio() },
            SheetAction(Icons.Rounded.Movie, "Импортировать видео") { actions.importVideo() },
            SheetAction(Icons.Rounded.CreateNewFolder, "Импортировать папку") { actions.importFolder() },
        ),
        header = {
            Text(
                "Добавить в библиотеку",
                style = MaterialTheme.typography.headlineSmall,
                color = Vesper.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        },
    )
}

fun LazyListScope.trackItems(
    tracks: List<TrackEntity>,
    currentId: Long?,
    isPlaying: Boolean,
    onPlay: (Int) -> Unit,
    onMore: (TrackEntity) -> Unit,
    numbered: Boolean = false,
) {
    itemsIndexed(tracks, key = { _, t -> t.id }) { index, t ->
        TrackRow(
            track = t,
            isCurrent = currentId == t.id,
            isPlaying = isPlaying,
            onClick = { onPlay(index) },
            onMore = { onMore(t) },
            index = if (numbered) (if (t.trackNumber > 0) t.trackNumber else index + 1) else null,
        )
    }
}

@Composable
private fun TrackListTab(
    tracks: List<TrackEntity>,
    empty: @Composable () -> Unit,
    showShuffle: Boolean = true,
) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val player by vm.player.state.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current
    if (tracks.isEmpty()) {
        Column(Modifier.fillMaxSize()) { empty() }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "actions") {
            PlayShuffleBar(
                count = tracks.size,
                onPlay = { vm.player.playTracks(tracks, 0, shuffle = false) },
                onShuffle = if (showShuffle) ({ vm.player.playTracks(tracks, shuffle = true) }) else null,
            )
        }
        trackItems(tracks, player.current?.trackId, player.isPlaying, onPlay = { vm.player.playTracks(tracks, it, shuffle = null, keepStart = true) }, onMore = { ui.showTrackOptions(it) })
    }
}

@Composable
fun PlayShuffleBar(count: Int, onPlay: () -> Unit, onShuffle: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.clip(Vesper.shapes.chip).background(c.accent).clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlayArrow, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(themedLabel("Слушать"), style = MaterialTheme.typography.labelLarge, color = c.onAccent)
        }
        if (onShuffle != null) {
            Row(
                Modifier.clip(Vesper.shapes.chip).border(1.dp, c.outline, Vesper.shapes.chip).clickable(onClick = onShuffle).padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shuffle, null, tint = c.textPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(themedLabel("Перемешать"), style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(tracksCount(count), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
    }
}

@Composable
private fun GroupGrid(groups: List<TrackGroup>, empty: @Composable () -> Unit) {
    val ui = LocalUi.current
    val bottom = LocalBottomInset.current
    if (groups.isEmpty()) {
        Column(Modifier.fillMaxSize()) { empty() }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(groups, key = { it.key }) { g ->
            MediaTile(
                title = g.title,
                subtitle = g.subtitle,
                artUri = g.artUri,
                onClick = { ui.openGroup(g.type, g.key) },
                modifier = Modifier.fillMaxWidth(),
                width = 400.dp,
            )
        }
    }
}

@Composable
private fun GroupList(groups: List<TrackGroup>, round: Boolean, empty: @Composable () -> Unit) {
    val ui = LocalUi.current
    val c = Vesper.colors
    val bottom = LocalBottomInset.current
    if (groups.isEmpty()) {
        Column(Modifier.fillMaxSize()) { empty() }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        items(groups, key = { it.key }) { g ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { ui.openGroup(g.type, g.key) }
                    .padding(horizontal = 20.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(56.dp)) {
                    Artwork(g.artUri, g.title, Modifier.fillMaxSize(), shape = if (round) CircleShape else Vesper.shapes.artwork)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Used by the group detail screen's type label. */
fun GroupType.label(): String = when (this) {
    GroupType.ALBUM -> "Альбом"
    GroupType.ARTIST -> "Исполнитель"
    GroupType.GENRE -> "Жанр"
}
