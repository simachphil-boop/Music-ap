package app.vesper.player.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.movies.Movie
import app.vesper.player.data.movies.MovieListParser
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.RemoteImage
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VesperTextField
import app.vesper.player.ui.components.bouncyClick
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Моя коллекция Кинопоиска» — the user's films inside Floya: want to watch, favourites, watched.
 * Imported from a list the user has; posters and descriptions come from Wikipedia; every film
 * opens on Kinopoisk itself (films are protected and are never downloaded).
 */
@Composable
fun MoviesScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottom = LocalBottomInset.current
    val movies by vm.movies.movies.collectAsStateWithLifecycle()
    val enriching by vm.movies.enriching.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
            }
            val list = text?.let { withContext(Dispatchers.Default) { MovieListParser.parse(it, uri.lastPathSegment.orEmpty()) } }.orEmpty()
            if (list.isEmpty()) vm.message("В файле не нашлось фильмов. Нужны строки «Название (год)» или таблица с колонкой «Название».")
            else vm.message("Добавлено фильмов: ${vm.movies.import(list)} из ${list.size}")
        }
    }

    val shown = when (tab) {
        1 -> movies.filter { it.watchlist && !it.watched }
        2 -> movies.filter { it.favorite }
        3 -> movies.filter { it.watched }
        else -> movies
    }

    LazyVerticalGrid(
        GridCells.Adaptive(118.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottom + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(Modifier.statusBarsPadding().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    PressableIcon(Icons.Rounded.Add, "Добавить фильмы", onClick = { adding = true }, tint = c.accentBright)
                }
                OverlineText("Коллекция", color = c.ornament)
                Text("Моя коллекция Кинопоиска", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                Text(
                    "${movies.size} фильмов" + if (enriching > 0) " · ищу постеры и описания: $enriching" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                ChipRow(
                    listOf("Все", "Хочу посмотреть", "Избранное", "Просмотрено"),
                    tab,
                    onSelect = { tab = it },
                    modifier = Modifier,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        if (movies.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    EmptyState(
                        Icons.Rounded.Movie,
                        "Коллекция пуста",
                        "Загрузите список фильмов (текст, таблица или архив данных Яндекс ID), вставьте его текстом или добавьте фильм по названию.",
                    )
                    PrimaryButton("Добавить фильмы", onClick = { adding = true }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        items(shown, key = { it.id }) { m -> PosterTile(m) { open = m.id } }
    }

    if (adding) {
        AddMoviesSheet(
            onDismiss = { adding = false },
            onPickFile = { adding = false; picker.launch(arrayOf("text/*", "application/json", "text/csv", "application/octet-stream")) },
        ) { text, watched ->
            adding = false
            val list = MovieListParser.parse(text)
            if (list.isEmpty()) vm.message("Не удалось разобрать список")
            else vm.message("Добавлено фильмов: ${vm.movies.import(list, watched)}")
        }
    }
    open?.let { id ->
        movies.firstOrNull { it.id == id }?.let { m -> MovieSheet(m, onDismiss = { open = null }) }
    }
}

@Composable
private fun PosterTile(m: Movie, onClick: () -> Unit) {
    val c = Vesper.colors
    Column(Modifier.bouncyClick(onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Vesper.shapes.medium).background(Brush.linearGradient(listOf(c.highlight, c.surfaceHigh)))) {
            if (m.poster != null) RemoteImage(m.poster, Modifier.fillMaxSize())
            else Icon(Icons.Rounded.Movie, null, tint = c.textMuted, modifier = Modifier.align(Alignment.Center).size(32.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (m.favorite) Badge(Icons.Rounded.Favorite)
                if (m.watched) Badge(Icons.Rounded.CheckCircle)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(m.title.ifBlank { "Фильм" }, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        m.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.padding(start = 4.dp).size(24.dp).clip(Vesper.shapes.chip).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun AddMoviesSheet(onDismiss: () -> Unit, onPickFile: () -> Unit, onText: (String, Boolean) -> Unit) {
    val c = Vesper.colors
    var text by remember { mutableStateOf("") }
    var watched by remember { mutableStateOf(false) }
    VesperSheet(onDismiss) { _ ->
        Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Добавить фильмы", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                "Кинопоиск не даёт сторонним приложениям читать вашу коллекцию. Перенесите её списком: " +
                    "скопируйте названия из своих папок на Кинопоиске или загрузите архив из «Яндекс ID → Данные → Скачать мои данные». " +
                    "Понимаются строки «Брат (1997)», ссылки kinopoisk.ru/film/…, таблицы и JSON.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Загрузить файл со списком", onClick = onPickFile, icon = Icons.Rounded.Description, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp),
                placeholder = { Text("Интерстеллар (2014)\nБрат, 1997\nhttps://www.kinopoisk.ru/film/326/") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary,
                    focusedBorderColor = c.accentBright, unfocusedBorderColor = c.outline, cursorColor = c.accentBright,
                ),
            )
            Spacer(Modifier.height(8.dp))
            ChipRow(listOf("В «Хочу посмотреть»", "В «Просмотрено»"), if (watched) 1 else 0, onSelect = { watched = it == 1 }, modifier = Modifier)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Добавить", onClick = { if (text.isNotBlank()) onText(text, watched) }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MovieSheet(m: Movie, onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    var title by remember(m.id) { mutableStateOf(m.title) }
    VesperSheet(onDismiss) { dismiss ->
        Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Row {
                Box(Modifier.width(110.dp).aspectRatio(2f / 3f).clip(Vesper.shapes.medium).background(c.surfaceHigh)) {
                    if (m.poster != null) RemoteImage(m.poster, Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.Movie, null, tint = c.textMuted, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title.ifBlank { "Фильм" }, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                    m.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary) }
                    if (!m.enriched) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = c.accentBright, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Ищу описание…", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle(if (m.watchlist && !m.watched) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Хочу", m.watchlist && !m.watched) {
                    vm.movies.update(m.id) { it.copy(watchlist = !(it.watchlist && !it.watched), watched = false) }
                }
                Toggle(if (m.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Избранное", m.favorite) {
                    vm.movies.update(m.id) { it.copy(favorite = !it.favorite) }
                }
                Toggle(if (m.watched) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, "Просмотрено", m.watched) {
                    vm.movies.update(m.id) { it.copy(watched = !it.watched, watchlist = if (!it.watched) false else it.watchlist) }
                }
            }
            m.description?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Text("Источник: Википедия", style = MaterialTheme.typography.labelSmall, color = c.textMuted, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (m.kinopoiskId != null) "Открыть на Кинопоиске" else "Найти на Кинопоиске", onClick = { openUrl(context, m.kinopoiskUrl) }, icon = Icons.Rounded.OpenInNew, modifier = Modifier.fillMaxWidth())
            m.watchUrl?.let { url ->
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Смотреть в онлайн-кинотеатре Кинопоиска", onClick = { openUrl(context, url) }, icon = Icons.Rounded.PlayCircle, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Трейлер на YouTube", onClick = { openUrl(context, m.trailerSearchUrl) }, icon = Icons.Rounded.PlayCircle, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OverlineText("Исправить название", color = c.textMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { VesperTextField(title, { title = it }, "Название") }
                PressableIcon(Icons.Rounded.Refresh, "Найти заново", onClick = {
                    vm.movies.update(m.id) { it.copy(title = title.trim(), poster = null, description = null, wikiUrl = null) }
                    vm.movies.refresh(m.id)
                }, tint = c.accentBright)
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Удалить из коллекции", onClick = { vm.movies.remove(m.id); dismiss {} }, icon = Icons.Rounded.Delete, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Toggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    Column(
        Modifier.clip(Vesper.shapes.medium).background(if (on) c.highlight else c.surfaceHigh).bouncyClick(onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (on) c.accentBright else c.textSecondary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) c.textPrimary else c.textSecondary)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
