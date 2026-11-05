package app.vesper.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.download.DownloadKind
import app.vesper.player.data.download.LinkInfo
import app.vesper.player.data.download.SearchResult
import app.vesper.player.data.download.SearchSource
import app.vesper.player.playback.OnlineItem
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ActionSheet
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.RemoteImage
import app.vesper.player.ui.components.SectionHeader
import app.vesper.player.ui.components.SheetAction
import app.vesper.player.ui.components.TrackRow
import app.vesper.player.ui.components.VideoRow
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private val searchTabs = listOf("Всё", "Песни", "Видео", "Моя библиотека", "SoundCloud", "Архив")

/** Unified search: songs (YouTube Music), videos (YouTube), your library and more. */
@Composable
fun SearchScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current

    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var videos by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var others by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var history by remember { mutableStateOf(vm.webSearch.history()) }
    var menuFor by remember { mutableStateOf<SearchResult?>(null) }

    fun run(q: String = query, t: Int = tab) {
        val text = q.trim()
        if (text.isEmpty()) return
        focusManager.clearFocus()
        submitted = text
        vm.webSearch.remember(text)
        history = vm.webSearch.history()
        if (t == 3) return // library only
        loading = true
        error = null
        scope.launch {
            when (t) {
                0 -> {
                    val s = async { vm.webSearch.songs(text) }
                    val v = async { vm.webSearch.videos(text) }
                    val sr = s.await()
                    val vr = v.await()
                    songs = sr.getOrDefault(emptyList())
                    videos = vr.getOrDefault(emptyList())
                    if (sr.isFailure && vr.isFailure) error = vr.exceptionOrNull()?.message ?: "Поиск не удался"
                }
                1 -> vm.webSearch.songs(text).onSuccess { songs = it }.onFailure { error = it.message }
                2 -> vm.webSearch.videos(text).onSuccess { videos = it }.onFailure { error = it.message }
                4 -> vm.webSearch.other(text, SearchSource.SOUNDCLOUD).onSuccess { others = it }.onFailure { error = it.message }
                5 -> vm.webSearch.other(text, SearchSource.ARCHIVE).onSuccess { others = it }.onFailure { error = it.message }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { if (submitted.isEmpty()) runCatching { focus.requestFocus() } }

    val q = submitted
    val libTracks = remember(q, lib.allTracks) {
        if (q.isBlank()) emptyList() else lib.sortedTracks.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }
    val libVideos = remember(q, vids.all) { if (q.isBlank()) emptyList() else vids.sorted.filter { it.title.contains(q, true) } }

    fun playSongs(list: List<SearchResult>, index: Int) =
        vm.player.playOnline(list.map { OnlineItem(it.url, it.title, it.author.substringBefore(" · "), it.durationSec, it.thumbnail) }, index)

    fun watch(r: SearchResult) = actions.openOnlineVideo(r.url, r.title)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 12.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                Row(
                    Modifier.weight(1f).clip(Vesper.shapes.medium).background(c.surfaceHigh).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                        if (query.isEmpty()) Text("Песни, исполнители, видео…", color = c.textMuted, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = c.textPrimary)),
                            cursorBrush = SolidColor(c.accentBright),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { run() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    if (query.isNotEmpty()) PressableIcon(Icons.Rounded.Close, "Очистить", onClick = { query = "" }, tint = c.textSecondary, size = 20.dp)
                }
            }
            ChipRow(searchTabs, tab, onSelect = {
                tab = it
                if (submitted.isNotBlank()) run(submitted, it)
            })
            Spacer(Modifier.height(8.dp))
        }

        if (submitted.isBlank()) {
            if (history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OverlineText("Недавние запросы", modifier = Modifier.weight(1f))
                        Text(
                            "Очистить",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.accentBright,
                            modifier = Modifier.clickable {
                                vm.webSearch.clearHistory()
                                history = emptyList()
                            },
                        )
                    }
                }
                items(history) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            query = h
                            run(h)
                        }.padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(h, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                item {
                    EmptyState(
                        Icons.Rounded.Search,
                        "Найдите любую музыку",
                        "Песни ищутся по каталогу YouTube Music, видео — по YouTube. Слушайте сразу, смотрите онлайн или скачивайте.",
                    )
                }
            }
            return@LazyColumn
        }

        if (loading) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = c.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Ищу «$submitted»…", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
            }
        }
        error?.let { e ->
            item { Text(e.substringBefore('\n'), style = MaterialTheme.typography.bodyMedium, color = c.danger, modifier = Modifier.padding(20.dp)) }
        }

        // Library matches ("Всё" shows a few, "Моя библиотека" shows all).
        if (tab == 0 || tab == 3) {
            val tracks = if (tab == 0) libTracks.take(3) else libTracks
            val vlist = if (tab == 0) libVideos.take(2) else libVideos
            if (tracks.isNotEmpty() || vlist.isNotEmpty()) {
                item { SectionHeader("В вашей библиотеке", actionLabel = if (tab == 0 && (libTracks.size > 3 || libVideos.size > 2)) "Все" else null, onAction = { tab = 3 }) }
                items(tracks, key = { "lt" + it.id }) { t: TrackEntity ->
                    TrackRow(t, isCurrent = false, isPlaying = false, onClick = { vm.player.playTracks(listOf(t), 0, shuffle = false) }, onMore = { ui.showTrackOptions(t) })
                }
                items(vlist, key = { "lv" + it.id }) { v: VideoEntity ->
                    VideoRow(v, onClick = { actions.openVideos(listOf(v), 0) }, onMore = { ui.showVideoOptions(v) })
                }
            } else if (tab == 3) {
                item { EmptyState(Icons.Rounded.MusicNote, "В библиотеке не найдено", "Попробуйте вкладки «Песни» и «Видео» — там поиск по всему интернету.") }
            }
        }

        if (tab == 0 || tab == 1) {
            val list = if (tab == 0) songs.take(8) else songs
            if (list.isNotEmpty()) {
                item { SectionHeader("Песни", actionLabel = if (tab == 0 && songs.size > 8) "Все" else null, onAction = { tab = 1 }) }
                songRows(list, onPlay = { i -> playSongs(list, i) }, onMenu = { menuFor = it })
            }
        }
        if (tab == 0 || tab == 2) {
            val list = if (tab == 0) videos.take(6) else videos
            if (list.isNotEmpty()) {
                item { SectionHeader("Видео", actionLabel = if (tab == 0 && videos.size > 6) "Все" else null, onAction = { tab = 2 }) }
                items(list, key = { "v" + it.url }) { r -> VideoResultRow(r, onClick = { watch(r) }, onMenu = { menuFor = r }) }
            }
        }
        if (tab == 4 || tab == 5) {
            songRows(others, onPlay = { i -> playSongs(others, i) }, onMenu = { menuFor = it })
        }
        if (!loading && error == null && tab != 3) {
            val empty = when (tab) {
                0 -> songs.isEmpty() && videos.isEmpty() && libTracks.isEmpty()
                1 -> songs.isEmpty()
                2 -> videos.isEmpty()
                else -> others.isEmpty()
            }
            if (empty) item { EmptyState(Icons.Rounded.Search, "Ничего не нашлось", "Попробуйте написать иначе или выбрать другую вкладку.") }
        }
    }

    menuFor?.let { r ->
        ActionSheet(
            onDismiss = { menuFor = null },
            actions = listOf(
                SheetAction(Icons.Rounded.PlayArrow, "Слушать") { playSongs(listOf(r), 0) },
                SheetAction(Icons.Rounded.Videocam, "Смотреть видео") { watch(r) },
                SheetAction(Icons.Rounded.PlaylistAdd, "Добавить в очередь") {
                    vm.player.addOnline(listOf(OnlineItem(r.url, r.title, r.author.substringBefore(" · "), r.durationSec, r.thumbnail)))
                    vm.message("Добавлено в очередь")
                },
                SheetAction(Icons.Rounded.MusicNote, "Скачать MP3") {
                    vm.downloader.download(LinkInfo(r.url, r.title, r.author.substringBefore(" · "), r.durationSec, r.thumbnail, "", emptyList()), DownloadKind.AUDIO, null)
                    vm.message("Скачиваю MP3 — прогресс во вкладке «Загрузки»")
                },
                SheetAction(Icons.Rounded.Download, "Скачать видео…") {
                    vm.sharedUrl.value = r.url
                },
            ),
        )
    }
}

private fun LazyListScope.songRows(list: List<SearchResult>, onPlay: (Int) -> Unit, onMenu: (SearchResult) -> Unit) {
    items(list.size, key = { "s" + list[it].url }) { i ->
        val r = list[i]
        val c = Vesper.colors
        Row(
            Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(start = 20.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(50.dp).clip(Vesper.shapes.artwork)) { RemoteImage(r.thumbnail, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(r.author, if (r.durationSec > 0) formatDuration(r.durationSec * 1000L) else "").filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PressableIcon(Icons.Rounded.MoreVert, "Действия", onClick = { onMenu(r) }, tint = c.textMuted, size = 20.dp)
        }
    }
}

@Composable
private fun VideoResultRow(r: SearchResult, onClick: () -> Unit, onMenu: () -> Unit) {
    val c = Vesper.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 136.dp, height = 77.dp).clip(Vesper.shapes.small)) {
            RemoteImage(r.thumbnail, Modifier.fillMaxSize())
            if (r.durationSec > 0) {
                Text(
                    formatDuration(r.durationSec * 1000L),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(Vesper.shapes.small)
                        .background(Color(0xB3000000)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.author.isNotBlank()) Text(r.author, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PressableIcon(Icons.Rounded.MoreVert, "Действия", onClick = onMenu, tint = c.textMuted, size = 20.dp)
    }
}
