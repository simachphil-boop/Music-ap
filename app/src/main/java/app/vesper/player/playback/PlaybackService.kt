package app.vesper.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.vesper.player.MainActivity
import app.vesper.player.appContainer
import app.vesper.player.data.media.ArtworkLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Background music playback. Owns the ExoPlayer + MediaSession, which gives us the
 * media notification, lock-screen controls, Bluetooth/headset buttons and audio focus.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { appContainer }

    private var queueRestored = false
    private var saveQueueJob: Job? = null
    private var lastMarkedId: String? = null
    private var consecutiveErrors = 0
    private var resumeOnConnect = false
    private var deviceCallback: AudioDeviceCallback? = null

    override fun onCreate() {
        super.onCreate()
        // Online tracks use floyastream:// URIs that are resolved to real stream URLs at load time.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(this, http)
        val appContext = applicationContext
        val resolving = ResolvingDataSource.Factory(upstream) { spec -> StreamResolver.resolveSpec(appContext, spec) }
        player = ExoPlayer.Builder(this, StudioRenderersFactory(this, container.studio.processor))
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)

        val openPlayer = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PLAYER
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openPlayer,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .setBitmapLoader(ArtworkBitmapLoader(this, DataSourceBitmapLoader(this)))
            .build()

        container.audioEffects.attach(player.audioSessionId)
        observeSettings()
        restoreQueue()
        registerDeviceCallback()
        startPositionSaver()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            savePositionNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePositionNow()
        deviceCallback?.let { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(it) }
        container.audioEffects.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------

    private fun observeSettings() {
        scope.launch {
            val initial = container.settings.current()
            player.repeatMode = initial.repeatMode
            container.settings.settings.collect { s ->
                player.setHandleAudioBecomingNoisy(s.pauseOnDisconnect)
                resumeOnConnect = s.resumeOnConnect
            }
        }
        scope.launch {
            container.studio.state.collect { s ->
                player.playbackParameters = androidx.media3.common.PlaybackParameters(s.speed, s.pitch)
            }
        }
        scope.launch {
            container.settings.settings.map { it.equalizer }.distinctUntilChanged().collect {
                container.audioEffects.apply(it)
            }
        }
    }

    private fun restoreQueue() {
        scope.launch {
            try {
                if (player.mediaItemCount == 0) {
                    val tracks = container.library.loadQueue()
                    if (tracks.isNotEmpty() && player.mediaItemCount == 0) {
                        val (index, position) = container.settings.queuePosition()
                        player.setMediaItems(tracks.map { it.toMediaItem() }, index.coerceIn(0, tracks.lastIndex), position)
                        player.prepare()
                    }
                }
            } finally {
                queueRestored = true
            }
        }
    }

    private fun scheduleQueueSave() {
        if (!queueRestored) return
        saveQueueJob?.cancel()
        saveQueueJob = scope.launch {
            delay(800)
            val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
            container.appScope.launch { container.library.saveQueue(ids) }
            savePositionNow()
        }
    }

    private fun savePositionNow() {
        if (!queueRestored || !::player.isInitialized) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        container.appScope.launch { container.settings.saveQueuePosition(index, position) }
    }

    private fun startPositionSaver() {
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) savePositionNow()
            }
        }
    }

    private fun markPlayedIfNeeded() {
        val item = player.currentMediaItem ?: return
        if (item.mediaId == lastMarkedId) return
        lastMarkedId = item.mediaId
        val id = item.mediaId.toLongOrNull() ?: return
        container.appScope.launch { container.library.markPlayed(id) }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) scheduleQueueSave()
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY,
                )
            ) {
                savePositionNow()
            }
            if (player.isPlaying) {
                consecutiveErrors = 0
                markPlayedIfNeeded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) lastMarkedId = null
            prefetchNextOnline()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val id = oldPosition.mediaItem?.mediaId?.toLongOrNull() ?: return
            when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION ->
                    container.appScope.launch { container.library.recordCompletion(id) }
                Player.DISCONTINUITY_REASON_SEEK ->
                    if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex && oldPosition.positionMs in 0 until 30_000) {
                        container.appScope.launch { container.library.recordSkip(id) }
                    }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            container.audioEffects.attach(audioSessionId)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            container.appScope.launch { container.settings.setRepeatMode(repeatMode) }
        }

        override fun onPlayerError(error: PlaybackException) {
            consecutiveErrors++
            if (consecutiveErrors <= 5 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private fun prefetchNextOnline() {
        val next = player.currentMediaItemIndex + 1
        if (next < player.mediaItemCount) {
            player.getMediaItemAt(next).pageUrl()?.let { StreamResolver.prefetch(applicationContext, it) }
        }
    }

    private fun registerDeviceCallback() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            private var initialReport = true

            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (initialReport) {
                    initialReport = false
                    return
                }
                if (!resumeOnConnect) return
                val headset = addedDevices.orEmpty().any { it.isSink && it.type in headsetTypes() }
                if (headset && player.mediaItemCount > 0 && !player.isPlaying) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        deviceCallback = callback
    }

    private fun headsetTypes(): Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withResolvedUri() }.toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val tracks = container.library.loadQueue()
            val (index, position) = container.settings.queuePosition()
            MediaSession.MediaItemsWithStartPosition(
                tracks.map { it.toMediaItem() },
                if (tracks.isEmpty()) 0 else index.coerceIn(0, tracks.lastIndex),
                position,
            )
        }
    }
}

/**
 * Resolves our `vesperart://` artwork URIs (embedded cover art) for the notification and
 * lock screen; everything else is delegated to Media3's default loader.
 */
private class ArtworkBitmapLoader(
    context: Context,
    private val fallback: BitmapLoader,
) : BitmapLoader by fallback {
    private val appContext = context.applicationContext
    private val executor: ListeningExecutorService =
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != ART_SCHEME) return fallback.loadBitmap(uri)
        val trackUri = uri.getQueryParameter("u")
            ?: return Futures.immediateFailedFuture(IllegalArgumentException("No track uri"))
        return executor.submit(
            Callable {
                ArtworkLoader.loadBlocking(appContext, trackUri, 512)
                    ?: throw IllegalStateException("No embedded artwork")
            },
        )
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val art = metadata.artworkUri
        return if (art != null && art.scheme == ART_SCHEME) loadBitmap(art) else fallback.loadBitmapFromMetadata(metadata)
    }
}
