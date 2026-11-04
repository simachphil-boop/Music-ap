package app.vesper.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.prefs.SettingsRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

data class QueueEntry(
    val key: String,
    val trackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    val durationMs: Long,
)

data class PlayerUiState(
    val connected: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val stopAfterCurrent: Boolean = false,
) {
    val current: QueueEntry? get() = queue.getOrNull(currentIndex)
    val hasNext: Boolean get() = currentIndex in 0 until queue.lastIndex || (repeatMode != Player.REPEAT_MODE_OFF && queue.isNotEmpty())
    val hasPrevious: Boolean get() = queue.isNotEmpty()
}

data class ProgressState(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * UI-side bridge to [PlaybackService] through a Media3 [MediaController].
 * All calls must happen on the main thread (Compose callbacks do).
 */
class PlayerConnection(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ProgressState())
    val progress: StateFlow<ProgressState> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var shuffleOn = false
    private var originalOrder: List<Long> = emptyList()
    private var fading = false

    init {
        scope.launch {
            val s = settings.current()
            shuffleOn = s.shuffle
            originalOrder = settings.originalOrder()
            _state.update { it.copy(shuffle = shuffleOn) }
        }
        scope.launch {
            while (isActive) {
                val visible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (visible) updateProgress()
                delay(if (_state.value.isPlaying && visible) 250 else 1000)
            }
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            if (c == null) {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            rebuildQueue(c)
            refreshFlags(c)
            _state.update { it.copy(connected = true) }
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null && c.isConnected) {
            action(c)
        } else {
            if (c != null && !c.isConnected) {
                controller = null
                future = null
            }
            pending.addLast(action)
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val c = controller ?: return
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED)) {
                rebuildQueue(c)
            }
            refreshFlags(c)
            updateProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _state.value.stopAfterCurrent) {
                controller?.let {
                    it.pause()
                    it.seekTo(it.currentMediaItemIndex, 0)
                }
                setStopAfterCurrent(false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && _state.value.stopAfterCurrent) setStopAfterCurrent(false)
        }

        override fun onPlayerError(error: PlaybackException) {
            val title = controller?.currentMediaItem?.mediaMetadata?.title ?: "трек"
            _messages.tryEmit("Не удалось воспроизвести «$title»: файл недоступен или формат не поддерживается")
        }
    }

    private fun refreshFlags(c: MediaController) {
        _state.update {
            it.copy(
                currentIndex = if (c.mediaItemCount == 0) -1 else c.currentMediaItemIndex,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                repeatMode = c.repeatMode,
                shuffle = shuffleOn,
            )
        }
    }

    private fun rebuildQueue(c: MediaController) {
        val seen = HashMap<String, Int>()
        val entries = (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            val n = (seen[item.mediaId] ?: 0) + 1
            seen[item.mediaId] = n
            val md = item.mediaMetadata
            QueueEntry(
                key = "${item.mediaId}#$n",
                trackId = item.mediaId.toLongOrNull() ?: -1,
                title = md.title?.toString().orEmpty(),
                artist = md.artist?.toString().orEmpty(),
                album = md.albumTitle?.toString().orEmpty(),
                uri = item.trackUri().orEmpty(),
                durationMs = md.extras?.getLong(EXTRA_DURATION) ?: 0L,
            )
        }
        _state.update { it.copy(queue = entries) }
    }

    private fun updateProgress() {
        val c = controller ?: return
        val entryDuration = _state.value.current?.durationMs ?: 0L
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: entryDuration
        _progress.value = ProgressState(
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            bufferedMs = c.bufferedPosition.coerceAtLeast(0),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------------

    /**
     * Replaces the queue. [shuffle] = true shuffles (random first track unless [startIndex] given
     * explicitly with [keepStart]), false plays in order, null follows the current shuffle mode.
     */
    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean? = null, keepStart: Boolean = false) {
        if (tracks.isEmpty()) return
        val doShuffle = shuffle ?: shuffleOn
        val items = tracks.map { it.toMediaItem() }
        withController { c ->
            if (doShuffle && items.size > 1) {
                val start = if (shuffle == true && !keepStart) Random.nextInt(items.size) else startIndex.coerceIn(0, items.lastIndex)
                val ordered = listOf(items[start]) + items.filterIndexed { i, _ -> i != start }.shuffled()
                c.setMediaItems(ordered, 0, 0)
                setOriginalOrder(tracks.map { it.id })
                setShuffleFlag(true)
            } else {
                c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
                if (shuffle == false) setShuffleFlag(false)
                setOriginalOrder(tracks.map { it.id })
            }
            c.prepare()
            c.play()
        }
    }

    fun playNext(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) {
                playTracks(tracks, 0, shuffle = false)
                return@withController
            }
            c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
            if (shuffleOn) setOriginalOrder(originalOrder + tracks.map { it.id })
            _messages.tryEmit(if (tracks.size == 1) "«${tracks[0].title}» сыграет следующим" else "${tracks.size} треков сыграют следующими")
        }
    }

    fun addToQueue(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) {
                playTracks(tracks, 0, shuffle = false)
                return@withController
            }
            c.addMediaItems(tracks.map { it.toMediaItem() })
            if (shuffleOn) setOriginalOrder(originalOrder + tracks.map { it.id })
            _messages.tryEmit(if (tracks.size == 1) "Добавлено в очередь" else "В очередь добавлено: ${tracks.size}")
        }
    }

    fun togglePlayPause() = withController { c ->
        when {
            c.mediaItemCount == 0 -> Unit
            c.isPlaying -> c.pause()
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0)
                c.play()
            }
        }
    }

    fun play() = withController { c ->
        if (c.mediaItemCount == 0) return@withController
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun pause() = withController { it.pause() }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else if (it.repeatMode == Player.REPEAT_MODE_ALL) it.seekTo(0, 0) }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(positionMs: Long) = withController {
        it.seekTo(positionMs)
        _progress.update { p -> p.copy(positionMs = positionMs) }
    }

    fun skipToQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        if (from != to && from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() = withController { c ->
        val enable = !shuffleOn
        val count = c.mediaItemCount
        if (count > 1) {
            val cur = c.currentMediaItemIndex.coerceIn(0, count - 1)
            val items = (0 until count).map { c.getMediaItemAt(it) }
            if (enable) {
                setOriginalOrder(items.map { it.mediaId.toLongOrNull() ?: -1L })
                val others = items.filterIndexed { i, _ -> i != cur }.shuffled()
                if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
                if (cur > 0) c.removeMediaItems(0, cur)
                c.addMediaItems(1, others)
            } else {
                val current = items[cur]
                val pool = LinkedHashMap<String, ArrayDeque<MediaItem>>()
                items.forEachIndexed { i, item ->
                    if (i != cur) pool.getOrPut(item.mediaId) { ArrayDeque() }.addLast(item)
                }
                val ordered = ArrayList<MediaItem>(count)
                var curPos = -1
                for (id in originalOrder) {
                    val key = id.toString()
                    if (curPos < 0 && key == current.mediaId) {
                        curPos = ordered.size
                        ordered += current
                        continue
                    }
                    pool[key]?.removeFirstOrNull()?.let { ordered += it }
                }
                if (curPos < 0) {
                    curPos = ordered.size
                    ordered += current
                }
                pool.values.forEach { ordered.addAll(it) }
                if (cur + 1 < count) c.removeMediaItems(cur + 1, count)
                if (cur > 0) c.removeMediaItems(0, cur)
                if (curPos > 0) c.addMediaItems(0, ordered.subList(0, curPos).toList())
                if (curPos + 1 < ordered.size) c.addMediaItems(curPos + 1, ordered.subList(curPos + 1, ordered.size).toList())
            }
        }
        setShuffleFlag(enable)
    }

    fun setStopAfterCurrent(enabled: Boolean) {
        _state.update { it.copy(stopAfterCurrent = enabled) }
    }

    /** Gentle fade used by the sleep timer. */
    fun fadeOutAndPause() {
        val c = controller ?: return
        if (fading) return
        fading = true
        scope.launch {
            val start = c.volume
            val steps = 20
            for (i in 1..steps) {
                c.volume = start * (1f - i / steps.toFloat())
                delay(150)
            }
            c.pause()
            c.volume = start
            fading = false
        }
    }

    private fun setShuffleFlag(enabled: Boolean) {
        shuffleOn = enabled
        _state.update { it.copy(shuffle = enabled) }
        scope.launch { settings.setShuffle(enabled) }
    }

    private fun setOriginalOrder(ids: List<Long>) {
        originalOrder = ids
        scope.launch { settings.setOriginalOrder(ids) }
    }
}
