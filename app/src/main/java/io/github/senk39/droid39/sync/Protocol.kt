package io.github.senk39.droid39.sync

import io.github.senk39.droid39.data.LocalDb
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pliki wymieniane z PC przez folder synchronizacji na NAS – zapis i odczyt po stronie telefonu w jednym miejscu,
 * bez zależności od Androida. Wzorcowe przykłady każdego formatu leżą w `protocol/` (katalog główny repozytorium);
 * sprawdzają je testy Kotlin (`ProtocolTest`) i Python (`pc/tests/test_protocol.py`, kod 39player i sync39).
 */
object Protocol {
    const val FORMAT = 1

    // ---------------------------------------------------------------- dziennik zmian: inbox/<urządzenie>/j-*.json
    /** Plik dziennika z kolejnymi zmianami telefonu (czyta go 39player: player39/droid_link.py). */
    fun journal(ops: List<LocalDb.Op>, device: String, deviceName: String, app: String, createdSec: Double): JSONObject {
        val events = JSONArray()
        for (op in ops) {
            val e = JSONObject()
            e.put("seq", op.seq)
            e.put("ts", op.ts / 1000.0)
            e.put("type", op.type)
            when (op.type) {
                "heart" -> {
                    e.put("track", op.trackId); e.put("rel", op.rel); e.put("level", op.value)
                }
                "mark" -> {
                    e.put("track", op.trackId); e.put("rel", op.rel)
                    e.put("best", if (op.value and 1 != 0) 1 else 0)
                    e.put("liked", if (op.value and 2 != 0) 1 else 0)
                    e.put("listened", if (op.value and 4 != 0) 1 else 0)
                }
                "play" -> {
                    e.put("track", op.trackId); e.put("rel", op.rel); e.put("played_ms", op.value)
                    e.put("counted", op.extra == "1")
                }
                "pl_add", "pl_remove" -> {
                    e.put("playlist", op.playlist)
                    e.put("tracks", JSONArray().put(op.trackId))
                    e.put("rels", JSONArray().put(op.rel))
                }
                "pl_create", "pl_delete" -> e.put("playlist", op.playlist)
                "pl_rename" -> {
                    e.put("playlist", op.playlist); e.put("new_playlist", op.extra)
                }
                "pl_move" -> {
                    e.put("playlist", op.playlist)
                    e.put("tracks", JSONArray().put(op.trackId))
                    e.put("rels", JSONArray().put(op.rel))
                    e.put("before", if (op.value > 0) op.value else JSONObject.NULL)
                    e.put("before_rel", op.extra ?: JSONObject.NULL)
                }
            }
            events.put(e)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("device", device)
            .put("device_name", deviceName)
            .put("app", app)
            .put("created", createdSec)
            .put("first_seq", ops.first().seq)
            .put("last_seq", ops.last().seq)
            .put("events", events)
    }

    // ---------------------------------------------------------------- stan odtwarzania: state/now-<urządzenie>.json
    data class TrackInfo(val id: Int, val rel: String, val title: String, val artist: String, val duration: Double)

    /** Stan odtwarzania telefonu („Kontynuuj z telefonu” w 39player). [repeat]: "off" / "all" / "one". */
    fun phoneState(
        device: String, name: String, app: String, tsSec: Double, playing: Boolean, positionMs: Long, index: Int,
        queue: List<Int>, track: TrackInfo?, shuffle: Boolean, repeat: String,
    ): JSONObject = JSONObject()
        .put("format", FORMAT)
        .put("device", device)
        .put("name", name)
        .put("kind", "phone")
        .put("app", app)
        .put("ts", tsSec)
        .put("playing", playing)
        .put("position_ms", positionMs.coerceAtLeast(0))
        .put("index", index)
        .put("queue", JSONArray(queue))
        .put("track", track?.let {
            JSONObject().put("id", it.id).put("rel", it.rel).put("title", it.title).put("artist", it.artist)
                .put("duration", it.duration)
        } ?: JSONObject.NULL)
        .put("shuffle", shuffle)
        .put("repeat", repeat)
        .put("label", "")

    // ---------------------------------------------------------------- polecenia: state/cmd-<urządzenie>.json
    data class Command(val cmd: String, val from: String, val name: String, val tsMs: Long)

    /** Polecenie pauzy dla PC (cmd-pc.json), gdy odtwarzanie przejmuje telefon. */
    fun pauseCommand(from: String, name: String, tsSec: Double): JSONObject =
        JSONObject().put("cmd", "pause").put("from", from).put("name", name).put("ts", tsSec)

    fun parseCommand(json: String): Command? {
        val o = try {
            JSONObject(json)
        } catch (e: org.json.JSONException) {
            return null
        }
        val cmd = o.optString("cmd").ifEmpty { return null }
        return Command(cmd, o.optString("from"), o.optString("name"), (o.optDouble("ts", 0.0) * 1000).toLong())
    }

    // ---------------------------------------------------------------- manifest.json (sync39)
    /** Co opublikował sync39: pełna biblioteka i plik stanu ([stateBase] – sha256 biblioteki, do której pasuje stan). */
    data class Manifest(
        val format: Int, val snapshot: String, val size: Long, val sha256: String, val createdSec: Double,
        val state: String, val stateSize: Long, val stateSha256: String, val stateBase: String, val stateCreatedSec: Double,
    ) {
        companion object {
            fun parse(json: String): Manifest {
                val o = JSONObject(json)
                return Manifest(
                    format = o.optInt("format", 0),
                    snapshot = o.optString("snapshot"),
                    size = o.optLong("size", 0),
                    sha256 = o.optString("sha256"),
                    createdSec = o.optDouble("created", 0.0),
                    state = o.optString("state"),
                    stateSize = o.optLong("state_size", 0),
                    stateSha256 = o.optString("state_sha256"),
                    stateBase = o.optString("state_base"),
                    stateCreatedSec = o.optDouble("state_created", 0.0),
                )
            }
        }
    }

    // ---------------------------------------------------------------- plik stanu → pełna biblioteka
    /**
     * Nałożenie pliku stanu (dołączonego jako „st”) na pełną bibliotekę. Tekst tych zapytań jest też w
     * `protocol/apply-state.sql` – sprawdza go test Kotlin (na schemacie z PC) i test sync39 (na prawdziwej migawce).
     */
    val APPLY_STATE: List<String> = listOf(
        "UPDATE track SET heart = 0, play_count = 0, last_played = NULL",
        "UPDATE track SET heart = (SELECT s.heart FROM st.track_state s WHERE s.id = track.id), " +
            "play_count = (SELECT s.play_count FROM st.track_state s WHERE s.id = track.id), " +
            "last_played = (SELECT s.last_played FROM st.track_state s WHERE s.id = track.id) " +
            "WHERE id IN (SELECT id FROM st.track_state)",
        "UPDATE node SET likes = 0",
        "UPDATE node SET likes = (SELECT s.likes FROM st.node_likes s WHERE s.id = node.id) " +
            "WHERE id IN (SELECT id FROM st.node_likes)",
        "UPDATE tag SET likes = 0",
        "UPDATE tag SET likes = (SELECT s.likes FROM st.tag_likes s WHERE s.id = tag.id) " +
            "WHERE id IN (SELECT id FROM st.tag_likes)",
    ) + listOf("playlist", "playlist_track", "unlistened", "commit_log", "star", "applied").flatMap { t ->
        listOf("DELETE FROM $t", "INSERT INTO $t SELECT * FROM st.$t")
    } + "UPDATE info SET value = (SELECT value FROM st.info WHERE key = 'created') WHERE key = 'created'"
}
