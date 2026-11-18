package io.github.senk39.droid39

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.senk39.droid39.core.Permissions
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.key
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
import io.github.senk39.droid39.ui.components.EdgeSwipeBack
import io.github.senk39.droid39.ui.screens.FolderScreen
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.DarkPalette
import io.github.senk39.droid39.ui.theme.LightPalette
import io.github.senk39.droid39.ui.theme.Theme39
import androidx.compose.foundation.isSystemInDarkTheme

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val light = isLightTheme(App.graph.settings.theme, systemDark())
        C39.palette = if (light) LightPalette else DarkPalette
        applySystemBars(light)
        super.onCreate(savedInstanceState)
        App.graph.player.connect()
        handleIntent(intent)
        setContent { Root() }
    }

    override fun onStart() {
        super.onStart()
        App.graph.live.setForeground(true)                       // zmiany z PC i „Kontynuuj z PC” na bieżąco
    }

    override fun onStop() {
        App.graph.live.setForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) Nav.playerOpen = true
        if (intent?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
            val g = App.graph
            g.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val lib = g.library
                val rows = when {
                    query.isBlank() -> lib.bestTracks().shuffled().take(500)
                    else -> lib.searchNodes(query, 5).firstOrNull { it.kind in setOf("vocap", "vocal", "variant", "album", "folder", "root") }
                        ?.let { lib.nodeTracks(it).shuffled().take(1000) } ?: lib.search(query, 200)
                }
                if (rows.isNotEmpty()) g.player.play(rows, 0, false, "Wyszukiwanie głosowe: $query")
            }
            Nav.playerOpen = true
        }
    }

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** Ikony pasków systemowych: jasne na ciemnym motywie, ciemne na jasnym. */
    fun applySystemBars(light: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        if (light) enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = SystemBarStyle.light(0xE6F4F5F7.toInt(), 0xE6F4F5F7.toInt()),
        ) else enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(0xE60D0F12.toInt()),
        )
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

fun isLightTheme(setting: String, systemDark: Boolean): Boolean = when (setting) {
    "light" -> true
    "system" -> !systemDark
    else -> false
}

@UnstableApi
@Composable
private fun Root() {
    val g = App.graph
    val settingsV by g.settings.changes.collectAsState()
    val ctx = LocalContext.current
    // Android 17: zgoda na sieć lokalną (NAS w LAN) – prośba przy starcie, gdy NAS jest już skonfigurowany
    val lanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            g.nas.invalidate()
            g.scope.launch { g.sync.syncNow() }
        }
    }
    LaunchedEffect(settingsV) {
        if (g.settings.nasConfigured && !Permissions.localNetworkGranted(ctx) && !lanAsked) {
            lanAsked = true
            lanLauncher.launch(Permissions.LOCAL_NETWORK)
        }
    }
    val row = rememberCurrentTrack()
    val artColor = rememberLoad(row?.artKey, settingsV) {
        if (!g.settings.accentFromArt) null
        else g.art.load(row?.artKey, row?.rel, 96)?.let { dominantColor(it) }
    }
    // motyw: ciemny / jasny / jak w systemie (zmiana w Ustawieniach działa od razu)
    val light = isLightTheme(g.settings.theme, isSystemInDarkTheme())   // settingsV – odświeżenie po zmianie
    LaunchedEffect(light) {
        C39.palette = if (light) LightPalette else DarkPalette
        (ctx as? MainActivity)?.applySystemBars(light)
    }
    val accent by animateColorAsState(C39.readableAccent(artColor ?: C39.Miku), tween(600), label = "accent")
    Theme39(accent) {
        val canGoBack = Nav.playerOpen || Nav.stack.size > 1
        BackHandler(enabled = canGoBack) { Nav.back() }
        EdgeSwipeBack(enabled = canGoBack, onBack = { Nav.back() }) {
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
                    Box(Modifier.fillMaxSize().background(C39.Bg)) { PlayerScreen() }
                }
            }
        }
    }
}

/** Prośba o sieć lokalną najwyżej raz na uruchomienie (odmowę obsługują Ustawienia). */
private var lanAsked = false

@Composable
private fun Screen(r: Route) = key(r) {
    // osobny stan (przewinięcie, sortowanie, filtr) dla każdego ekranu – bez tego np. przejście z Anime do
    // podkategorii zostawiało listę przewiniętą jak poprzednio
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
        is Route.Folder -> FolderScreen(r.path)
    }
}
