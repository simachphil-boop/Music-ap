package app.vesper.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.OrnamentDivider
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PlayPauseButton
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.SectionHeader
import app.vesper.player.ui.components.TrackRow
import app.vesper.player.ui.components.VesperSlider
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.theme.ThemeRegistry
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.VesperTheme
import app.vesper.player.ui.theme.VesperThemeProvider

@Composable
fun ThemePickerScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current
    var preview by remember { mutableStateOf<VesperTheme?>(null) }
    var lastPreview by remember { mutableStateOf(ThemeRegistry.gothic) }
    preview?.let { lastPreview = it }
    val sample = remember(lib.allTracks) {
        (lib.recentlyPlayed.ifEmpty { lib.sortedTracks }).take(4)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
                }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    OverlineText("Оформление", color = c.ornament)
                    Text("Темы", style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
                    Text(
                        "Нажмите на тему, чтобы увидеть её на своей музыке, а затем примените.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                    )
                    OrnamentDivider(Modifier.padding(vertical = 12.dp))
                }
            }
            items(ThemeRegistry.all, key = { it.id }) { theme ->
                ThemeCard(
                    theme = theme,
                    selected = theme.id == settings.themeId,
                    sampleTitle = sample.firstOrNull()?.title,
                    onClick = { preview = theme },
                )
            }
        }

        AnimatedVisibility(
            visible = preview != null,
            enter = slideInVertically(tween(360)) { it / 3 } + fadeIn(tween(260)),
            exit = slideOutVertically(tween(280)) { it / 3 } + fadeOut(tween(220)),
        ) {
            val t = preview ?: lastPreview
            ThemePreview(
                theme = t,
                sample = sample,
                isCurrent = t.id == settings.themeId,
                onClose = { preview = null },
                onApply = {
                    vm.setTheme(t.id)
                    preview = null
                },
            )
        }
    }
    BackHandler(enabled = preview != null) { preview = null }
}

/** A miniature, fully themed rendering of the player using that theme's own tokens. */
@Composable
private fun ThemeCard(theme: VesperTheme, selected: Boolean, sampleTitle: String?, onClick: () -> Unit) {
    val outer = Vesper.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(Vesper.shapes.large)
            .border(if (selected) 2.dp else 1.dp, if (selected) outer.accentBright else outer.outline, Vesper.shapes.large)
            .clickable(onClick = onClick),
    ) {
        VesperThemeProvider(theme, animate = false, applySystemBars = false) {
            val p = Vesper.colors
            Row(
                Modifier.fillMaxWidth().background(p.background).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(64.dp).clip(Vesper.shapes.artwork)) {
                    app.vesper.player.ui.components.ArtPlaceholder(seed = sampleTitle ?: theme.name, isVideo = false, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(theme.name, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary, maxLines = 1)
                    Text(theme.tagline, style = MaterialTheme.typography.bodySmall, color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(Vesper.shapes.chip).background(p.progressTrack)) {
                        Box(
                            Modifier.fillMaxWidth(0.62f).height(3.dp)
                                .background(Brush.horizontalGradient(listOf(p.progressStart, p.progressEnd))),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier.size(40.dp).clip(Vesper.shapes.playButton).background(p.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = p.onAccent, modifier = Modifier.size(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(p.background, p.surfaceHigh, p.accent, p.accentBright, p.ornament, p.textPrimary).forEach { col ->
                    Box(Modifier.padding(end = 6.dp).size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col).border(1.dp, p.outline, androidx.compose.foundation.shape.CircleShape))
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = p.accentBright, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Активна", style = MaterialTheme.typography.labelMedium, color = p.accentBright)
                } else {
                    Text("Предпросмотр", style = MaterialTheme.typography.labelMedium, color = p.textMuted)
                }
            }
        }
    }
}

/** Full-screen preview rendered in the candidate theme, using the user's real tracks when available. */
@Composable
private fun ThemePreview(
    theme: VesperTheme,
    sample: List<TrackEntity>,
    isCurrent: Boolean,
    onClose: () -> Unit,
    onApply: () -> Unit,
) {
    VesperThemeProvider(theme, animate = false, applySystemBars = false) {
        val p = Vesper.colors
        val lead = sample.firstOrNull()
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(p.backgroundAlt, p.background)))
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Закрыть предпросмотр", onClick = onClose)
                Column(Modifier.weight(1f)) {
                    OverlineText("Предпросмотр", color = p.ornament)
                    Text(theme.name, style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Artwork(
                    uri = lead?.uri,
                    seed = lead?.title ?: theme.name,
                    modifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f),
                    size = ArtSize.Large,
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(lead?.title ?: "Название трека", style = MaterialTheme.typography.headlineMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lead?.artist ?: "Исполнитель", style = MaterialTheme.typography.bodyLarge, color = p.textSecondary, maxLines = 1)
                    }
                    Icon(Icons.Rounded.Favorite, null, tint = p.accentBright)
                }
                Spacer(Modifier.height(12.dp))
                VesperSlider(value = 0.38f, onValueChange = {}, restingThumb = 10.dp)
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDuration((lead?.durationMs ?: 210_000L) * 38 / 100), style = MaterialTheme.typography.labelMedium, color = p.textMuted)
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(lead?.durationMs ?: 210_000L), style = MaterialTheme.typography.labelMedium, color = p.textMuted)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shuffle, null, tint = p.accentBright)
                    Icon(Icons.Rounded.SkipPrevious, null, tint = p.textPrimary, modifier = Modifier.size(34.dp))
                    PlayPauseButton(isPlaying = false, onClick = {}, size = 66.dp)
                    Icon(Icons.Rounded.SkipNext, null, tint = p.textPrimary, modifier = Modifier.size(34.dp))
                    Icon(Icons.Rounded.Repeat, null, tint = p.textMuted)
                }
            }
            if (sample.size > 1) {
                SectionHeader("Ваша библиотека")
                sample.drop(1).take(2).forEachIndexed { i, t ->
                    TrackRow(t, isCurrent = i == 0, isPlaying = false, onClick = {})
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton("Назад", onClick = onClose, modifier = Modifier.weight(1f))
                PrimaryButton(if (isCurrent) "Уже применена" else "Применить", onClick = onApply, modifier = Modifier.weight(1f))
            }
        }
    }
}
