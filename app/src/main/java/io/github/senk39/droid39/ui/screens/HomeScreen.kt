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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.ui.components.ScrollColumn
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.sync.DeviceState
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
    // wartości spoza stanu Compose odczytywane tu, przy zmianie wersji – elementy listy dostają je jako parametry
    val localCount = remember(filesV, snapV) { g.files.availableIds.size }
    val trackCount = remember(snapV) { lib.withSnap { it.trackCount } ?: 0 }
    val pending = rememberLoad(overlay, sync.running) { lib.pendingOps } ?: 0
    val ready = remember(snapV) { lib.ready }
    val settingsV by g.settings.changes.collectAsState()
    val uiScope = rememberCoroutineScope()

    // biblioteka z pliku (zanim NAS będzie gotowy) – plik .db.gz z `python -m sync39 export --out …`
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) g.scope.launch { g.sync.importFromUri(uri) }
    }

    // kopia biblioteki na karcie: propozycja folderu, zgoda na odczyt muzyki i skanowanie jednym przyciskiem
    val mirrorSuggestion = remember(settingsV, snapV) {
        if (!lib.ready || g.settings.mirrorList().isNotEmpty()) null
        else g.files.suggestMirrors().let { s -> s.firstOrNull { !it.startsWith("/storage/emulated") } ?: s.firstOrNull() }
    }
    var mirrorMsg by remember { mutableStateOf<String?>(null) }
    val scanMirrors = {
        mirrorMsg = "Skanowanie karty…"
        uiScope.launch {
            mirrorMsg = withContext(Dispatchers.IO) {
                g.files.scanMirrors().fold({ n ->
                    g.files.recomputeAvailable(lib)
                    "Znaleziono plików: $n · w bibliotece na telefonie: ${g.files.availableIds.size}"
                }, { "Błąd: ${it.message}" })
            }
        }
        Unit
    }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanMirrors() else mirrorMsg = "Bez zgody na odczyt muzyki aplikacja nie widzi plików na karcie."
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("39droid", showBack = false, extra = {
            SmallIconButton(Icons39.Sync, "Synchronizuj", tint = if (sync.running) LocalAccent.current else C39.Text) {
                g.scope.launch { g.sync.syncNow() }
            }
        })
        ScrollColumn {
            item { StatusCard(nas, sync.running, sync.message, sync.progress, sync.error, pending, localCount) }
            item { ContinueFromPcCard() }
            if (!ready) {
                item {
                    EmptyState(
                        "Brak biblioteki",
                        "Skonfiguruj NAS w Ustawieniach (na PC: python -m sync39 export) albo wczytaj plik biblioteki " +
                            "skopiowany na telefon (na PC: python -m sync39 export --out …).", Icons39.LibraryMusic,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeButton("Otwórz ustawienia", filled = true) { Nav.go(Route.Settings) }
                            HomeButton("Wczytaj z pliku", filled = false) { importLauncher.launch(arrayOf("*/*")) }
                        }
                    }
                }
                return@ScrollColumn
            }
            if (mirrorSuggestion != null || mirrorMsg != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
                            .background(C39.Surface).padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons39.SdCard, null, Modifier.size(20.dp), tint = LocalAccent.current)
                            Spacer(Modifier.width(10.dp))
                            Text("Kopia biblioteki na karcie", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        val msg = mirrorMsg
                        if (msg == null && mirrorSuggestion != null) {
                            Text(
                                "Znaleziono folder z muzyką: $mirrorSuggestion. Utwory z niego grają bez sieci i nie są pobierane drugi raz.",
                                color = C39.Text2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp),
                            )
                            HomeButton("Użyj i skanuj", filled = true) {
                                g.settings.mirrorFolders = mirrorSuggestion
                                if (g.files.hasAudioPermission()) scanMirrors()
                                else audioLauncher.launch(android.Manifest.permission.READ_MEDIA_AUDIO)
                            }
                        } else if (msg != null) {
                            Text(msg, color = C39.Text2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                            if (msg != "Skanowanie karty…") HomeButton("OK", filled = false) { mirrorMsg = null }
                        }
                    }
                }
            }
            item { SectionTitle("Słuchanie") }
            item { IconTextRow(Icons39.Inbox, "Do odsłuchania", "Nowe utwory do oceny", C39.Gold, counts?.get("unl")?.toString()) { Nav.go(Route.Special("unl")) } }
            item { IconTextRow(Icons39.Heart, "Najlepsze utwory", "♥♥ – _NAJLEPSZE", C39.Heart, counts?.get("best")?.toString()) { Nav.go(Route.Special("best")) } }
            item { IconTextRow(Icons39.HeartOutline, "Polubione utwory", "♥ – z PC i telefonu", C39.Pink, counts?.get("liked")?.toString()) { Nav.go(Route.Special("liked")) } }
            item { IconTextRow(Icons39.NewRelease, "Ostatnio dodane", null, LocalAccent.current) { Nav.go(Route.Special("recent")) } }
            item { IconTextRow(Icons39.History, "Ostatnio odtwarzane", null, LocalAccent.current) { Nav.go(Route.Special("played")) } }
            item { IconTextRow(Icons39.DownloadDone, "Na telefonie", "Pobrane i z karty SD – bez sieci", C39.Green, localCount.toString()) { Nav.go(Route.Special("offline")) } }
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
            item { IconTextRow(Icons39.Folder, "Foldery", "Układ folderów jak na PC", LocalAccent.current) { Nav.go(Route.Folder("")) } }
            item { IconTextRow(Icons39.LibraryMusic, "Wszystkie utwory", null, C39.Text2, trackCount.toString()) { Nav.go(Route.Special("all")) } }
        }
    }
}

/** „Kontynuuj z PC”: kolejka, utwór i pozycja z 39player (state/now-pc.json na NAS); PC dostaje polecenie pauzy. */
@Composable
private fun ContinueFromPcCard() {
    val g = App.graph
    val pc by g.live.pc.collectAsState()
    val now by g.player.now.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val st: DeviceState = pc ?: return
    val fresh = st.hasTrack && System.currentTimeMillis() - st.tsMs < 7L * 24 * 3600_000
    // telefon gra już ten utwór (np. zaraz po przejęciu) – karta zbędna
    if (!fresh || (now.playing && now.id != null && now.id == st.trackId)) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(C39.Surface).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons39.Computer, null, Modifier.size(20.dp), tint = LocalAccent.current)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(listOf(st.artist, st.title).filter { it.isNotBlank() }.joinToString(" – "), fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "PC: " + (if (st.playing) "gra" else "wstrzymany") + " · " + ago(st.tsMs) + " · " +
                        T.durationMs(st.positionMs) + " · w kolejce ${st.queue.size}",
                    color = C39.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HomeButton(if (busy) "Wczytywanie…" else "Kontynuuj z PC", filled = true) {
            if (busy) return@HomeButton
            busy = true
            scope.launch {
                val msg = g.live.continueFromPc(g.player)
                busy = false
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun ago(tsMs: Long): String {
    val s = ((System.currentTimeMillis() - tsMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "przed chwilą"
        s < 3600 -> "${s / 60} min temu"
        s < 86400 -> "${s / 3600} godz. temu"
        else -> "${s / 86400} dni temu"
    }
}

@Composable
private fun HomeButton(label: String, filled: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(
        label, Modifier.clip(RoundedCornerShape(50)).background(if (filled) accent else C39.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        color = if (filled) Color(0xFF00201D) else C39.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
    )
}

private fun colorFor(kind: String) = when (kind) {
    "vocap_root" -> C39.Miku
    "vocal_root" -> C39.Pink
    "engine_root" -> C39.Gold
    else -> Color(0xFF7FA7D9)
}

@Composable
private fun StatusCard(nas: NasState, running: Boolean, message: String, progress: Float?, error: String?, pending: Int, localCount: Int) {
    val g = App.graph
    val accent = LocalAccent.current
    val (icon, color, text) = when (val s = if (g.settings.nasConfigured) nas else NasState.NotConfigured) {
        is NasState.Online -> Triple(Icons39.Cloud, C39.Green, "NAS: połączono (${s.label})")
        is NasState.Offline -> Triple(Icons39.CloudOff, C39.Text2, "NAS niedostępny – grają tylko utwory na telefonie")
        NasState.NotConfigured -> Triple(Icons39.CloudOff, C39.Gold,
            "NAS nie jest skonfigurowany – grają tylko utwory na telefonie (Ustawienia → NAS)")
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
            if (localCount > 0) add("Na telefonie: ${T.tracks(localCount)}")
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
