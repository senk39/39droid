@file:OptIn(ExperimentalLayoutApi::class)

package io.github.senk39.droid39.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.ui.components.ScrollColumn
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.SnapshotDb
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.EmptyState
import io.github.senk39.droid39.ui.components.ListCtx
import io.github.senk39.droid39.ui.components.Loading
import io.github.senk39.droid39.ui.components.NodeRowView
import io.github.senk39.droid39.ui.components.Pill
import io.github.senk39.droid39.ui.components.SectionTitle
import io.github.senk39.droid39.ui.components.SmallIconButton
import io.github.senk39.droid39.ui.components.TrackListScreen
import io.github.senk39.droid39.ui.components.TrackRowView
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SpecialScreen(kind: String) {
    val g = App.graph
    val lib = g.library
    val snapV by lib.snapVersion.collectAsState()
    val overlay by lib.overlayVersion.collectAsState()
    val filesV by g.files.version.collectAsState()
    // listy zależne od serduszek odświeżają się po zmianie na telefonie; pozostałe tylko po nowej migawce
    val dep = if (kind in setOf("best", "liked", "unl", "played")) overlay else 0
    val title = when (kind) {
        "unl" -> "Do odsłuchania"
        "best" -> "Najlepsze utwory ♥♥"
        "liked" -> "Polubione utwory ♥"
        "recent" -> "Ostatnio dodane"
        "played" -> "Ostatnio odtwarzane"
        "offline" -> "Na telefonie"
        else -> "Wszystkie utwory"
    }
    val rows = rememberLoad(kind, snapV, dep, if (kind == "offline") filesV else 0) {
        when (kind) {
            "unl" -> lib.unlistenedTracks()
            "best" -> lib.bestTracks()
            "liked" -> lib.likedTracks()
            "recent" -> lib.recentlyAdded()
            "played" -> lib.recentlyPlayed()
            "offline" -> lib.localTracks(g.files.availableIds)
            else -> lib.allTracks()
        }
    }
    val pinKey = when (kind) { "best" -> "best"; "liked" -> "liked"; "unl" -> "unl"; else -> null }
    val actions = buildList<Pair<String, () -> Unit>> {
        if (pinKey != null) {
            val on = when (pinKey) { "best" -> g.settings.offlineBest; "liked" -> g.settings.offlineLiked; else -> g.settings.offlineUnlistened }
            add((if (on) "Nie trzymaj offline" else "Zawsze na telefonie (offline)") to {
                when (pinKey) {
                    "best" -> g.settings.offlineBest = !on
                    "liked" -> g.settings.offlineLiked = !on
                    else -> g.settings.offlineUnlistened = !on
                }
            })
        }
        add("Dodaj wszystkie do kolejki" to { rows?.let { g.player.enqueue(it) } })
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        val subtitle = if (kind == "unl" && rows != null) {
            val heard = rows.count { lib.marks(it.id)?.listened == true }
            "${T.tracks(rows.size)} · przesłuchane: $heard · zatwierdzasz w 39player na PC"
        } else null
        TrackListScreen(
            rows, ListCtx(title, unlistenedView = kind == "unl"), title, subtitle = subtitle, actions = actions,
            emptyText = if (kind == "offline") "Nic nie ma jeszcze na telefonie" else "Brak utworów",
        )
    }
}

@Composable
fun PlaylistsScreen() {
    val g = App.graph
    val lib = g.library
    val snapV by lib.snapVersion.collectAsState()
    val overlay by lib.overlayVersion.collectAsState()
    val lists = rememberLoad(snapV, overlay) { lib.playlists() }
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Playlisty", extra = { SmallIconButton(Icons39.Add, "Nowa playlista", tint = C39.Text) { create = true } })
        if (lists == null) { Loading(); return@Column }
        ScrollColumn {
            items(lists, key = { it.rel }) { p ->
                if (p.kind == "dir") {
                    SectionTitle(p.rel.replace("/", " › "))
                } else {
                    val pinned = g.offline.isPlaylistPinned(p.rel)
                    Row(
                        Modifier.fillMaxWidth().clickable { Nav.go(Route.Playlist(p.rel, p.name)) }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons39.PlaylistPlay, null, Modifier.size(24.dp), tint = LocalAccent.current)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                T.tracks(p.count) + (if (p.missing > 0) " · brakujących plików: ${p.missing}" else "") +
                                    (if (p.kind == "local") " · utworzona na telefonie" else ""),
                                color = C39.Text3, fontSize = 12.sp,
                            )
                        }
                        if (pinned) Icon(Icons39.DownloadDone, "Offline", Modifier.size(18.dp), tint = LocalAccent.current)
                    }
                }
            }
        }
    }
    if (create) {
        var name by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { create = false },
            title = { Text("Nowa playlista") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it; err = null }, label = { Text("Nazwa") }, singleLine = true)
                    if (err != null) Text(err!!, color = C39.Warn, fontSize = 12.sp)
                    Text("Playlista powstanie też na PC przy następnym imporcie zmian (sync39).", color = C39.Text3, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { lib.createPlaylist(name) }.onSuccess { create = false }.onFailure { err = it.message }
                }) { Text("Utwórz") }
            },
            dismissButton = { TextButton(onClick = { create = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
fun PlaylistScreen(rel: String, title: String) {
    val g = App.graph
    val lib = g.library
    val snapV by lib.snapVersion.collectAsState()
    val overlay by lib.overlayVersion.collectAsState()
    val rows = rememberLoad(rel, snapV, overlay) { lib.playlistTracks(rel) }
    val pinned = g.offline.isPlaylistPinned(rel)
    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        TrackListScreen(
            rows, ListCtx(title, playlistRel = rel), title,
            actions = listOf(
                (if (pinned) "Nie trzymaj offline" else "Zawsze na telefonie (offline)") to { g.offline.togglePlaylist(rel) },
                "Dodaj wszystkie do kolejki" to { rows?.let { g.player.enqueue(it) } },
            ),
        )
    }
}

@Composable
fun TagsScreen() {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val tags = rememberLoad(snapV) { lib.tags() }
    val starred = remember(snapV) { lib.stars().filter { it.kind == "tag" }.mapNotNull { it.ident.toLongOrNull() }.toSet() }
    var filter by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar("Tagi (VocaDB)")
        if (tags == null) { Loading(); return@Column }
        if (tags.isEmpty()) {
            EmptyState("Brak tagów", "Tagi pobierasz w 39player: VocaDB → Pobierz tagi z VocaDB.", Icons39.Tag); return@Column
        }
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), placeholder = { Text("Filtruj tagi…") }, singleLine = true)
        val tokens = T.queryTokens(filter)
        val shown = tags.filter { t -> tokens.isEmpty() || T.sortKey(t.name).let { k -> tokens.all { k.contains(it) } } }
        ScrollColumn {
            val stars = shown.filter { it.id in starred }
            if (stars.isNotEmpty()) {
                item { SectionTitle("★ Ulubione tagi") }
                item { TagFlow(stars) }
            }
            shown.groupBy { it.category }.toSortedMap(compareBy { SnapshotDb.tagCategoryRank(it) }).forEach { (cat, list) ->
                item(key = "c$cat") { SectionTitle("${SnapshotDb.TAG_CATEGORY_PL[cat] ?: cat} (${list.size})") }
                item(key = "t$cat") { TagFlow(list) }
            }
        }
    }
}

@Composable
private fun TagFlow(list: List<io.github.senk39.droid39.data.TagRow>) {
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { t -> Pill("${t.name}  ${t.count}", C39.TagColors[t.category] ?: C39.Text2, onClick = { Nav.go(Route.Tag(t.id)) }) }
    }
}

@Composable
fun TagScreen(id: Long) {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val tag = remember(id, snapV) { lib.tag(id) }
    val rows = rememberLoad(id, snapV) { lib.tagTracks(id) }
    val title = tag?.name ?: "Tag"
    Column(Modifier.fillMaxSize()) {
        TopBar(title)
        TrackListScreen(rows, ListCtx("Tag: $title"), title,
            subtitle = tag?.let { "${SnapshotDb.TAG_CATEGORY_PL[it.category] ?: it.category} · ${T.tracks(it.count)} · ♥ ${it.likes}" })
    }
}

@Composable
fun StarsScreen() {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val stars = remember(snapV) { lib.stars() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Ulubione (gwiazdki)")
        if (stars.isEmpty()) {
            EmptyState("Brak gwiazdek", "Gwiazdki dla tagów, vocaP i albumów nadajesz w 39player na PC.", Icons39.Star); return@Column
        }
        ScrollColumn {
            val vocap = stars.filter { it.kind == "vocap" }.mapNotNull { s -> lib.nodeByPath(s.ident) }
            if (vocap.isNotEmpty()) {
                item { SectionTitle("vocaP") }
                items(vocap, key = { "v${it.id}" }) { n -> NodeRowView(n) { Nav.go(Route.Node(n.id)) } }
            }
            val tags = stars.filter { it.kind == "tag" }.mapNotNull { s -> s.ident.toLongOrNull()?.let { lib.tag(it) } }
            if (tags.isNotEmpty()) {
                item { SectionTitle("Tagi") }
                item { TagFlow(tags) }
            }
            val albums = stars.filter { it.kind == "album" }
            if (albums.isNotEmpty()) {
                item { SectionTitle("Albumy") }
                items(albums, key = { "a${it.ident}" }) { s ->
                    var open by rememberSaveable(s.ident) { mutableStateOf(false) }
                    Column {
                        Text(s.name.ifEmpty { s.ident.substringAfterLast('/') }, Modifier.fillMaxWidth().clickable { open = !open }
                            .padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        if (open) {
                            val rows = rememberLoad(s.ident, snapV) { lib.albumTracks(s.ident) } ?: emptyList()
                            rows.forEachIndexed { i, r -> TrackRowView(r, ListCtx(s.name)) { App.graph.player.play(rows, i, false, s.name) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommitsScreen() {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val commits = rememberLoad(snapV) { lib.commits() }
    val fmt = remember { SimpleDateFormat("d.MM.yyyy HH:mm", Locale.ROOT) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Ostatnio zatwierdzone")
        if (commits == null) { Loading(); return@Column }
        if (commits.isEmpty()) { EmptyState("Brak zatwierdzeń", "Historia zatwierdzeń z listy „Nie przesłuchane” w 39player."); return@Column }
        ScrollColumn {
            itemsIndexed(commits) { idx, (ts, items) ->
                var open by rememberSaveable(idx) { mutableStateOf(idx == 0) }
                val best = items.count { it.second == 2 }
                val liked = items.count { it.second == 1 }
                Text(
                    "${fmt.format(Date((ts * 1000).toLong()))} · ${items.size} utw. (♥♥ $best · ♥ $liked)",
                    Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
                    fontWeight = FontWeight.Medium,
                )
                if (open) {
                    val rows = rememberLoad("c$idx", snapV) { lib.tracks(items.map { it.first }) } ?: emptyList()
                    rows.forEachIndexed { i, r -> TrackRowView(r, ListCtx("Zatwierdzone")) { App.graph.player.play(rows, i, false, "Zatwierdzone") } }
                }
            }
        }
    }
}

@Composable
fun QueueScreen() {
    val g = App.graph
    val ui by g.player.ui.collectAsState()
    val rows = rememberLoad(ui.queue) { g.library.tracks(ui.queue) }
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize()) {
        TopBar("Kolejka", extra = { SmallIconButton(Icons39.Close, "Wyczyść kolejkę", tint = C39.Text) { g.player.clearQueue() } })
        if (rows == null) { Loading(); return@Column }
        if (rows.isEmpty()) { EmptyState("Kolejka jest pusta", "Wybierz utwór z biblioteki.", Icons39.Queue); return@Column }
        ScrollColumn {
            itemsIndexed(rows, key = { i, r -> "$i-${r.id}" }) { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(40.dp).padding(start = 12.dp), color = if (i == ui.index) accent else C39.Text3, fontSize = 12.sp)
                    Column(Modifier.weight(1f)) { TrackRowView(r, ListCtx("Kolejka")) { g.player.skipTo(i) } }
                    SmallIconButton(Icons39.Close, "Usuń z kolejki", tint = C39.Text3, size = 18.dp) { g.player.remove(i) }
                }
            }
        }
    }
}

@Composable
fun SearchScreen() {
    val g = App.graph
    val lib = g.library
    var q by rememberSaveable { mutableStateOf("") }
    val results = rememberLoad(q) {
        kotlinx.coroutines.delay(250)
        if (q.trim().length < 2) null else Triple(lib.searchNodes(q), lib.searchTags(q), lib.search(q))
    }
    val playlists = remember(q) { if (q.trim().length < 2) emptyList() else lib.playlists().filter { it.kind != "dir" && T.sortKey(it.name).contains(T.sortKey(q.trim())) } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Szukaj")
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            placeholder = { Text("Tytuł, wykonawca, album, nazwa z VocaDB, tag…") }, singleLine = true,
            leadingIcon = { Icon(Icons39.Search, null) },
        )
        val r = results
        if (r == null) {
            if (q.trim().length >= 2) Loading()
            return@Column
        }
        val (nodes, tags, tracks) = r
        ScrollColumn {
            if (nodes.isNotEmpty()) {
                item { SectionTitle("Kategorie i wykonawcy") }
                items(nodes.take(20), key = { "n${it.id}" }) { n -> NodeRowView(n) { Nav.go(Route.Node(n.id)) } }
            }
            if (playlists.isNotEmpty()) {
                item { SectionTitle("Playlisty") }
                items(playlists, key = { "p${it.rel}" }) { p ->
                    Text(p.name, Modifier.fillMaxWidth().clickable { Nav.go(Route.Playlist(p.rel, p.name)) }.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            if (tags.isNotEmpty()) {
                item { SectionTitle("Tagi VocaDB") }
                item { TagFlow(tags) }
            }
            item { SectionTitle("Utwory (${tracks.size}${if (tracks.size >= 400) "+" else ""})") }
            itemsIndexed(tracks, key = { i, t -> "t${t.id}#$i" }) { i, t -> TrackRowView(t, ListCtx("Wyszukiwanie: $q")) { g.player.play(tracks, i, false, "Wyszukiwanie") } }
        }
    }
}
