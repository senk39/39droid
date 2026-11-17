package io.github.senk39.droid39

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.screens.CommitsScreen
import io.github.senk39.droid39.ui.screens.HomeScreen
import io.github.senk39.droid39.ui.screens.MiniPlayer
import io.github.senk39.droid39.ui.screens.MultiScreen
import io.github.senk39.droid39.ui.screens.NodeScreen
import io.github.senk39.droid39.ui.screens.PlayerScreen
import io.github.senk39.droid39.ui.screens.PlaylistScreen
import io.github.senk39.droid39.ui.screens.PlaylistsScreen
import io.github.senk39.droid39.ui.screens.QueueScreen
import io.github.senk39.droid39.ui.screens.SearchScreen
import io.github.senk39.droid39.ui.screens.SettingsScreen
import io.github.senk39.droid39.ui.screens.SpecialScreen
import io.github.senk39.droid39.ui.screens.StarsScreen
import io.github.senk39.droid39.ui.screens.TagScreen
import io.github.senk39.droid39.ui.screens.TagsScreen
import io.github.senk39.droid39.ui.screens.dominantColor
import io.github.senk39.droid39.ui.screens.rememberCurrentTrack
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.Theme39

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        App.graph.player.connect()
        handleIntent(intent)
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) Nav.playerOpen = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

@UnstableApi
@Composable
private fun Root() {
    val g = App.graph
    val settingsV by g.settings.changes.collectAsState()
    val row = rememberCurrentTrack()
    val artColor = rememberLoad(row?.artKey, settingsV) {
        if (!g.settings.accentFromArt) null
        else g.art.load(row?.artKey, row?.rel, 96)?.let { dominantColor(it) }
    }
    val accent by animateColorAsState(artColor ?: C39.Miku, tween(600), label = "accent")
    Theme39(accent) {
        BackHandler(enabled = Nav.playerOpen || Nav.stack.size > 1) { Nav.back() }
        Box(Modifier.fillMaxSize().background(C39.Bg)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { Screen(Nav.current) }
                MiniPlayer()
            }
            AnimatedVisibility(
                visible = Nav.playerOpen,
                enter = slideInVertically(tween(260)) { it },
                exit = slideOutVertically(tween(220)) { it },
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { PlayerScreen() }
            }
        }
    }
}

@Composable
private fun Screen(r: Route) {
    when (r) {
        Route.Home -> HomeScreen()
        is Route.Node -> NodeScreen(r.id)
        is Route.Multi -> MultiScreen(r.label, r.paths)
        Route.Playlists -> PlaylistsScreen()
        is Route.Playlist -> PlaylistScreen(r.rel, r.title)
        is Route.Special -> SpecialScreen(r.kind)
        Route.Tags -> TagsScreen()
        is Route.Tag -> TagScreen(r.id)
        Route.Search -> SearchScreen()
        Route.Settings -> SettingsScreen()
        Route.Stars -> StarsScreen()
        Route.Commits -> CommitsScreen()
        Route.Queue -> QueueScreen()
    }
}
