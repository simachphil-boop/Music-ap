package app.vesper.player.video

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.vesper.player.appContainer
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.data.media.ProbeResult
import app.vesper.player.ui.theme.ThemeRegistry
import app.vesper.player.ui.theme.VesperThemeProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dedicated video player. Uses its own ExoPlayer instance; audio focus makes background
 * music pause automatically while a video plays.
 */
class VideoPlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"

        fun intent(context: Context, ids: LongArray, index: Int): Intent =
            Intent(context, VideoPlayerActivity::class.java)
                .putExtra(EXTRA_IDS, ids)
                .putExtra(EXTRA_INDEX, index)
    }

    private lateinit var player: ExoPlayer
    private val container by lazy { appContainer }
    private var videos: List<VideoEntity> = emptyList()
    private var saveJob: Job? = null

    var inPip by mutableStateOf(false)
        private set
    var fullscreen by mutableStateOf(true)
        private set
    var rotationLocked by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    saveCurrentPosition()
                }
                updatePipParams()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                restorePositionForCurrent()
                markWatched()
            }
        })

        applyFullscreen(true)
        loadPlaylist()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val theme = ThemeRegistry.byId(settings?.themeId)
            VesperThemeProvider(theme, applySystemBars = false) {
                VideoPlayerScreen(
                    player = player,
                    activity = this,
                    initialSpeed = settings?.videoSpeed ?: 1f,
                    onClose = { finish() },
                )
            }
        }
        startPeriodicSave()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveCurrentPosition()
        setIntent(intent)
        loadPlaylist()
    }

    private fun loadPlaylist() {
        val ids = intent.getLongArrayExtra(EXTRA_IDS)
        val start = intent.getIntExtra(EXTRA_INDEX, 0)
        val data = intent.data
        lifecycleScope.launch {
            videos = when {
                ids != null -> container.library.videosByIds(ids.toList())
                data != null -> when (val r = container.library.importSingle(data)) {
                    is ProbeResult.Video -> listOf(r.video)
                    else -> emptyList()
                }
                else -> emptyList()
            }
            if (videos.isEmpty()) {
                if (data != null) {
                    // Unknown file — still try to play it directly.
                    player.setMediaItem(MediaItem.fromUri(data))
                    player.prepare()
                    player.play()
                } else {
                    finish()
                }
                return@launch
            }
            val items = videos.map { v ->
                MediaItem.Builder()
                    .setMediaId(v.id.toString())
                    .setUri(Uri.parse(v.uri))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
                    .build()
            }
            val index = start.coerceIn(0, items.lastIndex)
            val v = videos[index]
            val startPos = resumePositionFor(v)
            player.setMediaItems(items, index, startPos)
            player.prepare()
            player.play()
            markWatched()
        }
    }

    private fun resumePositionFor(v: VideoEntity): Long =
        if (v.lastPositionMs > 5_000 && v.lastPositionMs < v.durationMs - 5_000) v.lastPositionMs else 0L

    private fun restorePositionForCurrent() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        lifecycleScope.launch {
            val v = container.library.video(id) ?: return@launch
            val pos = resumePositionFor(v)
            if (pos > 0 && player.currentPosition < 2_000) player.seekTo(pos)
        }
    }

    private fun markWatched() {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val pos = player.currentPosition
        container.appScope.launch { container.library.saveVideoPosition(id, pos) }
    }

    fun saveCurrentPosition() {
        if (!::player.isInitialized) return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val duration = player.duration
        var pos = player.currentPosition
        // Finished videos restart from the beginning next time.
        if (duration > 0 && pos > duration - 5_000) pos = duration
        container.appScope.launch { container.library.saveVideoPosition(id, pos) }
    }

    private fun startPeriodicSave() {
        saveJob = lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveCurrentPosition()
            }
        }
    }

    fun currentVideo(): VideoEntity? {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return null
        return videos.firstOrNull { it.id == id }
    }

    fun toggleFavorite(onDone: (Boolean) -> Unit) {
        val v = currentVideo() ?: return
        lifecycleScope.launch {
            val fresh = container.library.video(v.id) ?: v
            val newValue = !fresh.favorite
            container.library.setVideoFavorite(v.id, newValue)
            onDone(newValue)
        }
    }

    suspend fun isFavorite(): Boolean {
        val v = currentVideo() ?: return false
        return container.library.video(v.id)?.favorite == true
    }

    fun saveSpeed(speed: Float) {
        container.appScope.launch { container.settings.setVideoSpeed(speed) }
    }

    fun applyFullscreen(enabled: Boolean) {
        fullscreen = enabled
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Cycles orientation: follow sensor → landscape → portrait. */
    fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        rotationLocked = true
    }

    fun unlockRotation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        rotationLocked = false
    }

    fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.01f, 1f) }
    }

    fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    fun enterPip() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            when {
                r > 2.39f -> Rational(239, 100)
                r < 1 / 2.39f -> Rational(100, 239)
                else -> Rational(size.width, size.height)
            }
        } else Rational(16, 9)
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(player.isPlaying)
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPosition()
        if (!isInPictureInPictureMode) player.pause()
    }

    override fun onDestroy() {
        saveCurrentPosition()
        saveJob?.cancel()
        player.release()
        super.onDestroy()
    }
}
