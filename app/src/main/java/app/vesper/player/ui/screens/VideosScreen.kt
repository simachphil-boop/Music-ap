package app.vesper.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.prefs.SortField
import app.vesper.player.data.prefs.SortOrder
import app.vesper.player.ui.LocalAppActions
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.ChoiceSheet
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.ScreenHeader
import app.vesper.player.ui.components.SearchField
import app.vesper.player.ui.components.VideoRow
import app.vesper.player.ui.components.formatTotalDuration
import app.vesper.player.ui.components.videosCount
import app.vesper.player.ui.theme.Vesper

private val videoTabs = listOf("Все", "Недавние", "Избранное", "Плейлисты")

private val videoSortOptions = listOf(
    "Название · А → Я" to SortOrder(SortField.TITLE, true),
    "Сначала новые" to SortOrder(SortField.DATE_ADDED, false),
    "Сначала старые" to SortOrder(SortField.DATE_ADDED, true),
    "Самые длинные" to SortOrder(SortField.DURATION, false),
    "Самые короткие" to SortOrder(SortField.DURATION, true),
)

@Composable
fun VideosScreen() {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val c = Vesper.colors
    val vids by vm.videos.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    vm.permissionVersion.collectAsStateWithLifecycle().value
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortSheet by remember { mutableStateOf(false) }
    var importSheet by remember { mutableStateOf(false) }
    val bottom = LocalBottomInset.current

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Видео",
            subtitle = "${videosCount(vids.all.size)} · ${formatTotalDuration(vids.all.sumOf { it.durationMs })}",
        ) {
            PressableIcon(Icons.Rounded.Download, "Скачать по ссылке", onClick = { ui.open(app.vesper.player.ui.Routes.DOWNLOADS) })
            PressableIcon(Icons.Rounded.Search, "Поиск", onClick = { searching = true })
            PressableIcon(Icons.Rounded.Sort, "Сортировка", onClick = { sortSheet = true })
            PressableIcon(Icons.Rounded.Add, "Добавить видео", onClick = { importSheet = true })
        }
        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp),
                color = c.accentBright,
                trackColor = c.progressTrack,
            )
        }
        if (searching) {
            SearchField(query, { query = it }, "Поиск по видео", onClose = {
                searching = false
                query = ""
            })
            Spacer(Modifier.height(8.dp))
        } else {
            ChipRow(videoTabs, ui.videoTab, onSelect = { ui.videoTab = it })
            Spacer(Modifier.height(6.dp))
        }

        if (!vm.hasVideoPermission() && vids.all.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Lock,
                title = "Нет доступа к видео",
                message = "Разрешите доступ к видео на устройстве или импортируйте файлы MP4, MKV, WEBM вручную.",
                primaryLabel = "Разрешить доступ",
                onPrimary = actions.requestPermissions,
                secondaryLabel = "Импортировать видео",
                onSecondary = actions.importVideo,
            )
            return@Column
        }

        if (searching) {
            val q = query.trim()
            val results = remember(q, vids.sorted) {
                if (q.isEmpty()) emptyList() else vids.sorted.filter { it.title.contains(q, true) || it.folder.contains(q, true) }
            }
            if (q.isNotEmpty() && results.isEmpty()) {
                EmptyState(Icons.Rounded.Search, "Ничего не найдено", "По запросу «$q» видео нет.")
            } else {
                VideoList(results)
            }
            return@Column
        }

        AnimatedContent(
            targetState = ui.videoTab,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            modifier = Modifier.fillMaxSize(),
            label = "videoTab",
        ) { tab ->
            when (tab) {
                0 -> if (vids.sorted.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.VideoLibrary,
                        "Видео пока нет",
                        "На устройстве не найдено видео. Импортируйте файлы MP4, MKV или WEBM — они откроются прямо в Floya FM.",
                        primaryLabel = "Импортировать видео",
                        onPrimary = actions.importVideo,
                    )
                } else VideoList(vids.sorted)
                1 -> if (vids.recent.isEmpty()) {
                    EmptyState(Icons.Rounded.History, "Ещё ничего не смотрели", "Здесь появятся видео, которые вы начали смотреть, — с места остановки.")
                } else VideoList(vids.recent)
                2 -> if (vids.favorites.isEmpty()) {
                    EmptyState(Icons.Rounded.Favorite, "Избранное пусто", "Добавляйте видео в избранное из меню или во время просмотра.")
                } else VideoList(vids.favorites)
                else -> PlaylistGrid(kind = PlaylistKind.VIDEO, bottom = bottom)
            }
        }
    }

    if (sortSheet) {
        ChoiceSheet(
            title = "Сортировка видео",
            options = videoSortOptions.map { it.first },
            selected = videoSortOptions.indexOfFirst { it.second == settings.videoSort },
            onDismiss = { sortSheet = false },
            onSelect = { vm.setVideoSort(videoSortOptions[it].second) },
        )
    }
    if (importSheet) ImportSheet(onDismiss = { importSheet = false })
}

@Composable
private fun VideoList(list: List<VideoEntity>) {
    val ui = LocalUi.current
    val actions = LocalAppActions.current
    val bottom = LocalBottomInset.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
        itemsIndexed(list, key = { _, v -> v.id }) { index, v ->
            VideoRow(
                video = v,
                onClick = { actions.openVideos(list, index) },
                onMore = { ui.showVideoOptions(v) },
            )
        }
    }
}
