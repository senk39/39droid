@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.senk39.droid39.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.App
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.data.SortMode
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.sync.NasState
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kontekst listy: etykieta źródła i czy to widok „Do odsłuchania” (serduszko = oznaczenie). */
data class ListCtx(val label: String, val unlistenedView: Boolean = false, val playlistRel: String? = null)

fun sortRows(rows: List<TrackRow>, mode: SortMode, desc: Boolean): List<TrackRow> {
    val sorted = when (mode) {
        SortMode.DEFAULT -> rows
        SortMode.TITLE -> rows.sortedBy { T.sortKey(it.title) }
        SortMode.ARTIST -> rows.sortedWith(compareBy({ T.sortKey(it.artist) }, { T.sortKey(it.album) }, { it.discNo }, { it.trackNo }))
        SortMode.ALBUM -> rows.sortedWith(compareBy({ T.sortKey(it.album) }, { it.discNo }, { it.trackNo }))
        SortMode.DURATION -> rows.sortedBy { it.duration }
        SortMode.ADDED -> rows.sortedByDescending { it.dateAdded }
        SortMode.PLAYS -> rows.sortedByDescending { App.graph.library.playCount(it) }
        SortMode.YEAR -> rows.sortedBy { it.year }
        SortMode.RANDOM -> rows.shuffled()
    }
    return if (desc && mode != SortMode.DEFAULT && mode != SortMode.RANDOM) sorted.reversed() else sorted
}

/** Lista utworów z nagłówkiem. `header` – dodatkowe elementy nad listą (półki, filtry). */
@Composable
fun TrackListScreen(
    rows: List<TrackRow>?,
    ctx: ListCtx,
    title: String,
    subtitle: String? = null,
    headerArt: String? = null,
    headerRel: String? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    header: (LazyListScope.() -> Unit)? = null,
    sortable: Boolean = true,
    emptyText: String = "Brak utworów",
) {
    var sort by rememberSaveable(title) { mutableStateOf(SortMode.DEFAULT) }
    var desc by rememberSaveable(title) { mutableStateOf(false) }
    var filter by rememberSaveable(title) { mutableStateOf("") }
    var showFilter by rememberSaveable(title) { mutableStateOf(false) }
    val sorted = remember(rows, sort, desc, filter) {
        rows?.let { r ->
            val tokens = T.queryTokens(filter)
            val f = if (tokens.isEmpty()) r else r.filter { t ->
                val k = T.sortKey("${t.title} ${t.artist} ${t.album}")
                tokens.all { k.contains(it) }
            }
            sortRows(f, sort, desc)
        }
    }
    val player = App.graph.player
    val state = rememberLazyListState()
    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "__head") {
            ListHeader(
                title = title,
                subtitle = subtitle ?: rows?.let { "${T.tracks(it.size)} · ${T.totalTime(it.sumOf { r -> r.duration })}" },
                artKey = headerArt, rel = headerRel,
                onPlay = { sorted?.let { player.play(it, 0, false, ctx.label) } },
                onShuffle = { sorted?.let { player.play(it, 0, true, ctx.label) } },
                sort = if (sortable) sort else null, desc = desc,
                onSort = { m -> if (m == sort) desc = !desc else { sort = m; desc = false } },
                onFilter = { showFilter = !showFilter; if (!showFilter) filter = "" },
                actions = actions,
            )
            if (showFilter) {
                OutlinedTextField(
                    filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Filtruj listę…") }, singleLine = true,
                )
            }
        }
        header?.invoke(this)
        when {
            sorted == null -> item { Loading() }
            sorted.isEmpty() -> item { EmptyState(emptyText, if (filter.isNotEmpty()) "Nic nie pasuje do filtra." else "") }
            else -> itemsIndexed(sorted, key = { i, r -> "${r.id}#$i" }) { i, r ->
                TrackRowView(r, ctx, onPlay = { player.play(sorted, i, false, ctx.label) })
            }
        }
    }
}

@Composable
fun ListHeader(
    title: String,
    subtitle: String?,
    artKey: String?,
    rel: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    sort: SortMode?,
    desc: Boolean,
    onSort: (SortMode) -> Unit,
    onFilter: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (artKey != null) {
                Art(artKey, rel, 84.dp, corner = 10.dp)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = C39.Text2, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(Icons39.Play, "Odtwórz", accent, filled = true, onClick = onPlay)
            ActionChip(Icons39.Shuffle, "Losowo", accent, onClick = onShuffle)
            Spacer(Modifier.weight(1f))
            SmallIconButton(Icons39.Filter, "Filtruj", onClick = onFilter)
            if (sort != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    SmallIconButton(Icons39.Sort, "Sortuj", tint = if (sort != SortMode.DEFAULT) accent else C39.Text2) { open = true }
                    DropdownMenu(open, { open = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label + if (m == sort && m != SortMode.DEFAULT && m != SortMode.RANDOM) (if (desc) "  ↓" else "  ↑") else "") },
                                onClick = { onSort(m); open = false },
                                leadingIcon = { if (m == sort) Icon(Icons39.Check, null, tint = accent) },
                            )
                        }
                    }
                }
            }
            if (actions.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    SmallIconButton(Icons39.More, "Więcej") { open = true }
                    DropdownMenu(open, { open = false }) {
                        actions.forEach { (label, act) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() }) }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionChip(icon: ImageVector, label: String, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (filled) Color(0xFF00201D) else color)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (filled) Color(0xFF00201D) else color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun TrackRowView(r: TrackRow, ctx: ListCtx, onPlay: () -> Unit) {
    val g = App.graph
    val lib = g.library
    val overlay by lib.overlayVersion.collectAsState()
    val filesV by g.files.version.collectAsState()
    val nas by g.nas.state.collectAsState()
    val now by g.player.now.collectAsState()
    val heart = remember(overlay, r) { lib.displayHeart(r, ctx.unlistenedView) }
    val local = remember(filesV, r.id) { g.files.isLocal(r.id) }
    val unavailable = !local && (nas is NasState.Offline || nas is NasState.NotConfigured)
    val playing = now.id == r.id
    var menu by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth()
            .background(if (playing) accent.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Art(r.artKey, r.rel, 48.dp)
            if (playing) Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                Icon(if (now.playing) Icons39.Equalizer else Icons39.Pause, null, Modifier.size(22.dp), tint = accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.title.ifEmpty { r.rel.substringAfterLast('/') }, Modifier.weight(1f, fill = false),
                    color = if (playing) accent else if (unavailable) C39.Text3 else C39.Text,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(5.dp))
                VdbDot(r.songId != null, r.vdbIssue)
                val st = r.songType
                if (!st.isNullOrEmpty() && st != "Original song" && st != "Unspecified") {
                    Spacer(Modifier.width(4.dp)); Pill(st, C39.Gold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ctx.unlistenedView) lib.marks(r.id)?.let { m ->
                    if (m.listened) { Icon(Icons39.CheckCircle, "Przesłuchane", Modifier.size(13.dp), tint = C39.Green); Spacer(Modifier.width(4.dp)) }
                }
                Text(
                    r.artist, color = if (unavailable) C39.Text3 else C39.Text2, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(T.duration(r.duration), color = C39.Text3, fontSize = 12.sp)
            if (local) Icon(Icons39.DownloadDone, "Na telefonie", Modifier.size(13.dp), tint = accent.copy(alpha = 0.8f))
            else if (unavailable) Icon(Icons39.CloudOff, "Niedostępny", Modifier.size(13.dp), tint = C39.Text3)
        }
        HeartIcon(heart, 20.dp, onClick = {
            g.scope.launch(Dispatchers.IO) { lib.cycleHeart(r, ctx.unlistenedView); g.player.heartChanged() }
        })
    }
    if (menu) TrackActionsSheet(listOf(r), ctx) { menu = false }
}

@Composable
fun TrackActionsSheet(rows: List<TrackRow>, ctx: ListCtx, onDismiss: () -> Unit) {
    val g = App.graph
    val lib = g.library
    val player = g.player
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addToPl by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val r = rows.first()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C39.Surface) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(r.artKey, r.rel, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (rows.size == 1) r.title else T.tracks(rows.size), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    if (rows.size == 1) Text(r.artist, color = C39.Text2, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            HorizontalDivider(color = C39.Line)
            val heart = lib.displayHeart(r, ctx.unlistenedView)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Bez polubienia", 1 to "♥ Polubione", 2 to "♥♥ Najlepsze").forEach { (lv, label) ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (heart == lv) C39.Heart.copy(alpha = 0.22f) else C39.Surface2)
                            .clickable {
                                g.scope.launch(Dispatchers.IO) { lib.setHeart(rows, lv, ctx.unlistenedView); player.heartChanged() }
                                onDismiss()
                            }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, fontSize = 12.sp, color = if (heart == lv) C39.Heart else C39.Text2, maxLines = 1) }
                }
            }
            if (ctx.unlistenedView && lib.inUnlistened(r.id)) {
                val m = lib.marks(r.id)
                SheetItem(Icons39.CheckCircle, if (m?.listened == true) "Odznacz „Przesłuchane”" else "Oznacz jako przesłuchane") {
                    g.scope.launch(Dispatchers.IO) { for (x in rows) lib.marks(x.id)?.let { mm -> lib.setMarks(x, mm.copy(listened = !(m?.listened ?: false))) } }
                    onDismiss()
                }
            }
            SheetItem(Icons39.PlaylistPlay, "Odtwórz następny") { player.playNext(rows); onDismiss() }
            SheetItem(Icons39.Queue, "Dodaj do kolejki") { player.enqueue(rows); onDismiss() }
            SheetItem(Icons39.PlaylistAdd, "Dodaj do playlisty…") { addToPl = true }
            if (ctx.playlistRel != null) {
                SheetItem(Icons39.Remove, "Usuń z tej playlisty") {
                    g.scope.launch(Dispatchers.IO) { lib.removeFromPlaylist(ctx.playlistRel, rows) }
                    onDismiss()
                }
            }
            if (rows.size == 1) {
                SheetItem(Icons39.Info, "Informacje o utworze (VocaDB)") { info = true }
                val nodes = rememberLoad(r.id) { lib.nodesOfTrack(r.id).filter { it.kind in setOf("vocap", "vocal", "variant", "album", "folder", "root") } }
                nodes?.sortedBy { listOf("vocap", "vocal", "variant", "album", "folder", "root").indexOf(it.kind) }?.take(6)?.forEach { n ->
                    SheetItem(Icons39.OpenIn, "Przejdź: " + lib.nodePathNames(n).joinToString(" › ") { it.name }) {
                        Nav.go(Route.Node(n.id)); onDismiss()
                    }
                }
            }
            val local = g.files.find(r.rel)
            if (rows.size == 1 && local?.source == "dl") {
                SheetItem(Icons39.Remove, "Usuń pobrany plik z telefonu") {
                    g.scope.launch(Dispatchers.IO) { g.files.removeDownload(r.rel); g.files.recomputeAvailable(lib) }
                    onDismiss()
                }
            }
        }
    }
    if (addToPl) AddToPlaylistDialog(rows) { addToPl = false; onDismiss() }
    if (info) TrackInfoSheet(r) { info = false; onDismiss() }
}

@Composable
fun SheetItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = C39.Text2)
        Spacer(Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AddToPlaylistDialog(rows: List<TrackRow>, onDone: () -> Unit) {
    val g = App.graph
    val lib = g.library
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val lists = rememberLoad("pl") { lib.playlists().filter { it.kind == "pl" || it.kind == "local" } }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Dodaj do playlisty") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(newName, { newName = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Nowa playlista") }, singleLine = true)
                if (error != null) Text(error!!, color = C39.Warn, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                lists?.forEach { p ->
                    Text(
                        (if (p.folder.isNotEmpty()) p.folder + " / " else "") + p.name + "  (${p.count})",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                            scope.launch(Dispatchers.IO) { lib.addToPlaylist(p.rel, rows) }
                            onDone()
                        }.padding(vertical = 10.dp, horizontal = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isBlank()) return@TextButton
                scope.launch {
                    val res = withContext(Dispatchers.IO) { runCatching { lib.createPlaylist(newName).also { lib.addToPlaylist(it, rows) } } }
                    res.onSuccess { onDone() }.onFailure { error = it.message }
                }
            }) { Text("Utwórz") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Anuluj") } },
    )
}

@Composable
fun TrackInfoSheet(r: TrackRow, onDismiss: () -> Unit) {
    val g = App.graph
    val lib = g.library
    val vdb = rememberLoad(r.id) { lib.vdb(r.id) }
    val none = rememberLoad("none", r.id) { lib.vdbNone(r.id) } ?: false
    val local = g.files.find(r.rel)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C39.Surface) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art(r.artKey, r.rel, 96.dp, corner = 10.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    Text(r.artist, color = C39.Text2)
                    Text(r.album, color = C39.Text3, fontSize = 13.sp)
                }
            }
            InfoSection("Plik")
            InfoLine("Ścieżka", r.rel)
            InfoLine("Długość", T.duration(r.duration))
            InfoLine("Rozmiar", T.size(r.size))
            if (r.year > 0) InfoLine("Rok", r.year.toString())
            if (!r.genre.isNullOrEmpty()) InfoLine("Gatunek", r.genre)
            if (!r.composer.isNullOrEmpty()) InfoLine("Kompozytor", r.composer)
            if (!r.albumArtist.isNullOrEmpty()) InfoLine("Wykonawca albumu", r.albumArtist)
            InfoLine("Odtworzenia", lib.playCount(r).toString())
            InfoLine("Na telefonie", when (local?.source) { "dl" -> "pobrany"; "sd" -> "kopia na karcie"; else -> "nie (strumień z NAS)" })
            InfoSection("VocaDB")
            when {
                vdb == null && none -> Text("Potwierdzony brak wpisu w VocaDB", color = C39.Text2)
                vdb == null -> Text("Utwór nie jest podpięty do VocaDB (podpinanie – w 39player na PC).", color = C39.Text2)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vdb.songId?.let { Pill("utwór ${T.vdbId(it)}", C39.Miku) }
                        vdb.albumId?.let { Pill("album ${T.vdbId(it)}", C39.Miku) }
                        if (vdb.songType.isNotEmpty()) Pill(vdb.songType, C39.Gold)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (vdb.nameJa.isNotEmpty()) InfoLine("Tytuł (japoński)", vdb.nameJa)
                    if (vdb.nameRomaji.isNotEmpty()) InfoLine("Romaji", vdb.nameRomaji)
                    if (vdb.nameEn.isNotEmpty()) InfoLine("Angielski", vdb.nameEn)
                    val album = listOf(vdb.albumEn, vdb.albumRomaji, vdb.albumJa).firstOrNull { it.isNotEmpty() }
                    if (album != null) InfoLine("Album (VocaDB)", album)
                    if (vdb.publishDate.isNotEmpty()) InfoLine("Publikacja", vdb.publishDate)
                    if (vdb.issue.isNotEmpty()) InfoLine("Niezgodność", vdb.issue, C39.Warn)
                    if (vdb.artists.isNotEmpty()) {
                        InfoSection("Artyści")
                        vdb.artists.forEach { (name, roles) -> InfoLine(roles.joinToString(", ").ifEmpty { "inne" }, name) }
                    }
                    if (vdb.tags.isNotEmpty()) {
                        InfoSection("Tagi VocaDB")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            vdb.tags.forEach { t ->
                                Pill(t.name, C39.TagColors[t.category] ?: C39.Text2, onClick = { Nav.go(Route.Tag(t.id)); onDismiss() })
                            }
                        }
                    } else if (!vdb.tagsFetched) {
                        Text("Tagi nie zostały jeszcze pobrane (39player → VocaDB → Pobierz tagi).", color = C39.Text3, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSection(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text.uppercase(), color = LocalAccent.current, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun InfoLine(label: String, value: String, color: Color = C39.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.width(130.dp), color = C39.Text3, fontSize = 13.sp)
        Text(value, color = color, fontSize = 13.sp)
    }
}
