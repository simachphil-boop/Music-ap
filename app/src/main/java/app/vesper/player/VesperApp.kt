package app.vesper.player

import android.app.Application
import android.content.Context
import app.vesper.player.data.LibraryRepository
import app.vesper.player.data.db.VesperDatabase
import app.vesper.player.data.prefs.SettingsRepository
import app.vesper.player.playback.AudioEffects
import app.vesper.player.playback.PlayerConnection
import app.vesper.player.playback.SleepTimer
import app.vesper.player.data.wave.LibraryAnalyzer
import app.vesper.player.data.wave.WaveEngine
import app.vesper.player.data.download.Downloader
import app.vesper.player.data.edit.MediaEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VesperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container — the app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
    /** Background work that must outlive screens (DB writes, saves). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Main-thread scope for controller interaction. */
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: VesperDatabase = VesperDatabase.build(app)
    val settings = SettingsRepository(app)
    val library = LibraryRepository(app, database, settings)
    val audioEffects = AudioEffects()
    val player = PlayerConnection(app, settings, mainScope)
    val sleepTimer = SleepTimer(mainScope, player)
    val analyzer = LibraryAnalyzer(app, database, appScope)
    val downloader = Downloader(app, library, appScope)
    val wave = WaveEngine(app, database, player, downloader, mainScope)
    val editor = MediaEditor(app, library, appScope)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as VesperApp).container
