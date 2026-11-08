package app.vesper.player.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.transfer.ImportedTrack
import app.vesper.player.data.transfer.MatchRow
import app.vesper.player.data.transfer.MatchState
import app.vesper.player.data.transfer.PlaylistTransfer
import app.vesper.player.data.transfer.TrackListParser
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.Routes
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.RemoteImage
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.components.tracksCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Перенести мою музыку из Яндекс Музыки».
 * Yandex doesn't let other apps take audio files (they're protected), so Floya moves the *library*:
 * playlists, titles, artists and order — then finds each track among legally streamable
 * versions and lets you check the matches before saving.
 */
@Composable
fun YandexImportScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottom = LocalBottomInset.current
    var login by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playlists by remember { mutableStateOf<List<PlaylistTransfer.YandexPlaylistRef>>(emptyList()) }
    var pasting by remember { mutableStateOf(false) }

    fun review(name: String, source: String, url: String, cover: String?, tracks: List<ImportedTrack>) {
        if (tracks.isEmpty()) {
            error = "В списке не нашлось ни одного трека. Нужны строки вида «Исполнитель — Название»."
            return
        }
        vm.importReview.start(name, source, url, cover, tracks)
        ui.open(Routes.IMPORT_REVIEW)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, text) = withContext(Dispatchers.IO) { readText(context, uri) }
            if (text == null) {
                error = "Не удалось прочитать файл"
                return@launch
            }
            val tracks = withContext(Dispatchers.Default) { TrackListParser.parse(text, name) }
            review(name.substringBeforeLast('.').ifBlank { "Импорт" }, "Файл", "", null, tracks)
        }
    }

    fun loadPlaylists() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { vm.transfer.yandexUserPlaylists(login) }
                .onSuccess { playlists = it }
                .onFailure { error = it.message ?: "Не удалось получить плейлисты" }
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OverlineText("Перенос", color = c.ornament)
                Text("Моя музыка из Яндекс Музыки", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Переносятся плейлисты, названия, исполнители и порядок треков. Сами аудиофайлы Яндекса защищены — " +
                        "Floya найдёт каждую песню среди открытых версий (YouTube Music), а вы проверите совпадения перед сохранением.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
        item { ImportMethodTitle(Icons.Rounded.Person, "По логину", "Покажет все открытые плейлисты и «Мне нравится». Пароль не нужен.") }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                VesperTextField(login, { login = it }, "Логин Яндекса или ссылка на профиль")
                Spacer(Modifier.height(8.dp))
                PrimaryButton(if (loading) "Ищу плейлисты…" else "Показать плейлисты", onClick = { loadPlaylists() }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Если плейлисты не находятся: Яндекс Музыка → Настройки → «Открыть мою фонотеку для всех».",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        items(playlists, key = { "${it.owner}/${it.kind}" }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = opening == null) {
                    opening = p.kind
                    error = null
                    scope.launch {
                        runCatching { vm.transfer.yandexPlaylist(p) }
                            .onSuccess { vm.importReview.start(it); ui.open(Routes.IMPORT_REVIEW) }
                            .onFailure { error = it.message ?: "Не удалось открыть плейлист" }
                        opening = null
                    }
                }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(Vesper.shapes.small).background(c.surfaceHigh)) { RemoteImage(p.cover, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tracksCount(p.trackCount), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                if (opening == p.kind) CircularProgressIndicator(Modifier.size(20.dp), color = c.accentBright, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.PlaylistAdd, "Перенести", tint = c.accentBright)
            }
        }
        item { ImportMethodTitle(Icons.Rounded.Link, "По ссылке", "Любой открытый плейлист или альбом: Яндекс Музыка, Spotify, Deezer, YouTube.") }
        item {
            SecondaryButton("Вставить ссылку на плейлист", onClick = { ui.transferVisible = true }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth())
        }
        item {
            ImportMethodTitle(
                Icons.Rounded.Description, "Из файла",
                "Текст (.txt), таблица (.csv) или архив данных (.json). Свои данные Яндекс отдаёт в «Яндекс ID → Данные → Скачать мои данные».",
            )
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Выбрать файл", onClick = { filePicker.launch(arrayOf("text/*", "application/json", "text/csv", "application/octet-stream")) }, modifier = Modifier.weight(1f))
                SecondaryButton("Вставить текстом", onClick = { pasting = true }, modifier = Modifier.weight(1f))
            }
        }
    }

    if (pasting) {
        PasteListSheet(onDismiss = { pasting = false }) { text ->
            pasting = false
            review("Мой список", "Список", "", null, TrackListParser.parse(text))
        }
    }
}

@Composable
private fun ImportMethodTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    val c = Vesper.colors
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) {
        Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

@Composable
private fun PasteListSheet(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val c = Vesper.colors
    var text by remember { mutableStateOf("") }
    VesperSheet(onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Список треков", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text("По одному на строку: «Исполнитель — Название». Номера и длительность можно не убирать.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                placeholder = { Text("Кино — Группа крови\nMiles Davis — So What") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                    focusedBorderColor = c.accentBright, unfocusedBorderColor = c.outline, cursorColor = c.accentBright,
                ),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Найти треки", onClick = { if (text.isNotBlank()) onDone(text) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun readText(context: Context, uri: Uri): Pair<String, String?> {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Импорт"
    val text = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            // Up to 20 MB — enough for any list, protects from opening a movie by mistake.
            val bytes = input.readNBytesCompat(20 * 1024 * 1024)
            String(bytes, Charsets.UTF_8).let { s -> if (s.count { it == '�' } > 20) String(bytes, charset("windows-1251")) else s }
        }
    }.getOrNull()
    return name to text
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

/** Checking found matches before they become a playlist. */
@Composable
fun ImportReviewScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottom = LocalBottomInset.current
    val state by vm.importReview.state.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<MatchRow?>(null) }
    var saving by remember { mutableStateOf(false) }
    val s = state

    if (s == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack, modifier = Modifier.padding(8.dp))
            EmptyState(Icons.Rounded.Search, "Нечего проверять", "Начните перенос из Яндекс Музыки, файла или по ссылке.")
        }
        return
    }
    val rows = when (filter) {
        1 -> s.rows.filter { it.state == MatchState.CHECK }
        2 -> s.rows.filter { it.state == MatchState.NOT_FOUND }
        3 -> s.rows.filter { it.state == MatchState.SKIPPED }
        else -> s.rows
    }
    val toSave = s.rows.count { it.willSave }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    OverlineText("Проверка · ${s.source}", color = c.ornament)
                    Text(s.name, style = MaterialTheme.typography.headlineMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (s.running) "Ищу совпадения: ${s.done} из ${s.rows.size}"
                        else "Найдено точно: ${s.sure} · проверить: ${s.check} · не найдено: ${s.missing.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    if (s.running) {
                        LinearProgressIndicator(
                            progress = { s.done.toFloat() / s.rows.size.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(3.dp),
                            color = c.accentBright, trackColor = c.progressTrack,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ChipRow(
                    listOf("Все ${s.rows.size}", "Проверить ${s.check}", "Не найдены ${s.missing.size}", "Пропущены ${s.rows.count { it.state == MatchState.SKIPPED }}"),
                    filter,
                    onSelect = { filter = it },
                )
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.check > 0) SecondaryButton("Принять все сомнительные", onClick = { vm.importReview.confirmAll() })
                    if (s.missing.isNotEmpty()) {
                        PressableIcon(Icons.Rounded.ContentCopy, "Скопировать ненайденные", onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Не найдены", vm.importReview.missingText()))
                            vm.message("Список ненайденных треков скопирован")
                        })
                    }
                }
            }
            itemsIndexed(rows, key = { _, r -> r.index }) { _, r -> MatchRowView(r) { editing = r } }
        }
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp + bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryButton("Отмена", onClick = { vm.importReview.close(); onBack() }, modifier = Modifier.weight(1f))
            PrimaryButton(
                if (saving) "Сохраняю…" else "Сохранить $toSave",
                onClick = {
                    if (saving || toSave == 0) return@PrimaryButton
                    saving = true
                    scope.launch {
                        val id = vm.importReview.save()
                        saving = false
                        if (id != null) {
                            val missing = s.missing.size
                            vm.message("Перенесено $toSave треков" + if (missing > 0) " · не найдено: $missing (список можно скопировать)" else "")
                            vm.importReview.close()
                            ui.open(Routes.onlinePlaylist(id))
                        }
                    }
                },
                modifier = Modifier.weight(1.4f),
            )
        }
    }

    editing?.let { r ->
        val live = s.rows.firstOrNull { it.index == r.index } ?: r
        CandidatesSheet(live, onDismiss = { editing = null })
    }
}

@Composable
private fun MatchRowView(r: MatchRow, onClick: () -> Unit) {
    val c = Vesper.colors
    val (badge, color) = when (r.state) {
        MatchState.WAITING -> "ждёт" to c.textMuted
        MatchState.SEARCHING -> "ищу…" to c.textMuted
        MatchState.SURE -> "найден" to c.accentBright
        MatchState.CHECK -> "проверьте" to Color(0xFFE0A33A)
        MatchState.NOT_FOUND -> "не найден" to c.danger
        MatchState.SKIPPED -> "пропущен" to c.textMuted
    }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = r.state != MatchState.SEARCHING && r.state != MatchState.WAITING, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${r.index + 1}. " + listOf(r.want.artist, r.want.title).filter { it.isNotBlank() }.joinToString(" — "),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(badge, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 8.dp))
        }
        val p = r.pick
        if (p != null && r.state != MatchState.NOT_FOUND) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(Vesper.shapes.small).background(c.surfaceHigh)) { RemoteImage(p.thumbnail, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${p.title} · ${p.author.substringBefore(" · ")}" + if (p.durationSec > 0) " · ${formatDuration(p.durationSec * 1000L)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.state == MatchState.SKIPPED) c.textMuted else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CandidatesSheet(r: MatchRow, onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    var query by remember(r.index) { mutableStateOf(listOf(r.want.artist, r.want.title).filter { it.isNotBlank() }.joinToString(" ")) }
    VesperSheet(onDismiss) { dismiss ->
        Column(Modifier.padding(horizontal = 24.dp)) {
            OverlineText("Ищем", color = c.ornament)
            Text(listOf(r.want.artist, r.want.title).filter { it.isNotBlank() }.joinToString(" — "), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Spacer(Modifier.height(10.dp))
            if (r.state == MatchState.SEARCHING) CircularProgressIndicator(Modifier.size(22.dp), color = c.accentBright, strokeWidth = 2.dp)
            r.candidates.forEachIndexed { i, (res, score) ->
                val chosen = i == r.chosen && r.state != MatchState.NOT_FOUND && r.state != MatchState.SKIPPED
                Row(
                    Modifier.fillMaxWidth().clip(Vesper.shapes.small)
                        .then(if (chosen) Modifier.border(1.dp, c.accentBright, Vesper.shapes.small) else Modifier)
                        .clickable { vm.importReview.choose(r.index, i); dismiss {} }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(Vesper.shapes.small).background(c.surfaceHigh)) { RemoteImage(res.thumbnail, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(res.title, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            res.author + if (res.durationSec > 0) " · ${formatDuration(res.durationSec * 1000L)}" else "",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text("${(score * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = if (score >= 0.72) c.accentBright else c.textMuted)
                    if (chosen) Icon(Icons.Rounded.CheckCircle, null, tint = c.accentBright, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                }
            }
            if (r.candidates.isEmpty() && r.state != MatchState.SEARCHING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.HelpOutline, null, tint = c.textMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ничего похожего не нашлось. Попробуйте изменить запрос.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { VesperTextField(query, { query = it }, "Свой запрос") }
                PressableIcon(Icons.Rounded.Search, "Искать", onClick = { vm.importReview.research(r.index, query) }, tint = c.accentBright)
            }
            Spacer(Modifier.height(10.dp))
            if (r.state == MatchState.SKIPPED) {
                SecondaryButton("Вернуть в перенос", onClick = { vm.importReview.restore(r.index); dismiss {} }, modifier = Modifier.fillMaxWidth())
            } else {
                SecondaryButton("Не переносить этот трек", onClick = { vm.importReview.skip(r.index); dismiss {} }, icon = Icons.Rounded.RemoveCircleOutline, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
