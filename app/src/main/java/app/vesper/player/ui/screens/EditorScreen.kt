package app.vesper.player.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.vesper.player.data.edit.CropAspect
import app.vesper.player.data.edit.EditSpec
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.EmptyState
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PlayPauseButton
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.PrimaryButton
import app.vesper.player.ui.components.SecondaryButton
import app.vesper.player.ui.components.VesperChip
import app.vesper.player.ui.components.VesperSlider
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay

/** Trim (audio & video) and crop (video) a library item into a new file. */
@Composable
fun EditorScreen(audio: Boolean, id: Long, onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    val lib by vm.library.collectAsStateWithLifecycle()
    val vids by vm.videos.collectAsStateWithLifecycle()
    val job by vm.editor.job.collectAsStateWithLifecycle()

    val track = if (audio) lib.trackById[id] else null
    val video = if (!audio) vids.byId[id] else null
    val uri = track?.uri ?: video?.uri
    val title = track?.title ?: video?.title ?: ""
    val knownDuration = track?.durationMs ?: video?.durationMs ?: 0L

    if (uri == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack, modifier = Modifier.padding(8.dp))
            if (lib.loaded && vids.loaded) EmptyState(Icons.Rounded.ErrorOutline, "Файл не найден", "Похоже, его больше нет в библиотеке.")
        }
        return
    }

    val player = remember(uri) { ExoPlayer.Builder(context).build() }
    var duration by remember { mutableLongStateOf(knownDuration) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }
    var range by remember { mutableStateOf(0f..1f) }
    var crop by remember { mutableStateOf<CropAspect?>(null) }
    var offset by remember { mutableFloatStateOf(0.5f) }
    var ringtoneChooser by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        vm.player.pause()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.duration > 0) duration = player.duration
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val startMs = (range.start * duration).toLong()
    val endMs = (range.endInclusive * duration).toLong()

    // Position ticker; loops the preview inside the selected fragment.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            val s = (range.start * duration).toLong()
            val e = (range.endInclusive * duration).toLong()
            if (player.isPlaying && duration > 0 && position >= e) {
                player.seekTo(s)
            }
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            Column(Modifier.weight(1f)) {
                OverlineText(if (audio) "Обрезка" else "Обрезка и кадрирование", color = c.ornament)
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // --- Preview ---------------------------------------------------------------------
            if (audio) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Artwork(uri, title, Modifier.size(200.dp), size = ArtSize.Large)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.aspectRatio(videoRatio, matchHeightConstraintsFirst = videoRatio < 1f).clip(Vesper.shapes.small).background(Color.Black)) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    this.player = player
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        val aspect = crop
                        if (aspect != null) {
                            CropOverlay(
                                videoRatio = videoRatio,
                                cropRatio = aspect.ratio,
                                offset = offset,
                                onOffset = { offset = it },
                                accent = c.accentBright,
                            )
                        }
                    }
                }
            }

            // --- Transport -------------------------------------------------------------------
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayPauseButton(
                    isPlaying = playing,
                    onClick = {
                        if (player.isPlaying) player.pause() else {
                            if (position < startMs || position >= endMs) player.seekTo(startMs)
                            player.play()
                        }
                    },
                    size = 52.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    VesperSlider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { player.seekTo((it * duration).toLong()) },
                        restingThumb = 8.dp,
                    )
                    Text(
                        "${formatDuration(position)} / ${formatDuration(duration)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textMuted,
                    )
                }
            }

            // --- Trim ------------------------------------------------------------------------
            Spacer(Modifier.height(18.dp))
            OverlineText("Фрагмент")
            RangeSlider(
                value = range,
                onValueChange = { r ->
                    val minGap = if (duration > 0) 1000f / duration else 0.01f
                    range = if (r.endInclusive - r.start < minGap) range else r
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = c.accentBright,
                    activeTrackColor = c.accent,
                    inactiveTrackColor = c.progressTrack,
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text("с ${formatDuration(startMs)}", style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
                Spacer(Modifier.weight(1f))
                Text("длина ${formatDuration(endMs - startMs)}", style = MaterialTheme.typography.labelMedium, color = c.accentBright)
                Spacer(Modifier.weight(1f))
                Text("до ${formatDuration(endMs)}", style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VesperChip("Начало здесь", false, onClick = {
                    if (duration > 0) range = (position.toFloat() / duration).coerceAtMost(range.endInclusive - 0.005f)..range.endInclusive
                })
                VesperChip("Конец здесь", false, onClick = {
                    if (duration > 0) range = range.start..(position.toFloat() / duration).coerceAtLeast(range.start + 0.005f)
                })
                VesperChip("Сбросить", false, onClick = { range = 0f..1f })
            }

            // --- Crop (video) ----------------------------------------------------------------
            if (!audio) {
                Spacer(Modifier.height(20.dp))
                OverlineText("Кадрирование")
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VesperChip("Без кадрирования", crop == null, onClick = { crop = null })
                    CropAspect.entries.forEach { a ->
                        VesperChip(a.label, crop == a, onClick = {
                            crop = a
                            offset = 0.5f
                        })
                    }
                }
                if (crop != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Перетащите рамку на видео или сдвиньте ползунок, чтобы выбрать, что останется в кадре.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                    VesperSlider(value = offset, onValueChange = { offset = it }, restingThumb = 12.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (audio) "Сохранится новый MP3 в «Музыка/Floya». Оригинал не изменится."
                else "Сохранится новое видео в «Фильмы/Floya». Оригинал не изменится." +
                    if (crop == null) " Без кадрирования обрезка идёт по ближайшему ключевому кадру — быстро и без потери качества." else "",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
            Spacer(Modifier.height(16.dp))
        }

        // --- Save / progress -----------------------------------------------------------------
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            val j = job
            when {
                j != null && j.running -> {
                    Text("${j.stage} ${(j.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { j.progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = c.accentBright,
                        trackColor = c.progressTrack,
                    )
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Отменить", onClick = { vm.editor.cancel() }, modifier = Modifier.fillMaxWidth())
                }
                j != null && j.error != null -> {
                    Text(j.error, style = MaterialTheme.typography.bodyMedium, color = c.danger)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Понятно", onClick = { vm.editor.dismiss() }, modifier = Modifier.fillMaxWidth())
                }
                j != null && j.resultId != null -> {
                    LaunchedEffect(j.resultId) {
                        vm.message("Сохранено: ${j.title}")
                        vm.editor.dismiss()
                        onBack()
                    }
                }
                else -> {
                    val noop = range.start <= 0f && range.endInclusive >= 1f && crop == null
                    PrimaryButton(
                        if (noop) "Выберите фрагмент или кадр" else "Сохранить",
                        onClick = {
                            if (noop) return@PrimaryButton
                            player.pause()
                            vm.editor.start(
                                sourceUri = uri,
                                audio = audio,
                                title = title,
                                durationMs = duration,
                                spec = EditSpec(
                                    startMs = if (range.start > 0f) startMs else 0L,
                                    endMs = if (range.endInclusive < 1f) endMs else 0L,
                                    crop = crop,
                                    offset = offset,
                                ),
                            )
                        },
                        icon = Icons.Rounded.ContentCut,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (audio) {
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton(
                            "Сделать рингтоном",
                            onClick = {
                                player.pause()
                                ringtoneChooser = true
                            },
                            icon = Icons.Rounded.NotificationsActive,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Совет: для звонка отметьте 20–40 секунд припева.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (ringtoneChooser) {
        app.vesper.player.ui.components.ChoiceSheet(
            title = "Поставить на…",
            options = app.vesper.player.data.edit.RingtoneKind.entries.map { it.title },
            selected = -1,
            onDismiss = { ringtoneChooser = false },
            onSelect = { idx ->
                val kind = app.vesper.player.data.edit.RingtoneKind.entries[idx]
                if (!app.vesper.player.data.edit.RingtonePublisher.canWriteSettings(context)) {
                    vm.message("Разрешите Floya Music менять системные настройки и нажмите ещё раз")
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                .setData(Uri.parse("package:" + context.packageName))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                } else {
                    vm.editor.startRingtone(
                        sourceUri = uri,
                        title = title,
                        durationMs = duration,
                        spec = EditSpec(
                            startMs = if (range.start > 0f) startMs else 0L,
                            endMs = if (range.endInclusive < 1f) endMs else if (duration > 40_000) startMs + 40_000 else 0L,
                        ),
                        kind = kind,
                    )
                }
            },
        )
    }
}

/** Dims everything outside the crop window; drag to move the window. */
@Composable
private fun CropOverlay(videoRatio: Float, cropRatio: Float, offset: Float, onOffset: (Float) -> Unit, accent: Color) {
    val horizontalFree = videoRatio > cropRatio
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(horizontalFree, videoRatio, cropRatio) {
                var current = offset
                detectDragGestures(onDragStart = { current = offset }) { change, amount ->
                    change.consume()
                    val free = if (horizontalFree) size.width * (1f - cropRatio / videoRatio) else size.height * (1f - videoRatio / cropRatio)
                    if (free > 1f) {
                        current = (current + (if (horizontalFree) amount.x else amount.y) / free).coerceIn(0f, 1f)
                        onOffset(current)
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val cw = if (horizontalFree) w * cropRatio / videoRatio else w
        val ch = if (horizontalFree) h else h * videoRatio / cropRatio
        val x = (w - cw) * offset
        val y = (h - ch) * offset
        val dim = Color(0xAA000000)
        drawRect(dim, Offset.Zero, Size(w, y))
        drawRect(dim, Offset(0f, y + ch), Size(w, h - y - ch))
        drawRect(dim, Offset(0f, y), Size(x, ch))
        drawRect(dim, Offset(x + cw, y), Size(w - x - cw, ch))
        drawRect(accent, Offset(x, y), Size(cw, ch), style = Stroke(width = 2.dp.toPx()))
        // Rule-of-thirds guides
        for (i in 1..2) {
            drawLine(Color(0x55FFFFFF), Offset(x + cw * i / 3f, y), Offset(x + cw * i / 3f, y + ch), 1f)
            drawLine(Color(0x55FFFFFF), Offset(x, y + ch * i / 3f), Offset(x + cw, y + ch * i / 3f), 1f)
        }
    }
}
