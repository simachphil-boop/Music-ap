package app.floya.desktop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.floya.desktop.Paths
import app.floya.desktop.Playlist
import app.floya.desktop.Track
import app.floya.desktop.formatTime
import app.floya.desktop.online.DlStatus
import app.floya.desktop.online.LinkInfo
import app.floya.desktop.online.SearchResult
import app.floya.desktop.online.StreamResolver
import app.floya.desktop.online.WebSearch
import app.floya.desktop.theme.PlaylistSkin
import app.floya.desktop.theme.PlaylistSkins
import app.floya.desktop.theme.SkinParticles
import app.floya.desktop.theme.SkinScene
import app.floya.desktop.theme.ThemeRegistry
import app.floya.desktop.theme.Vesper
import app.floya.desktop.theme.VesperThemeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import javax.swing.JFileChooser

sealed class Screen {
    data object Home : Screen()
    data object Search : Screen()
    data object Library : Screen()
    data object Playlists : Screen()
    data class PlaylistView(val id: Long) : Screen()
    data object Downloads : Screen()
    data object Settings : Screen()
}

@Composable
private fun Page(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val c = Vesper.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
            actions()
        }
        content()
    }
}

// ---- Home -------------------------------------------------------------------------------------

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val app = LocalApp.current
    val c = Vesper.colors
    val tracks by app.library.tracks.collectAsState()
    val recent by app.recent.collectAsState()
    val playlists by app.playlists.collectAsState()
    val seen = remember { mutableSetOf<String>() }
    val hour = java.time.LocalTime.now().hour
    val hello = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 26.dp)) {
        item {
            Appear(seen, "hello", 0) {
                Column {
                    Overline(hello)
                    Text("Floya", style = MaterialTheme.typography.displayLarge, color = c.textPrimary, modifier = Modifier.themedShine())
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Перемешать всё", Icons.Rounded.Shuffle, onClick = { app.player.playList(tracks, shuffle = true) })
                        SecondaryButton("Найти музыку", Icons.Rounded.Search, onClick = { open(Screen.Search) })
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { Appear(seen, "recent", 1) { Shelf("Недавно играло", recent.take(20)) { i -> app.player.playList(recent, i) } } }
        }
        val top = app.mostPlayed(tracks + recent, 20)
        if (top.isNotEmpty()) {
            item { Appear(seen, "top", 2) { Shelf("Слушаете чаще всего", top) { i -> app.player.playList(top, i) } } }
        }
        if (tracks.isNotEmpty()) {
            val fresh = tracks.sortedByDescending { it.addedAt }.take(20)
            item { Appear(seen, "fresh", 3) { Shelf("Добавлено недавно", fresh) { i -> app.player.playList(fresh, i) } } }
        }
        if (playlists.isNotEmpty()) {
            item {
                Appear(seen, "pls", 4) {
                    Column {
                        SectionTitle("Ваши плейлисты", Modifier.padding(top = 28.dp, bottom = 12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(playlists, key = { it.id }) { p -> PlaylistTile(p, Modifier.width(170.dp)) { open(Screen.PlaylistView(p.id)) } }
                        }
                    }
                }
            }
        }
        if (tracks.isEmpty() && recent.isEmpty()) {
            item {
                Empty(Icons.Rounded.LibraryMusic, "Музыки пока нет", "Floya ищет музыку в папке «Музыка». Добавьте свои папки в настройках или найдите треки в интернете.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Найти в интернете", Icons.Rounded.Search, onClick = { open(Screen.Search) })
                        SecondaryButton("Папки с музыкой", Icons.Rounded.Folder, onClick = { open(Screen.Settings) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun Shelf(title: String, tracks: List<Track>, onPlay: (Int) -> Unit) {
    Column {
        SectionTitle(title, Modifier.padding(top = 28.dp, bottom = 12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(tracks, key = { i, t -> "$i${t.id}" }) { i, t -> TrackTile(t, Modifier.width(160.dp)) { onPlay(i) } }
        }
    }
}

@Composable
fun TrackTile(t: Track, modifier: Modifier, onClick: () -> Unit) {
    val c = Vesper.colors
    ContextMenuArea(items = trackMenu(t)) {
        Column(modifier.bouncyClick(onClick).pointerHoverIcon(PointerIcon.Hand)) {
            Cover(t, Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(8.dp))
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlaylistTile(p: Playlist, modifier: Modifier, onClick: () -> Unit) {
    val c = Vesper.colors
    val skins by PlaylistSkins.choices.collectAsState()
    val skin = PlaylistSkins.byId(skins[p.id])
    Column(modifier.bouncyClick(onClick).pointerHoverIcon(PointerIcon.Hand)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(Vesper.shapes.artwork)) {
            if (skin != null) {
                SkinScene(skin.scene, Modifier.fillMaxSize(), fadeTo = skin.theme.palette.background.copy(alpha = 0.7f), animate = false)
                Text(skin.name, style = MaterialTheme.typography.labelSmall, color = skin.theme.palette.textPrimary, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            } else {
                Cover(p.tracks.firstOrNull(), Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${p.tracks.size} треков", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

// ---- Search -----------------------------------------------------------------------------------

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val c = Vesper.colors
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current by app.player.current.collectAsState()
    val playing by app.player.engine.playing.collectAsState()
    val library by app.library.tracks.collectAsState()

    fun run() {
        val q = query.trim()
        if (q.isEmpty()) return
        loading = true
        error = null
        scope.launch {
            val r = if (tab == 0) WebSearch.songs(q) else WebSearch.videos(q)
            r.onSuccess { list ->
                results = list
                // Warm up the first results so a click starts almost instantly.
                list.take(3).forEach { s -> launch(Dispatchers.IO) { runCatching { StreamResolver.resolve(s.url) } } }
            }.onFailure { error = it.message ?: "Поиск не удался" }
            loading = false
        }
    }

    Page("Поиск", "Песни и видео из интернета — слушайте сразу или скачивайте") {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Исполнитель, песня, альбом…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.textMuted) },
            modifier = Modifier.fillMaxWidth().trackTextFocus().onKeyEvent { if (it.key == Key.Enter) { run(); true } else false },
            colors = fieldColors(),
            shape = Vesper.shapes.medium,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Песни", tab == 0) { tab = 0; run() }
            Chip("Видео", tab == 1) { tab = 1; run() }
            if (loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), color = c.accentBright, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(12.dp))
        val localHits = remember(query, library) {
            val q = query.trim()
            if (q.length < 2) emptyList() else library.filter { it.title.contains(q, true) || it.artist.contains(q, true) }.take(5)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (localHits.isNotEmpty()) {
                item { Overline("В вашей библиотеке", Modifier.padding(vertical = 8.dp)) }
                itemsIndexed(localHits, key = { _, t -> "l" + t.id }) { i, t ->
                    TrackRow(t, null, current?.id == t.id, playing, onPlay = { app.player.playList(localHits, i) }, menu = trackMenu(t))
                }
                item { Overline("В интернете", Modifier.padding(top = 16.dp, bottom = 8.dp)) }
            }
            error?.let { item { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) } }
            val tracks = results.map { it.toTrack() }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(
                    t, null, current?.id == t.id, playing,
                    onPlay = { app.player.playList(tracks, i) },
                    menu = trackMenu(t),
                    trailing = {
                        IconBtn(Icons.Rounded.Download, "Скачать", {
                            app.downloads.enqueue(t.url!!, t.title, t.artist, t.thumbnail, video = false)
                            app.say("Скачиваю «${t.title}»")
                        }, 18.dp)
                    },
                )
            }
            if (!loading && results.isEmpty() && error == null && query.isBlank()) {
                item { Empty(Icons.Rounded.Search, "Что послушаем?", "Введите название и нажмите Enter. Трек начнёт играть сразу, без скачивания.") }
            }
        }
    }
}

// ---- Library ----------------------------------------------------------------------------------

@Composable
fun LibraryScreen() {
    val app = LocalApp.current
    val c = Vesper.colors
    val tracks by app.library.tracks.collectAsState()
    val scanning by app.library.scanning.collectAsState()
    val current by app.player.current.collectAsState()
    val playing by app.player.engine.playing.collectAsState()
    var filter by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var group by remember { mutableStateOf<Pair<String, List<Track>>?>(null) }

    val shown = remember(tracks, filter, sort) {
        val q = filter.trim()
        val f = if (q.isEmpty()) tracks else tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
        when (sort) {
            1 -> f.sortedBy { it.title.lowercase() }
            2 -> f.sortedBy { it.artist.lowercase() }
            else -> f.sortedByDescending { it.addedAt }
        }
    }
    Page(
        "Библиотека",
        "${tracks.size} треков" + if (scanning) " · ищу новые файлы…" else "",
        actions = {
            IconBtn(Icons.Rounded.Refresh, "Обновить", { app.rescan() })
            PrimaryButton("Играть", Icons.Rounded.PlayArrow, onClick = { app.player.playList(shown) })
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Перемешать", Icons.Rounded.Shuffle, onClick = { app.player.playList(shown, shuffle = true) })
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Треки", tab == 0) { tab = 0; group = null }
            Chip("Альбомы", tab == 1) { tab = 1; group = null }
            Chip("Исполнители", tab == 2) { tab = 2; group = null }
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                filter, { filter = it }, singleLine = true, placeholder = { Text("Фильтр") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.textMuted) },
                modifier = Modifier.width(280.dp).trackTextFocus(), colors = fieldColors(), shape = Vesper.shapes.medium,
            )
        }
        Spacer(Modifier.height(10.dp))
        val g = group
        when {
            g != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Rounded.Close, "Назад", { group = null })
                    Text(g.first, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                    PrimaryButton("Играть", Icons.Rounded.PlayArrow, onClick = { app.player.playList(g.second) })
                }
                TrackList(g.second, current, playing)
            }
            tab == 0 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    Chip("Новые", sort == 0) { sort = 0 }
                    Chip("По названию", sort == 1) { sort = 1 }
                    Chip("По исполнителю", sort == 2) { sort = 2 }
                }
                if (shown.isEmpty() && !scanning) {
                    Empty(Icons.Rounded.LibraryMusic, "Пусто", "Добавьте папки с музыкой в настройках.")
                } else {
                    TrackList(shown, current, playing)
                }
            }
            else -> {
                val groups = remember(shown, tab) {
                    shown.groupBy { if (tab == 1) it.album.ifBlank { "Без альбома" } else it.artist }.toList().sortedBy { it.first.lowercase() }
                }
                LazyVerticalGrid(GridCells.Adaptive(170.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(groups, key = { it.first }) { (name, list) ->
                        Column(Modifier.bouncyClick({ group = name to list }).pointerHoverIcon(PointerIcon.Hand)) {
                            Cover(list.first(), Modifier.fillMaxWidth().aspectRatio(1f), if (tab == 2) CircleShape else Vesper.shapes.artwork)
                            Spacer(Modifier.height(8.dp))
                            Text(name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (tab == 1) list.first().artist else "${list.size} треков", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackList(list: List<Track>, current: Track?, playing: Boolean) {
    val app = LocalApp.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, i, current?.id == t.id, playing, onPlay = { app.player.playList(list, i) }, menu = trackMenu(t))
        }
    }
}

// ---- Playlists --------------------------------------------------------------------------------

@Composable
fun PlaylistsScreen(open: (Screen) -> Unit) {
    val app = LocalApp.current
    val playlists by app.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    Page("Плейлисты", "${playlists.size} плейлистов · у каждого может быть своя тема", actions = {
        PrimaryButton("Создать", Icons.Rounded.Add, onClick = { creating = true })
    }) {
        if (playlists.isEmpty()) {
            Empty(Icons.Rounded.QueueMusic, "Плейлистов нет", "Создайте плейлист и добавляйте треки через правый клик.")
        } else {
            LazyVerticalGrid(GridCells.Adaptive(180.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(playlists, key = { it.id }) { p -> PlaylistTile(p, Modifier) { open(Screen.PlaylistView(p.id)) } }
            }
        }
    }
    if (creating) {
        TextDialog("Новый плейлист", "", "Создать", { creating = false }) { name ->
            creating = false
            open(Screen.PlaylistView(app.createPlaylist(name)))
        }
    }
}

@Composable
fun PlaylistScreen(id: Long, back: () -> Unit) {
    val skins by PlaylistSkins.choices.collectAsState()
    val skin = PlaylistSkins.byId(skins[id])
    if (skin == null) PlaylistBody(id, back, null)
    else VesperThemeProvider(skin.theme) { PlaylistBody(id, back, skin) }
}

@Composable
private fun PlaylistBody(id: Long, back: () -> Unit, skin: PlaylistSkin?) {
    val app = LocalApp.current
    val c = Vesper.colors
    val playlists by app.playlists.collectAsState()
    val p = playlists.firstOrNull { it.id == id }
    val current by app.player.current.collectAsState()
    val playing by app.player.engine.playing.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    if (p == null) {
        Empty(Icons.Rounded.QueueMusic, "Плейлист удалён", "") { SecondaryButton("Назад", onClick = back) }
        return
    }
    Box(Modifier.fillMaxSize().then(if (skin != null) Modifier.background(c.background) else Modifier)) {
        if (skin != null) {
            SkinScene(
                skin.scene,
                Modifier.fillMaxWidth().height(380.dp).graphicsLayer {
                    val off = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset.toFloat() else 1000f
                    translationY = -off * 0.45f
                    alpha = (1f - off / 700f).coerceIn(0f, 1f)
                },
                fadeTo = c.background,
            )
            SkinParticles(skin.scene, Modifier.fillMaxSize())
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp)) {
            item {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = if (skin != null) 140.dp else 10.dp, bottom = 18.dp)) {
                    if (skin == null) {
                        Cover(p.tracks.firstOrNull(), Modifier.size(190.dp))
                        Spacer(Modifier.width(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Overline("Плейлист" + (skin?.let { " · ${it.name}" } ?: ""))
                        Text(p.name, style = MaterialTheme.typography.displayMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.tracks.size} треков · ${formatTime(p.tracks.sumOf { it.durationMs })}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton("Слушать", Icons.Rounded.PlayArrow, onClick = { app.player.playList(p.tracks) })
                            SecondaryButton("Перемешать", Icons.Rounded.Shuffle, onClick = { app.player.playList(p.tracks, shuffle = true) })
                            SecondaryButton("Тема", Icons.Rounded.Palette, onClick = { picking = true })
                            IconBtn(Icons.Rounded.Edit, "Переименовать", { renaming = true })
                            IconBtn(Icons.Rounded.Delete, "Удалить плейлист", { app.deletePlaylist(id); back() })
                            IconBtn(Icons.Rounded.Close, "Закрыть", back)
                        }
                    }
                }
            }
            if (p.tracks.isEmpty()) {
                item { Empty(Icons.Rounded.QueueMusic, "Плейлист пуст", "Нажмите правой кнопкой на любой трек → «В плейлист»") }
            }
            itemsIndexed(p.tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(
                    t, i, current?.id == t.id, playing,
                    onPlay = { app.player.playList(p.tracks, i) },
                    menu = trackMenu(
                        t,
                        buildList {
                            add(ContextMenuItem("Убрать из плейлиста") { app.removeFromPlaylist(id, t.id) })
                            if (i > 0) add(ContextMenuItem("Переместить выше") { app.movePlaylistTrack(id, i, i - 1) })
                            if (i < p.tracks.lastIndex) add(ContextMenuItem("Переместить ниже") { app.movePlaylistTrack(id, i, i + 1) })
                        },
                    ),
                )
            }
        }
    }
    if (renaming) TextDialog("Переименовать", p.name, "Сохранить", { renaming = false }) { app.renamePlaylist(id, it); renaming = false }
    if (picking) SkinPicker(skin?.id, { picking = false }) { app.setSkin(id, it); picking = false }
}

@Composable
private fun SkinPicker(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = Vesper.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(760.dp).height(600.dp).clip(Vesper.shapes.large).background(c.surface).padding(24.dp)) {
            Text("Тема плейлиста", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text("Своё оформление только для этого плейлиста — тема программы не меняется.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(14.dp))
            val options: List<PlaylistSkin?> = listOf(null) + PlaylistSkins.all
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(options, key = { it?.id ?: "none" }) { s ->
                    val selected = s?.id == current
                    val shape = Vesper.shapes.medium
                    Column(
                        Modifier.clip(shape).border(if (selected) 2.dp else 1.dp, if (selected) c.accentBright else c.outline, shape)
                            .clickable { onPick(s?.id) }.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.6f)) {
                            if (s != null) SkinScene(s.scene, Modifier.fillMaxSize(), fadeTo = s.theme.palette.background.copy(alpha = 0.6f), animate = true)
                            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.highlight, c.surfaceHigh))), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Palette, null, tint = c.textMuted, modifier = Modifier.size(28.dp))
                            }
                            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(CircleShape).background(c.accentBright), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(15.dp))
                            }
                        }
                        Column(Modifier.fillMaxWidth().background(s?.theme?.palette?.surface ?: c.surfaceHigh).padding(10.dp)) {
                            Text(s?.name ?: "Как в программе", style = MaterialTheme.typography.titleSmall, color = s?.theme?.palette?.textPrimary ?: c.textPrimary)
                            Text(s?.tagline ?: "Цвета текущей темы Floya", style = MaterialTheme.typography.bodySmall, color = s?.theme?.palette?.textSecondary ?: c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---- Downloads --------------------------------------------------------------------------------

@Composable
fun DownloadsScreen() {
    val app = LocalApp.current
    val c = Vesper.colors
    val scope = rememberCoroutineScope()
    val tasks by app.downloads.tasks.collectAsState()
    val settings by app.settings.collectAsState()
    var link by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check() {
        val u = link.trim()
        if (!u.startsWith("http")) { error = "Вставьте ссылку, начинающуюся с http"; return }
        loading = true; error = null; info = null
        scope.launch {
            app.downloads.info(u).onSuccess { info = it }.onFailure { error = it.message ?: "Не удалось открыть ссылку" }
            loading = false
        }
    }

    Page("Загрузки", "Сохраняются в ${settings.downloadDir}", actions = {
        SecondaryButton("Открыть папку", Icons.Rounded.Folder, onClick = { runCatching { java.awt.Desktop.getDesktop().open(File(settings.downloadDir).apply { mkdirs() }) } })
        Spacer(Modifier.width(8.dp))
        SecondaryButton("Очистить список", onClick = { app.downloads.clearFinished() })
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                link, { link = it }, singleLine = true,
                placeholder = { Text("Ссылка на YouTube, VK, SoundCloud, TikTok или прямая ссылка на файл") },
                leadingIcon = { Icon(Icons.Rounded.Link, null, tint = c.textMuted) },
                modifier = Modifier.weight(1f).trackTextFocus().onKeyEvent { if (it.key == Key.Enter) { check(); true } else false },
                colors = fieldColors(), shape = Vesper.shapes.medium,
            )
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Открыть", onClick = { check() })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = c.accentBright, trackColor = c.progressTrack)
        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        info?.let { i ->
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(Vesper.shapes.medium).background(c.surfaceHigh).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(Track(i.url, i.title, i.author, thumbnail = i.thumbnail, url = i.url), Modifier.size(64.dp), Vesper.shapes.small)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(i.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(i.author, i.source, if (i.durationSec > 0) formatTime(i.durationSec * 1000L) else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                PrimaryButton("Музыка", Icons.Rounded.Download, onClick = { app.downloads.enqueue(i.url, i.title, i.author, i.thumbnail, video = false); info = null; link = "" })
                Spacer(Modifier.width(8.dp))
                SecondaryButton("Видео", onClick = { app.downloads.enqueue(i.url, i.title, i.author, i.thumbnail, video = true); info = null; link = "" })
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clip(Vesper.shapes.medium).background(c.surface.copy(alpha = 0.7f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(Track(t.url, t.title, t.artist, thumbnail = t.thumbnail, url = t.url), Modifier.size(46.dp), Vesper.shapes.small)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when (t.status) {
                            DlStatus.QUEUED -> "В очереди"
                            DlStatus.DOWNLOADING -> "Скачивание ${(t.progress * 100).toInt()}%" + if (t.speed.isNotBlank()) " · ${t.speed}" else ""
                            DlStatus.PROCESSING -> "Обработка…"
                            DlStatus.DONE -> "Готово"
                            DlStatus.FAILED -> "Ошибка: ${t.error}"
                            DlStatus.CANCELED -> "Отменено"
                        }
                        Text(status, style = MaterialTheme.typography.bodySmall, color = if (t.status == DlStatus.FAILED) c.danger else c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.status == DlStatus.DOWNLOADING || t.status == DlStatus.PROCESSING) {
                            LinearProgressIndicator(
                                progress = { if (t.status == DlStatus.PROCESSING) 1f else t.progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                                color = c.accentBright, trackColor = c.progressTrack,
                            )
                        }
                    }
                    when (t.status) {
                        DlStatus.DONE -> t.file?.let { f -> IconBtn(Icons.Rounded.Folder, "Показать в папке", { runCatching { Runtime.getRuntime().exec(arrayOf("explorer.exe", "/select,", f)) } }) }
                        DlStatus.QUEUED, DlStatus.DOWNLOADING, DlStatus.PROCESSING -> IconBtn(Icons.Rounded.Close, "Отменить", { app.downloads.cancel(t.id) })
                        else -> Unit
                    }
                }
            }
        }
    }
}

// ---- Settings ---------------------------------------------------------------------------------

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val c = Vesper.colors
    val s by app.settings.collectAsState()
    Page("Настройки") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            SectionTitle("Тема программы", Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ThemeRegistry.all.forEach { t ->
                    val selected = t.id == s.themeId
                    Column(
                        Modifier.weight(1f).clip(Vesper.shapes.medium)
                            .border(if (selected) 2.dp else 1.dp, if (selected) c.accentBright else c.outline, Vesper.shapes.medium)
                            .clickable { app.updateSettings { it.copy(themeId = t.id) } }.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Box(Modifier.fillMaxWidth().height(70.dp).background(Brush.linearGradient(listOf(t.palette.background, t.palette.highlight, t.palette.accent)))) {
                            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(t.palette.accent, t.palette.accentBright, t.palette.ornament).forEach { col -> Box(Modifier.size(12.dp).clip(CircleShape).background(col)) }
                            }
                        }
                        Column(Modifier.fillMaxWidth().background(t.palette.surface).padding(8.dp)) {
                            Text(t.name, style = MaterialTheme.typography.labelLarge, color = t.palette.textPrimary, maxLines = 1)
                        }
                    }
                }
            }
            SettingSwitch("Живой фон", "Анимации темы: угольки и летучие мыши, звёзды, неоновый дождь, лепестки, светлячки", s.ambience) { v -> app.updateSettings { it.copy(ambience = v) } }

            SectionTitle("Папки с музыкой", Modifier.padding(top = 28.dp, bottom = 8.dp))
            s.folders.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = c.ornament, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(f, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Rounded.Close, "Убрать", { app.updateSettings { it.copy(folders = it.folders - f) }; app.rescan() }, 18.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                SecondaryButton("Добавить папку", Icons.Rounded.Add, onClick = {
                    chooseFolder("Папка с музыкой")?.let { dir ->
                        app.updateSettings { it.copy(folders = (it.folders + dir).distinct()) }
                        app.rescan()
                    }
                })
                SecondaryButton("Пересканировать", Icons.Rounded.Refresh, onClick = { app.rescan() })
            }

            SectionTitle("Скачивание", Modifier.padding(top = 28.dp, bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.downloadDir, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                SecondaryButton("Изменить", Icons.Rounded.Folder, onClick = { chooseFolder("Куда скачивать")?.let { d -> app.updateSettings { it.copy(downloadDir = d) } } })
            }
            SettingSwitch(
                "Скачивать музыку в MP3",
                if (s.mp3) "Медленнее: каждый трек перекодируется" else "Выключено — M4A без перекодирования: быстрее и без потери качества",
                s.mp3,
            ) { v -> app.updateSettings { it.copy(mp3 = v) } }

            SectionTitle("О программе", Modifier.padding(top = 28.dp, bottom = 8.dp))
            val ffmpegOk = remember { runCatching { ProcessBuilder(Paths.ffmpeg, "-version").start().waitFor() == 0 }.getOrDefault(false) }
            val ytOk = remember { runCatching { ProcessBuilder(Paths.ytDlp, "--version").start().waitFor() == 0 }.getOrDefault(false) }
            Text("Floya Music для Windows", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            Text("Звук: ${if (ffmpegOk) "работает" else "не найден ffmpeg"} · Загрузчик: ${if (ytOk) "работает" else "не найден yt-dlp"}", style = MaterialTheme.typography.bodySmall, color = if (ffmpegOk && ytOk) c.textSecondary else c.danger)
            Text("Пробел — пауза, Ctrl+→ / Ctrl+← — следующий / предыдущий трек", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = Vesper.colors
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).clickable { onChange(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surfaceHigh, uncheckedThumbColor = c.textMuted, uncheckedBorderColor = c.outline))
    }
}

private fun chooseFolder(title: String): String? {
    val ch = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) ch.selectedFile?.absolutePath else null
}
