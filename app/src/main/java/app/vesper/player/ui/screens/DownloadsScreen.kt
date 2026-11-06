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
import androidx.compose.material.icons.rounded.CreateNewFolder
import app.vesper.player.ui.components.ScreenHeader
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
import app.vesper.player.playback.OnlineItem
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.data.download.SearchResult
import app.vesper.player.data.download.SearchSource
import app.vesper.player.ui.components.ChipRow
import androidx.compose.material.icons.rounded.Search
import app.vesper.player.data.download.CookieSite
import app.vesper.player.data.download.CookieStore
import app.vesper.player.data.download.DownloadError
import androidx.compose.material.icons.rounded.AccountCircle
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.Routes
import app.vesper.player.data.edit.CropAspect
import app.vesper.player.data.edit.EditSpec
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
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
fun DownloadsScreen() {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
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
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorNeedsLogin by remember { mutableStateOf(false) }
    var accountsVersion by remember { mutableStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") accountsVersion
    val signedIn = CookieStore.signedInSites(context)
    var info by remember { mutableStateOf<LinkInfo?>(null) }
    var kind by rememberSaveable { mutableStateOf(DownloadKind.VIDEO) }
    var height by rememberSaveable { mutableStateOf<Int?>(null) }
    var fragment by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(0f..1f) }
    var crop by remember { mutableStateOf<CropAspect?>(null) }

    var mode by rememberSaveable { mutableStateOf(0) }

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
                    fragment = false
                    range = 0f..1f
                    crop = null
                }
                .onFailure {
                    error = it.message ?: "Не удалось получить данные"
                    errorNeedsLogin = (it as? DownloadError)?.needsLogin == true
                }
            loading = false
        }
    }

    // Link shared from another app ("Поделиться → Floya Music").
    LaunchedEffect(shared) {
        val s = shared
        if (!s.isNullOrBlank()) {
            mode = 0
            url = s
            lookup(s)
        }
        if (s != null) vm.sharedUrl.value = null
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            ScreenHeader("Загрузки", subtitle = "Скачивание и импорт")
            ChipRow(listOf("По ссылке", "Поиск"), 0, onSelect = { if (it == 1) ui.open(Routes.SEARCH) })
            Spacer(Modifier.height(12.dp))
            if (mode == 0) {
                Text(
                    "YouTube, VK, Rutube, TikTok, SoundCloud и сотни других сайтов, а также прямые ссылки на файлы. " +
                        "Совет: в приложении YouTube нажмите «Поделиться» → Floya Music.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        if (mode == 0) item {
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
                error?.let { err ->
                    Spacer(Modifier.height(12.dp))
                    val main = err.substringBefore('\n')
                    val detail = err.substringAfter('\n', "")
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(main, style = MaterialTheme.typography.bodyMedium, color = c.danger)
                            if (detail.isNotBlank()) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (errorNeedsLogin) {
                        Spacer(Modifier.height(10.dp))
                        val site = guessSite(url)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (if (site != null) listOf(site) else CookieSite.entries.toList()).forEach { s ->
                                VesperChip("Войти в ${s.title}", false, onClick = { ui.open(Routes.login(s)) })
                            }
                            VesperChip("Повторить", false, onClick = { lookup(url) })
                        }
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
                            VesperChip("Только аудио", kind == DownloadKind.AUDIO, onClick = { kind = DownloadKind.AUDIO })
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
                        Spacer(Modifier.height(14.dp))
                        OverlineText("Обрезка")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VesperChip("Целиком", !fragment, onClick = { fragment = false })
                            VesperChip("Фрагмент", fragment, onClick = { fragment = true }, )
                        }
                        val totalMs = i.durationSec * 1000L
                        AnimatedVisibility(visible = fragment && totalMs > 0) {
                            Column {
                                RangeSlider(
                                    value = range,
                                    onValueChange = { r -> if (r.endInclusive - r.start > 1000f / totalMs.coerceAtLeast(1)) range = r },
                                    colors = SliderDefaults.colors(
                                        thumbColor = c.accentBright,
                                        activeTrackColor = c.accent,
                                        inactiveTrackColor = c.progressTrack,
                                    ),
                                )
                                Row(Modifier.fillMaxWidth()) {
                                    Text("с ${formatDuration((range.start * totalMs).toLong())}", style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
                                    Spacer(Modifier.weight(1f))
                                    Text("до ${formatDuration((range.endInclusive * totalMs).toLong())}", style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
                                }
                                Text("Скачается только этот кусок — быстрее и меньше трафика.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                            }
                        }
                        AnimatedVisibility(visible = kind == DownloadKind.VIDEO) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                OverlineText("Кадрирование")
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    VesperChip("Без кадрирования", crop == null, onClick = { crop = null })
                                    CropAspect.entries.forEach { a -> VesperChip(a.label, crop == a, onClick = { crop = a }) }
                                }
                                if (crop != null) {
                                    Text(
                                        "Кадр берётся по центру. Точно выставить рамку можно после загрузки: «Обрезать и кадрировать».",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textMuted,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        SecondaryButton(
                            "Слушать онлайн",
                            onClick = { vm.player.playOnline(listOf(OnlineItem(i.url, i.title, i.uploader, i.durationSec, i.thumbnail))) },
                            icon = Icons.Rounded.PlayArrow,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(
                            "Скачать",
                            onClick = {
                                val totalMs2 = i.durationSec * 1000L
                                val edit = EditSpec(
                                    startMs = if (fragment && range.start > 0f) (range.start * totalMs2).toLong() else 0L,
                                    endMs = if (fragment && range.endInclusive < 1f) (range.endInclusive * totalMs2).toLong() else 0L,
                                    crop = if (kind == DownloadKind.VIDEO) crop else null,
                                )
                                vm.downloader.download(i, kind, if (kind == DownloadKind.VIDEO) height else null, edit)
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
                    onEdit = {
                        t.mediaId?.let { id -> ui.open(Routes.editor(audio = t.kind == DownloadKind.AUDIO, id = id)) }
                    },
                    onCancel = { vm.downloader.cancel(t.id) },
                    onOpen = {
                        val id = t.mediaId ?: return@DownloadRow
                        if (t.kind == DownloadKind.AUDIO) {
                            lib.trackById[id]?.let { vm.player.playTracks(listOf(it), 0, shuffle = false) }
                        } else {
                            vids.byId[id]?.let { actions.openVideos(listOf(it), 0) }
                        }
                    },
                    onLogin = { ui.open(Routes.login(guessSite(t.url) ?: CookieSite.YOUTUBE)) },
                    onRetry = {
                        url = t.url
                        lookup(t.url)
                    },
                )
            }
        }

        item(key = "import") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 24.dp)) {
                OverlineText("С телефона", color = c.ornament)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Файлы не копируются — Floya Music просто запоминает, где они лежат.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImportTile(Icons.Rounded.MusicNote, "Аудиофайлы", "MP3, FLAC, M4A…", Modifier.weight(1f), onClick = actions.importAudio)
                    ImportTile(Icons.Rounded.Videocam, "Видеофайлы", "MP4, MKV, WEBM…", Modifier.weight(1f), onClick = actions.importVideo)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImportTile(Icons.Rounded.CreateNewFolder, "Папку целиком", "Всё аудио и видео", Modifier.weight(1f), onClick = actions.importFolder)
                    ImportTile(
                        Icons.Rounded.Refresh,
                        if (scanning) "Сканирую…" else "Найти на телефоне",
                        "${lib.allTracks.size} аудио · ${vids.all.size} видео",
                        Modifier.weight(1f),
                        onClick = {
                            if (vm.hasAudioPermission() || vm.hasVideoPermission()) vm.rescan() else actions.requestPermissions()
                        },
                    )
                }
            }
        }

        item(key = "accounts") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp)) {
                OverlineText("Аккаунты для загрузки")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Нужны для YouTube (если просит подтвердить, что вы не бот), видео 18+, закрытых роликов VK и Instagram.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CookieSite.entries.forEach { site ->
                        val on = site in signedIn
                        VesperChip(if (on) "✓ ${site.title}" else site.title, on, onClick = { ui.open(Routes.login(site)) })
                    }
                    VesperChip("Импорт cookies.txt", false, onClick = {
                        actions.pickTextFile { uri ->
                            if (uri != null) {
                                val n = runCatching { CookieStore.importFile(context, uri) }.getOrDefault(0)
                                vm.message(if (n > 0) "Импортировано cookies: $n" else "В файле нет cookies в формате Netscape")
                                accountsVersion++
                            }
                        }
                    })
                    if (signedIn.isNotEmpty() || CookieStore.hasAny(context)) {
                        VesperChip("Выйти из всех", false, onClick = {
                            CookieStore.clear(context)
                            accountsVersion++
                            vm.message("Данные входа удалены")
                        })
                    }
                }
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
private fun DownloadRow(task: DownloadTask, onEdit: () -> Unit, onLogin: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit, onRetry: () -> Unit) {
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
            task.status == DownloadStatus.DONE && task.mediaId != null ->
                PressableIcon(Icons.Rounded.ContentCut, "Обрезать", onClick = onEdit, tint = c.accentBright, size = 20.dp)
            task.status == DownloadStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = c.accentBright, modifier = Modifier.padding(12.dp).size(22.dp))
            task.status == DownloadStatus.FAILED -> Row {
                if (task.needsLogin) PressableIcon(Icons.Rounded.AccountCircle, "Войти", onClick = onLogin, tint = c.accentBright, size = 20.dp)
                PressableIcon(Icons.Rounded.Refresh, "Повторить", onClick = onRetry, tint = c.accentBright, size = 20.dp)
            }
            else -> PressableIcon(Icons.Rounded.PlayArrow, "Открыть", onClick = onOpen, tint = c.textMuted, size = 20.dp, enabled = false)
        }
    }
}

/** Which sign-in a link probably needs. */
private fun guessSite(url: String): CookieSite? {
    val u = url.lowercase()
    return when {
        "youtu" in u -> CookieSite.YOUTUBE
        "vk.com" in u || "vkvideo" in u || "vk.ru" in u -> CookieSite.VK
        "instagram" in u -> CookieSite.INSTAGRAM
        "tiktok" in u -> CookieSite.TIKTOK
        else -> null
    }
}

@Composable
private fun ImportTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Vesper.colors
    Column(
        modifier
            .clip(Vesper.shapes.medium)
            .background(c.surface)
            .border(1.dp, c.outline, Vesper.shapes.medium)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
