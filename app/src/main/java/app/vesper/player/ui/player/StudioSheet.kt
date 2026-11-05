package app.vesper.player.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.playback.StudioPreset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.VesperSheet
import app.vesper.player.ui.components.VesperSlider
import app.vesper.player.ui.screens.VesperSwitch
import app.vesper.player.ui.theme.Vesper
import java.util.Locale

@Composable
fun StudioSheet(onDismiss: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val studio by vm.studio.state.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    val lib by vm.library.collectAsStateWithLifecycle()
    val job by vm.editor.job.collectAsStateWithLifecycle()
    val current = player.current
    val track = current?.let { lib.trackById[it.trackId] }

    VesperSheet(onDismiss) { _ ->
        Text("Студия звука", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.padding(horizontal = 24.dp))
        Text(
            "Эффект звучит сразу, для всей музыки. Оригинальные файлы не меняются.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(StudioPreset.entries) { p ->
                val selected = studio.preset == p
                val bg by animateColorAsState(if (selected) c.highlight else c.surfaceHigh, tween(200), label = "preset")
                Column(
                    Modifier
                        .width(132.dp)
                        .clip(Vesper.shapes.medium)
                        .background(bg)
                        .border(1.dp, if (selected) c.accentBright else c.outline, Vesper.shapes.medium)
                        .clickable { vm.studio.applyPreset(p) }
                        .padding(12.dp),
                ) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall, color = if (selected) c.accentBright else c.textPrimary, maxLines = 1)
                    Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            StudioSlider("Скорость", "%.2f×".format(Locale.US, studio.speed), (studio.speed - 0.5f) / 1f) { f ->
                vm.studio.update { it.copy(speed = 0.5f + f) }
            }
            StudioSlider("Тон", "%.2f×".format(Locale.US, studio.pitch), (studio.pitch - 0.5f) / 1f) { f ->
                vm.studio.update { it.copy(pitch = 0.5f + f) }
            }
            StudioSlider("Эхо (реверберация)", "${(studio.reverb * 100).toInt()}%", studio.reverb) { f ->
                vm.studio.update { it.copy(reverb = f) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("8D-звук", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text("Лучше всего в наушниках", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                VesperSwitch(studio.eightD) { on -> vm.studio.update { it.copy(eightD = on) } }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
            val j = job
            when {
                j != null && j.running -> {
                    OverlineText("${j.stage} ${(j.progress * 100).toInt()}%")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { j.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = c.accentBright,
                        trackColor = c.progressTrack,
                    )
                }
                j != null && j.error != null -> {
                    Text(j.error, style = MaterialTheme.typography.bodySmall, color = c.danger)
                    Text(
                        "Закрыть",
                        color = c.accentBright,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { vm.editor.dismiss() }.padding(vertical = 8.dp),
                    )
                }
                j != null && j.resultId != null -> {
                    Text("Сохранено: ${j.title}", style = MaterialTheme.typography.bodyMedium, color = c.accentBright)
                    Text(
                        "Ок",
                        color = c.accentBright,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clickable { vm.editor.dismiss() }.padding(vertical = 8.dp),
                    )
                }
                track != null && !studio.isNormal -> PrimaryButton(
                    "Сохранить «${track.title}» с эффектом в MP3",
                    onClick = {
                        vm.editor.startEffectExport(
                            sourceUri = track.uri,
                            title = track.title,
                            durationMs = track.durationMs,
                            filter = vm.studio.ffmpegFilter(studio),
                            label = studio.label,
                            speed = studio.speed,
                        )
                    },
                    icon = Icons.Rounded.Save,
                    modifier = Modifier.fillMaxWidth(),
                )
                current?.online == true && !studio.isNormal -> Text(
                    "Чтобы сохранить онлайн-трек с эффектом, сначала скачайте его кнопкой ↓ в плеере.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
        }
    }
}

@Composable
private fun StudioSlider(title: String, value: String, fraction: Float, onChange: (Float) -> Unit) {
    val c = Vesper.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, color = c.accentBright)
    }
    VesperSlider(value = fraction.coerceIn(0f, 1f), onValueChange = onChange, restingThumb = 12.dp)
}
