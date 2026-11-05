package app.vesper.player.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.vesper.player.data.db.ArtistStat
import app.vesper.player.data.db.TrackStat
import app.vesper.player.data.wave.Vibe
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ChipRow
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.plural
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

data class VibeStats(
    val totalMs: Long,
    val plays: Int,
    val artists: Int,
    val topArtists: List<ArtistStat>,
    val topTracks: List<TrackStat>,
    val peak: String?,
    val favoriteVibe: Vibe?,
)

private val periods = listOf("Неделя" to 7, "Месяц" to 30, "Всё время" to 0)

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottom = LocalBottomInset.current
    var period by remember { mutableIntStateOf(1) }
    val stats by produceState<VibeStats?>(null, period) { value = vm.loadStats(periods[period].second) }
    val layer = rememberGraphicsLayer()

    fun share() {
        scope.launch {
            val bitmap = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
            if (bitmap == null) {
                vm.message("Не удалось подготовить картинку")
                return@launch
            }
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "floya-vibe.png")
                FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(context, context.packageName + ".files", file)
            }
            val intent = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Поделиться вайбом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                OverlineText("Статистика", color = c.ornament)
                Text("Твой вайб", style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            ChipRow(periods.map { it.first }, period, onSelect = { period = it })
            Spacer(Modifier.height(14.dp))
        }
        val s = stats
        if (s == null) return@LazyColumn
        if (s.plays == 0) {
            item {
                EmptyState(
                    Icons.Rounded.Insights,
                    "Пока нечего считать",
                    "Послушайте музыку — здесь появятся ваши любимые исполнители, треки и настроение.",
                )
            }
            return@LazyColumn
        }
        item {
            // The card that gets shared as an image.
            Box(
                Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .aspectRatio(9f / 14f)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                VibeCard(s, periods[period].first)
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Поделиться в сторис", onClick = { share() }, icon = Icons.Rounded.Share, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth())
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                OverlineText("Топ исполнителей")
                Spacer(Modifier.height(8.dp))
                val max = s.topArtists.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
                s.topArtists.forEachIndexed { i, a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.textMuted, modifier = Modifier.width(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.artist, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(Vesper.shapes.chip).background(c.progressTrack)) {
                                Box(Modifier.fillMaxWidth(a.ms.toFloat() / max).height(4.dp).background(c.accentBright))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("${a.ms / 60_000} мин", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(18.dp))
                OverlineText("Чаще всего звучало")
                Spacer(Modifier.height(8.dp))
                s.topTracks.forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = c.textMuted, modifier = Modifier.width(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(plural(t.plays, "раз", "раза", "раз"), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun VibeCard(s: VibeStats, periodLabel: String) {
    val c = Vesper.colors
    val minutes = s.totalMs / 60_000
    Column(
        Modifier
            .fillMaxSize()
            .clip(Vesper.shapes.large)
            .background(Brush.linearGradient(listOf(c.highlight, c.background, c.surfaceHigh)))
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("FLOYA MUSIC · ${periodLabel.uppercase()}", style = MaterialTheme.typography.labelSmall, color = c.ornament)
            Spacer(Modifier.height(6.dp))
            Text("Вайб Floyachieee", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
        }
        Column {
            Text("$minutes", style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp), color = c.accentBright)
            Text(plural(minutes.toInt(), "минута", "минуты", "минут").substringAfter(' ') + " музыки", style = MaterialTheme.typography.titleMedium, color = c.textSecondary)
            Text("${plural(s.plays, "трек", "трека", "треков")} · ${plural(s.artists, "исполнитель", "исполнителя", "исполнителей")}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.topArtists.firstOrNull()?.let { CardLine("Главный исполнитель", it.artist) }
            s.topTracks.firstOrNull()?.let { CardLine("Трек на повторе", "${it.title} — ${it.artist}") }
            s.favoriteVibe?.let { CardLine("Любимая волна", it.title) }
            s.peak?.let { CardLine("Слушаешь чаще всего", it) }
        }
    }
}

@Composable
private fun CardLine(label: String, value: String) {
    val c = Vesper.colors
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "утром / днём / вечером / ночью" from listening start times. */
fun peakPartOfDay(times: List<Long>): String? {
    if (times.isEmpty()) return null
    val cal = Calendar.getInstance()
    val buckets = IntArray(4)
    times.forEach {
        cal.timeInMillis = it
        val h = cal.get(Calendar.HOUR_OF_DAY)
        buckets[when (h) { in 5..11 -> 0; in 12..17 -> 1; in 18..23 -> 2; else -> 3 }]++
    }
    return listOf("утром", "днём", "вечером", "ночью")[buckets.indices.maxBy { buckets[it] }]
}
