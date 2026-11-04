package app.vesper.player.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.download.DownloadKind
import app.vesper.player.data.download.DownloadStatus
import app.vesper.player.data.download.DownloadTask
import app.vesper.player.data.download.LinkInfo
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.RemoteImage
import app.vesper.player.ui.components.SectionHeader
import app.vesper.player.ui.components.VesperChip
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by vm.downloader.tasks.collectAsStateWithLifecycle()
    val engine by vm.downloader.engine.collectAsStateWithLifecycle()
    val shared by vm.sharedUrl.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current

    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var kind by rememberSaveable { mutableStateOf(DownloadKind.VIDEO) }
    var height by rememberSaveable { mutableStateOf<Int?>(null) }

    fun lookup(target: String) {
        if (target.isBlank() || loading) return
        loading = true
        error = null
        info = null
        scope.launch {
            vm.downloader.fetchInfo(target)
                .onSuccess {
                    info = it
                    height = it.heights.firstOrNull { h -> h <= 1080 }
                }
                .onFailure { error = it.message ?: "Не удалось получить данные" }
            loading = false
        }
    }

    // Link shared from another app ("Поделиться → Floya FM").
    LaunchedEffect(shared) {
        val s = shared
        if (!s.isNullOrBlank()) {
            url = s
            lookup(s)
        }
        if (s != null) vm.sharedUrl.value = null
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OverlineText("По ссылке", color = c.ornament)
                Text("Загрузки", style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
                Text(
                    "YouTube, VK, Rutube, TikTok, SoundCloud и сотни других сайтов, а также прямые ссылки на файлы. " +
                        "Совет: в приложении YouTube нажмите «Поделиться» → Floya FM.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { VesperTextField(url, { url = it }, "Ссылка на видео или трек") }
                    Spacer(Modifier.width(6.dp))
                    PressableIcon(
                        Icons.Rounded.ContentPaste, "Вставить",
                        onClick = {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!text.isNullOrBlank()) {
                                url = text.trim()
                                lookup(url)
                            } else {
                                vm.message("Буфер обмена пуст")
                            }
                        },
                        tint = c.accentBright,
                    )
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    if (loading) "Ищу…" else "Найти",
                    onClick = { lookup(url) },
                    icon = Icons.Rounded.Link,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = c.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (engine.preparing) "Первый запуск: готовлю загрузчик…" else if (engine.updating) "Обновляю движок загрузки…" else "Получаю данные о ролике…",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger)
                    }
                }
            }
        }

        info?.let { i ->
            item(key = "preview") {
                Column(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .clip(Vesper.shapes.large)
                        .background(c.surface)
                        .border(1.dp, c.outline, Vesper.shapes.large),
                ) {
                    RemoteImage(i.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Column(Modifier.padding(16.dp)) {
                        Text(i.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(i.uploader, if (i.durationSec > 0) formatDuration(i.durationSec * 1000L) else "", i.source)
                                .filter { it.isNotBlank() }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VesperChip("Видео", kind == DownloadKind.VIDEO, onClick = { kind = DownloadKind.VIDEO })
                            VesperChip("Только аудио (MP3)", kind == DownloadKind.AUDIO, onClick = { kind = DownloadKind.AUDIO })
                        }
                        AnimatedVisibility(visible = kind == DownloadKind.VIDEO && i.heights.isNotEmpty()) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                OverlineText("Качество")
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    VesperChip("Лучшее", height == null, onClick = { height = null })
                                    i.heights.filter { it >= 240 }.take(6).forEach { h ->
                                        VesperChip("${h}p", height == h, onClick = { height = h })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton(
                            "Скачать",
                            onClick = {
                                vm.downloader.download(i, kind, if (kind == DownloadKind.VIDEO) height else null)
                                info = null
                                url = ""
                            },
                            icon = Icons.Rounded.Download,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (tasks.isNotEmpty()) {
            item(key = "tasksHeader") {
                SectionHeader(
                    "Очередь загрузок",
                    actionLabel = if (tasks.any { !it.active }) "Очистить" else null,
                    onAction = { vm.downloader.clearFinished() },
                )
            }
            items(tasks, key = { it.id }) { t ->
                DownloadRow(
                    task = t,
                    onCancel = { vm.downloader.cancel(t.id) },
                    onOpen = {
                        val id = t.mediaId ?: return@DownloadRow
                        if (t.kind == DownloadKind.AUDIO) {
                            lib.trackById[id]?.let { vm.player.playTracks(listOf(it), 0, shuffle = false) }
                        } else {
                            vids.byId[id]?.let { actions.openVideos(listOf(it), 0) }
                        }
                    },
                    onRetry = {
                        url = t.url
                        lookup(t.url)
                    },
                )
            }
        }

        item(key = "engine") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp)) {
                Row(
                    Modifier.clip(Vesper.shapes.chip).clickable(enabled = !engine.updating) { vm.updateDownloadEngine() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = c.accentBright, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        themedLabel(if (engine.updating) "Обновляю движок…" else "Обновить движок загрузки"),
                        style = MaterialTheme.typography.labelLarge,
                        color = c.accentBright,
                    )
                }
                Text(
                    "Версия yt-dlp: ${engine.version ?: "ещё не запускался"}. Если сайт перестал скачиваться — обновите движок. " +
                        "Видео сохраняются в «Фильмы/Floya», аудио — в «Музыка/Floya». Скачивайте только то, на что у вас есть права.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(task: DownloadTask, onCancel: () -> Unit, onOpen: () -> Unit, onRetry: () -> Unit) {
    val c = Vesper.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = task.status == DownloadStatus.DONE, onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 88.dp, height = 50.dp).clip(Vesper.shapes.small)) {
            RemoteImage(task.thumbnail, Modifier.fillMaxSize())
            Icon(
                if (task.kind == DownloadKind.AUDIO) Icons.Rounded.MusicNote else Icons.Rounded.Videocam,
                null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (task.status) {
                DownloadStatus.QUEUED -> "В очереди"
                DownloadStatus.DOWNLOADING -> "${(task.progress * 100).toInt()}%" + if (task.etaSec > 0) " · осталось ${formatDuration(task.etaSec * 1000)}" else ""
                DownloadStatus.PROCESSING -> "Обработка…"
                DownloadStatus.DONE -> "Готово — нажмите, чтобы открыть"
                DownloadStatus.FAILED -> task.error ?: "Ошибка"
                DownloadStatus.CANCELED -> "Отменено"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = when (task.status) {
                    DownloadStatus.FAILED -> c.danger
                    DownloadStatus.DONE -> c.accentBright
                    else -> c.textSecondary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.active) {
                Spacer(Modifier.height(6.dp))
                if (task.status == DownloadStatus.DOWNLOADING && task.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = c.accentBright,
                        trackColor = c.progressTrack,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = c.accentBright,
                        trackColor = c.progressTrack,
                    )
                }
            }
        }
        when {
            task.active -> PressableIcon(Icons.Rounded.Close, "Отменить", onClick = onCancel, tint = c.textMuted, size = 20.dp)
            task.status == DownloadStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = c.accentBright, modifier = Modifier.padding(12.dp).size(22.dp))
            task.status == DownloadStatus.FAILED -> PressableIcon(Icons.Rounded.Refresh, "Повторить", onClick = onRetry, tint = c.accentBright, size = 20.dp)
            else -> PressableIcon(Icons.Rounded.PlayArrow, "Открыть", onClick = onOpen, tint = c.textMuted, size = 20.dp, enabled = false)
        }
    }
}
