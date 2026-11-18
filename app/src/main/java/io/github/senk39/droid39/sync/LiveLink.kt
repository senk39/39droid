package io.github.senk39.droid39.sync

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.BuildConfig
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.core.Text
import io.github.senk39.droid39.data.Library
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.media.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException

/** Stan odtwarzania urządzenia z pliku state/now-<urządzenie>.json (ten sam format co w 39player). */
data class DeviceState(
    val device: String,
    val name: String,
    val kind: String,
    val tsMs: Long,
    val playing: Boolean,
    val positionMs: Long,
    val index: Int,
    val queue: List<Int>,
    val trackId: Int?,
    val trackRel: String?,
    val title: String,
    val artist: String,
    val durationS: Double,
    val shuffle: Boolean,
    val repeat: String,
) {
    /** Pozycja do wznowienia: świeży stan „gra” – przesunięta o czas od zapisu; najwyżej do 3 s przed końcem. */
    fun resumePosition(now: Long = System.currentTimeMillis()): Long {
        var pos = positionMs.coerceAtLeast(0)
        val age = now - tsMs
        if (playing && age in 1 until EXTRAPOLATE_MS) pos += age
        val dur = (durationS * 1000).toLong()
        if (dur > 0) pos = pos.coerceAtMost((dur - 3000).coerceAtLeast(0))
        return pos
    }

    val hasTrack: Boolean get() = trackId != null || !trackRel.isNullOrEmpty()

    companion object {
        const val EXTRAPOLATE_MS = 120_000L

        private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

        fun parse(o: JSONObject): DeviceState? {
            val dev = o.str("device") ?: return null
            val q = o.optJSONArray("queue")?.let { a -> List(a.length()) { a.optInt(it, -1) }.filter { it >= 0 } } ?: emptyList()
            val tr = o.optJSONObject("track")
            return DeviceState(
                device = dev,
                name = o.str("name") ?: dev,
                kind = o.str("kind") ?: "",
                tsMs = (o.optDouble("ts", 0.0) * 1000).toLong(),
                playing = o.optBoolean("playing"),
                positionMs = o.optLong("position_ms"),
                index = o.optInt("index"),
                queue = q,
                trackId = tr?.takeIf { it.has("id") && !it.isNull("id") }?.optInt("id"),
                trackRel = tr?.str("rel")?.takeIf { it.isNotEmpty() },
                title = tr?.str("title") ?: "",
                artist = tr?.str("artist") ?: "",
                durationS = tr?.optDouble("duration", 0.0) ?: 0.0,
                shuffle = o.optBoolean("shuffle"),
                repeat = o.str("repeat") ?: "off",
            )
        }
    }
}

/**
 * Łączność „na żywo” z PC przez folder synchronizacji (gdy aplikacja jest na ekranie albo gra):
 *  * co 45 s – nowa biblioteka z PC (manifest) i niewysłane zmiany z telefonu,
 *  * stan odtwarzania PC (state/now-pc.json) – „Kontynuuj z PC”,
 *  * stan telefonu (state/now-<id>.json) – „Kontynuuj z telefonu” w 39player,
 *  * polecenie pauzy (state/cmd-<id>.json), gdy odtwarzanie przejmie PC.
 */
@UnstableApi
class LiveLink(
    private val settings: Settings,
    private val nas: Nas,
    private val sync: SyncManager,
    private val library: Library,
    private val scope: CoroutineScope,
) {
    private val _pc = MutableStateFlow<DeviceState?>(null)
    val pc: StateFlow<DeviceState?> = _pc
    private val _pcStatus = MutableStateFlow<PcStatus?>(null)

    /** Stan sync39 na PC (state/pc-status.json) – ostrzeżenia przy chmurce; ostatni znany także po restarcie. */
    val pcStatus: StateFlow<PcStatus?> = _pcStatus
    @Volatile private var foreground = false
    @Volatile private var playing = false
    @Volatile private var pcNow = false
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private val publishLock = Mutex()
    @Volatile private var pending: PhoneQueue? = null

    /** Usługa odtwarzania: pauza na polecenie innego urządzenia (wołane na wątku głównym, z nazwą urządzenia). */
    var onPause: ((String) -> Unit)? = null

    /** Kiedy telefon zaczął grać (ms) – starsze polecenia pauzy są pomijane. */
    @Volatile var playStartedAt = 0L
        private set

    data class PhoneQueue(
        val ids: List<Int>, val index: Int, val currentId: Int?, val positionMs: Long, val playing: Boolean,
        val shuffle: Boolean, val repeatMode: Int,
    )

    private fun syncDir() = settings.syncPath.ifBlank { "39sync" }
    private fun statePath(name: String) = RemoteFs.join(syncDir(), "state", name)

    fun setForeground(on: Boolean) {
        foreground = on
        if (on) pcNow = true
        update()
    }

    /** Telefon gra (zamiar użytkownika – bez chwilowych przerw na buforowanie i zmianę utworu). */
    fun setPlaying(on: Boolean) {
        if (on && !playing) playStartedAt = System.currentTimeMillis()
        playing = on
        update()
    }

    private fun update() {
        if ((foreground || playing) && settings.nasConfigured && job?.isActive != true) {
            job = scope.launch(Dispatchers.IO) { loop() }
        }
    }

    private suspend fun loop() {
        var lastSync = 0L
        var lastPc = 0L
        var lastCmd = 0L
        while (currentCoroutineContext().isActive && (foreground || playing) && settings.nasConfigured) {
            val now = System.currentTimeMillis()
            if (!nas.recentlyFailed(60_000)) {
                if (playing && now - lastCmd >= CMD_EVERY_MS) {
                    lastCmd = now
                    runCatching { checkCommand() }
                }
                if (foreground && (pcNow || now - lastPc >= PC_EVERY_MS)) {
                    pcNow = false
                    lastPc = now
                    runCatching { refreshPc() }
                    runCatching { refreshPcStatus() }
                }
                if (now - lastSync >= SYNC_EVERY_MS) {
                    lastSync = now
                    // w sieci komórkowej tylko zmiany i mały plik stanu; pełna biblioteka – przez Wi-Fi
                    runCatching { sync.quickSync(allowFull = !nas.metered()) }
                }
            }
            delay(2_000)
        }
    }

    // ---------------------------------------------------------------- stan PC i „Kontynuuj z PC”
    /** Odczyt stanu PC (null – brak pliku). */
    suspend fun refreshPc(): DeviceState? = withContext(Dispatchers.IO) {
        val bytes = try {
            nas.use { it.read(statePath("now-pc.json")) }
        } catch (e: FileNotFoundException) {
            _pc.value = null
            return@withContext null
        }
        DeviceState.parse(JSONObject(String(bytes, Charsets.UTF_8))).also { _pc.value = it }
    }

    init {
        scope.launch(Dispatchers.IO) { runCatching { _pcStatus.value = PcStatus.parse(library.local.get(KV_PC_STATUS)) } }
    }

    /** Odczyt state/pc-status.json (brak pliku – sync39 sprzed tej wersji: bez ostrzeżeń). */
    suspend fun refreshPcStatus(): PcStatus? = withContext(Dispatchers.IO) {
        val json = try {
            String(nas.use { it.read(statePath("pc-status.json")) }, Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            return@withContext null
        }
        val st = PcStatus.parse(json) ?: return@withContext null
        library.local.put(KV_PC_STATUS, json)
        _pcStatus.value = st
        st
    }

    /** Kolejka innego urządzenia jako utwory tej biblioteki: (utwory, indeks bieżącego). */
    fun resolveQueue(st: DeviceState): Pair<List<TrackRow>, Int> {
        val rows = library.tracks(st.queue).associateBy { it.id }
        var cur = st.trackId?.let { library.track(it) }
        val rel = st.trackRel
        if (rel != null && (cur == null || Text.relKey(cur.rel) != Text.relKey(rel))) {
            cur = library.withSnap { it.trackIdByRel(rel) }?.let { library.track(it) } ?: cur
        }
        val out = ArrayList<TrackRow>(st.queue.size)
        var idx = -1
        st.queue.forEachIndexed { k, id ->
            if (k == st.index && cur != null) {
                idx = out.size
                out += cur
                return@forEachIndexed
            }
            val r = rows[id] ?: return@forEachIndexed
            if (k == st.index) idx = out.size
            out += r
        }
        if (idx < 0) {
            if (cur != null) {
                out.add(0, cur)
                idx = 0
            } else idx = st.index.coerceIn(0, (out.size - 1).coerceAtLeast(0))
        }
        return out to idx
    }

    /** „Kontynuuj z PC”: kolejka, utwór i pozycja z PC; PC dostaje polecenie pauzy. Zwraca komunikat. */
    suspend fun continueFromPc(player: PlayerConnection): String = withContext(Dispatchers.IO) {
        val st = try {
            refreshPc()
        } catch (e: Exception) {
            return@withContext "NAS niedostępny – nie można odczytać kolejki z PC"
        } ?: return@withContext "Brak stanu odtwarzania z PC – 39player jeszcze nic nie zapisał"
        val (rows, idx) = resolveQueue(st)
        if (rows.isEmpty()) return@withContext "Utworów z kolejki PC nie ma w bibliotece telefonu"
        val repeat = when (st.repeat) {
            "one" -> Player.REPEAT_MODE_ONE
            "all" -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        player.resume(rows, idx, st.resumePosition(), repeat, "Kolejka z PC")
        if (st.playing) {
            runCatching {
                writeJson("cmd-pc.json", JSONObject().put("cmd", "pause").put("from", settings.deviceId)
                    .put("name", settings.deviceName).put("ts", System.currentTimeMillis() / 1000.0))
            }
        }
        "Kontynuacja z PC: ${rows[idx].title} od ${Text.durationMs(st.resumePosition())}"
    }

    // ---------------------------------------------------------------- polecenia dla telefonu
    private fun checkCommand() {
        val path = statePath("cmd-${settings.deviceId}.json")
        val bytes = try {
            nas.use { it.read(path) }
        } catch (e: FileNotFoundException) {
            return
        }
        runCatching { nas.use { it.delete(path) } }
        val o = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val ts = (o.optDouble("ts", 0.0) * 1000).toLong()
        val fresh = System.currentTimeMillis() - ts < CMD_MAX_AGE_MS && ts > playStartedAt
        if (o.optString("cmd") == "pause" && fresh && playing) {
            val who = if (o.optString("from") == "pc") "PC" else o.optString("name", "inne urządzenie")
            main.post { onPause?.invoke(who) }
        }
    }

    // ---------------------------------------------------------------- stan telefonu
    /** Stan telefonu → state/now-<id>.json (zapis w tle; przy kilku naraz – tylko najnowszy). */
    fun publish(q: PhoneQueue) {
        if (!settings.nasConfigured) return
        pending = q
        scope.launch(Dispatchers.IO) {
            publishLock.withLock {
                val st = pending ?: return@withLock
                pending = null
                if (nas.recentlyFailed(60_000)) return@withLock
                runCatching { writeJson("now-${settings.deviceId}.json", stateJson(st)) }
            }
        }
    }

    private fun stateJson(q: PhoneQueue): JSONObject {
        val row = q.currentId?.let { library.track(it) }
        val track = row?.let {
            JSONObject().put("id", it.id).put("rel", it.rel).put("title", it.title).put("artist", it.artist)
                .put("duration", it.duration)
        }
        return JSONObject()
            .put("format", 1)
            .put("device", settings.deviceId)
            .put("name", settings.deviceName)
            .put("kind", "phone")
            .put("app", "39droid ${BuildConfig.VERSION_NAME}")
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("playing", q.playing)
            .put("position_ms", q.positionMs.coerceAtLeast(0))
            .put("index", q.index)
            .put("queue", JSONArray(q.ids))
            .put("track", track ?: JSONObject.NULL)
            .put("shuffle", q.shuffle)
            .put("repeat", when (q.repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            })
            .put("label", "")
    }

    private fun writeJson(name: String, obj: JSONObject) {
        nas.use { fs ->
            val tmp = statePath("$name.tmp")
            fs.write(tmp, obj.toString().toByteArray(Charsets.UTF_8))
            fs.rename(tmp, statePath(name))
        }
    }

    companion object {
        const val SYNC_EVERY_MS = 45_000L
        const val KV_PC_STATUS = "pc_status_json"
        const val PC_EVERY_MS = 30_000L
        const val CMD_EVERY_MS = 10_000L
        const val CMD_MAX_AGE_MS = 180_000L
        const val MAX_QUEUE = 5000
    }
}
