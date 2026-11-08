package app.vesper.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.assistant.AiConfig
import app.vesper.player.data.assistant.AssistantAnswer
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.VesperChip
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.launch

private val examples = listOf(
    "Подбери музыку для прогулки",
    "Что-нибудь похожее на этот трек",
    "Спокойную музыку для вечера",
    "Составь плейлист для тренировки на час",
    "Бодрое на утро",
    "Для концентрации на учёбе",
    "Давно не слушал — удиви",
)

/**
 * «Подбор музыки». Locally it's an honest algorithm (sound analysis + your taste + history);
 * with a user-supplied AI key it becomes a real language model choosing from your own tracks.
 */
@Composable
fun AssistantScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val scope = rememberCoroutineScope()
    val bottom = LocalBottomInset.current
    val lib by vm.library.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<AssistantAnswer?>(null) }
    var found by remember { mutableStateOf<app.vesper.player.data.assistant.Discovery?>(null) }
    var searching by remember { mutableStateOf(false) }
    val vids by vm.videos.collectAsStateWithLifecycle()
    val actions = app.vesper.player.ui.LocalAppActions.current
    val initial = app.vesper.player.ui.LocalUi.current.assistantQuery
    var settings by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf("") }
    val cfg = remember(settings) { vm.assistant.config }

    fun ask(text: String) {
        val q = text.trim()
        if (q.isEmpty() || loading) return
        lastQuery = q
        loading = true
        searching = true
        found = null
        val current = player.current?.trackId?.takeIf { it > 0 }
        scope.launch {
            val profiles = vm.soundProfiles()
            answer = vm.assistant.ask(q, lib.allTracks, profiles, current)
            loading = false
        }
        scope.launch {
            found = runCatching {
                vm.assistant.discover(q, lib.allTracks, vids.all, current?.let { id -> lib.allTracks.firstOrNull { it.id == id } }, vm.webSearch)
            }.getOrNull()
            searching = false
        }
    }

    val uiCtl = app.vesper.player.ui.LocalUi.current
    androidx.compose.runtime.LaunchedEffect(initial) {
        val q = initial
        uiCtl.assistantQuery = null
        if (!q.isNullOrBlank()) {
            query = q
            ask(q)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                Spacer(Modifier.weight(1f))
                PressableIcon(Icons.Rounded.Settings, "Подключить ИИ", onClick = { settings = true })
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OverlineText(if (cfg.usable) "ИИ · ${cfg.model}" else "Локальный подбор · без ИИ", color = c.ornament)
                Text("Подбор музыки и видео", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                Text(
                    if (cfg.usable) "ИИ выбирает из вашей библиотеки и советует новые песни и видео из интернета."
                    else "Из вашей библиотеки — по звучанию, избранному и истории; новое — поиском в YouTube Music и YouTube. Настоящую ИИ-модель можно подключить в ⚙.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { VesperTextField(query, { query = it }, "Что послушать или посмотреть?") }
                    PressableIcon(Icons.Rounded.Send, "Подобрать", onClick = { ask(query) }, tint = c.accentBright)
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(examples) { e ->
                    val needsTrack = e.contains("этот трек") && player.current == null
                    VesperChip(if (needsTrack) "$e (включите трек)" else e, false, onClick = { if (!needsTrack) { query = e; ask(e) } })
                }
            }
            if (loading) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.accentBright, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Подбираю…", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
        }
        answer?.let { a ->
            val s = a.suggestion
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (a.byAi) Icon(Icons.Rounded.AutoAwesome, null, tint = c.accentBright, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text(s.title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                    }
                    Text(s.intro, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    a.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(top = 4.dp)) }
                    if (s.picks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Слушать", onClick = { vm.player.playTracks(s.picks.map { it.track }, 0, shuffle = false) }, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                            SecondaryButton("В плейлист", onClick = { vm.createPlaylist(s.title, PlaylistKind.MUSIC, s.picks.map { it.track.id }) }, icon = Icons.Rounded.PlaylistAdd, modifier = Modifier.weight(1f))
                            PressableIcon(Icons.Rounded.Refresh, "Другие варианты", onClick = { ask(lastQuery) })
                        }
                    }
                }
            }
            itemsIndexed(s.picks, key = { _, p -> p.track.id }) { i, p ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.player.playTracks(s.picks.map { it.track }, i, shuffle = false) }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(p.track.uri, p.track.title, modifier = Modifier.size(46.dp), size = ArtSize.Small)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.track.title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.track.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        p.reasons.firstOrNull()?.let { Text("— $it", style = MaterialTheme.typography.labelSmall, color = c.ornament, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        if (searching) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accentBright, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Ищу новое в интернете…", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
        found?.let { d ->
            d.note?.let { n -> item { Text(n, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) } }
            if (d.songs.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            OverlineText(if (d.byAi) "Советует ИИ · онлайн" else "Новое · онлайн", color = c.ornament)
                            Text(d.title.ifBlank { "Новые песни" }, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                        }
                        PressableIcon(Icons.Rounded.PlayArrow, "Слушать все", onClick = { playFound(vm, d.songs, 0) }, tint = c.accentBright)
                    }
                }
                itemsIndexed(d.songs, key = { _, f -> "s" + f.result.url }) { i, f ->
                    OnlineRow(f.result.thumbnail, f.result.title, f.result.author.substringBefore(" · "), f.why) { playFound(vm, d.songs, i) }
                }
            }
            if (d.localVideos.isNotEmpty() || d.onlineVideos.isNotEmpty()) {
                item {
                    Column(Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)) {
                        OverlineText("Видео", color = c.ornament)
                        Text("Посмотреть", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
                    }
                }
                itemsIndexed(d.localVideos, key = { _, v -> "lv" + v.id }) { i, v ->
                    OnlineRow(v.uri, v.title, "У вас на телефоне", "") { actions.openVideos(d.localVideos, i) }
                }
                itemsIndexed(d.onlineVideos, key = { _, r -> "ov" + r.url }) { _, r ->
                    OnlineRow(r.thumbnail, r.title, r.author, "") { actions.openOnlineVideo(r.url, r.title) }
                }
            }
            if (d.songs.isEmpty() && d.onlineVideos.isEmpty() && d.localVideos.isEmpty()) {
                item { Text("В интернете ничего не нашлось — проверьте подключение или измените запрос.", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(20.dp)) }
            }
        }
    }

    if (settings) AiSettingsSheet(onDismiss = { settings = false })
}

private fun playFound(vm: app.vesper.player.ui.MainViewModel, list: List<app.vesper.player.data.assistant.OnlineFind>, index: Int) =
    vm.player.playOnline(list.map { app.vesper.player.playback.OnlineItem(it.result.url, it.result.title, it.result.author.substringBefore(" · "), it.result.durationSec, it.result.thumbnail) }, index)

@Composable
private fun OnlineRow(image: String?, title: String, subtitle: String, why: String, onClick: () -> Unit) {
    val c = Vesper.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = Vesper.shapes.artwork
        if (image != null && image.startsWith("http")) {
            androidx.compose.foundation.layout.Box(Modifier.size(46.dp).clip(shape).background(c.surfaceHigh)) {
                app.vesper.player.ui.components.RemoteImage(image, Modifier.fillMaxSize())
            }
        } else {
            Artwork(image, title, modifier = Modifier.size(46.dp), isVideo = image?.startsWith("content") == true && subtitle == "У вас на телефоне", size = ArtSize.Small)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (why.isNotBlank()) Text("— $why", style = MaterialTheme.typography.labelSmall, color = c.ornament, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AiSettingsSheet(onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val scope = rememberCoroutineScope()
    val initial = remember { vm.assistant.config }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var base by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf(initial.apiKey) }
    var consent by remember { mutableStateOf(initial.consent) }
    var history by remember { mutableStateOf(vm.assistant.useHistory) }
    var status by remember { mutableStateOf<String?>(null) }

    VesperSheet(onDismiss) { dismiss ->
        Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Подбор музыки", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Spacer(Modifier.height(8.dp))
            SwitchLine("Учитывать историю прослушивания", "Используется только на телефоне (и в запросе к ИИ — если он включён)", history) { history = it }
            Spacer(Modifier.height(12.dp))
            OverlineText("Своя ИИ-модель (необязательно)", color = c.ornament)
            Text(
                "Подходит любой сервис с OpenAI-совместимым API: OpenAI, OpenRouter, DeepSeek, локальный LM Studio. " +
                    "Ключ хранится только на этом телефоне и не встроен в приложение.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
            SwitchLine("Использовать ИИ", null, enabled) { enabled = it }
            VesperTextField(base, { base = it }, "Адрес API (https://…/v1)")
            Spacer(Modifier.height(6.dp))
            VesperTextField(model, { model = it }, "Модель")
            Spacer(Modifier.height(6.dp))
            VesperTextField(key, { key = it }, "API-ключ")
            Spacer(Modifier.height(6.dp))
            SwitchLine(
                "Разрешаю отправлять названия треков",
                "В запрос попадут: ваш текст, до 120 названий и исполнителей из библиотеки и любимые исполнители. Файлы и данные аккаунтов не отправляются.",
                consent,
            ) { consent = it }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.accentBright, modifier = Modifier.padding(vertical = 6.dp)) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Проверить", onClick = {
                    status = "Проверяю…"
                    scope.launch { status = vm.assistant.test(AiConfig(true, base, model, key, consent)).fold({ it }, { "Ошибка: ${it.message}" }) }
                }, modifier = Modifier.weight(1f))
                PrimaryButton("Сохранить", onClick = {
                    vm.assistant.config = AiConfig(enabled, base, model, key, consent)
                    vm.assistant.useHistory = history
                    if (enabled && !consent) vm.message("ИИ не будет использоваться без вашего разрешения на отправку названий")
                    dismiss {}
                }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = Vesper.colors
    Row(Modifier.fillMaxWidth().clip(Vesper.shapes.small).clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.outline, uncheckedThumbColor = c.textMuted))
    }
}
