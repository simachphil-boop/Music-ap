package app.vesper.player.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vesper.player.ui.LocalBottomInset
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OrnamentDivider
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.VesperChip
import app.vesper.player.ui.components.VesperSlider
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val info by vm.audioEffects.info.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val eq = settings.equalizer
    val bottom = LocalBottomInset.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    OverlineText("Звук", color = c.ornament)
                    Text("Эквалайзер", style = MaterialTheme.typography.displaySmall, color = c.textPrimary)
                }
                VesperSwitch(eq.enabled) { vm.setEqualizerEnabled(it) }
            }
            OrnamentDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }

        val i = info
        if (i == null) {
            item {
                EmptyState(
                    icon = Icons.Rounded.GraphicEq,
                    title = "Эквалайзер недоступен",
                    message = "Начните воспроизведение музыки — эквалайзер подключится к аудиосессии плеера. Если сообщение не исчезает, устройство не поддерживает системные аудиоэффекты.",
                )
            }
            return@LazyColumn
        }

        item {
            OverlineText("Пресеты", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VesperChip("Свой", selected = eq.preset < 0, onClick = {
                    vm.setEqualizerBands(eq.bandLevels.ifEmpty { List(i.bandCount) { 0 } })
                })
                i.presets.forEachIndexed { index, name ->
                    VesperChip(name, selected = eq.preset == index, onClick = {
                        vm.applyEqualizerPreset(index)
                        if (!eq.enabled) vm.setEqualizerEnabled(true)
                    })
                }
            }
        }

        item {
            val stored = if (eq.bandLevels.size == i.bandCount) eq.bandLevels else List(i.bandCount) { 0 }
            var levels by remember(i.bandCount) { mutableStateOf(stored) }
            var dirty by remember { mutableStateOf(0) }
            LaunchedEffect(stored) { if (dirty == 0) levels = stored }
            LaunchedEffect(dirty) {
                if (dirty == 0) return@LaunchedEffect
                delay(120)
                vm.setEqualizerBands(levels)
                if (!eq.enabled) vm.setEqualizerEnabled(true)
                dirty = 0
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                levels.forEachIndexed { band, level ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "%+.0f".format(level / 100f),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (eq.enabled) c.accentBright else c.textMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                        BandSlider(
                            value = (level - i.minLevel).toFloat() / (i.maxLevel - i.minLevel).coerceAtLeast(1),
                            enabled = eq.enabled,
                            onChange = { f ->
                                val newLevel = (i.minLevel + f * (i.maxLevel - i.minLevel)).toInt()
                                levels = levels.toMutableList().also { it[band] = newLevel }
                                dirty++
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        val hz = i.centerFrequenciesHz.getOrElse(band) { 0 }
                        Text(
                            if (hz >= 1000) "${hz / 1000}к" else "$hz",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Text(
                "дБ · Гц",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        if (i.bassBoostSupported) {
            item {
                EffectSlider("Усиление басов", eq.bassBoost) { vm.setBassBoost(it) }
            }
        }
        if (i.virtualizerSupported) {
            item {
                EffectSlider("Объёмный звук", eq.virtualizer) { vm.setVirtualizer(it) }
            }
        }
        item {
            Text(
                "Настройки применяются ко всей музыке и сохраняются после перезапуска.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun EffectSlider(title: String, value: Int, onChange: (Int) -> Unit) {
    val c = Vesper.colors
    var local by remember(value) { mutableStateOf(value / 1000f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            Text("${(local * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.accentBright)
        }
        VesperSlider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange((local * 1000).toInt()) },
            restingThumb = 12.dp,
        )
    }
}

@Composable
private fun BandSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val c = Vesper.colors
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .width(36.dp)
            .height(200.dp)
            .pointerInput(Unit) {
                detectTapGestures { off -> change((1f - off.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures { ch, _ ->
                    ch.consume()
                    change((1f - ch.position.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        val w = 4.dp.toPx()
        val x = size.width / 2
        val v = value.coerceIn(0f, 1f)
        val midY = size.height / 2
        drawRoundRect(c.progressTrack, Offset(x - w / 2, 0f), Size(w, size.height), CornerRadius(w / 2))
        drawLine(c.outline, Offset(x - 10.dp.toPx(), midY), Offset(x + 10.dp.toPx(), midY), 1.dp.toPx())
        val y = size.height * (1f - v)
        val top = minOf(y, midY)
        val h = kotlin.math.abs(midY - y)
        if (h > 0f) {
            drawRoundRect(
                if (enabled) Brush.verticalGradient(listOf(c.progressEnd, c.progressStart)) else Brush.verticalGradient(listOf(c.textMuted, c.textMuted)),
                Offset(x - w / 2, top), Size(w, h), CornerRadius(w / 2),
            )
        }
        drawCircle(if (enabled) c.accentBright else c.textMuted, radius = 9.dp.toPx(), center = Offset(x, y))
        drawCircle(c.background, radius = 4.dp.toPx(), center = Offset(x, y))
    }
}
