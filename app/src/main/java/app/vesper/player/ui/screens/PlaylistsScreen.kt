package app.vesper.player.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.PlaylistSummary
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ActionSheet
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.ConfirmDialog
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.PlaylistArt
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.ScreenHeader
import app.vesper.player.ui.components.SheetAction
import app.vesper.player.ui.components.TextInputDialog
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.components.videosCount
import app.vesper.player.ui.theme.Vesper

@Composable
fun PlaylistsScreen() {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val bottom = LocalBottomInset.current
    val music = playlists.count { it.kind == PlaylistKind.MUSIC }
    val video = playlists.count { it.kind == PlaylistKind.VIDEO }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Плейлисты", subtitle = "Музыка: $music · Видео: $video") {
            PressableIcon(Icons.Rounded.SwapHoriz, "Перенести из другого сервиса", onClick = { ui.transferVisible = true })
            PressableIcon(Icons.Rounded.Add, "Создать плейлист", onClick = { if (ui.playlistKind == 2) ui.transferVisible = true else creating = true })
        }
        ChipRow(listOf("Музыка", "Видео", "Перенесённые"), ui.playlistKind, onSelect = { ui.playlistKind = it })
        Spacer(Modifier.height(10.dp))
        if (ui.playlistKind == 2) {
            TransferredList(bottom = bottom, onTransfer = { ui.transferVisible = true })
        } else {
            PlaylistGrid(kind = if (ui.playlistKind == 0) PlaylistKind.MUSIC else PlaylistKind.VIDEO, bottom = bottom)
        }
    }

    if (creating) {
        val kind = if (ui.playlistKind == 0) PlaylistKind.MUSIC else PlaylistKind.VIDEO
        TextInputDialog(
            title = if (kind == PlaylistKind.MUSIC) "Новый плейлист" else "Новый видеоплейлист",
            label = "Название",
            initial = "",
            confirmLabel = "Создать",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                vm.createPlaylist(name, kind) { id -> ui.openPlaylist(id) }
            },
        )
    }
}

/** Grid of playlists of one kind, with create/rename/delete. Shared by Playlists and Videos tabs. */
@Composable
fun PlaylistGrid(kind: Int, bottom: Dp) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val all by vm.playlists.collectAsStateWithLifecycle()
    val previews by vm.playlistPreview.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    val playlists = all.filter { it.kind == kind }
    var options by remember { mutableStateOf<PlaylistSummary?>(null) }
    var renaming by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }
    var creating by remember { mutableStateOf(false) }

    if (playlists.isEmpty()) {
        EmptyState(
            icon = if (kind == PlaylistKind.MUSIC) Icons.Rounded.QueueMusic else Icons.Rounded.VideoLibrary,
            title = if (kind == PlaylistKind.MUSIC) "Нет плейлистов" else "Нет видеоплейлистов",
            message = if (kind == PlaylistKind.MUSIC) {
                "Соберите музыку под настроение: создайте плейлист и добавляйте треки из библиотеки или прямо из плеера."
            } else {
                "Объединяйте фильмы, клипы и записи в подборки — они сохраняются на устройстве."
            },
            primaryLabel = "Создать плейлист",
            onPrimary = { creating = true },
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottom + 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(playlists, key = { it.id }) { p ->
                Column(
                    Modifier
                        .clip(Vesper.shapes.small)
                        .combinedClickable(onClick = { ui.openPlaylist(p.id) }, onLongClick = { options = p }),
                ) {
                    PlaylistArt(p, previews[p.id].orEmpty(), lib, vids, Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(10.dp))
                    Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (kind == PlaylistKind.VIDEO) videosCount(p.itemCount) else tracksCount(p.itemCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Удерживайте плейлист для быстрых действий",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
    }

    options?.let { p ->
        ActionSheet(
            onDismiss = { options = null },
            actions = buildList {
                if (kind == PlaylistKind.MUSIC) {
                    add(SheetAction(Icons.Rounded.PlayArrow, "Слушать") { vm.playPlaylist(p.id, shuffle = false) })
                    add(SheetAction(Icons.Rounded.Shuffle, "Перемешать") { vm.playPlaylist(p.id, shuffle = true) })
                } else {
                    add(SheetAction(Icons.Rounded.PlayArrow, "Смотреть") {
                        val ids = previews[p.id].orEmpty()
                        if (ids.isEmpty()) vm.message("Плейлист пуст") else ui.openPlaylist(p.id)
                    })
                }
                add(SheetAction(Icons.Rounded.Edit, "Переименовать") { renaming = p })
                add(SheetAction(Icons.Rounded.Delete, "Удалить", destructive = true) { deleting = p })
            },
        )
    }
    renaming?.let { p ->
        TextInputDialog("Переименовать", "Название", p.name, "Сохранить", onDismiss = { renaming = null }) {
            vm.renamePlaylist(p.id, it)
            renaming = null
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Удалить «${p.name}»?",
            message = "Плейлист будет удалён. Сами файлы останутся на устройстве.",
            confirmLabel = "Удалить",
            onDismiss = { deleting = null },
            onConfirm = {
                vm.deletePlaylist(p.id)
                deleting = null
            },
        )
    }
    if (creating) {
        TextInputDialog(
            title = if (kind == PlaylistKind.MUSIC) "Новый плейлист" else "Новый видеоплейлист",
            label = "Название",
            initial = "",
            confirmLabel = "Создать",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                vm.createPlaylist(name, kind) { id -> ui.openPlaylist(id) }
            },
        )
    }
}
