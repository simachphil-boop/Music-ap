package app.vesper.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.vesper.player.ui.player.MiniPlayer
import app.vesper.player.ui.player.NowPlayingScreen
import app.vesper.player.ui.screens.EqualizerScreen
import app.vesper.player.ui.screens.GroupDetailScreen
import app.vesper.player.ui.screens.HomeScreen
import app.vesper.player.ui.screens.MusicScreen
import app.vesper.player.ui.screens.PlaylistDetailScreen
import app.vesper.player.ui.screens.PlaylistsScreen
import app.vesper.player.ui.screens.SettingsScreen
import app.vesper.player.ui.screens.ThemePickerScreen
import app.vesper.player.ui.screens.VideosScreen
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Главная", Icons.Rounded.Home),
    Tab(Routes.MUSIC, "Музыка", Icons.Rounded.LibraryMusic),
    Tab(Routes.VIDEOS, "Видео", Icons.Rounded.VideoLibrary),
    Tab(Routes.PLAYLISTS, "Плейлисты", Icons.Rounded.QueueMusic),
    Tab(Routes.DOWNLOADS, "Загрузки", Icons.Rounded.Download),
    Tab(Routes.SETTINGS, "Настройки", Icons.Rounded.Settings),
)

@Composable
fun VesperRoot(vm: MainViewModel, actions: AppActions, openPlayerSignal: Int) {
    val nav = rememberNavController()
    val ui = remember(nav) { UiController(nav) }
    val c = Vesper.colors
    val playerState by vm.player.state.collectAsStateWithLifecycle()
    val hasQueue = playerState.queue.isNotEmpty()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val shared by vm.sharedUrl.collectAsStateWithLifecycle()
    LaunchedEffect(shared) {
        if (shared != null && nav.currentBackStackEntry?.destination?.route != Routes.DOWNLOADS) {
            ui.playerExpanded = false
            ui.goToTab(Routes.DOWNLOADS)
        }
    }
    LaunchedEffect(openPlayerSignal) {
        if (openPlayerSignal > 0) ui.playerExpanded = true
    }
    LaunchedEffect(Unit) {
        merge(vm.messages, vm.player.messages).collect { msg ->
            launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(msg)
            }
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset by animateDpAsState(
        targetValue = 64.dp + navBottom + (if (hasQueue) 72.dp else 8.dp),
        animationSpec = spring(stiffness = 500f),
        label = "inset",
    )

    BackHandler(enabled = ui.playerExpanded) { ui.playerExpanded = false }

    CompositionLocalProvider(
        LocalUi provides ui,
        LocalAppActions provides actions,
        LocalViewModel provides vm,
        LocalBottomInset provides bottomInset,
    ) {
        Box(Modifier.fillMaxSize().background(c.background)) {
            if (appSettings.ambience) {
                app.vesper.player.ui.theme.ThemeAmbience(Modifier.fillMaxSize(), playing = playerState.isPlaying, intensity = 0.8f)
            }
            VesperNavHost(nav)

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(
                    visible = hasQueue,
                    enter = slideInVertically(tween(320)) { it } + fadeIn(tween(240)),
                    exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
                ) {
                    MiniPlayer(onExpand = { ui.playerExpanded = true })
                }
                BottomNavBar(nav)
            }

            AnimatedVisibility(
                visible = ui.playerExpanded && hasQueue,
                enter = slideInVertically(tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it } +
                    fadeIn(tween(220)),
                exit = slideOutVertically(tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { it } +
                    fadeOut(tween(260)),
            ) {
                NowPlayingScreen(onCollapse = { ui.playerExpanded = false })
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (ui.playerExpanded) navBottom + 16.dp else bottomInset)
                    .padding(horizontal = 16.dp),
            ) { data ->
                Text(
                    data.visuals.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Vesper.shapes.medium)
                        .background(c.surfaceHigh)
                        .clickable { data.dismiss() }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }

            GlobalSheets()
        }
    }
}

@Composable
private fun VesperNavHost(nav: NavHostController) {
    val tabRoutes = remember { tabs.map { it.route }.toSet() }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            if (targetState.destination.route in tabRoutes) fadeIn(tween(240))
            else slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(260))
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(240)) },
        popExitTransition = {
            if (initialState.destination.route in tabRoutes) fadeOut(tween(180))
            else slideOutHorizontally(tween(280)) { it / 6 } + fadeOut(tween(220))
        },
    ) {
        composable(Routes.HOME) { HomeScreen() }
        composable(Routes.MUSIC) { MusicScreen() }
        composable(Routes.VIDEOS) { VideosScreen() }
        composable(Routes.PLAYLISTS) { PlaylistsScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.THEMES) { ThemePickerScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.EQUALIZER) { EqualizerScreen(onBack = { nav.popBackStack() }) }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("id") { type = NavType.LongType },
            ),
        ) { entry ->
            app.vesper.player.ui.screens.EditorScreen(
                audio = entry.arguments?.getString("kind") == "audio",
                id = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.LOGIN, arguments = listOf(navArgument("site") { type = NavType.StringType })) { entry ->
            val site = runCatching { app.vesper.player.data.download.CookieSite.valueOf(entry.arguments?.getString("site").orEmpty()) }
                .getOrDefault(app.vesper.player.data.download.CookieSite.YOUTUBE)
            app.vesper.player.ui.screens.LoginScreen(site, onBack = { nav.popBackStack() })
        }
        composable(Routes.ONLINE_PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            app.vesper.player.ui.screens.OnlinePlaylistScreen(entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
        composable(Routes.SEARCH) { app.vesper.player.ui.screens.SearchScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ASSISTANT) { app.vesper.player.ui.screens.AssistantScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ACCOUNT) { app.vesper.player.ui.screens.AccountScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MOVIES) { app.vesper.player.ui.screens.MoviesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.YANDEX_IMPORT) { app.vesper.player.ui.screens.YandexImportScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.IMPORT_REVIEW) { app.vesper.player.ui.screens.ImportReviewScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CHORUS_FEED) { app.vesper.player.ui.screens.ChorusFeedScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ENERGY_CURVE) { app.vesper.player.ui.screens.EnergyCurveScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.STATS) { app.vesper.player.ui.screens.StatsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.DOWNLOADS) { app.vesper.player.ui.screens.DownloadsScreen() }
        composable(
            Routes.GROUP,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("key") { type = NavType.StringType },
            ),
        ) { entry ->
            val type = runCatching { GroupType.valueOf(entry.arguments?.getString("type").orEmpty()) }
                .getOrDefault(GroupType.ALBUM)
            val key = entry.arguments?.getString("key").orEmpty()
            GroupDetailScreen(type, key, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.PLAYLIST,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            PlaylistDetailScreen(entry.arguments?.getLong("id") ?: 0L, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun BottomNavBar(nav: NavHostController) {
    val c = Vesper.colors
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val ui = LocalUi.current
    // Detail screens keep the tab they were opened from highlighted.
    val selected = remember(route) { route }
    val tabRoute = tabs.firstOrNull { it.route == selected }?.route
        ?: nav.previousBackStackEntry?.destination?.route?.takeIf { r -> tabs.any { it.route == r } }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background.copy(alpha = 0.94f))
            .navigationBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline.copy(alpha = 0.6f)))
        Row(
            Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = tab.route == tabRoute
                val tint by animateColorAsState(if (active) c.accentBright else c.textMuted, tween(240), label = "tabTint")
                val indicator by animateDpAsState(if (active) 18.dp else 0.dp, spring(stiffness = 600f), label = "tabInd")
                // The chosen tab's icon pops up with a little spring.
                val pop by androidx.compose.animation.core.animateFloatAsState(
                    if (active) 1.14f else 1f,
                    spring(dampingRatio = 0.38f, stiffness = 420f),
                    label = "tabPop",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            ui.playerExpanded = false
                            if (route == tab.route) return@clickable
                            ui.goToTab(tab.route)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.height(3.dp).width(indicator).clip(Vesper.shapes.chip).background(c.accentBright))
                    Spacer(Modifier.height(7.dp))
                    Icon(
                        tab.icon, tab.label, tint = tint,
                        modifier = Modifier.size(24.dp).graphicsLayer {
                            scaleX = pop
                            scaleY = pop
                            translationY = (1f - pop) * 10f
                        },
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
