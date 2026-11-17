package io.github.senk39.droid39.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.sync.NasState
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.EmptyState
import io.github.senk39.droid39.ui.components.IconTextRow
import io.github.senk39.droid39.ui.components.SectionTitle
import io.github.senk39.droid39.ui.components.SmallIconButton
import io.github.senk39.droid39.ui.components.nodeIcon
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.launch

/** Pasek górny ekranu: wstecz, tytuł, wyszukiwanie i ustawienia. */
@Composable
fun TopBar(title: String, showBack: Boolean = Nav.stack.size > 1, extra: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) SmallIconButton(Icons39.Back, "Wstecz", tint = C39.Text) { Nav.back() }
        else Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra?.invoke()
        SmallIconButton(Icons39.Search, "Szukaj", tint = C39.Text) { Nav.go(Route.Search) }
        SmallIconButton(Icons39.Settings, "Ustawienia", tint = C39.Text) { Nav.go(Route.Settings) }
    }
}

@Composable
fun HomeScreen() {
    val g = App.graph
    val lib = g.library
    val snapV by lib.snapVersion.collectAsState()
    val overlay by lib.overlayVersion.collectAsState()
    val nas by g.nas.state.collectAsState()
    val sync by g.sync.status.collectAsState()
    val filesV by g.files.version.collectAsState()
    val counts = rememberLoad(snapV, overlay) {
        mapOf(
            "best" to lib.bestTracks().size, "liked" to lib.likedTracks().size, "unl" to lib.unlistenedCount,
            "pl" to lib.playlists().count { it.kind != "dir" }, "tags" to (lib.withSnap { it.tags.size } ?: 0),
        )
    }
    val roots = rememberLoad(snapV) { lib.roots() } ?: emptyList()
    Column(Modifier.fillMaxSize()) {
        TopBar("39droid", showBack = false, extra = {
            SmallIconButton(Icons39.Sync, "Synchronizuj", tint = if (sync.running) LocalAccent.current else C39.Text) {
                g.scope.launch { g.sync.syncNow() }
            }
        })
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item { StatusCard(nas, sync.running, sync.message, sync.progress, sync.error, lib.pendingOps, filesV) }
            if (!lib.ready) {
                item {
                    EmptyState(
                        "Brak biblioteki", "Skonfiguruj NAS w Ustawieniach i uruchom na PC: python -m sync39 export. " +
                            "Aplikacja pobierze migawkę biblioteki 39player.", Icons39.LibraryMusic,
                    ) {
                        Text("Otwórz ustawienia", Modifier.clip(RoundedCornerShape(50)).background(LocalAccent.current)
                            .clickable { Nav.go(Route.Settings) }.padding(horizontal = 18.dp, vertical = 9.dp), color = Color(0xFF00201D))
                    }
                }
                return@LazyColumn
            }
            item { SectionTitle("Słuchanie") }
            item { IconTextRow(Icons39.Inbox, "Do odsłuchania", "Nowe utwory do oceny", C39.Gold, counts?.get("unl")?.toString()) { Nav.go(Route.Special("unl")) } }
            item { IconTextRow(Icons39.Heart, "Najlepsze utwory", "♥♥ – _NAJLEPSZE", C39.Heart, counts?.get("best")?.toString()) { Nav.go(Route.Special("best")) } }
            item { IconTextRow(Icons39.HeartOutline, "Polubione utwory", "♥ – z PC i telefonu", C39.Pink, counts?.get("liked")?.toString()) { Nav.go(Route.Special("liked")) } }
            item { IconTextRow(Icons39.NewRelease, "Ostatnio dodane", null, LocalAccent.current) { Nav.go(Route.Special("recent")) } }
            item { IconTextRow(Icons39.History, "Ostatnio odtwarzane", null, LocalAccent.current) { Nav.go(Route.Special("played")) } }
            item { IconTextRow(Icons39.DownloadDone, "Na telefonie", "Pobrane i z karty SD – bez sieci", C39.Green, g.files.availableIds.size.toString()) { Nav.go(Route.Special("offline")) } }
            item { IconTextRow(Icons39.Queue, "Kolejka odtwarzania", null, LocalAccent.current) { Nav.go(Route.Queue) } }
            item { SectionTitle("Kategorie") }
            roots.forEach { n ->
                item(key = "root${n.id}") {
                    IconTextRow(nodeIcon(n.kind), n.name, null, colorFor(n.kind), n.count.toString()) { Nav.go(Route.Node(n.id)) }
                }
            }
            item { SectionTitle("Biblioteka") }
            item { IconTextRow(Icons39.PlaylistPlay, "Playlisty", null, LocalAccent.current, counts?.get("pl")?.toString()) { Nav.go(Route.Playlists) } }
            item { IconTextRow(Icons39.Tag, "Tagi (VocaDB)", null, C39.Miku, counts?.get("tags")?.toString()) { Nav.go(Route.Tags) } }
            item { IconTextRow(Icons39.Star, "Ulubione (gwiazdki)", "Tagi, vocaP i albumy z 39player", C39.Gold) { Nav.go(Route.Stars) } }
            item { IconTextRow(Icons39.CheckCircle, "Ostatnio zatwierdzone", "Historia z listy Nie przesłuchane", C39.Green) { Nav.go(Route.Commits) } }
            item { IconTextRow(Icons39.LibraryMusic, "Wszystkie utwory", null, C39.Text2, lib.withSnap { it.trackCount }?.toString()) { Nav.go(Route.Special("all")) } }
        }
    }
}

private fun colorFor(kind: String) = when (kind) {
    "vocap_root" -> C39.Miku
    "vocal_root" -> C39.Pink
    "engine_root" -> C39.Gold
    else -> Color(0xFF7FA7D9)
}

@Composable
private fun StatusCard(nas: NasState, running: Boolean, message: String, progress: Float?, error: String?, pending: Int, filesV: Int) {
    val g = App.graph
    val accent = LocalAccent.current
    val (icon, color, text) = when (nas) {
        is NasState.Online -> Triple(Icons39.Cloud, C39.Green, "NAS: połączono (${nas.label})")
        is NasState.Offline -> Triple(Icons39.CloudOff, C39.Text2, "NAS niedostępny – grają tylko utwory na telefonie")
        NasState.NotConfigured -> Triple(Icons39.CloudOff, C39.Gold, "NAS nie jest skonfigurowany")
        NasState.Unknown -> Triple(Icons39.Cloud, C39.Text3, "NAS: sprawdzanie przy pierwszym użyciu")
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(C39.Surface).clickable { Nav.go(Route.Settings) }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val snapAt = g.settings.snapshotCreated
        val lines = buildList {
            if (snapAt > 0) add("Biblioteka z PC: " + java.text.SimpleDateFormat("d.MM.yyyy HH:mm", java.util.Locale.ROOT).format(java.util.Date(snapAt)))
            if (pending > 0) add("Zmiany czekające na wysłanie: $pending")
            if (g.files.availableIds.isNotEmpty()) add("Na telefonie: ${T.tracks(g.files.availableIds.size)}")
        }
        if (lines.isNotEmpty()) Text(lines.joinToString(" · "), color = C39.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (running) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = accent, fontSize = 12.sp)
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = accent)
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = accent)
        } else if (error != null) {
            Text("Synchronizacja: $error", color = C39.Warn, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else if (message.isNotEmpty()) {
            Text(message, color = C39.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
