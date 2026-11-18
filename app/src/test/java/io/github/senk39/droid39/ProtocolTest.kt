package io.github.senk39.droid39

import io.github.senk39.droid39.data.LocalDb
import io.github.senk39.droid39.sync.DeviceState
import io.github.senk39.droid39.sync.PcStatus
import io.github.senk39.droid39.sync.Protocol
import io.github.senk39.droid39.sync.RejectedChange
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * Wspólne testy formatów wymienianych przez NAS (strona telefonu). Wzorcowe pliki: `protocol/` w katalogu głównym
 * repozytorium – te same sprawdza `pc/tests/test_protocol.py` (39player, sync39). Telefon musi zapisywać dokładnie
 * takie pliki, jakie są wzorcem, i poprawnie czytać te, które zapisuje PC.
 */
class ProtocolTest {
    private val dir: File = listOf(File("../protocol"), File("protocol")).first { it.isDirectory }
    private fun text(name: String) = File(dir, name).readText(Charsets.UTF_8)
    private fun json(name: String) = JSONObject(text(name))

    /** JSON → struktury Kotlin z liczbami jako Double (porównanie niezależne od Int/Long/Double i kolejności kluczy). */
    private fun norm(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().toList().associateWith { norm(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { norm(v.get(it)) }
        is Number -> v.toDouble()
        JSONObject.NULL -> null
        else -> v
    }

    private val dev = "0123456789abcdef"
    private val rel1 = "Vocaloid/DECO*27/[2020.01.01] Ghost Rule/01 - ゴーストルール.mp3"
    private val rel2 = "Vocaloid/DECO*27/[2020.01.01] Ghost Rule/02 - Łódź.mp3"
    private val rel3 = "Anime/Love Live!/[2021.07.21] Single/01 - Start!! True dreams.flac"

    // ---------------------------------------------------------------- telefon zapisuje
    @Test
    fun journalMatchesFixture() {
        val ops = listOf(
            LocalDb.Op(41, 1795000000500, "heart", 1001, rel1, null, 2, null),
            LocalDb.Op(42, 1795000010500, "mark", 1002, rel2, null, 6, null),                 // ♥ + przesłuchane
            LocalDb.Op(43, 1795000020500, "play", 1001, rel1, null, 183000, "1"),
            LocalDb.Op(44, 1795000030500, "play", 1003, rel3, null, 5000, "0"),
            LocalDb.Op(45, 1795000040500, "pl_create", null, null, "Z telefonu/Na drogę.m3u8", 0, null),
            LocalDb.Op(46, 1795000050500, "pl_add", 1001, rel1, "Z telefonu/Na drogę.m3u8", 0, null),
            LocalDb.Op(47, 1795000060500, "pl_remove", 1002, rel2, "Ulubione.m3u8", 0, null),
        )
        val got = Protocol.journal(ops, dev, "Galaxy A55", "39droid 0.1.0", 1795000100.25)
        assertEquals(norm(json("journal.json")), norm(JSONObject(got.toString(1))))
    }

    @Test
    fun phoneStateMatchesFixture() {
        val track = Protocol.TrackInfo(1001, rel1, "ゴーストルール", "DECO*27 feat. 初音ミク", 215.5)
        val got = Protocol.phoneState(dev, "Galaxy A55", "39droid 0.1.0", 1795000200.75, true, 61000, 1,
            listOf(1003, 1001, 1002), track, true, "all")
        assertEquals(norm(json("now-phone.json")), norm(JSONObject(got.toString())))
    }

    @Test
    fun pauseCommandMatchesFixture() {
        val got = Protocol.pauseCommand(dev, "Galaxy A55", 1795000310.5)
        assertEquals(norm(json("cmd-from-phone.json")), norm(JSONObject(got.toString())))
    }

    // ---------------------------------------------------------------- telefon czyta (zapisuje PC)
    @Test
    fun pcStateIsRead() {
        val st = DeviceState.parse(json("now-pc.json"))!!
        assertEquals("pc", st.device)
        assertEquals(listOf(1001, 1002, 1003), st.queue)
        assertEquals(1001, st.trackId)
        assertEquals(rel1, st.trackRel)
        assertEquals("DECO*27 feat. 初音ミク", st.artist)
        assertEquals("one", st.repeat)
        assertEquals(1795000250500L, st.tsMs)
        assertEquals(42000L, st.resumePosition(st.tsMs + 10_000))                         // wstrzymany – bez przesunięcia
    }

    @Test
    fun pcCommandIsRead() {
        val c = Protocol.parseCommand(text("cmd-from-pc.json"))!!
        assertEquals(Protocol.Command("pause", "pc", "", 1795000300500L), c)
        assertNull(Protocol.parseCommand("{\"from\":\"pc\"}"))
    }

    @Test
    fun manifestIsRead() {
        val m = Protocol.Manifest.parse(text("manifest.json"))
        assertEquals(1, m.format)
        assertEquals("snapshot/library-20261118-193741.db.gz", m.snapshot)
        assertEquals(12607520L, m.size)
        assertEquals("snapshot/state-20261118-193741.db.gz", m.state)
        assertEquals(485858L, m.stateSize)
        assertEquals(m.sha256, m.stateBase)
        assertTrue(m.stateSha256.startsWith("8d1139c0563c"))
        assertEquals(1795000600.5, m.createdSec, 0.0)
        assertEquals(1795000600.5, m.stateCreatedSec, 0.0)
    }

    @Test
    fun pcStatusIsRead() {
        val s = PcStatus.parse(text("pc-status.json"))!!
        assertEquals("waiting_confirmation", s.state)
        assertEquals(20, s.pendingDeletions)
        assertEquals(1795000500500L, s.tsMs)
        assertEquals(1795000300000L, s.lastExportMs)
        assertTrue(s.mirror)
        val w = s.warnings(s.tsMs + 60_000)
        assertEquals(1, w.size)
        assertTrue(w[0].text.contains("usunięcia 20 plików"))
    }

    @Test
    fun rejectedIsRead() {
        val r = RejectedChange.parseAll(text("rejected.json"))
        assertEquals(listOf(41L, 46L), r.map { it.seq })
        assertEquals(2, r[0].level)
        assertEquals(1001, r[1].trackId)
        assertEquals("Z telefonu/Na drogę.m3u8", r[1].playlist)
        assertEquals(1795000050500L, r[1].tsMs)
        assertEquals("Dodanie do „Na drogę”: Tytuł", RejectedChange.describe(r[1], "Tytuł"))
        assertEquals("♥♥: 01 - ゴーストルール.mp3", RejectedChange.describe(r[0], null))
    }

    // ---------------------------------------------------------------- plik stanu nakładany na migawkę z PC
    @Test
    fun applyStateSqlMatchesSharedFile() {
        val shared = text("apply-state.sql").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("--") }.map { it.removeSuffix(";") }
        assertEquals(shared, Protocol.APPLY_STATE)
    }

    /** Zapytania telefonu wykonane na schemacie migawki i pliku stanu zapisanym przez PC (sync39). */
    @Test
    fun applyStateRunsOnPcSchema() {
        val tmp = kotlin.io.path.createTempDirectory("protocol").toFile()
        try {
            val full = File(tmp, "full.db")
            val state = File(tmp, "state.db")
            fun exec(db: File, sql: String) = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
                c.createStatement().use { st -> sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) } }
            }
            exec(full, text("snapshot-schema.sql"))
            exec(full, """
                INSERT INTO info VALUES('created', '1');
                INSERT INTO track(id, rel, heart, play_count, last_played) VALUES(1001, 'a', 1, 3, 1.0);
                INSERT INTO track(id, rel, heart, play_count, last_played) VALUES(1002, 'b', 2, 5, 2.0);
                INSERT INTO node(id, pos, path, name, kind, count, likes) VALUES(1, 0, 'p', 'n', 'folder', 2, 2);
                INSERT INTO tag(id, name, count, likes) VALUES(7, 'tag', 2, 2);
                INSERT INTO playlist(id, rel, name, folder, kind, pos) VALUES(1, 'Stara.m3u8', 'Stara', '', 'pl', 0);
                INSERT INTO applied VALUES('$dev', 40, 1.0)
            """)
            exec(state, text("state-schema.sql"))
            exec(state, """
                INSERT INTO info VALUES('created', '1795000600.5');
                INSERT INTO track_state VALUES(1001, 2, 9, 1795000000.5);
                INSERT INTO node_likes VALUES(1, 1);
                INSERT INTO playlist(id, rel, name, folder, kind, pos, count) VALUES(1, 'Nowa.m3u8', 'Nowa', '', 'pl', 0, 1);
                INSERT INTO playlist_track VALUES(1, 0, 1001);
                INSERT INTO applied VALUES('$dev', 47, 2.0)
            """)
            DriverManager.getConnection("jdbc:sqlite:${full.path}").use { c ->
                c.createStatement().use { it.execute("ATTACH DATABASE '${state.path.replace("'", "''")}' AS st") }
                c.autoCommit = false
                c.createStatement().use { st -> Protocol.APPLY_STATE.forEach { st.execute(it) } }
                c.commit()
                c.autoCommit = true
                fun q(sql: String): List<List<Any?>> = c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        val n = rs.metaData.columnCount
                        buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
                    }
                }
                assertEquals(listOf(listOf(1001, 2, 9, 1795000000.5), listOf(1002, 0, 0, null)),
                    q("SELECT id, heart, play_count, last_played FROM track ORDER BY id"))
                assertEquals(listOf(listOf(1)), q("SELECT likes FROM node"))
                assertEquals(listOf(listOf(0)), q("SELECT likes FROM tag"))
                assertEquals(listOf(listOf("Nowa.m3u8")), q("SELECT rel FROM playlist"))
                assertEquals(listOf(listOf(1, 0, 1001)), q("SELECT * FROM playlist_track"))
                assertEquals(listOf(listOf(47)), q("SELECT seq FROM applied"))
                assertEquals(listOf(listOf("1795000600.5")), q("SELECT value FROM info WHERE key = 'created'"))
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
