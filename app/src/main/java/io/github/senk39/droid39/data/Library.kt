package io.github.senk39.droid39.data

import android.content.Context
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.core.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Biblioteka: migawka z PC (tylko odczyt) + nakładka zmian zrobionych na telefonie (serduszka, oznaczenia
 * „Do odsłuchania”, odtworzenia, playlisty). Zmiany trafiają do dziennika (LocalDb.op), który SyncManager wysyła
 * na NAS; gdy nowa migawka potwierdzi ich zastosowanie na PC, nakładka je porzuca.
 */
class Library(
    context: Context,
    private val settings: Settings,
    val local: LocalDb,
    /** Folder migawki – w miejscu na dane wybranym w ustawieniach (karta SD albo pamięć wewnętrzna). */
    private val snapDir: () -> File = { File(context.filesDir, "snapshot").apply { mkdirs() } },
) {
    val snapFile: File get() = File(snapDir(), "library.db")

    fun snapshotDir(): File = snapDir()
    private val lock = ReentrantReadWriteLock()
    @Volatile private var snapshot: SnapshotDb? = null

    private val _snapVersion = MutableStateFlow(0)
    val snapVersion: StateFlow<Int> = _snapVersion
    private val _overlay = MutableStateFlow(0)
    val overlayVersion: StateFlow<Int> = _overlay
    @Volatile var openError: String? = null
        private set

    /** Wołane po każdej nowej zmianie lokalnej (SyncManager wysyła dziennik z opóźnieniem). */
    var onLocalChange: (() -> Unit)? = null

    @Volatile private var heartOv: Map<Int, Int> = emptyMap()
    @Volatile private var markOv: Map<Int, Int> = emptyMap()
    @Volatile private var playOv: Map<Int, Int> = emptyMap()
    @Volatile private var lastPlayOv: Map<Int, Long> = emptyMap()
    @Volatile private var plOps: List<LocalDb.Op> = emptyList()
    @Volatile private var unlistenedOrder: List<Int> = emptyList()
    @Volatile private var unlistenedMarks: Map<Int, Marks> = emptyMap()
    @Volatile private var llIndex: Map<Int, LlTrack>? = null
    @Volatile private var llRosterCache: LlRoster? = null
    @Volatile private var franchiseCache: List<Franchise>? = null

    val ready: Boolean get() = snapshot != null

    fun <T> withSnap(block: (SnapshotDb) -> T): T? = lock.read { snapshot?.let(block) }

    // ---------------------------------------------------------------- migawka
    fun open() {
        lock.write {
            if (snapshot == null && snapFile.exists()) {
                snapshot = try {
                    SnapshotDb.open(snapFile).also { openError = null }
                } catch (e: Exception) {
                    openError = e.message
                    null
                }
            }
        }
        afterSnapshotChange()
    }

    /** Zamyka migawkę (np. przed przeniesieniem na inny wolumin); `open()` otwiera ją z bieżącego miejsca. */
    fun close() {
        lock.write {
            snapshot?.close()
            snapshot = null
        }
        afterSnapshotChange()
    }

    /** Odtworzenia (z PC i z telefonu) wg ścieżki: klucz ścieżki → (liczba, ostatnie odtworzenie w s). */
    fun playStats(): Map<String, Pair<Int, Double>> = withSnap { s ->
        val plays = playOv
        val last = lastPlayOv
        s.fileStats().associate { f ->
            Text.relKey(f.rel) to ((f.plays + (plays[f.id] ?: 0)) to maxOf(f.lastPlayed, (last[f.id] ?: 0L) / 1000.0))
        }
    } ?: emptyMap()

    /** Podmienia migawkę na nowo pobraną (plik `fresh` zostaje przeniesiony). */
    fun install(fresh: File) {
        SnapshotDb.open(fresh).close()                            // sprawdzenie formatu przed podmianą
        lock.write {
            snapshot?.close()
            snapshot = null
            File(snapFile.path + "-journal").delete()
            if (!fresh.renameTo(snapFile)) {
                fresh.copyTo(snapFile, overwrite = true)
                fresh.delete()
            }
            snapshot = SnapshotDb.open(snapFile)
            openError = null
        }
        afterSnapshotChange()
    }

    private fun afterSnapshotChange() {
        llIndex = null
        llRosterCache = null
        franchiseCache = null
        val s = snapshot
        if (s != null) {
            // zmiany potwierdzone przez PC (zawarte w tej migawce) przestają być nakładane
            val acked = s.applied[settings.deviceId] ?: 0L
            val upTo = minOf(acked, settings.uploadedSeq)
            if (upTo > 0) local.dropOpsUpTo(upTo)
            val unl = s.unlistened()
            unlistenedOrder = unl.map { it.first }
            unlistenedMarks = unl.toMap()
        } else {
            unlistenedOrder = emptyList()
            unlistenedMarks = emptyMap()
        }
        reloadOverlay()
        _snapVersion.value++
    }

    @Synchronized
    fun reloadOverlay() {
        val ops = local.ops()
        val hearts = HashMap<Int, Int>()
        val marks = HashMap<Int, Int>()
        val plays = HashMap<Int, Int>()
        val last = HashMap<Int, Long>()
        val pl = ArrayList<LocalDb.Op>()
        for (op in ops) {
            val tid = op.trackId
            when (op.type) {
                "heart" -> if (tid != null) hearts[tid] = op.value
                "mark" -> if (tid != null) marks[tid] = op.value
                "play" -> if (tid != null) {
                    if (op.extra == "1") plays[tid] = (plays[tid] ?: 0) + 1
                    last[tid] = maxOf(last[tid] ?: 0L, op.ts)
                }
                "pl_add", "pl_remove", "pl_create", "pl_rename", "pl_delete", "pl_move" -> pl += op
            }
        }
        heartOv = hearts; markOv = marks; playOv = plays; lastPlayOv = last; plOps = pl
        _overlay.value++
    }

    private fun changed() {
        reloadOverlay()
        onLocalChange?.invoke()
    }

    val pendingOps: Int get() = local.ops(settings.uploadedSeq).size

    // ---------------------------------------------------------------- stan efektywny
    fun heart(row: TrackRow): Int = heartOv[row.id] ?: row.snapHeart
    fun inUnlistened(id: Int): Boolean = unlistenedMarks.containsKey(id)
    fun marks(id: Int): Marks? = markOv[id]?.let { Marks.of(it) } ?: unlistenedMarks[id]
    fun playCount(row: TrackRow): Int = row.playCount + (playOv[row.id] ?: 0)

    /** Serduszko jak w widoku: na liście „Do odsłuchania” – z oznaczeń (zapisuje się po Zatwierdź na PC). */
    fun displayHeart(row: TrackRow, unlistenedView: Boolean): Int =
        if (unlistenedView && inUnlistened(row.id)) marks(row.id)?.heart ?: 0 else heart(row)

    // ---------------------------------------------------------------- zmiany z telefonu
    fun setHeart(rows: List<TrackRow>, level: Int, unlistenedView: Boolean) {
        for (r in rows) {
            if (unlistenedView && inUnlistened(r.id)) {
                val m = marks(r.id) ?: Marks(false, false, false)
                setMarks(r, Marks(level == 2, level >= 1, m.listened), notify = false)
            } else {
                local.addOp("heart", r.id, r.rel, null, level.coerceIn(0, 2))
                // jak set_heart(immediate=True) w 39player: utwór z listy „Do odsłuchania” dostaje też oznaczenie
                if (inUnlistened(r.id)) {
                    val m = marks(r.id) ?: Marks(false, false, false)
                    local.addOp("mark", r.id, r.rel, null, Marks(level == 2, level >= 1, m.listened).bits)
                }
            }
        }
        changed()
    }

    /** Ostatni numer zmiany potwierdzony przez PC (z bieżącej migawki). */
    fun ackedSeq(): Long = withSnap { it.applied[settings.deviceId] } ?: 0L

    fun cycleHeart(row: TrackRow, unlistenedView: Boolean): Int {
        val next = (displayHeart(row, unlistenedView) + 1) % 3
        setHeart(listOf(row), next, unlistenedView)
        return next
    }

    fun setMarks(row: TrackRow, m: Marks, notify: Boolean = true) {
        if (!inUnlistened(row.id)) return
        local.addOp("mark", row.id, row.rel, null, m.bits)
        if (notify) changed()
    }

    /** Odtworzenie: `counted` – wysłuchano min. 50% (licznik odtworzeń jak w 39player). */
    fun recordPlay(id: Int, rel: String, playedMs: Long, counted: Boolean) {
        local.addOp("play", id, rel, null, playedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), if (counted) "1" else "0")
        changed()
    }

    /** „Przesłuchane” po wysłuchaniu 51% utworu z listy „Do odsłuchania”. */
    fun markListenedAuto(row: TrackRow): Boolean {
        val m = marks(row.id) ?: return false
        if (m.listened) return false
        setMarks(row, m.copy(listened = true))
        return true
    }

    fun addToPlaylist(rel: String, rows: List<TrackRow>): Int {
        val have = playlistTrackIds(rel).toHashSet()
        var n = 0
        for (r in rows) {
            if (r.id in have) continue
            have += r.id
            local.addOp("pl_add", r.id, r.rel, rel, 0)
            n++
        }
        if (n > 0) changed()
        return n
    }

    fun removeFromPlaylist(rel: String, rows: List<TrackRow>) {
        for (r in rows) local.addOp("pl_remove", r.id, r.rel, rel, 0)
        changed()
    }

    /** Nazwa pliku playlisty jak w 39player (Library.clean_name): bez znaków niedozwolonych w Windows. */
    private fun cleanPlaylistName(name: String): String =
        name.map { if (it in "<>:\"/\\|?*" || it.code < 32) '_' else it }.joinToString("").trim().trimEnd('.')

    fun createPlaylist(name: String, folder: String = ""): String {
        val clean = cleanPlaylistName(name)
        require(clean.isNotEmpty()) { "Podaj nazwę playlisty." }
        val file = if (clean.endsWith(".m3u8", true) || clean.endsWith(".m3u", true)) clean else "$clean.m3u8"
        val rel = if (folder.isNotEmpty()) "$folder/$file" else file
        require(playlists().none { it.rel.equals(rel, true) }) { "Playlista „$clean” już istnieje." }
        local.addOp("pl_create", null, null, rel, 0)
        changed()
        return rel
    }

    /** Zmiana nazwy playlisty (w tym samym folderze). Zwraca nową ścieżkę względną. */
    fun renamePlaylist(rel: String, newName: String): String {
        val clean = cleanPlaylistName(newName)
        require(clean.isNotEmpty()) { "Podaj nazwę playlisty." }
        val ext = "." + rel.substringAfterLast('.', "m3u8")
        val file = if (clean.endsWith(".m3u8", true) || clean.endsWith(".m3u", true)) clean else clean + ext
        val folder = rel.substringBeforeLast('/', "")
        val newRel = if (folder.isNotEmpty()) "$folder/$file" else file
        if (newRel == rel) return rel
        require(newRel.equals(rel, true) || playlists(includeHidden = true).none { it.rel.equals(newRel, true) }) {
            "Playlista „${file.substringBeforeLast('.')}” już istnieje."
        }
        local.addOp("pl_rename", null, null, rel, 0, newRel)
        changed()
        return newRel
    }

    /** Usunięcie playlisty (na PC trafia do Kosza). */
    fun deletePlaylist(rel: String) {
        local.addOp("pl_delete", null, null, rel, 0)
        changed()
    }

    /** Przeniesienie utworu w playliście przed [before] (null – na koniec). */
    fun movePlaylistTrack(rel: String, row: TrackRow, before: TrackRow?) {
        if (before?.id == row.id) return
        local.addOp("pl_move", row.id, row.rel, rel, before?.id ?: 0, before?.rel)
        changed()
    }

    /** Playlista po zmianach z telefonu: [origin] – ścieżka w migawce z PC (null – utworzona na telefonie). */
    private class PlState(val origin: String?, var rel: String) {
        val ops = ArrayList<LocalDb.Op>()
    }

    /** Playlisty po nałożeniu zmian z telefonu (zmiany nazw, usunięcia, nowe), klucz – bieżąca ścieżka małymi literami. */
    private fun plStates(base: List<PlaylistRow>): LinkedHashMap<String, PlState> {
        val map = LinkedHashMap<String, PlState>()
        for (p in base) if (p.kind != "dir") map[p.rel.lowercase()] = PlState(p.rel, p.rel)
        for (op in plOps) {
            val pl = op.playlist ?: continue
            val k = pl.lowercase()
            when (op.type) {
                "pl_create" -> if (k !in map) map[k] = PlState(null, pl)
                "pl_rename" -> {
                    val st = map.remove(k) ?: continue
                    st.rel = op.extra ?: continue
                    map[st.rel.lowercase()] = st
                }
                "pl_delete" -> map.remove(k)
                else -> map[k]?.ops?.add(op)
            }
        }
        return map
    }

    // ---------------------------------------------------------------- playlisty (z nakładką)
    fun playlists(includeHidden: Boolean = false): List<PlaylistRow> {
        val base = withSnap { it.playlists } ?: emptyList()
        val states = plStates(base)
        val byOrigin = states.values.filter { it.origin != null }.associateBy { it.origin!!.lowercase() }
        val out = ArrayList<PlaylistRow>()
        for (p in base) {
            if (!includeHidden && p.hidden) continue
            if (p.kind == "dir") { out += p; continue }
            val st = byOrigin[p.rel.lowercase()] ?: continue                     // usunięta na telefonie
            out += if (st.rel == p.rel && st.ops.isEmpty()) p
            else p.copy(rel = st.rel, name = st.rel.substringAfterLast('/').substringBeforeLast('.'),
                count = playlistTrackIds(st.rel).size)
        }
        var fake = -1
        for (st in states.values) {
            if (st.origin != null) continue
            out += PlaylistRow(fake--, st.rel, st.rel.substringAfterLast('/').substringBeforeLast('.'),
                st.rel.substringBeforeLast('/', ""), "local", false, playlistTrackIds(st.rel).size, 0)
        }
        return out
    }

    /** Playlistę można zmieniać na telefonie (nie systemowa: Najlepsze, Polubione, Do odsłuchania). */
    fun playlistEditable(rel: String): Boolean =
        playlists(includeHidden = true).firstOrNull { it.rel.equals(rel, true) }?.kind.let { it == "pl" || it == "local" }

    fun playlistByKind(kind: String): PlaylistRow? = withSnap { s -> s.playlists.firstOrNull { it.kind == kind } }

    fun playlistTrackIds(rel: String): List<Int> {
        val all = withSnap { it.playlists } ?: emptyList()
        val st = plStates(all)[rel.lowercase()] ?: return emptyList()
        val list = ArrayList<Int>(withSnap { s ->
            st.origin?.let { o -> s.playlists.firstOrNull { it.rel.equals(o, true) && it.kind != "dir" } }?.let { s.playlistTrackIds(it.id) }
        } ?: emptyList())
        for (op in st.ops) {
            val tid = op.trackId ?: continue
            when (op.type) {
                "pl_add" -> if (tid !in list) list.add(tid)
                "pl_remove" -> list.remove(tid)
                "pl_move" -> if (list.remove(tid)) {
                    val at = if (op.value > 0) list.indexOf(op.value) else -1
                    if (at >= 0) list.add(at, tid) else list.add(tid)
                }
            }
        }
        return list
    }

    fun playlistTracks(rel: String): List<TrackRow> = withSnap { it.tracksByIds(playlistTrackIds(rel)) } ?: emptyList()

    /** Najlepsze (♥♥): _NAJLEPSZE z PC + zmiany serduszek na telefonie. */
    fun bestTracks(): List<TrackRow> {
        val ids = LinkedHashSet<Int>()
        withSnap { s -> s.playlists.firstOrNull { it.kind == "best" }?.let { ids.addAll(s.playlistTrackIds(it.id)) } }
        applyHearts(ids) { it == 2 }
        return withSnap { it.tracksByIds(ids.toList()) } ?: emptyList()
    }

    /** Polubione (♥): _REKOMENDACJE Z PC ∪ _REKOMENDACJE Z TEL (AUTO) + zmiany na telefonie. */
    fun likedTracks(): List<TrackRow> {
        val ids = LinkedHashSet<Int>()
        withSnap { s ->
            for (k in listOf("liked", "liked_tel")) s.playlists.firstOrNull { it.kind == k }?.let { ids.addAll(s.playlistTrackIds(it.id)) }
        }
        applyHearts(ids) { it >= 1 }
        return withSnap { it.tracksByIds(ids.toList()) } ?: emptyList()
    }

    private fun applyHearts(ids: LinkedHashSet<Int>, keep: (Int) -> Boolean) {
        for ((id, level) in heartOv) {
            if (keep(level)) ids.add(id) else ids.remove(id)
        }
    }

    fun unlistenedTracks(): List<TrackRow> = withSnap { it.tracksByIds(unlistenedOrder) } ?: emptyList()
    val unlistenedCount: Int get() = unlistenedOrder.size

    // ---------------------------------------------------------------- kategorie i inne widoki
    fun roots(): List<NodeRow> = withSnap { it.roots } ?: emptyList()
    fun node(id: Int): NodeRow? = withSnap { it.nodes[id] }
    fun nodeByPath(path: String): NodeRow? = withSnap { it.nodeByPath[path] }
    fun children(id: Int): List<NodeRow> = withSnap { it.children(id) } ?: emptyList()

    fun nodeTracks(node: NodeRow): List<TrackRow> = withSnap { s ->
        if (node.hasTracks) s.nodeTracks(node.id) else s.tracksByIds(s.aggregateTrackIds(node.id))
    } ?: emptyList()

    fun nodeTrackIds(node: NodeRow): List<Int> = withSnap { s ->
        if (node.hasTracks) s.nodeTrackIds(node.id) else s.aggregateTrackIds(node.id)
    } ?: emptyList()

    fun nodePathNames(node: NodeRow): List<NodeRow> {
        val out = ArrayList<NodeRow>()
        var n: NodeRow? = node
        while (n != null) {
            out.add(0, n)
            n = n.parent?.let { node(it) }
        }
        return out
    }

    fun tracks(ids: List<Int>): List<TrackRow> = withSnap { it.tracksByIds(ids) } ?: emptyList()
    fun track(id: Int): TrackRow? = withSnap { it.track(id) }

    fun recentlyAdded(): List<TrackRow> = withSnap { s ->
        val days = s.info["recent_days"]?.toIntOrNull() ?: 60
        val limit = System.currentTimeMillis() / 1000.0 - days * 86400.0
        val rows = s.query("t.date_added >= ?", arrayOf(limit.toString()), "t.date_added DESC, t.rel")
        if (rows.size >= 100) rows else s.query("", emptyArray(), "t.date_added DESC, t.rel", 300)
    } ?: emptyList()

    fun recentlyPlayed(): List<TrackRow> {
        val localIds = lastPlayOv.entries.sortedByDescending { it.value }.map { it.key }
        val snapRows = withSnap { it.query("t.last_played > 0", emptyArray(), "t.last_played DESC", 500) } ?: emptyList()
        val ids = LinkedHashSet(localIds)
        snapRows.forEach { ids.add(it.id) }
        return tracks(ids.toList().take(500))
    }

    fun allTracks(): List<TrackRow> = withSnap { it.query("", emptyArray(), "t.rel") } ?: emptyList()

    /** Folder biblioteki: podfoldery (nazwa, liczba utworów w poddrzewie) i utwory bezpośrednio w nim. */
    fun folderListing(dir: String): FolderListing? = withSnap { s ->
        val (subs, ids) = s.folderListing(dir)
        FolderListing(subs.map { (name, n) -> FolderEntry(name, if (dir.isEmpty()) name else "$dir/$name", n) }, s.tracksByIds(ids))
    }

    /** Wszystkie utwory folderu z podfolderami. */
    fun folderTracks(dir: String): List<TrackRow> = withSnap { it.folderTracks(dir) } ?: emptyList()

    /** Utwory albumu (klucz: folder albumu względem folderu muzyki, małymi literami). */
    fun albumTracks(albumKey: String): List<TrackRow> =
        withSnap { it.query("t.album_key = ?", arrayOf(albumKey), "t.disc_no, t.track_no, t.rel") } ?: emptyList()

    fun localTracks(ids: Set<Int>): List<TrackRow> = withSnap { s -> s.tracksByIds(ids.toList()).sortedBy { it.rel } } ?: emptyList()

    fun search(q: String, limit: Int = 400): List<TrackRow> = withSnap { it.search(Text.queryTokens(q), limit) } ?: emptyList()

    fun searchNodes(q: String, limit: Int = 60): List<NodeRow> {
        val tokens = Text.queryTokens(q)
        if (tokens.isEmpty()) return emptyList()
        return withSnap { s ->
            s.nodes.values.asSequence()
                .filter { n -> n.kind != "vocal_section" && Text.sortKey(n.name).let { k -> tokens.all { k.contains(it) } } }
                .sortedWith(compareBy<NodeRow>({ kindRank(it.kind) }, { -it.count }))
                .take(limit).toList()
        } ?: emptyList()
    }

    fun searchTags(q: String): List<TagRow> {
        val tokens = Text.queryTokens(q)
        if (tokens.isEmpty()) return emptyList()
        return withSnap { s ->
            s.tags.values.filter { t -> Text.sortKey(t.name).let { k -> tokens.all { k.contains(it) } } }.sortedByDescending { it.count }.take(30)
        } ?: emptyList()
    }

    private fun kindRank(kind: String) = when (kind) {
        "vocap" -> 0; "vocal" -> 1; "variant" -> 2; "root", "folder" -> 3; "album" -> 4; else -> 5
    }

    fun tags(): List<TagRow> = withSnap { s -> s.tags.values.sortedWith(compareBy({ SnapshotDb.tagCategoryRank(it.category) }, { -it.count })) } ?: emptyList()
    fun tag(id: Long): TagRow? = withSnap { it.tags[id] }
    fun tagTracks(id: Long): List<TrackRow> = withSnap { s -> s.tracksByIds(s.tagTrackIds(id)) } ?: emptyList()
    fun vdb(trackId: Int): VdbInfo? = withSnap { it.vdb(trackId) }
    fun vdbNone(trackId: Int): Boolean = withSnap { trackId in it.vdbNone } ?: false

    // ---------------------------------------------------------------- wiki piosenek, kategorie bez VocaDB
    fun wikiLinks(trackId: Int): List<WikiLink> = withSnap { it.wikiLinks(trackId) } ?: emptyList()
    fun wikiPage(src: String, page: String): WikiPage? = withSnap { it.wikiPage(src, page) }

    /** Węzeł w kategorii bez VocaDB (jak w 39player: listy bez kolumn VocaDB). */
    fun noVdbPath(path: String): Boolean = withSnap { s -> path.substringBefore(s.sep) in s.noVdbRoots } ?: false
    fun stars(): List<Star> = withSnap { it.stars } ?: emptyList()
    fun nodesOfTrack(trackId: Int): List<NodeRow> = withSnap { it.nodesOfTrack(trackId) } ?: emptyList()
    fun commits() = withSnap { it.recentCommits() } ?: emptyList()

    fun franchises(): List<Franchise> = franchiseCache ?: (withSnap { it.franchises() } ?: emptyList()).also { franchiseCache = it }

    fun llIndex(): Map<Int, LlTrack> = llIndex ?: (withSnap { it.llTracks() } ?: emptyMap()).also { llIndex = it }
    fun llRoster(): LlRoster? = llRosterCache ?: withSnap { it.llRoster() }?.also { llRosterCache = it }

    /** Czy węzeł to Love Live! albo jego podkategoria; zwraca (węzeł LL, grupa z folderu). */
    fun llContext(node: NodeRow): Pair<NodeRow, String>? {
        val roster = llRoster() ?: return null
        val llPath = roster.nodePath ?: return null
        val sep = withSnap { it.sep } ?: "\u001f"
        if (node.path != llPath && !node.path.startsWith(llPath + sep)) return null
        val ll = nodeByPath(llPath) ?: return null
        val group = roster.folders.entries.firstOrNull { node.path == it.key || node.path.startsWith(it.key + sep) }?.value ?: ""
        return ll to group
    }

    fun info(key: String): String? = withSnap { it.info[key] }
    val sep: String get() = withSnap { it.sep } ?: "\u001f"
}
