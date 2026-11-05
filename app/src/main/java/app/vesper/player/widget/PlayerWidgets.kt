package app.vesper.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import app.vesper.player.MainActivity
import app.vesper.player.R
import app.vesper.player.data.media.ArtworkLoader
import app.vesper.player.playback.PlaybackService
import java.util.concurrent.Executors

/** 4×1 home-screen player. */
class SmallPlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = WidgetUpdater.refresh(context)
}

/** 4×2 home-screen player with "Моя волна". */
class LargePlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = WidgetUpdater.refresh(context)
}

/** Handles widget buttons: controls the running player, or resumes playback if the app is closed. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val service = PlaybackService.instance
        if (service != null) {
            service.widgetCommand(action)
            return
        }
        // Not running: ask the session service to resume the last queue (play key).
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)
        val start = Intent(context, PlaybackService::class.java)
            .setAction(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, key)
        runCatching { ContextCompat.startForegroundService(context, start) }
    }

    companion object {
        const val PLAY_PAUSE = "app.vesper.player.widget.PLAY_PAUSE"
        const val NEXT = "app.vesper.player.widget.NEXT"
        const val PREVIOUS = "app.vesper.player.widget.PREVIOUS"
    }
}

object WidgetUpdater {
    data class Snapshot(
        val title: String,
        val artist: String,
        val playing: Boolean,
        /** Local media URI or http thumbnail for the artwork. */
        val artUri: String?,
    )

    private val idle = Snapshot("Floya Music", "Нажмите ▶, чтобы продолжить", false, null)

    @Volatile private var last: Snapshot = idle
    @Volatile private var artKey: String? = null
    @Volatile private var art: Bitmap? = null
    private val loader = Executors.newSingleThreadExecutor()

    fun push(context: Context, snapshot: Snapshot) {
        val app = context.applicationContext
        last = snapshot
        render(app)
        val uri = snapshot.artUri
        if (uri != artKey) {
            artKey = uri
            art = null
            render(app)
            if (!uri.isNullOrBlank()) {
                loader.execute {
                    val bmp = runCatching { ArtworkLoader.loadBlocking(app, uri, 300) }.getOrNull()
                    if (artKey == uri) {
                        art = bmp?.let { scale(it) }
                        render(app)
                    }
                }
            }
        }
    }

    fun refresh(context: Context) = push(context, last)

    fun idle(context: Context) = push(context, last.copy(playing = false))

    private fun scale(b: Bitmap): Bitmap {
        val max = 256
        if (b.width <= max && b.height <= max) return b
        val f = max.toFloat() / maxOf(b.width, b.height)
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    @Synchronized
    private fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val small = manager.getAppWidgetIds(ComponentName(context, SmallPlayerWidget::class.java))
        val large = manager.getAppWidgetIds(ComponentName(context, LargePlayerWidget::class.java))
        if (small.isNotEmpty()) manager.updateAppWidget(small, views(context, R.layout.widget_small, large = false))
        if (large.isNotEmpty()) manager.updateAppWidget(large, views(context, R.layout.widget_large, large = true))
    }

    private fun views(context: Context, layout: Int, large: Boolean): RemoteViews {
        val s = last
        return RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.widget_title, s.title)
            setTextViewText(R.id.widget_artist, s.artist)
            setImageViewResource(R.id.widget_play, if (s.playing) R.drawable.w_pause else R.drawable.w_play)
            val bmp = art
            if (bmp != null) setImageViewBitmap(R.id.widget_art, bmp) else setImageViewResource(R.id.widget_art, R.drawable.ic_launcher_foreground)
            setOnClickPendingIntent(R.id.widget_play, action(context, WidgetActionReceiver.PLAY_PAUSE, 1))
            setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActionReceiver.NEXT, 2))
            setOnClickPendingIntent(R.id.widget_prev, action(context, WidgetActionReceiver.PREVIOUS, 3))
            setOnClickPendingIntent(R.id.widget_root, activity(context, MainActivity.ACTION_OPEN_PLAYER, 10))
            if (large) setOnClickPendingIntent(R.id.widget_wave, activity(context, MainActivity.ACTION_START_WAVE, 11))
        }
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun activity(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
