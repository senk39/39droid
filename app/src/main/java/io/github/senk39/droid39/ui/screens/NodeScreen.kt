@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.senk39.droid39.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.Franchise
import io.github.senk39.droid39.data.LlEntry
import io.github.senk39.droid39.data.LlFilter
import io.github.senk39.droid39.data.LlRoster
import io.github.senk39.droid39.data.NodeRow
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.Art
import io.github.senk39.droid39.ui.components.CoverTile
import io.github.senk39.droid39.ui.components.ListCtx
import io.github.senk39.droid39.ui.components.LlIcon
import io.github.senk39.droid39.ui.components.Loading
import io.github.senk39.droid39.ui.components.NodeRowView
import io.github.senk39.droid39.ui.components.SectionTitle
import io.github.senk39.droid39.ui.components.TrackListScreen
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent

/** Zapamiętany wybór filtrów Love Live! (jak w 39player – do zmiany ręcznej albo „Wyczyść filtry”). */
object LlState {
    var filter by mutableStateOf(LlFilter())
}

@Composable
fun NodeScreen(id: Int) {
    val g = App.graph
    val lib = g.library
    val snapV by lib.snapVersion.collectAsState()
    val node = remember(id, snapV) { lib.node(id) }
    if (node == null) {
        Column { TopBar("Kategoria"); Loading() }
        return
    }
    val path = remember(node) { lib.nodePathNames(node).joinToString(" › ") { it.name } }
    Column(Modifier.fillMaxSize()) {
        TopBar(path)
        if (node.isAggregate) AggregateNode(node) else TrackNode(node)
    }
}

/** Węzeł z utworami: podkategorie, albumy (półka okładek), Serie (Anime), filtry Love Live!, lista utworów. */
@Composable
private fun TrackNode(node: NodeRow) {
    val g = App.graph
    val lib = g.library
    val snapV by lib.snapVersion.collectAsState()
    val rows = rememberLoad(node.id, snapV) { lib.nodeTracks(node) }
    val children = remember(node, snapV) { lib.children(node.id) }
    val albums = children.filter { it.isAlbum }
    val folders = children.filter { !it.isAlbum }
    val isAnimeRoot = node.parent == null && node.path == "anime"
    val franchises = rememberLoad("fr", snapV, isAnimeRoot) { if (isAnimeRoot) lib.franchises() else emptyList() } ?: emptyList()
    val ll = rememberLoad("ll", node.id, snapV) { lib.llContext(node) }
    val llIndex = rememberLoad("lli", snapV, ll != null) { if (ll != null) lib.llIndex() else emptyMap() }
    val roster = rememberLoad("llr", snapV, ll != null) { if (ll != null) lib.llRoster() else null }
    val folderGroup = ll?.second ?: ""
    val stateFilter = LlState.filter
    val effective = if (folderGroup.isNotEmpty()) stateFilter.copy(group = folderGroup) else stateFilter
    val filtered = remember(rows, effective, llIndex, ll) {
        if (ll == null || rows == null || llIndex == null || !effective.active) rows
        else rows.filter { effective.matches(llIndex[it.id]) }
    }
    var albumsExpanded by rememberSaveable(node.id) { mutableStateOf(false) }
    val pinned = g.offline.isNodePinned(node.path)
    TrackListScreen(
        rows = filtered,
        ctx = ListCtx(node.name),
        title = node.name,
        subtitle = filtered?.let {
            val base = "${T.tracks(it.size)} · ${T.totalTime(it.sumOf { r -> r.duration })}"
            if (ll != null && effective.active && rows != null) "$base (z ${rows.size})" else base
        },
        headerArt = node.art,
        actions = listOf(
            (if (pinned) "Nie trzymaj offline" else "Zawsze na telefonie (offline)") to { g.offline.toggleNode(node.path) },
            "Dodaj wszystkie do kolejki" to { filtered?.let { g.player.enqueue(it) } },
        ),
        header = {
            if (node.vdbArtistId != null) item { VdbArtistLine(node) }
            if (franchises.isNotEmpty()) franchiseShelf(franchises)
            if (ll != null && roster != null && llIndex != null) {
                item { LoveLiveFilterBar(effective, folderGroup, roster) }
            }
            if (folders.isNotEmpty()) {
                item { SectionTitle(if (node.kind == "vocal") "Warianty" else "Podkategorie") }
                items(folders, key = { "f${it.id}" }) { c -> NodeRowView(c) { Nav.go(Route.Node(c.id)) } }
            }
            if (albums.isNotEmpty()) {
                item {
                    SectionTitle("Albumy (${albums.size})") {
                        if (albums.size > 6) Text(if (albumsExpanded) "Zwiń" else "Pokaż wszystkie",
                            Modifier.clickable { albumsExpanded = !albumsExpanded }.padding(8.dp), color = LocalAccent.current, fontSize = 13.sp)
                    }
                }
                val shown = if (albumsExpanded || albums.size <= 6) albums else albums.take(6)
                items(shown.chunked(3), key = { "a" + it.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { a -> Box(Modifier.weight(1f)) { CoverTile(a.name, T.tracks(a.count), a.art, 108.dp) { Nav.go(Route.Node(a.id)) } } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (folders.isNotEmpty() || albums.isNotEmpty() || franchises.isNotEmpty()) item { SectionTitle("Utwory") }
        },
    )
}

@Composable
private fun VdbArtistLine(node: NodeRow) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        io.github.senk39.droid39.ui.components.Pill("VocaDB ${T.vdbId(node.vdbArtistId)}", C39.Miku)
        val type = node.vdbArtistType
        if (!type.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(listOfNotNull(node.vdbArtistName, type).joinToString(" · "), color = C39.Text3, fontSize = 12.sp)
        }
    }
}

/** Węzeł zbiorczy (vocaP, Wokaliści, Silniki syntezy): półki i lista podkategorii z filtrem nazw. */
@Composable
private fun AggregateNode(node: NodeRow) {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val children = remember(node, snapV) { lib.children(node.id) }
    var filter by rememberSaveable(node.id) { mutableStateOf("") }
    val sections = children.all { it.kind == "vocal_section" }
    val top = remember(children) { children.sortedByDescending { it.count }.take(16) }
    val liked = remember(children) {
        children.filter { it.likes >= 3 }.sortedByDescending { it.likes }.take(40).filter { it !in top }.shuffled().take(16)
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(node.name, style = MaterialTheme.typography.titleLarge)
                Text("${T.tracks(node.count)} · ${children.size} pozycji", color = C39.Text2, fontSize = 13.sp)
            }
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Filtruj nazwy…") }, singleLine = true)
        }
        if (node.kind == "vocap_root" && filter.isEmpty()) {
            item { ArtistShelf("Najwięcej utworów", top) }
            item { ArtistShelf("Często polubieni", liked) }
            item { SectionTitle("Wszyscy (A–Z)") }
        }
        val tokens = T.queryTokens(filter)
        fun match(n: NodeRow) = tokens.isEmpty() || T.sortKey(n.name).let { k -> tokens.all { k.contains(it) } }
        if (sections) {
            children.forEach { sec ->
                val kids = lib.children(sec.id).filter(::match)
                if (kids.isNotEmpty()) {
                    item(key = "s${sec.id}") { SectionTitle("${sec.name} (${kids.size})") }
                    items(kids, key = { it.id }) { c -> NodeRowView(c, showArt = false) { Nav.go(Route.Node(c.id)) } }
                }
            }
        } else {
            items(children.filter(::match), key = { it.id }) { c -> NodeRowView(c, showArt = node.kind != "engine_root") { Nav.go(Route.Node(c.id)) } }
        }
    }
}

@Composable
private fun ArtistShelf(title: String, nodes: List<NodeRow>) {
    if (nodes.isEmpty()) return
    SectionTitle(title)
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(nodes, key = { it.id }) { n ->
            CoverTile(n.name, "${n.count} · ♥ ${n.likes}", n.art, 104.dp) { Nav.go(Route.Node(n.id)) }
        }
    }
}

/** Półka „Serie” w kategorii Anime (Love Live!, BanG Dream!, Project Sekai, THE IDOLM@STER, D4DJ, K-On!, DEN-ON-BU). */
private fun LazyListScope.franchiseShelf(list: List<Franchise>) {
    item { SectionTitle("Serie") }
    items(list, key = { "fr${it.id}" }) { f ->
        val lib = App.graph.library
        val enabled = f.count > 0
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(RoundedCornerShape(12.dp))
                .background(C39.Surface).padding(10.dp),
        ) {
            Box(Modifier.clickable(enabled = enabled && f.nodePath != null) { lib.nodeByPath(f.nodePath!!)?.let { Nav.go(Route.Node(it.id)) } }) {
                Art(f.art, null, 76.dp, corner = 10.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.label, style = MaterialTheme.typography.titleSmall, color = if (enabled) C39.Text else C39.Text3)
                Text(T.tracks(f.count), color = C39.Text3, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.items.filter { it.label != "Wszystkie utwory" }.forEach { it2 ->
                        val on = it2.count > 0 && it2.nodePaths.isNotEmpty()
                        Text(
                            it2.label, Modifier.clip(RoundedCornerShape(50)).background(if (on) C39.Surface2 else C39.Surface)
                                .clickable(enabled = on) {
                                    if (it2.nodePaths.size == 1) lib.nodeByPath(it2.nodePaths[0])?.let { n -> Nav.go(Route.Node(n.id)) }
                                    else Nav.go(Route.Multi("${f.label} – ${it2.label}", it2.nodePaths))
                                }.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 12.sp, color = if (on) C39.Text else C39.Text3,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- filtry Love Live!

@Composable
private fun LoveLiveFilterBar(state: LlFilter, folderGroup: String, roster: LlRoster) {
    var picker by remember { mutableStateOf<String?>(null) }
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C39.Surface).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Filtry Love Live!", Modifier.weight(1f), color = accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (state.active && !(folderGroup.isNotEmpty() && state.copy(group = "") == LlFilter())) {
                Text("Wyczyść", Modifier.clip(RoundedCornerShape(50)).clickable { LlState.filter = LlFilter() }.padding(6.dp), color = C39.Text2, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip39("Grupa", state.group.ifEmpty { null }, roster.groups.firstOrNull { it.name == state.group }?.icon, enabled = folderGroup.isEmpty()) { picker = "group" }
            FilterChip39("Postać", state.char.ifEmpty { null }, roster.chars.firstOrNull { it.name == state.char }?.icon) { picker = "char" }
            FilterChip39("Subunit", state.unit.ifEmpty { null }, roster.units.firstOrNull { it.name == state.unit }?.icon) { picker = "unit" }
            FilterChip39("Tylko solowe", if (state.solo) "tak" else null, null) { LlState.filter = LlState.filter.copy(solo = !state.solo) }
            FilterChip39("Instrumentalne", tri(state.inst), null) { LlState.filter = LlState.filter.copy(inst = nextTri(state.inst)) }
            FilterChip39("OST", tri(state.ost), null) { LlState.filter = LlState.filter.copy(ost = nextTri(state.ost)) }
        }
    }
    when (picker) {
        "group" -> LlPicker("Grupa", listOf(roster.groups), state.group, { picker = null }) { name ->
            LlState.filter = LlState.filter.copy(group = name, char = "", unit = "")
        }
        "char" -> {
            val groups = if (state.group.isNotEmpty()) roster.groups.filter { it.name == state.group } else roster.groups
            LlPicker("Postać", groups.map { g -> roster.chars.filter { it.group == g.name && it.count > 0 } }, state.char, { picker = null }) { name ->
                LlState.filter = LlState.filter.copy(char = name, unit = "")
            }
        }
        "unit" -> {
            val units = roster.units.filter { u ->
                u.count > 0 && (state.group.isEmpty() || u.group?.split('|')?.contains(state.group) == true) &&
                    (state.char.isEmpty() || state.char in u.members)
            }
            LlPicker("Subunit", listOf(units), state.unit, { picker = null }) { name -> LlState.filter = LlState.filter.copy(unit = name) }
        }
    }
}

private fun tri(v: String) = when (v) { "nie" -> "bez"; "tylko" -> "tylko"; else -> null }
private fun nextTri(v: String) = when (v) { "tak" -> "nie"; "nie" -> "tylko"; else -> "tak" }

@Composable
private fun FilterChip39(label: String, value: String?, icon: String?, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val on = value != null
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (on) accent.copy(alpha = 0.18f) else C39.Surface2)
            .border(1.dp, if (on) accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { LlIcon(icon, 18.dp); Spacer(Modifier.width(4.dp)) }
        Text(if (on) "$label: $value" else label, fontSize = 12.sp, color = if (on) accent else if (enabled) C39.Text else C39.Text3, maxLines = 1)
    }
}

@Composable
private fun LlPicker(title: String, columns: List<List<LlEntry>>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C39.Surface) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PickItem("Wszystkie", null, null, selected.isEmpty()) { onPick(""); onDismiss() }
            columns.forEach { col ->
                if (col.isEmpty()) return@forEach
                col.first().group?.takeIf { columns.size > 1 }?.let { g ->
                    Text(g, color = LocalAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    col.forEach { e -> PickItem(e.name, e.icon, e.count, e.name == selected) { onPick(e.name); onDismiss() } }
                }
            }
        }
    }
}

@Composable
private fun PickItem(name: String, icon: String?, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.2f) else C39.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { LlIcon(icon, 24.dp); Spacer(Modifier.width(6.dp)) }
        Text(name, fontSize = 13.sp, color = if (selected) accent else C39.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("  $count", fontSize = 11.sp, color = C39.Text3)
    }
}

/** Kilka kategorii naraz (np. „Love Live! – Pozostałe”). */
@Composable
fun MultiScreen(label: String, paths: List<String>) {
    val lib = App.graph.library
    val snapV by lib.snapVersion.collectAsState()
    val rows = rememberLoad(paths, snapV) {
        val seen = LinkedHashSet<Int>()
        paths.mapNotNull { lib.nodeByPath(it) }.forEach { seen.addAll(lib.nodeTrackIds(it)) }
        lib.tracks(seen.toList())
    }
    val nodes = remember(paths, snapV) { paths.mapNotNull { lib.nodeByPath(it) } }
    Column(Modifier.fillMaxSize()) {
        TopBar(label)
        TrackListScreen(rows, ListCtx(label), label, header = {
            item { SectionTitle("Kategorie") }
            items(nodes, key = { it.id }) { n -> NodeRowView(n) { Nav.go(Route.Node(n.id)) } }
            item { SectionTitle("Utwory") }
        })
    }
}
