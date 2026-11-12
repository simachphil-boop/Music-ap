package app.vesper.player.video

import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.vesper.player.ui.components.ChoiceSheet
import app.vesper.player.ui.components.PlayPauseButton
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.VesperSlider
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AspectMode(val label: String) {
    FIT("Вписать"),
    FILL("Заполнить (обрезка)"),
    STRETCH("Растянуть"),
    R16_9("16:9"),
    R4_3("4:3"),
    R21_9("21:9"),
}

private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private enum class Hud { NONE, VOLUME, BRIGHTNESS, SEEK_BACK, SEEK_FORWARD }

@Composable
fun VideoPlayerScreen(
    player: ExoPlayer,
    activity: VideoPlayerActivity,
    initialSpeed: Float,
    onClose: () -> Unit,
) {
    val c = Vesper.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString().orEmpty()) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrev by remember { mutableStateOf(false) }
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }
    var favorite by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var locked by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(AspectMode.FIT) }
    var speed by remember { mutableFloatStateOf(initialSpeed) }
    var speedSheet by remember { mutableStateOf(false) }
    var aspectSheet by remember { mutableStateOf(false) }
    var qualitySheet by remember { mutableStateOf(false) }
    val online = activity.onlineUrl != null
    val opening = activity.onlineLoading
    val shownTitle = title.ifBlank { activity.onlineTitle }
    var scrub by remember { mutableStateOf<Float?>(null) }

    var hud by remember { mutableStateOf(Hud.NONE) }
    var hudValue by remember { mutableFloatStateOf(0f) }
    var hudTick by remember { mutableIntStateOf(0) }

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    fun touch() {
        lastInteraction = System.currentTimeMillis()
        controlsVisible = true
    }

    LaunchedEffect(initialSpeed) {
        speed = initialSpeed
        player.playbackParameters = PlaybackParameters(initialSpeed)
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                isPlaying = p.isPlaying
                buffering = p.playbackState == Player.STATE_BUFFERING
                // Playback recovered (fresh links after an error) — the old message no longer applies.
                if (p.playbackState == Player.STATE_READY && p.playerError == null) error = null
                title = p.mediaMetadata.title?.toString().orEmpty()
                hasNext = p.hasNextMediaItem()
                hasPrev = p.hasPreviousMediaItem()
                duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                    error = null
                    scope.launch { favorite = activity.isFavorite() }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    -> "Формат этого видео не поддерживается устройством"
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                    -> "Файл недоступен: возможно, он перемещён или удалён"
                    else -> "Не удалось воспроизвести видео"
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(Unit) {
        favorite = activity.isFavorite()
        while (true) {
            position = player.currentPosition
            if (duration <= 0) duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(if (isPlaying) 250 else 600)
        }
    }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, lastInteraction, isPlaying, scrub) {
        if (controlsVisible && isPlaying && scrub == null) {
            delay(3500)
            if (System.currentTimeMillis() - lastInteraction >= 3400) controlsVisible = false
        }
    }
    LaunchedEffect(hudTick) {
        if (hud != Hud.NONE) {
            delay(900)
            hud = Hud.NONE
        }
    }

    BackHandler(enabled = locked) { touch() }

    val pip = activity.inPip

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // --- Video surface ---------------------------------------------------------------------
        val ratioModifier = when (aspect) {
            AspectMode.R16_9 -> Modifier.aspectRatio(16f / 9f)
            AspectMode.R4_3 -> Modifier.aspectRatio(4f / 3f)
            AspectMode.R21_9 -> Modifier.aspectRatio(21f / 9f)
            else -> Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = when (aspect) {
                    AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
            modifier = Modifier.align(Alignment.Center).then(ratioModifier),
        )

        if (!pip) {

        // --- Gesture layer ---------------------------------------------------------------------
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) controlsVisible = false else touch()
                        },
                        onDoubleTap = { off ->
                            if (locked) return@detectTapGestures
                            val forward = off.x > size.width / 2
                            if (forward) player.seekForward() else player.seekBack()
                            hud = if (forward) Hud.SEEK_FORWARD else Hud.SEEK_BACK
                            hudTick++
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var leftSide = false
                    detectVerticalDragGestures(
                        onDragStart = { off ->
                            leftSide = off.x < size.width / 2
                            hudValue = if (leftSide) activity.currentBrightness()
                            else audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume.toFloat()
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            val delta = -amount / (size.height * 0.7f)
                            hudValue = (hudValue + delta).coerceIn(0f, 1f)
                            if (leftSide) {
                                activity.setBrightness(hudValue)
                                hud = Hud.BRIGHTNESS
                            } else {
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (hudValue * maxVolume).toInt(), 0)
                                hud = Hud.VOLUME
                            }
                            hudTick++
                        },
                    )
                },
        )

        // --- HUD -------------------------------------------------------------------------------
        AnimatedVisibility(
            visible = hud != Hud.NONE,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            HudBubble(hud, hudValue)
        }

        val openError = activity.onlineError
        if (opening && openError == null) {
            // Links are being fetched: say what is happening instead of a silent black screen.
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(color = c.accentBright, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("Открываю видео…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (activity.onlineTitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        activity.onlineTitle,
                        color = Color(0xB3FFFFFF),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else if (buffering && error == null && openError == null) {
            androidx.compose.material3.CircularProgressIndicator(
                color = c.accentBright,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(44.dp),
            )
        }

        if (openError != null) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(Vesper.shapes.large).background(Color(0xE6000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text("Не удалось открыть видео", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(openError, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Повторить",
                        color = c.accentBright,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(Vesper.shapes.chip).clickable { activity.retryOnline() }.padding(10.dp),
                    )
                    Text(
                        "Закрыть",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(Vesper.shapes.chip).clickable(onClick = onClose).padding(10.dp),
                    )
                }
            }
        }

        error?.takeIf { !opening && openError == null }?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(Vesper.shapes.large).background(Color(0xE6000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(10.dp))
                Text(msg, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                if (hasNext) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Следующее видео",
                        color = c.accentBright,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(Vesper.shapes.chip).clickable {
                            error = null
                            player.seekToNextMediaItem()
                            player.prepare()
                            player.play()
                        }.padding(10.dp),
                    )
                }
            }
        }

        // --- Lock badge ------------------------------------------------------------------------
        if (locked) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(16.dp),
            ) {
                PressableIcon(
                    Icons.Rounded.Lock, "Разблокировать",
                    onClick = {
                        locked = false
                        touch()
                    },
                    tint = Color.White,
                    modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(Color(0x66000000)),
                )
            }
        } else {

        // --- Controls --------------------------------------------------------------------------
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
                // Top bar
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onClose, tint = Color.White)
                    Text(
                        shownTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (!online) {
                        PressableIcon(
                            if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            "Избранное",
                            onClick = {
                                touch()
                                activity.toggleFavorite { favorite = it }
                            },
                            tint = if (favorite) c.accentBright else Color.White,
                        )
                    } else if (activity.onlineHeights.size > 1) {
                        // Online video: picture quality instead of the (file-only) favourite mark.
                        Text(
                            "${activity.onlineHeight}p",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(Vesper.shapes.chip)
                                .background(Color(0x33FFFFFF))
                                .clickable { touch(); qualitySheet = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    PressableIcon(
                        Icons.Rounded.Headphones,
                        if (activity.backgroundSound) "Звук в фоне включён" else "Звук в фоне выключен",
                        onClick = {
                            touch()
                            activity.toggleBackgroundSound()
                            android.widget.Toast.makeText(
                                context,
                                if (activity.backgroundSound) "Звук продолжит играть при выключенном экране" else "При выключенном экране видео остановится",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        tint = if (activity.backgroundSound) c.accentBright else Color.White,
                    )
                    PressableIcon(Icons.Rounded.Speed, "Скорость", onClick = { touch(); speedSheet = true }, tint = Color.White)
                    PressableIcon(Icons.Rounded.AspectRatio, "Соотношение сторон", onClick = { touch(); aspectSheet = true }, tint = Color.White)
                    PressableIcon(Icons.Rounded.PictureInPictureAlt, "Картинка в картинке", onClick = { activity.enterPip() }, tint = Color.White)
                }

                // Center transport
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PressableIcon(Icons.Rounded.SkipPrevious, "Предыдущее", onClick = { touch(); player.seekToPreviousMediaItem() }, tint = Color.White, enabled = hasPrev, size = 32.dp, touchSize = 52.dp)
                    PressableIcon(Icons.Rounded.Replay10, "Назад 10 секунд", onClick = { touch(); player.seekBack() }, tint = Color.White, size = 32.dp, touchSize = 52.dp)
                    PlayPauseButton(isPlaying, onClick = {
                        touch()
                        if (player.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            player.play()
                        }
                    }, size = 68.dp)
                    PressableIcon(Icons.Rounded.Forward10, "Вперёд 10 секунд", onClick = { touch(); player.seekForward() }, tint = Color.White, size = 32.dp, touchSize = 52.dp)
                    PressableIcon(Icons.Rounded.SkipNext, "Следующее", onClick = { touch(); player.seekToNextMediaItem() }, tint = Color.White, enabled = hasNext, size = 32.dp, touchSize = 52.dp)
                }

                // Bottom bar
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val shown = scrub ?: if (duration > 0) position.toFloat() / duration else 0f
                    VesperSlider(
                        value = shown,
                        onValueChange = {
                            touch()
                            scrub = it
                        },
                        onValueChangeFinished = {
                            scrub?.let { player.seekTo((it * duration).toLong()) }
                            scrub = null
                        },
                        restingThumb = 12.dp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatDuration(if (scrub != null) ((scrub ?: 0f) * duration).toLong() else position) + " / " + formatDuration(duration),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (speed != 1f) {
                            Spacer(Modifier.width(10.dp))
                            Text("${speed}×", color = c.accentBright, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        PressableIcon(Icons.Rounded.LockOpen, "Заблокировать экран", onClick = {
                            locked = true
                            touch()
                        }, tint = Color.White)
                        PressableIcon(
                            Icons.Rounded.ScreenRotation,
                            if (activity.rotationLocked) "Автоповорот" else "Повернуть экран",
                            onClick = {
                                touch()
                                activity.toggleOrientation()
                            },
                            tint = if (activity.rotationLocked) c.accentBright else Color.White,
                        )
                        PressableIcon(
                            if (activity.fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            "Полноэкранный режим",
                            onClick = {
                                touch()
                                activity.applyFullscreen(!activity.fullscreen)
                            },
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        }
        }
    }

    if (speedSheet) {
        ChoiceSheet(
            title = "Скорость воспроизведения",
            options = speeds.map { if (it == 1f) "Обычная" else "${it}×" },
            selected = speeds.indexOf(speed),
            onDismiss = { speedSheet = false },
            onSelect = {
                speed = speeds[it]
                player.playbackParameters = PlaybackParameters(speed)
                activity.saveSpeed(speed)
            },
        )
    }
    if (qualitySheet) {
        val heights = activity.onlineHeights
        ChoiceSheet(
            title = "Качество видео",
            options = heights.map { h -> if (h >= 1440) "${h}p · нужен быстрый интернет" else "${h}p" },
            selected = heights.indexOf(activity.onlineHeight),
            onDismiss = { qualitySheet = false },
            onSelect = { activity.setOnlineQuality(heights[it]) },
        )
    }
    if (aspectSheet) {
        ChoiceSheet(
            title = "Соотношение сторон",
            options = AspectMode.entries.map { it.label },
            selected = aspect.ordinal,
            onDismiss = { aspectSheet = false },
            onSelect = { aspect = AspectMode.entries[it] },
        )
    }
}

@Composable
private fun HudBubble(hud: Hud, value: Float) {
    val c = Vesper.colors
    Column(
        Modifier.clip(Vesper.shapes.large).background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (hud) {
            Hud.VOLUME, Hud.BRIGHTNESS -> {
                Icon(
                    if (hud == Hud.VOLUME) Icons.Rounded.VolumeUp else Icons.Rounded.BrightnessMedium,
                    null, tint = Color.White, modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(120.dp).height(4.dp).clip(Vesper.shapes.chip).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(value).background(c.accentBright))
                }
                Spacer(Modifier.height(6.dp))
                Text("${(value * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Hud.SEEK_BACK, Hud.SEEK_FORWARD -> {
                Icon(
                    if (hud == Hud.SEEK_FORWARD) Icons.Rounded.Forward10 else Icons.Rounded.Replay10,
                    null, tint = Color.White, modifier = Modifier.size(34.dp),
                )
                Text(if (hud == Hud.SEEK_FORWARD) "+10 с" else "−10 с", color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Hud.NONE -> Unit
        }
    }
}
