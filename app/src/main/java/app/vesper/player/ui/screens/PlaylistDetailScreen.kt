package app.vesper.player.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import app.vesper.player.ui.theme.PlaylistSkin
import app.vesper.player.ui.theme.PlaylistSkins
import app.vesper.player.ui.theme.SkinParticles
import app.vesper.player.ui.theme.SkinScene
import app.vesper.player.ui.theme.VesperThemeProvider
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.ui.ExtraTrackAction
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.PlaylistEntry
import app.vesper.player.ui.components.ActionSheet
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.ConfirmDialog
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OrnamentDivider
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PlaylistCover
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.SearchField
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.SheetAction
import app.vesper.player.ui.components.TextInputDialog
import app.vesper.player.ui.components.TrackRow
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VideoRow
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.components.formatTotalDuration
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.components.videosCount
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun PlaylistDetailScreen(playlistId: Long, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { PlaylistSkins.load(context) }
    val choices by PlaylistSkins.choices.collectAsStateWithLifecycle()
    val skin = PlaylistSkins.byId(choices[playlistId])
    if (skin == null) {
        PlaylistDetailBody(playlistId, onBack, null)
    } else {
        // The playlist's own theme: palette, fonts and shapes for everything inside, independent of the app theme.
        VesperThemeProvider(skin.theme, applySystemBars = false) {
            PlaylistDetailBody(playlistId, onBack, skin)
        }
    }
}

@Composable
private fun PlaylistDetailBody(playlistId: Long, onBack: () -> Unit, skin: PlaylistSkin?) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val flow = remember(playlistId) { vm.playlistDetail(playlistId) }
    val detail by flow.collectAsState(initial = null)
    val player by vm.player.state.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current

    var entries by remember { mutableStateOf<List<PlaylistEntry>>(emptyList()) }
    var dirty by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(detail) {
        detail?.let {
            entries = it.entries
            loaded = true
        }
    }
    LaunchedEffect(dirty) {
        if (dirty == 0) return@LaunchedEffect
        delay(450)
        vm.reorderPlaylist(playlistId, entries.map { it.item.id })
    }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = entries.indexOfFirst { it.item.id == from.key }
        val t = entries.indexOfFirst { it.item.id == to.key }
        if (f >= 0 && t >= 0) {
            entries = entries.toMutableList().apply { add(t, removeAt(f)) }
            dirty++
        }
    }

    var menu by remember { mutableStateOf(false) }
    var coverMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    val d = detail
    val isVideo = d?.playlist?.kind == PlaylistKind.VIDEO

    Box(Modifier.fillMaxSize().then(if (skin != null) Modifier.background(c.background) else Modifier)) {
    if (skin != null) {
        // Living picture behind the header; it slides up slower than the list (parallax).
        val density = androidx.compose.ui.platform.LocalDensity.current
        SkinScene(
            skin.scene,
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .graphicsLayer {
                    val first = listState.firstVisibleItemIndex
                    val off = if (first == 0) listState.firstVisibleItemScrollOffset.toFloat() else with(density) { 800.dp.toPx() }
                    translationY = -off * 0.45f
                    alpha = (1f - off / with(density) { 520.dp.toPx() }).coerceIn(0f, 1f)
                },
            fadeTo = c.background,
        )
        SkinParticles(skin.scene, Modifier.fillMaxSize())
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (d != null) {
                    PressableIcon(Icons.Rounded.Palette, "Тема плейлиста", onClick = { picking = true })
                    PressableIcon(Icons.Rounded.Add, "Добавить", onClick = { adding = true })
                    PressableIcon(Icons.Rounded.MoreVert, "Ещё", onClick = { menu = true })
                }
            }
        }
        if (d == null) {
            if (loaded) item { EmptyState(Icons.Rounded.QueueMusic, "Плейлист удалён", "Этого плейлиста больше нет.") }
            return@LazyColumn
        }
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (skin != null) Spacer(Modifier.height(120.dp))
                Box(
                    Modifier
                        .size(200.dp)
                        .shadow(18.dp, Vesper.shapes.artwork)
                        .clip(Vesper.shapes.artwork)
                        .clickable { coverMenu = true },
                ) {
                    val firstUri = entries.firstOrNull()?.let { it.track?.uri ?: it.video?.uri }
                    if (d.playlist.coverPath != null) {
                        PlaylistCover(d.playlist.coverPath, d.playlist.name, Modifier.fillMaxSize())
                    } else {
                        Artwork(firstUri, d.playlist.name, Modifier.fillMaxSize(), isVideo = isVideo, size = app.vesper.player.ui.components.ArtSize.Large)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OverlineText(
                    (if (isVideo) "Видеоплейлист" else "Плейлист") + (skin?.let { " · ${it.name}" } ?: ""),
                    color = c.ornament,
                )
                Text(
                    d.playlist.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = c.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val total = entries.sumOf { it.track?.durationMs ?: it.video?.durationMs ?: 0L }
                Text(
                    (if (isVideo) videosCount(entries.size) else tracksCount(entries.size)) + " · " + formatTotalDuration(total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
                OrnamentDivider(Modifier.padding(top = 14.dp))
            }
        }
        item(key = "bar") {
            if (entries.isEmpty()) {
                EmptyState(
                    icon = if (isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.QueueMusic,
                    title = "Плейлист пуст",
                    message = if (isVideo) "Добавьте видео из библиотеки." else "Добавьте треки из библиотеки или из плеера.",
                    primaryLabel = "Добавить",
                    onPrimary = { adding = true },
                )
            } else if (isVideo) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Смотреть", onClick = { actions.openVideos(entries.mapNotNull { it.video }, 0) }, icon = Icons.Rounded.PlayArrow)
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Добавить", onClick = { adding = true }, icon = Icons.Rounded.Add)
                }
            } else {
                PlayShuffleBar(
                    count = entries.size,
                    onPlay = { vm.player.playTracks(entries.mapNotNull { it.track }, 0, shuffle = false) },
                    onShuffle = { vm.player.playTracks(entries.mapNotNull { it.track }, shuffle = true) },
                )
            }
        }
        items(entries, key = { it.item.id }) { entry ->
            ReorderableItem(reorderState, key = entry.item.id) { dragging ->
                val handle: @Composable () -> Unit = {
                    Icon(
                        Icons.Rounded.DragHandle, "Перетащить",
                        tint = c.textMuted,
                        modifier = Modifier.draggableHandle().padding(12.dp).size(22.dp),
                    )
                }
                val removeAction = ExtraTrackAction("Удалить из плейлиста") { vm.removePlaylistItem(playlistId, entry.item.id) }
                Box(Modifier.then(if (dragging) Modifier.shadow(10.dp).background(c.surfaceHigh) else if (skin == null) Modifier.background(c.background) else Modifier)) {
                    val track = entry.track
                    val video = entry.video
                    if (track != null) {
                        TrackRow(
                            track = track,
                            isCurrent = player.current?.trackId == track.id,
                            isPlaying = player.isPlaying,
                            onClick = {
                                val list = entries.mapNotNull { it.track }
                                vm.player.playTracks(list, list.indexOf(track), shuffle = null, keepStart = true)
                            },
                            onMore = { ui.showTrackOptions(track, removeAction) },
                            trailing = { handle() },
                        )
                    } else if (video != null) {
                        VideoRow(
                            video = video,
                            onClick = {
                                val list = entries.mapNotNull { it.video }
                                actions.openVideos(list, list.indexOf(video))
                            },
                            onMore = { ui.showVideoOptions(video, removeAction) },
                            trailing = { handle() },
                        )
                    }
                }
            }
        }
    }
    }

    if (menu && d != null) {
        ActionSheet(
            onDismiss = { menu = false },
            actions = listOf(
                SheetAction(Icons.Rounded.Add, if (isVideo) "Добавить видео" else "Добавить треки") { adding = true },
                SheetAction(Icons.Rounded.Edit, "Переименовать") { renaming = true },
                SheetAction(Icons.Rounded.Image, "Изменить обложку") { coverMenu = true },
                SheetAction(Icons.Rounded.Palette, "Тема плейлиста") { picking = true },
                SheetAction(Icons.Rounded.Delete, "Удалить плейлист", destructive = true) { deleting = true },
            ),
        )
    }
    if (coverMenu && d != null) {
        ActionSheet(
            onDismiss = { coverMenu = false },
            actions = buildList {
                add(SheetAction(Icons.Rounded.Image, "Выбрать изображение") {
                    actions.pickImage { uri -> if (uri != null) vm.setPlaylistCover(playlistId, uri) }
                })
                if (d.playlist.coverPath != null) {
                    add(SheetAction(Icons.Rounded.Delete, "Убрать обложку", destructive = true) { vm.setPlaylistCover(playlistId, null) })
                }
            },
        )
    }
    if (renaming && d != null) {
        TextInputDialog("Переименовать", "Название", d.playlist.name, "Сохранить", onDismiss = { renaming = false }) {
            vm.renamePlaylist(playlistId, it)
            renaming = false
        }
    }
    if (deleting && d != null) {
        ConfirmDialog(
            title = "Удалить «${d.playlist.name}»?",
            message = "Плейлист будет удалён. Сами файлы останутся на устройстве.",
            confirmLabel = "Удалить",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                vm.deletePlaylist(playlistId)
                onBack()
            },
        )
    }
    if (picking) {
        SkinPickerSheet(current = skin?.id, onDismiss = { picking = false }) { PlaylistSkins.set(context, playlistId, it) }
    }
    if (adding && d != null) {
        AddItemsSheet(
            isVideo = isVideo,
            existing = entries.map { it.item.mediaId }.toSet(),
            onDismiss = { adding = false },
            onAdd = { ids -> vm.addIdsToPlaylist(playlistId, ids) },
        )
    }
}

/** Multi-select picker of library items to add to a playlist. */
@Composable
private fun AddItemsSheet(
    isVideo: Boolean,
    existing: Set<Long>,
    onDismiss: () -> Unit,
    onAdd: (List<Long>) -> Unit,
) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }

    data class Candidate(val id: Long, val title: String, val subtitle: String, val uri: String)

    val all = remember(lib, vids, isVideo) {
        if (isVideo) vids.sorted.map { Candidate(it.id, it.title, formatDuration(it.durationMs), it.uri) }
        else lib.sortedTracks.map { Candidate(it.id, it.title, "${it.artist} · ${formatDuration(it.durationMs)}", it.uri) }
    }
    val q = query.trim()
    val shown = if (q.isEmpty()) all else all.filter { it.title.contains(q, true) || it.subtitle.contains(q, true) }

    VesperSheet(onDismiss) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isVideo) "Добавить видео" else "Добавить треки",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Добавить (${selected.size})",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected.isEmpty()) c.textMuted else c.accentBright,
                modifier = Modifier
                    .clip(Vesper.shapes.chip)
                    .clickable(enabled = selected.isNotEmpty()) {
                        val ids = selected.toList()
                        dismiss { onAdd(ids) }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        SearchFieldInline(query) { query = it }
        Spacer(Modifier.height(6.dp))
        if (all.isEmpty()) {
            EmptyState(Icons.Rounded.Search, "Библиотека пуста", "Сначала добавьте файлы в библиотеку.")
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(shown, key = { it.id }) { cand ->
                val already = cand.id in existing
                val checked = already || cand.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !already) {
                            if (cand.id in selected) selected.remove(cand.id) else selected.add(cand.id)
                        }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(cand.uri, cand.title, Modifier.size(42.dp), isVideo = isVideo)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cand.title, style = MaterialTheme.typography.titleSmall, color = if (already) c.textMuted else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (already) "Уже в плейлисте" else cand.subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = !already,
                        colors = CheckboxDefaults.colors(
                            checkedColor = c.accent,
                            uncheckedColor = c.outline,
                            checkmarkColor = c.onAccent,
                            disabledCheckedColor = c.textMuted,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchFieldInline(query: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (open) {
        SearchField(query, onChange, "Поиск", onClose = {
            open = false
            onChange("")
        })
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(Vesper.shapes.medium)
                .background(Vesper.colors.surfaceHigh).clickable { open = true }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Vesper.colors.textMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Поиск", style = MaterialTheme.typography.bodyLarge, color = Vesper.colors.textMuted)
        }
    }
}

/** Grid of playlist themes with live miniature scenes. [onPick] gets null for "as in the app". */
@Composable
fun SkinPickerSheet(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = Vesper.colors
    VesperSheet(onDismiss) { dismiss ->
        Text(
            "Тема плейлиста",
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
        )
        Text(
            "Своё оформление только для этого плейлиста — тема приложения не меняется.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        val options: List<PlaylistSkin?> = listOf(null) + PlaylistSkins.all
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(options.chunked(2)) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { option ->
                        SkinCard(option, selected = option?.id == current, modifier = Modifier.weight(1f)) {
                            onPick(option?.id)
                            dismiss {}
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SkinCard(skin: PlaylistSkin?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Vesper.colors
    val shape = Vesper.shapes.medium
    Column(
        modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, c.accentBright, shape) else Modifier.border(1.dp, c.outline, shape)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f)) {
            if (skin != null) {
                SkinScene(skin.scene, Modifier.fillMaxSize(), fadeTo = skin.theme.palette.background.copy(alpha = 0.6f), animate = selected)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.highlight, c.surfaceHigh))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Palette, null, tint = c.textMuted, modifier = Modifier.size(28.dp))
                }
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.accentBright),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(15.dp)) }
            }
        }
        Column(Modifier.background(skin?.theme?.palette?.surface ?: c.surface).fillMaxWidth().padding(10.dp)) {
            Text(
                skin?.name ?: "Как в приложении",
                style = MaterialTheme.typography.titleSmall,
                color = skin?.theme?.palette?.textPrimary ?: c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                skin?.tagline ?: "Цвета текущей темы Floya",
                style = MaterialTheme.typography.bodySmall,
                color = skin?.theme?.palette?.textSecondary ?: c.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
