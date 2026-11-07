package app.vesper.player.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.vesper.player.MainActivity
import app.vesper.player.R
import app.vesper.player.playback.PlaybackService

/**
 * Floya's own tile in the phone's control panel (next to Wi-Fi, flashlight…).
 * Shows the current track; tap = play/pause, long press = open the player.
 * Unlike the system media card, tiles are open to every app.
 */
class FloyaTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val service = PlaybackService.instance
        if (service != null) {
            service.widgetCommand(WidgetActionReceiver.PLAY_PAUSE)
            // The player state changes asynchronously; draw the expected state right away.
            render(expectPlaying = !WidgetUpdater.current.playing)
            return
        }
        // Floya isn't running: resume the last queue like the headphone play button does…
        runCatching {
            sendBroadcast(Intent(this, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.PLAY_PAUSE))
        }
        // …and if the phone didn't let it start in the background, open the player instead.
        android.os.Handler(mainLooper).postDelayed({
            if (PlaybackService.instance == null) openPlayer() else render()
        }, 1_500)
    }

    private fun openPlayer() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_PLAYER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(expectPlaying: Boolean? = null) {
        val tile = qsTile ?: return
        val s = WidgetUpdater.current
        val running = PlaybackService.instance != null && s.title != WidgetUpdater.IDLE_TITLE
        val playing = expectPlaying ?: s.playing
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, if (playing) R.drawable.w_pause else R.drawable.w_play)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.label = if (running) s.title else "Floya"
            tile.subtitle = when {
                !running -> "Продолжить"
                s.artist.isNotBlank() -> s.artist
                playing -> "Играет"
                else -> "Пауза"
            }
        } else {
            tile.label = if (running) s.title else "Floya"
        }
        tile.contentDescription = if (playing) "Пауза: ${s.title}" else "Играть: ${s.title}"
        tile.updateTile()
    }

    companion object {
        /** Asks the system to redraw the tile (called whenever the track or play state changes). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, FloyaTileService::class.java)) }
        }
    }
}
