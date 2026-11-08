package app.vesper.player.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity

/** Extra action shown in a track's option sheet (e.g. "remove from this playlist"). */
data class ExtraTrackAction(val label: String, val onClick: () -> Unit)

/** System-level actions that need an Activity (pickers, permissions, video player). */
class AppActions(
    val importAudio: () -> Unit,
    val importVideo: () -> Unit,
    val importFolder: () -> Unit,
    val requestPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val openVideos: (videos: List<VideoEntity>, index: Int) -> Unit,
    val pickImage: (onPicked: (android.net.Uri?) -> Unit) -> Unit,
    val pickTextFile: (onPicked: (android.net.Uri?) -> Unit) -> Unit,
    val openOnlineVideo: (pageUrl: String, title: String) -> Unit,
)

/**
 * Screen-independent UI state: navigation shortcuts, the expanded player and global sheets.
 */
@Stable
class UiController(val nav: NavHostController) {
    var playerExpanded by mutableStateOf(false)
    var queueVisible by mutableStateOf(false)
    var sleepSheetVisible by mutableStateOf(false)
    var studioVisible by mutableStateOf(false)
    var transferVisible by mutableStateOf(false)

    var trackOptions by mutableStateOf<Pair<TrackEntity, ExtraTrackAction?>?>(null)
    var videoOptions by mutableStateOf<Pair<VideoEntity, ExtraTrackAction?>?>(null)
    var addToPlaylist by mutableStateOf<Pair<List<Long>, Int>?>(null)
    var trackInfo by mutableStateOf<TrackEntity?>(null)
    var editTrack by mutableStateOf<TrackEntity?>(null)

    fun showTrackOptions(track: TrackEntity, extra: ExtraTrackAction? = null) {
        trackOptions = track to extra
    }

    fun showVideoOptions(video: VideoEntity, extra: ExtraTrackAction? = null) {
        videoOptions = video to extra
    }

    fun showAddToPlaylist(ids: List<Long>, kind: Int) {
        if (ids.isNotEmpty()) addToPlaylist = ids to kind
    }

    fun openGroup(type: GroupType, key: String) {
        playerExpanded = false
        nav.navigate(Routes.group(type, key))
    }

    fun openPlaylist(id: Long) {
        nav.navigate(Routes.playlist(id))
    }

    fun open(route: String) {
        nav.navigate(route)
    }

    /** Selected sub-tab of the Music / Videos screens (survives navigation). */
    var musicTab by mutableIntStateOf(0)
    var videoTab by mutableIntStateOf(0)
    var playlistKind by mutableIntStateOf(0)

    /** Switches bottom-navigation tab, preserving each tab's back stack. */
    fun goToTab(route: String) {
        playerExpanded = false
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

object Routes {
    const val HOME = "home"
    const val MUSIC = "music"
    const val VIDEOS = "videos"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val THEMES = "themes"
    const val EQUALIZER = "equalizer"
    const val DOWNLOADS = "downloads"
    const val EDITOR = "editor/{kind}/{id}"
    const val LOGIN = "login/{site}"
    const val STATS = "stats"
    const val SEARCH = "search"
    const val ONLINE_PLAYLIST = "online_playlist/{id}"
    const val CHORUS_FEED = "chorus_feed"
    const val YANDEX_IMPORT = "yandex_import"
    const val MOVIES = "movies"
    const val IMPORT_REVIEW = "import_review"
    const val ENERGY_CURVE = "energy_curve"

    fun onlinePlaylist(id: Long) = "online_playlist/$id"

    fun login(site: app.vesper.player.data.download.CookieSite) = "login/${site.name}"

    fun editor(audio: Boolean, id: Long) = "editor/${if (audio) "audio" else "video"}/$id"
    const val GROUP = "group/{type}/{key}"
    const val PLAYLIST = "playlist/{id}"

    fun group(type: GroupType, key: String) = "group/${type.name}/${android.net.Uri.encode(key)}"
    fun playlist(id: Long) = "playlist/$id"
}

val LocalUi = staticCompositionLocalOf<UiController> { error("UiController not provided") }
val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
val LocalViewModel = staticCompositionLocalOf<MainViewModel> { error("ViewModel not provided") }

/** Space screens must leave at the bottom for the mini player + navigation bar. */
val LocalBottomInset = compositionLocalOf<Dp> { 0.dp }
