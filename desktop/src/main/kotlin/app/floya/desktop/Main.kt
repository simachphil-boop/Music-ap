package app.floya.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.floya.desktop.theme.ThemeAmbience
import app.floya.desktop.theme.ThemeRegistry
import app.floya.desktop.theme.Vesper
import app.floya.desktop.theme.VesperThemeProvider
import app.floya.desktop.ui.DownloadsScreen
import app.floya.desktop.ui.HomeScreen
import app.floya.desktop.ui.LibraryScreen
import app.floya.desktop.ui.LocalApp
import app.floya.desktop.ui.NowPlaying
import app.floya.desktop.ui.Overline
import app.floya.desktop.ui.PlayerBar
import app.floya.desktop.ui.PlaylistScreen
import app.floya.desktop.ui.PlaylistsScreen
import app.floya.desktop.ui.Screen
import app.floya.desktop.ui.SearchScreen
import app.floya.desktop.ui.SettingsScreen
import app.floya.desktop.ui.themedShine

fun main() {
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    val app = AppState()
    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 820.dp), position = WindowPosition(Alignment.Center))
        Window(
            onCloseRequest = {
                app.player.engine.stop()
                exitApplication()
            },
            state = state,
            title = "Floya Music",
            icon = painterResource("icon.png"),
            onPreviewKeyEvent = { e ->
                if (e.type != KeyEventType.KeyDown) return@Window false
                when {
                    e.key == Key.Spacebar && !textFocused -> { app.player.toggle(); true }
                    e.isCtrlPressed && e.key == Key.DirectionRight -> { app.player.next(); true }
                    e.isCtrlPressed && e.key == Key.DirectionLeft -> { app.player.previous(); true }
                    e.key == Key.MediaPlayPause -> { app.player.toggle(); true }
                    e.key == Key.MediaNext -> { app.player.next(); true }
                    e.key == Key.MediaPrevious -> { app.player.previous(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(980, 640)
            CompositionLocalProvider(LocalApp provides app) { Root() }
        }
    }
}

/** True while a text field has focus — then Space types a space instead of pausing. */
@Volatile var textFocused = false

@Composable
private fun Root() {
    val app = LocalApp.current
    val settings by app.settings.collectAsState()
    val theme = ThemeRegistry.all.firstOrNull { it.id == settings.themeId } ?: ThemeRegistry.gothic
    VesperThemeProvider(theme) {
        val c = Vesper.colors
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var expanded by remember { mutableStateOf(false) }
        val playing by app.player.engine.playing.collectAsState()
        val message by app.message.collectAsState()
        Box(Modifier.fillMaxSize().background(c.background)) {
            if (settings.ambience) ThemeAmbience(Modifier.fillMaxSize(), playing = playing, intensity = 0.8f)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    Sidebar(screen) { screen = it; expanded = false }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { 24 }) togetherWith fadeOut(tween(160)) },
                            label = "screen",
                        ) { s ->
                            when (s) {
                                Screen.Home -> HomeScreen { screen = it }
                                Screen.Search -> SearchScreen()
                                Screen.Library -> LibraryScreen()
                                Screen.Playlists -> PlaylistsScreen { screen = it }
                                is Screen.PlaylistView -> PlaylistScreen(s.id) { screen = Screen.Playlists }
                                Screen.Downloads -> DownloadsScreen()
                                Screen.Settings -> SettingsScreen()
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                PlayerBar(onExpand = { expanded = !expanded })
            }
            Box(Modifier.fillMaxSize().padding(bottom = 89.dp)) {
                NowPlaying(expanded) { expanded = false }
            }
            AnimatedVisibility(
                visible = message != null,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp),
            ) {
                Text(
                    message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                    modifier = Modifier.clip(Vesper.shapes.medium).background(c.surfaceHigh).padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun Sidebar(screen: Screen, go: (Screen) -> Unit) {
    val app = LocalApp.current
    val c = Vesper.colors
    val playlists by app.playlists.collectAsState()
    val downloads by app.downloads.tasks.collectAsState()
    val active = downloads.count { it.status.name in setOf("QUEUED", "DOWNLOADING", "PROCESSING") }
    Column(Modifier.width(236.dp).fillMaxHeight().background(c.surface.copy(alpha = 0.72f)).padding(horizontal = 14.dp, vertical = 20.dp)) {
        Text(
            if (Vesper.theme.ornament.name == "NEON") "FLOYA" else "Floya",
            style = MaterialTheme.typography.headlineLarge,
            color = c.textPrimary,
            modifier = Modifier.padding(start = 10.dp, bottom = 18.dp).themedShine(),
        )
        NavItem(Icons.Rounded.Home, "Главная", screen == Screen.Home) { go(Screen.Home) }
        NavItem(Icons.Rounded.Search, "Поиск", screen == Screen.Search) { go(Screen.Search) }
        NavItem(Icons.Rounded.LibraryMusic, "Библиотека", screen == Screen.Library) { go(Screen.Library) }
        NavItem(Icons.Rounded.QueueMusic, "Плейлисты", screen == Screen.Playlists) { go(Screen.Playlists) }
        NavItem(Icons.Rounded.Download, if (active > 0) "Загрузки · $active" else "Загрузки", screen == Screen.Downloads) { go(Screen.Downloads) }
        NavItem(Icons.Rounded.Settings, "Настройки", screen == Screen.Settings) { go(Screen.Settings) }
        Spacer(Modifier.height(18.dp))
        Overline("Ваши плейлисты", Modifier.padding(start = 10.dp, bottom = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { p ->
                val sel = (screen as? Screen.PlaylistView)?.id == p.id
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (sel) c.accentBright else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(Vesper.shapes.small).clickable { go(Screen.PlaylistView(p.id)) }
                        .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Vesper.colors
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(Vesper.shapes.medium)
            .hoverable(src)
            .background(if (selected) c.highlight else if (hovered) c.surfaceHigh.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) c.accentBright else c.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (selected) c.textPrimary else c.textSecondary)
    }
}
