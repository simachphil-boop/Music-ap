package app.vesper.player.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.BuildConfig
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.Routes
import app.vesper.player.ui.components.ConfirmDialog
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.ScreenHeader
import app.vesper.player.ui.components.SettingRow
import app.vesper.player.ui.theme.ThemeRegistry
import app.vesper.player.ui.theme.Vesper

@Composable
fun SettingsScreen() {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val sleep by vm.sleepTimer.state.collectAsStateWithLifecycle()
    vm.permissionVersion.collectAsStateWithLifecycle().value
    val bottom = LocalBottomInset.current
    var confirmClear by remember { mutableStateOf(false) }
    val theme = ThemeRegistry.byId(s.themeId)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        item { ScreenHeader("Настройки", subtitle = "Floya Music ${BuildConfig.VERSION_NAME}") }

        item { Group("Оформление") }
        item {
            SettingRow(
                title = "Тема",
                subtitle = "${theme.name} — ${theme.tagline}",
                icon = Icons.Rounded.Palette,
                onClick = { ui.open(Routes.THEMES) },
                trailing = { ThemeSwatch(theme.palette.background, theme.palette.accent, theme.palette.textPrimary) },
            )
        }
        item {
            ToggleRow(
                "Цвет плеера по обложке",
                "Фон полноэкранного плеера мягко окрашивается в тон обложки",
                Icons.Rounded.Image,
                s.artworkTint,
            ) { vm.setArtworkTint(it) }
        }

        item { Group("Воспроизведение") }
        item {
            SettingRow(
                title = "Эквалайзер",
                subtitle = if (s.equalizer.enabled) "Включён" else "Выключен",
                icon = Icons.Rounded.Equalizer,
                onClick = { ui.open(Routes.EQUALIZER) },
                trailing = { Chevron() },
            )
        }
        item {
            SettingRow(
                title = "Таймер сна",
                subtitle = if (sleep.active) "Активен" else "Плавно остановит музыку через заданное время",
                icon = Icons.Rounded.NightsStay,
                onClick = { ui.sleepSheetVisible = true },
                trailing = { Chevron() },
            )
        }
        item {
            ToggleRow(
                "Пауза при отключении наушников",
                "Проводных и Bluetooth — музыка не заиграет из динамика",
                Icons.Rounded.HeadsetOff,
                s.pauseOnDisconnect,
            ) { vm.setPauseOnDisconnect(it) }
        }
        item {
            ToggleRow(
                "Продолжать при подключении",
                "Возобновлять воспроизведение, когда подключены наушники",
                Icons.Rounded.Headphones,
                s.resumeOnConnect,
            ) { vm.setResumeOnConnect(it) }
        }

        item { Group("Волна") }
        item {
            val a by vm.analysis.collectAsStateWithLifecycle()
            SettingRow(
                title = "Анализ звучания",
                subtitle = when {
                    a.total == 0 -> "Появится, когда в библиотеке будет музыка"
                    a.complete -> "Готово: ${a.analyzed} треков. Нажмите, чтобы проанализировать заново"
                    else -> "Проанализировано ${a.analyzed} из ${a.total}" + if (a.running) " · идёт анализ" else ""
                },
                icon = Icons.Rounded.GraphicEq,
                onClick = { vm.reanalyzeLibrary() },
            )
        }
        item {
            SettingRow(
                title = "Сбросить обучение волны",
                subtitle = "Забыть пропуски и треки с отметкой «Не нравится»",
                icon = Icons.Rounded.RestartAlt,
                onClick = { vm.wave.resetLearning() },
            )
        }

        item { Group("Библиотека") }
        item {
            SettingRow(
                title = "Сканировать устройство",
                subtitle = "${lib.allTracks.size} аудио · ${vids.all.size} видео · ${playlists.size} плейлистов",
                icon = Icons.Rounded.Refresh,
                onClick = { if (vm.hasAudioPermission() || vm.hasVideoPermission()) vm.rescan() else actions.requestPermissions() },
            )
        }
        item {
            ToggleRow(
                "Автосканирование при запуске",
                "Новые файлы на устройстве появятся в библиотеке сами",
                Icons.Rounded.Sync,
                s.autoRescan,
            ) { vm.setAutoRescan(it) }
        }
        item {
            ToggleRow(
                "Скрывать короткие записи",
                "Не показывать аудио короче 30 секунд (звуки, голосовые)",
                Icons.Rounded.Timer,
                s.skipShortAudio,
            ) { vm.setSkipShortAudio(it) }
        }
        item {
            SettingRow(
                "Скачать по ссылке",
                subtitle = "Видео и музыка с YouTube, VK и других сайтов",
                icon = Icons.Rounded.Download,
                onClick = { ui.goToTab(Routes.DOWNLOADS) },
                trailing = { Chevron() },
            )
        }
        item { SettingRow("Импортировать аудиофайлы", subtitle = "MP3, FLAC, M4A, AAC, OGG, OPUS, WAV", icon = Icons.Rounded.MusicNote, onClick = actions.importAudio) }
        item { SettingRow("Импортировать видео", subtitle = "MP4, MKV, WEBM, 3GP, MOV", icon = Icons.Rounded.Movie, onClick = actions.importVideo) }
        item { SettingRow("Импортировать папку", subtitle = "Все аудио и видео из выбранной папки", icon = Icons.Rounded.CreateNewFolder, onClick = actions.importFolder) }
        item { SettingRow("Очистить историю", subtitle = "Прослушивания и позиции просмотра", icon = Icons.Rounded.DeleteSweep, onClick = { confirmClear = true }) }

        item { Group("Доступ") }
        item {
            val audio = vm.hasAudioPermission()
            val video = vm.hasVideoPermission()
            SettingRow(
                title = "Разрешения",
                subtitle = "Музыка: ${if (audio) "есть доступ" else "нет доступа"} · Видео: ${if (video) "есть доступ" else "нет доступа"}",
                icon = Icons.Rounded.Lock,
                onClick = { if (audio && video) actions.openAppSettings() else actions.requestPermissions() },
                trailing = { Chevron() },
            )
        }

        item { Group("О приложении") }
        item {
            SettingRow(
                title = "Floya Music ${BuildConfig.VERSION_NAME}",
                subtitle = "Работает полностью офлайн. Библиотека, плейлисты и настройки хранятся только на этом устройстве; файлы не копируются.",
                icon = Icons.Rounded.Info,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистить историю?",
            message = "Счётчики прослушиваний, «Недавние» и позиции просмотра видео будут сброшены.",
            confirmLabel = "Очистить",
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                vm.clearHistory()
            },
        )
    }
}

@Composable
private fun Group(title: String) {
    OverlineText(title, color = Vesper.colors.ornament, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Chevron() {
    Icon(Icons.Rounded.KeyboardArrowRight, null, tint = Vesper.colors.textMuted)
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = { onChange(!checked) },
        trailing = { VesperSwitch(checked, onChange) },
    )
}

@Composable
fun VesperSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Vesper.colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onAccent,
            checkedTrackColor = c.accent,
            checkedBorderColor = c.accent,
            uncheckedThumbColor = c.textMuted,
            uncheckedTrackColor = c.surfaceHigh,
            uncheckedBorderColor = c.outline,
        ),
    )
}

@Composable
fun ThemeSwatch(bg: androidx.compose.ui.graphics.Color, accent: androidx.compose.ui.graphics.Color, text: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.clip(Vesper.shapes.chip).background(bg).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(text))
    }
}
