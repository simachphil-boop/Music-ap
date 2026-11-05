package app.vesper.player.ui.player

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.media.ArtworkLoader
import app.vesper.player.ui.GroupType
import app.vesper.player.ui.LocalUi
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.Routes
import app.vesper.player.ui.components.ArtSize
import app.vesper.player.ui.components.Artwork
import app.vesper.player.ui.components.FavoriteButton
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PlayPauseButton
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.components.SeekBar
import app.vesper.player.ui.components.VesperSlider
import app.vesper.player.ui.components.formatDuration
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(onCollapse: () -> Unit) {
    val vm = LocalViewModel.current
    val ui = LocalUi.current
    val c = Vesper.colors
    val theme = Vesper.theme
    val context = LocalContext.current
    val state by vm.player.state.collectAsStateWithLifecycle()
    val progress by vm.player.progress.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sleep by vm.sleepTimer.state.collectAsStateWithLifecycle()
    val current = state.current ?: return
    val track = library.trackById[current.trackId]

    // --- Artwork-derived tint -------------------------------------------------------------------
    val artBitmap by produceState<Bitmap?>(null, current.uri) {
        value = ArtworkLoader.load(context, current.uri, false, ArtSize.Large.px)
    }
    val artTint by produceState<Color?>(null, artBitmap, settings.artworkTint) {
        val bmp = artBitmap
        value = if (bmp == null || !settings.artworkTint) null else withContext(Dispatchers.Default) {
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            val argb = if (c.isDark) {
                p.getDarkVibrantColor(p.getDarkMutedColor(p.getDominantColor(0)))
            } else {
                p.getLightMutedColor(p.getLightVibrantColor(p.getDominantColor(0)))
            }
            if (argb == 0) null else Color(argb)
        }
    }
    val topColor by animateColorAsState(
        artTint?.let { lerp(c.background, it, if (c.isDark) 0.62f else 0.45f) } ?: c.backgroundAlt,
        tween(700),
        label = "npTop",
    )

    // --- Drag-down to dismiss -------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissPx = with(density) { 160.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.value.roundToInt().coerceAtLeast(0)) }
            .graphicsLayer {
                val f = (dragOffset.value / (dismissPx * 3)).coerceIn(0f, 0.12f)
                scaleX = 1f - f * 0.4f
                scaleY = 1f - f * 0.4f
            }
            .background(Brush.verticalGradient(0f to topColor, 0.55f to c.background, 1f to c.background))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dragOffset.value > dismissPx) {
                                onCollapse()
                                dragOffset.snapTo(0f)
                            } else {
                                dragOffset.animateTo(0f, spring(dampingRatio = 0.75f))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { dragOffset.animateTo(0f) } },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        scope.launch { dragOffset.snapTo((dragOffset.value + amount).coerceAtLeast(0f)) }
                    },
                )
            },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PressableIcon(Icons.Rounded.KeyboardArrowDown, "Свернуть", onClick = onCollapse, size = 30.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val wave by vm.wave.state.collectAsStateWithLifecycle()
                    OverlineText(
                        if (wave.active) "Волна · ${wave.vibe?.title ?: "Моя волна"}" else "Сейчас играет",
                        color = c.ornament,
                    )
                    Text(
                        "${state.currentIndex + 1} из ${state.queue.size}" + if (current.album.isNotBlank()) "  ·  ${current.album}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PressableIcon(
                    Icons.Rounded.MoreVert, "Действия",
                    onClick = { track?.let { ui.showTrackOptions(it) } },
                    enabled = track != null,
                )
            }

            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight - 8.dp).coerceAtLeast(120.dp)
                ArtworkStage(
                    entryKey = current.key,
                    uri = current.uri,
                    title = current.title,
                    index = state.currentIndex,
                    modifier = Modifier.size(side),
                    onSwipeNext = { vm.player.next() },
                    onSwipePrevious = { vm.player.previous() },
                    ornament = theme.ornament,
                )
            }

            // Title + favourite
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.key },
                    transitionSpec = {
                        (fadeIn(tween(320)) + slideInHorizontally(tween(320)) { it / 8 }) togetherWith fadeOut(tween(180))
                    },
                    modifier = Modifier.weight(1f),
                    label = "npTitle",
                ) { entry ->
                    Column {
                        Text(
                            track?.title ?: entry.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = c.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                        )
                        Text(
                            track?.artist ?: entry.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = !entry.online) {
                                ui.playerExpanded = false
                                ui.openGroup(GroupType.ARTIST, track?.artist ?: entry.artist)
                            },
                        )
                    }
                }
                if (state.queueOwner == app.vesper.player.data.wave.WaveEngine.OWNER) {
                    PressableIcon(
                        androidx.compose.material.icons.Icons.Rounded.ThumbDown,
                        "Не нравится",
                        onClick = { vm.wave.dislike(current) },
                        tint = c.textMuted,
                        size = 22.dp,
                    )
                }
                if (track != null) {
                    FavoriteButton(track.favorite, onToggle = { vm.toggleFavorite(track) }, size = 28.dp)
                } else if (current.online) {
                    var liked by remember(current.key) { mutableStateOf(vm.wave.isLikedArtist(current.artist)) }
                    FavoriteButton(liked, onToggle = {
                        if (!liked) vm.wave.likeOnline(current)
                        liked = true
                    }, size = 28.dp)
                    PressableIcon(
                        androidx.compose.material.icons.Icons.Rounded.Download,
                        "Скачать MP3",
                        onClick = { vm.downloadOnline(current) },
                        tint = c.accentBright,
                        size = 24.dp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SeekBar(progress.positionMs, progress.durationMs, onSeek = { vm.player.seekTo(it) })
            Spacer(Modifier.height(6.dp))

            // Transport
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleIcon(
                    icon = Icons.Rounded.Shuffle,
                    description = "Перемешать",
                    active = state.shuffle,
                    onClick = { vm.player.toggleShuffle() },
                )
                PressableIcon(Icons.Rounded.SkipPrevious, "Предыдущий", onClick = { vm.player.previous() }, size = 38.dp, touchSize = 56.dp)
                PlayPauseButton(state.isPlaying, onClick = { vm.player.togglePlayPause() }, size = 76.dp)
                PressableIcon(
                    Icons.Rounded.SkipNext, "Следующий",
                    onClick = { vm.player.next() },
                    size = 38.dp, touchSize = 56.dp,
                    enabled = state.hasNext,
                )
                ToggleIcon(
                    icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    description = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Повтор трека"
                        Player.REPEAT_MODE_ALL -> "Повтор очереди"
                        else -> "Повтор выключен"
                    },
                    active = state.repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = { vm.player.cycleRepeat() },
                )
            }

            Spacer(Modifier.height(10.dp))
            VolumeRow()

            // Secondary actions
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PressableIcon(Icons.Rounded.QueueMusic, "Очередь", onClick = { ui.queueVisible = true }, tint = c.textSecondary)
                SleepButton(active = sleep.active, endOfTrack = sleep.endOfTrack, endsAt = sleep.endsAtMillis, onClick = { ui.sleepSheetVisible = true })
                PressableIcon(
                    Icons.Rounded.Equalizer, "Эквалайзер",
                    onClick = {
                        ui.playerExpanded = false
                        ui.open(Routes.EQUALIZER)
                    },
                    tint = if (settings.equalizer.enabled) c.accentBright else c.textSecondary,
                )
                PressableIcon(
                    Icons.Rounded.PlaylistAdd, "В плейлист",
                    onClick = { ui.showAddToPlaylist(listOf(current.trackId), PlaylistKind.MUSIC) },
                    tint = c.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ArtworkStage(
    entryKey: String,
    uri: String,
    title: String,
    index: Int,
    modifier: Modifier,
    onSwipeNext: () -> Unit,
    onSwipePrevious: () -> Unit,
    ornament: Ornament,
) {
    val c = Vesper.colors
    val shape = Vesper.shapes.artwork
    var swipe by remember { mutableFloatStateOf(0f) }
    val swipeAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var lastIndex by remember { mutableStateOf(index) }
    val forward = index >= lastIndex
    LaunchedEffect(index) { lastIndex = index }

    val frame = when (ornament) {
        Ornament.GOTHIC -> Modifier
            .border(1.dp, c.ornament.copy(alpha = 0.55f), shape)
            .padding(7.dp)
            .border(1.dp, c.ornament.copy(alpha = 0.25f), shape)
            .padding(5.dp)
        Ornament.NEON -> Modifier.shadow(28.dp, shape, ambientColor = c.accent, spotColor = c.accentBright)
        Ornament.MINIMAL -> Modifier.shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.25f))
        Ornament.NONE -> Modifier
        else -> Modifier.shadow(22.dp, shape)
    }

    Box(
        modifier
            .graphicsLayer {
                translationX = swipeAnim.value
                rotationZ = swipeAnim.value / 90f
                alpha = 1f - (kotlin.math.abs(swipeAnim.value) / (size.width * 1.4f)).coerceIn(0f, 0.6f)
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { swipe = 0f },
                    onDragEnd = {
                        val threshold = size.width * 0.22f
                        when {
                            swipe < -threshold -> onSwipeNext()
                            swipe > threshold -> onSwipePrevious()
                        }
                        scope.launch { swipeAnim.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f)) }
                    },
                    onDragCancel = { scope.launch { swipeAnim.animateTo(0f) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipe += amount
                        scope.launch { swipeAnim.snapTo(swipe * 0.6f) }
                    },
                )
            },
    ) {
        AnimatedContent(
            targetState = Triple(entryKey, uri, title),
            contentKey = { it.first },
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (fadeIn(tween(360)) + scaleIn(tween(420), initialScale = 0.9f) +
                    slideInHorizontally(tween(380)) { it / 5 * dir }) togetherWith
                    (fadeOut(tween(240)) + scaleOut(tween(300), targetScale = 0.94f))
            },
            label = "npArt",
        ) { (_, u, t) ->
            Box(Modifier.fillMaxSize().then(frame)) {
                Artwork(u, t, Modifier.fillMaxSize().aspectRatio(1f), size = ArtSize.Large, shape = shape)
            }
        }
    }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val tint by animateColorAsState(if (active) c.accentBright else c.textMuted, tween(220), label = "toggle")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PressableIcon(icon, description, onClick = onClick, tint = tint, size = 24.dp)
        Box(
            Modifier
                .offset(y = (-6).dp)
                .size(4.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (active) c.accentBright else Color.Transparent),
        )
    }
}

@Composable
private fun SleepButton(active: Boolean, endOfTrack: Boolean, endsAt: Long, onClick: () -> Unit) {
    val c = Vesper.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active, endsAt) {
        while (active && endsAt > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Row(
        Modifier.clip(Vesper.shapes.chip).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.NightsStay, "Таймер сна", tint = if (active) c.accentBright else c.textSecondary, modifier = Modifier.size(22.dp))
        if (active) {
            Spacer(Modifier.width(6.dp))
            Text(
                if (endOfTrack) "трек" else formatDuration((endsAt - now).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelMedium,
                color = c.accentBright,
            )
        }
    }
}

@Composable
private fun VolumeRow() {
    val c = Vesper.colors
    val (volume, setVolume) = rememberSystemVolume()
    Row(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeDown, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        VesperSlider(
            value = volume,
            onValueChange = setVolume,
            modifier = Modifier.weight(1f),
            trackHeight = 2.dp,
            activeColor = c.textSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.VolumeUp, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
    }
}

/** System music-stream volume, kept in sync with hardware volume keys. */
@Composable
fun rememberSystemVolume(): Pair<Float, (Float) -> Unit> {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volume = am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val setter: (Float) -> Unit = { f ->
        volume = f
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, (f * max).roundToInt(), 0) }
    }
    return volume to setter
}
