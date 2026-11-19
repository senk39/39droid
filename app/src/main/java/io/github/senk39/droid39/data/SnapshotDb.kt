package io.github.senk39.droid39.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/** Migawka biblioteki z PC (sync39) – tylko do odczytu. Małe tabele (węzły, playlisty, tagi) trzymane w pamięci. */
class SnapshotDb private constructor(private val db: SQLiteDatabase, val file: File) : Closeable {

    val info: Map<String, String>
    val nodes: Map<Int, NodeRow>
    val roots: List<NodeRow>
    private val childrenOf: Map<Int, List<NodeRow>>
    val nodeByPath: Map<String, NodeRow>
    val playlists: List<PlaylistRow>
    val tags: Map<Long, TagRow>
    val vdbNone: Set<Int>
    val stars: List<Star>
    val applied: Map<String, Long>
    val sep: String

    init {
        info = HashMap<String, String>().also { m ->
            db.rawQuery("SELECT key, value FROM info", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) ?: "" }
        }
        sep = info["sep"] ?: "\u001f"
        val all = ArrayList<NodeRow>()
        db.rawQuery(
            "SELECT id, parent, pos, path, name, kind, count, likes, has_tracks, vdb_artist_id, vdb_artist_name, " +
                "vdb_artist_type, engines, art FROM node ORDER BY parent, pos", null
        ).use { c ->
            while (c.moveToNext()) {
                all += NodeRow(
                    id = c.getInt(0), parent = if (c.isNull(1)) null else c.getInt(1), pos = c.getInt(2),
                    path = c.getString(3), name = c.getString(4), kind = c.getString(5), count = c.getInt(6),
                    likes = c.getInt(7), hasTracks = c.getInt(8) != 0,
                    vdbArtistId = if (c.isNull(9)) null else c.getLong(9), vdbArtistName = c.getString(10),
                    vdbArtistType = c.getString(11),
                    engines = c.getString(12)?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
                    art = c.getString(13),
                )
            }
        }
        nodes = all.associateBy { it.id }
        nodeByPath = all.associateBy { it.path }
        childrenOf = all.filter { it.parent != null }.groupBy { it.parent!! }.mapValues { e -> e.value.sortedBy { it.pos } }
        roots = all.filter { it.parent == null }.sortedBy { it.pos }
        playlists = ArrayList<PlaylistRow>().also { l ->
            db.rawQuery("SELECT id, rel, name, folder, kind, hidden, count, missing FROM playlist ORDER BY pos", null).use { c ->
                while (c.moveToNext()) l += PlaylistRow(
                    c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getInt(5) != 0, c.getInt(6), c.getInt(7)
                )
            }
        }
        tags = HashMap<Long, TagRow>().also { m ->
            db.rawQuery("SELECT id, name, category, count, likes FROM tag", null).use { c ->
                while (c.moveToNext()) m[c.getLong(0)] = TagRow(c.getLong(0), c.getString(1), c.getString(2) ?: "", c.getInt(3), c.getInt(4))
            }
        }
        vdbNone = HashSet<Int>().also { s -> db.rawQuery("SELECT track_id FROM vdb_none", null).use { c -> while (c.moveToNext()) s += c.getInt(0) } }
        stars = ArrayList<Star>().also { l ->
            db.rawQuery("SELECT kind, ident, name FROM star ORDER BY kind, pos", null).use { c ->
                while (c.moveToNext()) l += Star(c.getString(0), c.getString(1), c.getString(2) ?: "")
            }
        }
        applied = HashMap<String, Long>().also { m ->
            db.rawQuery("SELECT device, seq FROM applied", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getLong(1) }
        }
    }

    val trackCount: Int get() = info["tracks"]?.toIntOrNull() ?: 0
    val createdAt: Double get() = info["created"]?.toDoubleOrNull() ?: 0.0

    fun children(nodeId: Int): List<NodeRow> = childrenOf[nodeId] ?: emptyList()

    // ---------------------------------------------------------------- utwory
    private fun row(c: Cursor) = TrackRow(
        id = c.getInt(0), rel = c.getString(1), title = c.getString(2) ?: "", artist = c.getString(3) ?: "",
        album = c.getString(4) ?: "", albumArtist = c.getString(5), duration = c.getDouble(6), size = c.getLong(7),
        snapHeart = c.getInt(8), artKey = c.getString(9), trackNo = c.getInt(10), discNo = c.getInt(11),
        year = c.getInt(12), playCount = c.getInt(13), dateAdded = c.getDouble(14), lastPlayed = c.getDouble(15),
        songId = if (c.isNull(16)) null else c.getLong(16), songType = c.getString(17),
        vdbIssue = !c.getString(18).isNullOrEmpty(), genre = c.getString(19), composer = c.getString(20),
    )

    private val cols = "t.id, t.rel, t.title, t.artist, t.album, t.album_artist, t.duration, t.size, t.heart, t.art, " +
        "COALESCE(t.track_no,0), COALESCE(t.disc_no,0), COALESCE(t.year,0), COALESCE(t.play_count,0), " +
        "COALESCE(t.date_added,0), COALESCE(t.last_played,0), v.song_id, v.song_type, v.issue, t.genre, t.composer"
    private val from = "FROM track t LEFT JOIN vdb v ON v.track_id = t.id"

    fun query(where: String, args: Array<String>, order: String = "", limit: Int = 0): List<TrackRow> {
        val sql = buildString {
            append("SELECT ").append(cols).append(' ').append(from)
            if (where.isNotEmpty()) append(" WHERE ").append(where)
            if (order.isNotEmpty()) append(" ORDER BY ").append(order)
            if (limit > 0) append(" LIMIT ").append(limit)
        }
        val out = ArrayList<TrackRow>()
        db.rawQuery(sql, args).use { c -> while (c.moveToNext()) out += row(c) }
        return out
    }

    /** Utwory w kolejności podanych ID (brakujące pomijane). */
    fun tracksByIds(ids: List<Int>): List<TrackRow> {
        if (ids.isEmpty()) return emptyList()
        val map = HashMap<Int, TrackRow>(ids.size * 2)
        ids.distinct().chunked(900).forEach { part ->
            val q = part.joinToString(",")
            db.rawQuery("SELECT $cols $from WHERE t.id IN ($q)", null).use { c -> while (c.moveToNext()) row(c).let { map[it.id] = it } }
        }
        return ids.mapNotNull { map[it] }
    }

    fun track(id: Int): TrackRow? = tracksByIds(listOf(id)).firstOrNull()

    /** Utwór po ścieżce względnej (gdy ID z innego urządzenia jest nieaktualne). */
    fun trackIdByRel(rel: String): Int? {
        for (sql in arrayOf("SELECT id FROM track WHERE rel = ? LIMIT 1", "SELECT id FROM track WHERE rel = ? COLLATE NOCASE LIMIT 1")) {
            db.rawQuery(sql, arrayOf(rel)).use { c -> if (c.moveToFirst()) return c.getInt(0) }
        }
        return null
    }

    fun nodeTrackIds(nodeId: Int): List<Int> {
        val out = ArrayList<Int>()
        db.rawQuery("SELECT track_id FROM node_track WHERE node_id = ? ORDER BY pos", arrayOf(nodeId.toString())).use { c ->
            while (c.moveToNext()) out += c.getInt(0)
        }
        return out
    }

    fun nodeTracks(nodeId: Int): List<TrackRow> {
        val out = ArrayList<TrackRow>()
        db.rawQuery(
            "SELECT $cols FROM node_track nt JOIN track t ON t.id = nt.track_id LEFT JOIN vdb v ON v.track_id = t.id " +
                "WHERE nt.node_id = ? ORDER BY nt.pos", arrayOf(nodeId.toString())
        ).use { c -> while (c.moveToNext()) out += row(c) }
        return out
    }

    /** Utwory węzła zbiorczego (np. cała grupa Wokaliści) – suma podkategorii bez powtórzeń. */
    fun aggregateTrackIds(nodeId: Int): List<Int> {
        val seen = LinkedHashSet<Int>()
        fun walk(n: NodeRow) {
            if (n.hasTracks) seen.addAll(nodeTrackIds(n.id)) else children(n.id).forEach { walk(it) }
        }
        nodes[nodeId]?.let { walk(it) }
        return seen.toList()
    }

    fun nodesOfTrack(trackId: Int): List<NodeRow> {
        val out = ArrayList<NodeRow>()
        db.rawQuery("SELECT DISTINCT node_id FROM node_track WHERE track_id = ?", arrayOf(trackId.toString())).use { c ->
            while (c.moveToNext()) nodes[c.getInt(0)]?.let { out += it }
        }
        return out
    }

    fun playlistTrackIds(playlistId: Int): List<Int> {
        val out = ArrayList<Int>()
        db.rawQuery("SELECT track_id FROM playlist_track WHERE playlist_id = ? ORDER BY pos", arrayOf(playlistId.toString())).use { c ->
            while (c.moveToNext()) out += c.getInt(0)
        }
        return out
    }

    fun playlistsOfTrack(trackId: Int): Set<Int> {
        val out = HashSet<Int>()
        db.rawQuery("SELECT DISTINCT playlist_id FROM playlist_track WHERE track_id = ?", arrayOf(trackId.toString())).use { c ->
            while (c.moveToNext()) out += c.getInt(0)
        }
        return out
    }

    /** (id, oznaczenia) listy „Do odsłuchania” w kolejności z PC. */
    fun unlistened(): List<Pair<Int, Marks>> {
        val out = ArrayList<Pair<Int, Marks>>()
        db.rawQuery("SELECT track_id, best, liked, listened FROM unlistened ORDER BY pos", null).use { c ->
            while (c.moveToNext()) out += c.getInt(0) to Marks(c.getInt(1) != 0, c.getInt(2) != 0, c.getInt(3) != 0)
        }
        return out
    }

    fun tagTrackIds(tagId: Long): List<Int> {
        val out = ArrayList<Int>()
        db.rawQuery("SELECT track_id FROM track_tag WHERE tag_id = ?", arrayOf(tagId.toString())).use { c ->
            while (c.moveToNext()) out += c.getInt(0)
        }
        return out
    }

    fun search(tokens: List<String>, limit: Int): List<TrackRow> {
        if (tokens.isEmpty()) return emptyList()
        val where = tokens.joinToString(" AND ") { "instr(t.search, ?) > 0" }
        return query(where, tokens.toTypedArray(), "t.sort_title", limit)
    }

    /**
     * Zawartość folderu biblioteki `dir` („” – folder główny): podfoldery z liczbą utworów w całym poddrzewie
     * i ID utworów leżących bezpośrednio w folderze (w kolejności nazw plików).
     */
    fun folderListing(dir: String): Pair<List<Pair<String, Int>>, List<Int>> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val subs = HashMap<String, Int>()
        val direct = ArrayList<Pair<String, Int>>()
        val sql = if (prefix.isEmpty()) "SELECT id, rel FROM track"
        else "SELECT id, rel FROM track WHERE substr(rel, 1, ${prefix.length}) = ?"
        db.rawQuery(sql, if (prefix.isEmpty()) null else arrayOf(prefix)).use { c ->
            while (c.moveToNext()) {
                val rest = c.getString(1).substring(prefix.length)
                val i = rest.indexOf('/')
                if (i < 0) direct += rest to c.getInt(0) else subs.merge(rest.substring(0, i), 1, Int::plus)
            }
        }
        val folders = subs.entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key }).map { it.key to it.value }
        return folders to direct.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
    }

    /** Wszystkie utwory w folderze i jego podfolderach (kolejność ścieżek). */
    fun folderTracks(dir: String): List<TrackRow> =
        if (dir.isEmpty()) query("", emptyArray(), "t.rel")
        else query("substr(t.rel, 1, ${dir.length + 1}) = ?", arrayOf("$dir/"), "t.rel")

    /** Ścieżki utworów z daną okładką (do wyciągnięcia jej z pliku na telefonie). */
    fun relsForArt(artKey: String, limit: Int = 8): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT rel FROM track WHERE art = ? LIMIT $limit", arrayOf(artKey)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** Odtworzenia wg ścieżki: (id, ścieżka, rozmiar, liczba odtworzeń, ostatnie odtworzenie) – np. do zwalniania miejsca. */
    data class FileStat(val id: Int, val rel: String, val size: Long, val plays: Int, val lastPlayed: Double)

    fun fileStats(): List<FileStat> {
        val out = ArrayList<FileStat>()
        db.rawQuery("SELECT id, rel, COALESCE(size,0), COALESCE(play_count,0), COALESCE(last_played,0) FROM track", null).use { c ->
            while (c.moveToNext()) out += FileStat(c.getInt(0), c.getString(1), c.getLong(2), c.getInt(3), c.getDouble(4))
        }
        return out
    }

    fun allIdsWithSize(): List<Triple<Int, String, Long>> {
        val out = ArrayList<Triple<Int, String, Long>>()
        db.rawQuery("SELECT id, rel, COALESCE(size,0) FROM track", null).use { c ->
            while (c.moveToNext()) out += Triple(c.getInt(0), c.getString(1), c.getLong(2))
        }
        return out
    }

    private fun hasTable(name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    /** Źródła wiki (migawki sprzed wiki – puste). */
    val wikiSources: Map<String, WikiSource> by lazy {
        if (!hasTable("wiki_source")) return@lazy emptyMap()
        LinkedHashMap<String, WikiSource>().also { m ->
            db.rawQuery("SELECT id, name, site, prefix, franchise, manual_only, color FROM wiki_source ORDER BY pos", null).use { c ->
                while (c.moveToNext()) m[c.getString(0)] = WikiSource(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: "",
                    c.getInt(5) != 0, c.getString(6) ?: "#9A9AA0",
                )
            }
        }
    }

    /** Kategorie bez VocaDB (39player: Anime, Gry, Muzyka azjatycka, Touhou) – klucze węzłów najwyższego poziomu. */
    val noVdbRoots: Set<String> by lazy {
        runCatching {
            val a = JSONArray(info["no_vdb"] ?: "[]")
            (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun wikiLinks(trackId: Int): List<WikiLink> {
        if (wikiSources.isEmpty()) return emptyList()
        val out = ArrayList<WikiLink>()
        db.rawQuery("SELECT src, page, kind, album_page FROM wiki_link WHERE track_id = ?", arrayOf(trackId.toString())).use { c ->
            while (c.moveToNext()) {
                val src = wikiSources[c.getString(0)] ?: continue
                out += WikiLink(src, c.getString(1), c.getString(2), c.getString(3))
            }
        }
        val order = wikiSources.keys.toList()
        return out.sortedBy { order.indexOf(it.source.id) }
    }

    fun wikiPage(src: String, page: String): WikiPage? {
        if (!hasTable("wiki_page")) return null
        val js = db.rawQuery("SELECT data FROM wiki_page WHERE src = ? AND page = ?", arrayOf(src, page)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return null
        return runCatching { parseWikiPage(JSONObject(js), page) }.getOrNull()
    }

    private fun parseWikiPage(o: JSONObject, page: String): WikiPage {
        fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        fun pairs(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { p -> p.optString(0) to p.optString(1) } }
        } ?: emptyList()
        val lyrics = o.optJSONArray("lyrics")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { WikiLyric(it.optString("label"), it.optString("value")) }
                .filter { it.value.isNotBlank() }
        } ?: emptyList()
        return WikiPage(
            title = o.optString("title", page), url = o.optString("url"), ja = o.optString("ja"), meaning = o.optString("meaning"),
            info = pairs("info"), intro = strings("intro"), description = o.optString("description"), tracks = pairs("tracks"),
            trivia = strings("trivia"), lives = strings("lives"), lyrics = lyrics,
        )
    }

    fun vdb(trackId: Int): VdbInfo? {
        val tagsOf = ArrayList<TagRow>()
        db.rawQuery("SELECT tag_id FROM track_tag WHERE track_id = ?", arrayOf(trackId.toString())).use { c ->
            while (c.moveToNext()) tags[c.getLong(0)]?.let { tagsOf += it }
        }
        db.rawQuery(
            "SELECT song_id, album_id, song_type, name_ja, name_romaji, name_en, album_ja, album_romaji, album_en, roles, " +
                "artists, publish_date, issue, tags_fetched FROM vdb WHERE track_id = ?", arrayOf(trackId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val artists = ArrayList<Pair<String, List<String>>>()
            c.getString(10)?.let { js ->
                runCatching {
                    val arr = JSONArray(js)
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONArray(i)
                        val roles = a.getJSONArray(1)
                        artists += a.getString(0) to (0 until roles.length()).map { roles.getString(it) }
                    }
                }
            }
            return VdbInfo(
                songId = if (c.isNull(0)) null else c.getLong(0), albumId = if (c.isNull(1)) null else c.getLong(1),
                songType = c.getString(2) ?: "", nameJa = c.getString(3) ?: "", nameRomaji = c.getString(4) ?: "",
                nameEn = c.getString(5) ?: "", albumJa = c.getString(6) ?: "", albumRomaji = c.getString(7) ?: "",
                albumEn = c.getString(8) ?: "", roles = c.getString(9) ?: "", artists = artists,
                publishDate = c.getString(11) ?: "", issue = c.getString(12) ?: "", tagsFetched = c.getInt(13) != 0,
                tags = tagsOf.sortedBy { tagCategoryRank(it.category) },
            )
        }
    }

    fun llTracks(): Map<Int, LlTrack> {
        val out = HashMap<Int, LlTrack>()
        fun set(s: String?) = s?.split('|')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        db.rawQuery("SELECT track_id, groups, chars, voices, units, solo, inst, ost FROM ll_track", null).use { c ->
            while (c.moveToNext()) out[c.getInt(0)] = LlTrack(
                set(c.getString(1)), set(c.getString(2)), set(c.getString(3)), set(c.getString(4)),
                c.getInt(5) != 0, c.getInt(6) != 0, c.getInt(7) != 0
            )
        }
        return out
    }

    fun llRoster(): LlRoster {
        val groups = ArrayList<LlEntry>()
        val chars = ArrayList<LlEntry>()
        val units = ArrayList<LlEntry>()
        val folders = HashMap<String, String>()
        db.rawQuery("SELECT kind, name, grp, members, icon, count FROM ll_roster ORDER BY kind, pos", null).use { c ->
            while (c.moveToNext()) {
                val e = LlEntry(
                    c.getString(1), c.getString(2), c.getString(3)?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
                    c.getString(4), c.getInt(5)
                )
                when (c.getString(0)) {
                    "group" -> groups += e
                    "char" -> chars += e
                    "unit" -> units += e
                    "folder" -> folders[e.name] = e.group ?: ""
                }
            }
        }
        return LlRoster(groups, chars, units, folders, info["ll_node"])
    }

    fun franchises(): List<Franchise> {
        val items = HashMap<Int, MutableList<FranchiseItem>>()
        db.rawQuery("SELECT franchise_id, label, node_paths, count, art FROM franchise_item ORDER BY franchise_id, pos", null).use { c ->
            while (c.moveToNext()) {
                val paths = runCatching {
                    val a = JSONArray(c.getString(2) ?: "[]"); (0 until a.length()).map { a.getString(it) }
                }.getOrDefault(emptyList())
                items.getOrPut(c.getInt(0)) { ArrayList() } += FranchiseItem(c.getString(1), paths, c.getInt(3), c.getString(4))
            }
        }
        val out = ArrayList<Franchise>()
        db.rawQuery("SELECT id, label, node_path, count, art FROM franchise ORDER BY pos", null).use { c ->
            while (c.moveToNext()) out += Franchise(
                c.getInt(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), items[c.getInt(0)] ?: emptyList()
            )
        }
        return out
    }

    fun recentCommits(): List<Pair<Double, List<Pair<Int, Int>>>> {
        val out = ArrayList<Pair<Double, List<Pair<Int, Int>>>>()
        db.rawQuery("SELECT ts, items FROM commit_log ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                val a = runCatching { JSONArray(c.getString(1)) }.getOrNull() ?: continue
                out += c.getDouble(0) to (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getInt(0) to x.getInt(1) } }
            }
        }
        return out
    }

    override fun close() = db.close()

    companion object {
        const val FORMAT = 1

        fun open(file: File): SnapshotDb {
            val db = SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            try {
                val fmt = db.rawQuery("SELECT value FROM info WHERE key = 'format'", null).use {
                    if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: 0 else 0
                }
                require(fmt == FORMAT) { "Nieobsługiwany format migawki ($fmt) – zaktualizuj aplikację lub sync39" }
                return SnapshotDb(db, file)
            } catch (e: Exception) {
                db.close()
                throw e
            }
        }

        private val TAG_ORDER = listOf(
            "Genres", "Subjective", "Themes", "Animation", "Copyrights", "Sources", "Series", "Vocalists",
            "Instruments", "Composition", "Languages", "Lyrics", "Media", "Distribution", "Event"
        )

        fun tagCategoryRank(cat: String): Int = TAG_ORDER.indexOf(cat).let { if (it < 0) TAG_ORDER.size else it }

        val TAG_CATEGORY_PL = mapOf(
            "Genres" to "Gatunki", "Subjective" to "Subiektywne", "Themes" to "Tematy", "Animation" to "Animacja",
            "Copyrights" to "Prawa autorskie", "Sources" to "Źródła", "Series" to "Serie", "Vocalists" to "Wokaliści",
            "Instruments" to "Instrumenty", "Composition" to "Kompozycja", "Languages" to "Języki", "Lyrics" to "Tekst",
            "Media" to "Media", "Distribution" to "Dystrybucja", "Event" to "Wydarzenia", "" to "Bez kategorii",
            "View count milestones" to "Liczba wyświetleń",
        )
    }
}
