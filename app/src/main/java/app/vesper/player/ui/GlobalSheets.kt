package app.vesper.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.MediaSource
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.media.MediaImporter
import app.vesper.player.ui.components.ActionSheet
import app.vesper.player.ui.components.AddToPlaylistSheet
import app.vesper.player.ui.components.SheetAction
import app.vesper.player.ui.components.SheetMediaHeader
import app.vesper.player.ui.components.StatLine
import app.vesper.player.ui.components.TextInputDialog
import app.vesper.player.ui.components.VesperDialog
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.components.formatDate
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.components.formatSize
import app.vesper.player.ui.player.QueueSheet
import app.vesper.player.ui.player.SleepTimerSheet
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GlobalSheets() {
    val ui = LocalUi.current
    val vm = LocalViewModel.current
    val actions = LocalAppActions.current
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()

    ui.trackOptions?.let { (stale, extra) ->
        // Always show the freshest copy (favourite state may have changed).
        val track = library.trackById[stale.id] ?: stale
        TrackOptionsSheet(track, extra, onDismiss = { ui.trackOptions = null })
    }

    ui.videoOptions?.let { (stale, extra) ->
        val video = videos.byId[stale.id] ?: stale
        val list = videos.sorted
        var renaming by remember(video.id) { mutableStateOf(false) }
        ActionSheet(
            onDismiss = { ui.videoOptions = null },
            header = { SheetMediaHeader(video.title, formatDuration(video.durationMs) + "  ·  " + formatSize(video.sizeBytes), video.uri, isVideo = true) },
            actions = buildList {
                add(SheetAction(Icons.Rounded.PlayArrow, "Смотреть") {
                    val idx = list.indexOfFirst { it.id == video.id }
                    if (idx >= 0) actions.openVideos(list, idx) else actions.openVideos(listOf(video), 0)
                })
                add(
                    SheetAction(
                        if (video.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (video.favorite) "Убрать из избранного" else "В избранное",
                    ) { vm.toggleVideoFavorite(video) },
                )
                add(SheetAction(Icons.Rounded.PlaylistAdd, "Добавить в видеоплейлист") {
                    ui.showAddToPlaylist(listOf(video.id), PlaylistKind.VIDEO)
                })
                add(SheetAction(Icons.Rounded.Edit, "Переименовать") { renaming = true })
                extra?.let { e -> add(SheetAction(Icons.Rounded.RemoveCircleOutline, e.label, destructive = true, onClick = e.onClick)) }
                if (video.source == MediaSource.IMPORTED) {
                    add(SheetAction(Icons.Rounded.Delete, "Убрать из библиотеки", destructive = true) {
                        vm.removeVideosFromLibrary(listOf(video.id))
                    })
                }
            },
        )
        if (renaming) {
            TextInputDialog(
                title = "Переименовать видео",
                label = "Название",
                initial = video.title,
                confirmLabel = "Сохранить",
                onDismiss = { renaming = false },
                onConfirm = {
                    vm.renameVideo(video.id, it)
                    renaming = false
                },
            )
        }
    }

    ui.addToPlaylist?.let { (ids, kind) ->
        AddToPlaylistSheet(
            playlists = playlists.filter { it.kind == kind },
            onDismiss = { ui.addToPlaylist = null },
            onPick = { vm.addToPlaylist(it, ids) },
            onCreate = { name -> vm.createPlaylist(name, kind, ids) },
        )
    }

    ui.trackInfo?.let { track ->
        TrackInfoSheet(track, onDismiss = { ui.trackInfo = null })
    }

    ui.editTrack?.let { track ->
        EditTrackDialog(track, onDismiss = { ui.editTrack = null }) { title, artist, album, genre ->
            vm.editTrack(track.id, title, artist, album, genre)
            ui.editTrack = null
        }
    }

    if (ui.queueVisible) QueueSheet(onDismiss = { ui.queueVisible = false })
    if (ui.sleepSheetVisible) SleepTimerSheet(onDismiss = { ui.sleepSheetVisible = false })
}

@Composable
private fun TrackOptionsSheet(track: TrackEntity, extra: ExtraTrackAction?, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val vm = LocalViewModel.current
    val library by vm.library.collectAsStateWithLifecycle()
    ActionSheet(
        onDismiss = onDismiss,
        header = { SheetMediaHeader(track.title, "${track.artist} · ${track.album}", track.uri) },
        actions = buildList {
            add(SheetAction(Icons.Rounded.PlaylistPlay, "Играть следующим") { vm.player.playNext(listOf(track)) })
            add(SheetAction(Icons.Rounded.QueueMusic, "Добавить в очередь") { vm.player.addToQueue(listOf(track)) })
            add(SheetAction(Icons.Rounded.PlaylistAdd, "Добавить в плейлист") {
                ui.showAddToPlaylist(listOf(track.id), PlaylistKind.MUSIC)
            })
            add(
                SheetAction(
                    if (track.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (track.favorite) "Убрать из избранного" else "В избранное",
                ) { vm.toggleFavorite(track) },
            )
            val albumKey = "${track.album}⁣${track.artist}"
            if (library.albums.any { it.key == albumKey }) {
                add(SheetAction(Icons.Rounded.Album, "Перейти к альбому") { ui.openGroup(GroupType.ALBUM, albumKey) })
            }
            add(SheetAction(Icons.Rounded.Person, "Перейти к исполнителю") { ui.openGroup(GroupType.ARTIST, track.artist) })
            add(SheetAction(Icons.Rounded.Edit, "Изменить название и исполнителя") { ui.editTrack = track })
            add(SheetAction(Icons.Rounded.Info, "Сведения о файле") { ui.trackInfo = track })
            extra?.let { e -> add(SheetAction(Icons.Rounded.RemoveCircleOutline, e.label, destructive = true, onClick = e.onClick)) }
            if (track.source == MediaSource.IMPORTED) {
                add(SheetAction(Icons.Rounded.Delete, "Убрать из библиотеки", destructive = true) {
                    vm.removeTracksFromLibrary(listOf(track.id))
                })
            }
        },
    )
}

@Composable
private fun TrackInfoSheet(track: TrackEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = Vesper.colors
    val tech by produceState<Map<String, String>>(emptyMap(), track.uri) {
        value = withContext(Dispatchers.IO) { MediaImporter.technicalInfo(context, Uri.parse(track.uri)) }
    }
    VesperSheet(onDismiss) { _ ->
        SheetMediaHeader(track.title, track.artist, track.uri)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            StatLine("Альбом", track.album)
            StatLine("Жанр", track.genre)
            if (track.year > 0) StatLine("Год", track.year.toString())
            if (track.trackNumber > 0) StatLine("Номер", track.trackNumber.toString())
            StatLine("Длительность", formatDuration(track.durationMs))
            StatLine("Формат", track.mimeType.ifBlank { "—" })
            StatLine("Размер", formatSize(track.sizeBytes))
            tech.forEach { (k, v) -> StatLine(k, v) }
            StatLine("Файл", track.displayName.ifBlank { "—" })
            StatLine("Папка", track.folder.ifBlank { "—" })
            StatLine("Источник", if (track.source == MediaSource.IMPORTED) "Импортирован" else "Медиатека устройства")
            StatLine("Добавлен", formatDate(track.dateAdded))
            StatLine("Прослушиваний", track.playCount.toString())
            if (track.edited) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Название и исполнитель изменены в приложении — исходный файл не затронут.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun EditTrackDialog(
    track: TrackEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var title by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artist) }
    var album by remember { mutableStateOf(track.album) }
    var genre by remember { mutableStateOf(track.genre) }
    VesperDialog(
        title = "Сведения о треке",
        onDismiss = onDismiss,
        confirmLabel = "Сохранить",
        confirmEnabled = title.isNotBlank() && artist.isNotBlank(),
        onConfirm = { onSave(title, artist, album, genre) },
    ) {
        VesperTextField(title, { title = it }, "Название")
        VesperTextField(artist, { artist = it }, "Исполнитель")
        VesperTextField(album, { album = it }, "Альбом")
        VesperTextField(genre, { genre = it }, "Жанр")
        Text(
            "Изменения хранятся в библиотеке Vesper; файл на устройстве не перезаписывается.",
            style = MaterialTheme.typography.bodySmall,
            color = Vesper.colors.textMuted,
        )
    }
}
