package app.vesper.player.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Brightness3
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.data.wave.SoundProfile
import app.vesper.player.data.wave.Vibe
import app.vesper.player.data.wave.VibeGroup
import app.vesper.player.data.wave.Vibes
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PlayPauseButton
import app.vesper.player.ui.theme.Vesper
import kotlin.math.PI
import kotlin.math.sin

fun vibeIcon(vibe: Vibe): ImageVector = when (vibe.id) {
    "my" -> Icons.Rounded.GraphicEq
    "fav" -> Icons.Rounded.Favorite
    "new" -> Icons.Rounded.NewReleases
    "old" -> Icons.Rounded.History
    "energetic" -> Icons.Rounded.FlashOn
    "happy" -> Icons.Rounded.WbSunny
    "calm" -> Icons.Rounded.Spa
    "sad" -> Icons.Rounded.Cloud
    "dark" -> Icons.Rounded.Brightness3
    "workout" -> Icons.Rounded.FitnessCenter
    "focus" -> Icons.Rounded.CenterFocusStrong
    "drive" -> Icons.Rounded.DirectionsCar
    "party" -> Icons.Rounded.Audiotrack
    "relax" -> Icons.Rounded.Weekend
    else -> Icons.Rounded.NightsStay
}

/** Home-screen block: animated wave hero + mood / activity / character presets. */
@Composable
fun WaveSection() {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val wave by vm.wave.state.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val activeVibe = if (wave.active) wave.vibe else null
    val playing = wave.active && player.isPlaying

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp)
                .fillMaxWidth()
                .height(214.dp)
                .clip(Vesper.shapes.large)
                .background(Brush.verticalGradient(listOf(c.surfaceHigh, c.surface)))
                .border(1.dp, c.outline, Vesper.shapes.large)
                .clickable {
                    if (wave.active) vm.player.togglePlayPause() else vm.startWave(Vibes.myWave)
                },
        ) {
            WaveCanvas(Modifier.fillMaxSize(), active = playing, profile = wave.nowProfile)
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                OverlineText(if (wave.active) "Волна играет" else "Волна", color = c.ornament)
                AnimatedContent(
                    targetState = activeVibe ?: Vibes.myWave,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                    label = "waveTitle",
                ) { v ->
                    Column {
                        Text(v.title, style = MaterialTheme.typography.headlineLarge, color = c.textPrimary, maxLines = 1)
                        Text(
                            if (wave.active && player.current != null) "${player.current?.title} — ${player.current?.artist}" else v.subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayPauseButton(
                        isPlaying = playing,
                        onClick = { if (wave.active) vm.player.togglePlayPause() else vm.startWave(Vibes.myWave) },
                        size = 56.dp,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            if (wave.active) "Бесконечный поток" else "Слушать волну",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.textPrimary,
                        )
                        Text(
                            when {
                                wave.starting -> "Подбираю музыку…"
                                wave.active -> "Пропускайте — волна учится"
                                wave.mode == app.vesper.player.data.wave.WaveMode.ONLINE -> "Из интернета, по вашему вкусу"
                                else -> "Из ваших загрузок"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            app.vesper.player.data.wave.WaveMode.entries.forEach { m ->
                app.vesper.player.ui.components.VesperChip(m.title, wave.mode == m, onClick = { vm.wave.setMode(m) })
            }
        }
        Text(
            if (wave.mode == app.vesper.player.data.wave.WaveMode.ONLINE) {
                "Музыка из интернета под настроение. Понравился трек — ❤ или скачайте его кнопкой ↓ в плеере."
            } else {
                "Только треки, которые вы скачали через Floya Music."
            },
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )

        if (wave.mode == app.vesper.player.data.wave.WaveMode.DOWNLOADS && analysis.total > 0 && !analysis.complete) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text(
                    "Floya изучает звучание библиотеки · ${analysis.analyzed} из ${analysis.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).clip(Vesper.shapes.chip).background(c.progressTrack)) {
                    Box(Modifier.fillMaxWidth(analysis.fraction).height(2.dp).background(c.accentBright))
                }
            }
        }

        listOf(VibeGroup.MOOD, VibeGroup.ACTIVITY, VibeGroup.CHARACTER).forEach { group ->
            OverlineText(group.title, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(Vibes.all.filter { it.group == group && it.id != Vibes.myWave.id }, key = { it.id }) { vibe ->
                    VibeTile(
                        vibe = vibe,
                        selected = activeVibe?.id == vibe.id,
                        loading = wave.starting,
                        onClick = { vm.startWave(vibe) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VibeTile(vibe: Vibe, selected: Boolean, loading: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val bg by animateColorAsState(if (selected) c.highlight else c.surface, tween(250), label = "vibeBg")
    val border by animateColorAsState(if (selected) c.accentBright else c.outline, tween(250), label = "vibeBorder")
    Column(
        Modifier
            .width(118.dp)
            .clip(Vesper.shapes.medium)
            .background(bg)
            .border(1.dp, border, Vesper.shapes.medium)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(12.dp),
    ) {
        Icon(vibeIcon(vibe), null, tint = if (selected) c.accentBright else c.ornament, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(vibe.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1)
        Text(vibe.subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Three layered sine waves. Speed follows the current track's tempo and height its energy,
 * so the visual "breathes" with the music without needing microphone/visualizer access.
 */
@Composable
fun WaveCanvas(modifier: Modifier, active: Boolean, profile: SoundProfile?) {
    val c = Vesper.colors
    val energy = profile?.energy ?: 0.5f
    val tempo = profile?.tempo ?: 0.5f
    val amplitude by animateFloatAsState(if (active) 0.45f + energy * 0.55f else 0.28f, tween(1400), label = "waveAmp")
    val speed by animateFloatAsState(if (active) 0.35f + tempo * 0.9f else 0.12f, tween(1400), label = "waveSpeed")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f * speed
                last = now
            }
        }
    }
    val layers = listOf(
        Triple(c.accent, 0.62f, 1.0f),
        Triple(c.accentBright, 0.70f, 1.45f),
        Triple(c.ornament, 0.78f, 2.1f),
    )
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        layers.forEachIndexed { i, (color, baseline, freq) ->
            val path = Path()
            val base = h * baseline
            val amp = h * 0.11f * amplitude * (1f - i * 0.18f)
            path.moveTo(0f, h)
            var x = 0f
            while (x <= w + 6f) {
                val nx = x / w
                val y = base +
                    sin((nx * freq * 2f * PI + time * (2.2f + i * 0.7f) * PI + i * 1.3f).toFloat()) * amp +
                    sin((nx * freq * 4.3f * PI - time * 1.4f * PI).toFloat()) * amp * 0.35f
                path.lineTo(x, y)
                x += 6f
            }
            path.lineTo(w, h)
            path.close()
            drawPath(
                path,
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.34f - i * 0.07f), Color.Transparent),
                    startY = base - amp,
                    endY = h,
                ),
            )
        }
    }
}
