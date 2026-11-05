package app.vesper.player.ui.screens

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vesper.player.data.download.DownloadKind
import app.vesper.player.data.download.LinkInfo
import app.vesper.player.data.transfer.PlaylistTransfer
import app.vesper.player.data.transfer.TransferredPlaylist
import app.vesper.player.playback.OnlineItem
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.Routes
import app.vesper.player.ui.components.ActionSheet
import app.vesper.player.ui.components.ArtPlaceholder
import app.vesper.player.ui.components.ConfirmDialog
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.RemoteImage
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.SheetAction
import app.vesper.player.ui.components.TextInputDialog
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.components.tracksCount
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.launch

/** Paste a playlist link from another service, preview it, save it. */
@Composable
fun TransferSheet(onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<TransferredPlaylist?>(null) }

    fun load() {
        if (url.isBlank() || loading) return
        loading = true
        error = null
        preview = null
        scope.launch {
            runCatching { vm.transfer.fetch(url) }
                .onSuccess { preview = it }
                .onFailure { error = it.message ?: "Не удалось прочитать плейлист" }
            loading = false
        }
    }

    VesperSheet(onDismiss) { dismiss ->
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Перенести плейлист", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                "Spotify, Яндекс Музыка, Deezer, YouTube, SoundCloud. Треки будут играть онлайн, а любой можно скачать в MP3.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { VesperTextField(url, { url = it }, "Ссылка на плейлист или альбом") }
                PressableIcon(
                    Icons.Rounded.ContentPaste, "Вставить",
                    onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let {
                            url = it.trim()
                            load()
                        }
                    },
                    tint = c.accentBright,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (preview == null) {
                PrimaryButton(if (loading) "Читаю плейлист…" else "Загрузить", onClick = { load() }, modifier = Modifier.fillMaxWidth())
            }
            if (loading) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(color = c.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger)
            }
            preview?.let { p ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(p.coverUrl, p.name, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        OverlineText(p.source, color = c.ornament)
                        Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(tracksCount(p.items.size), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                p.items.take(5).forEach {
                    Text(
                        "${it.artist} — ${it.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (p.items.size > 5) Text("и ещё ${p.items.size - 5}…", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Слушать", onClick = { vm.player.playOnline(p.items) }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                    PrimaryButton(
                        "Сохранить",
                        onClick = {
                            scope.launch {
                                val id = vm.transfer.save(p)
                                vm.message("Плейлист «${p.name}» перенесён")
                                dismiss { ui.open(Routes.onlinePlaylist(id)) }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** List of transferred playlists (third chip on the Playlists tab). */
@Composable
fun TransferredList(bottom: Dp, onTransfer: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val list by vm.transfer.playlists.collectAsState(initial = emptyList())
    if (list.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.SwapHoriz,
            title = "Перенесите свою музыку",
            message = "Вставьте ссылку на плейлист из Spotify, Яндекс Музыки, Deezer, YouTube или SoundCloud — и слушайте его здесь бесплатно.",
            primaryLabel = "Перенести плейлист",
            onPrimary = onTransfer,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        itemsIndexed(list, key = { _, p -> p.id }) { _, p ->
            Row(
                Modifier.fillMaxWidth().clickable { ui.open(Routes.onlinePlaylist(p.id)) }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(p.coverUrl, p.name, 60.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.source} · ${tracksCount(p.itemCount)}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                PressableIcon(
                    Icons.Rounded.PlayArrow, "Слушать",
                    onClick = { vm.player.playOnline(PlaylistTransfer.decode(p.itemsJson)) },
                    tint = c.accentBright,
                )
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                SecondaryButton("Перенести ещё", onClick = onTransfer, icon = Icons.Rounded.SwapHoriz)
            }
        }
    }
}

@Composable
fun OnlinePlaylistScreen(id: Long, onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val flow = remember(id) { vm.transfer.playlist(id) }
    val p by flow.collectAsState(initial = null)
    val bottom = LocalBottomInset.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    val playlist = p
    val items = remember(playlist?.itemsJson) { playlist?.let { PlaylistTransfer.decode(it.itemsJson) } ?: emptyList() }

    fun download(list: List<OnlineItem>) {
        list.forEach { item ->
            vm.downloader.download(
                LinkInfo(item.pageUrl, item.title, item.artist, item.durationSec, item.thumbnail, "", emptyList()),
                DownloadKind.AUDIO,
                null,
            )
        }
        vm.message(if (list.size == 1) "Скачиваю «${list[0].title}»" else "В очередь загрузок: ${list.size} — прогресс во вкладке «Загрузки»")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (playlist != null) PressableIcon(Icons.Rounded.MoreVert, "Ещё", onClick = { menu = true })
            }
        }
        if (playlist == null) return@LazyColumn
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(playlist.coverUrl, playlist.name, 180.dp)
                Spacer(Modifier.height(14.dp))
                OverlineText("Перенесено из ${playlist.source}", color = c.ornament)
                Text(playlist.name, style = MaterialTheme.typography.headlineLarge, color = c.textPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tracksCount(items.size), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Слушать", onClick = { vm.player.playOnline(items) }, icon = Icons.Rounded.PlayArrow)
                    SecondaryButton("Перемешать", onClick = { vm.player.playOnline(items.shuffled()) }, icon = Icons.Rounded.Shuffle)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Скачать все в MP3", onClick = { confirmDownload = true }, icon = Icons.Rounded.Download)
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(items, key = { i, it -> "$i:${it.pageUrl}" }) { index, item ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.player.playOnline(items, index) }.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(item.thumbnail, item.title, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.artist + if (item.durationSec > 0) "  ·  ${formatDuration(item.durationSec * 1000L)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PressableIcon(Icons.Rounded.Download, "Скачать MP3", onClick = { download(listOf(item)) }, tint = c.textMuted, size = 20.dp)
            }
        }
    }

    if (menu && playlist != null) {
        ActionSheet(
            onDismiss = { menu = false },
            actions = listOf(
                SheetAction(Icons.Rounded.Edit, "Переименовать") { renaming = true },
                SheetAction(Icons.Rounded.Delete, "Удалить", destructive = true) { deleting = true },
            ),
        )
    }
    if (renaming && playlist != null) {
        TextInputDialog("Переименовать", "Название", playlist.name, "Сохранить", onDismiss = { renaming = false }) {
            renaming = false
            scope.launch { vm.transfer.rename(id, it) }
        }
    }
    if (deleting && playlist != null) {
        ConfirmDialog(
            title = "Удалить «${playlist.name}»?",
            message = "Скачанные из него треки останутся в библиотеке.",
            confirmLabel = "Удалить",
            onDismiss = { deleting = false },
            onConfirm = {
                deleting = false
                scope.launch { vm.transfer.delete(id) }
                onBack()
            },
        )
    }
    if (confirmDownload) {
        ConfirmDialog(
            title = "Скачать ${tracksCount(items.size)}?",
            message = "Треки будут найдены и скачаны в MP3 по очереди. Это займёт время и трафик.",
            confirmLabel = "Скачать",
            onDismiss = { confirmDownload = false },
            onConfirm = {
                confirmDownload = false
                download(items)
            },
        )
    }
}

@Composable
private fun Cover(url: String?, name: String, size: Dp) {
    Box(Modifier.size(size).clip(Vesper.shapes.artwork).background(Vesper.colors.surfaceHigh)) {
        if (url != null) RemoteImage(url, Modifier.fillMaxSize())
        else ArtPlaceholder(name, isVideo = false, modifier = Modifier.fillMaxSize())
    }
}
