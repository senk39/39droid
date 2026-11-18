package io.github.senk39.droid39.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.BuildConfig
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.data.Library
import io.github.senk39.droid39.data.LocalDb
import io.github.senk39.droid39.files.LocalFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

data class SyncStatus(
    val running: Boolean = false,
    val message: String = "",
    val progress: Float? = null,
    val error: String? = null,
)

/**
 * Synchronizacja z PC przez folder na NAS (sync39):
 *  1. wysyła dziennik zmian z telefonu do <sync>/inbox/<urządzenie>/,
 *  2. pobiera nową migawkę biblioteki, jeśli manifest.json wskazuje inną niż obecna.
 */
@UnstableApi
class SyncManager(
    private val context: Context,
    private val settings: Settings,
    private val library: Library,
    private val nas: Nas,
    private val files: LocalFiles,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status
    private var pushJob: Job? = null
    private val _rejected = MutableStateFlow<List<RejectedChange>>(emptyList())

    /** Zmiany z telefonu, których PC nie przyjął (jeszcze nieukryte przez użytkownika), z opisem i powodem. */
    val rejected: StateFlow<List<RejectedChange>> = _rejected

    init {
        scope.launch(Dispatchers.IO) { runCatching { showRejected(library.local.get(KV_REJECTED)) } }
    }

    /** Callback po zainstalowaniu nowej migawki (np. planowanie pobierania offline). */
    var onSnapshotInstalled: (() -> Unit)? = null

    private fun syncDir() = settings.syncPath.ifBlank { "39sync" }

    private companion object {
        /** Zmian w jednym pliku dziennika. */
        const val CHUNK = 500
        const val KV_REJECTED = "rejected_json"
    }

    /** Pełna synchronizacja. Zwraca opis wyniku. */
    /** [allowFull] = false – pełna biblioteka (kilkanaście MB) czeka na Wi-Fi; zmiany i mały plik stanu idą od razu. */
    suspend fun syncNow(allowFull: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = SyncStatus(true, "Łączenie z NAS…")
            try {
                val sent = pushLocked()
                _status.value = SyncStatus(true, "Sprawdzanie migawki biblioteki…")
                val pulled = pullLocked(allowFull)
                val newlyRejected = runCatching { fetchRejected() }.getOrDefault(0)
                settings.lastSyncAt = System.currentTimeMillis()
                settings.lastSyncError = ""
                val msg = buildString {
                    val deferred = deferredFullBytes
                    append(when {
                        pulled -> "Pobrano zmiany z PC"
                        deferred > 0 -> "Nowa biblioteka z PC (${kb(deferred.toInt())}) – pobierze się przez Wi-Fi"
                        else -> "Biblioteka aktualna"
                    })
                    if (sent > 0) append(" · wysłano zmian: $sent")
                    if (newlyRejected > 0) append(" · PC nie przyjął zmian: $newlyRejected")
                }
                _status.value = SyncStatus(false, msg)
                if (pulled) onSnapshotInstalled?.invoke()
                Result.success(msg)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                settings.lastSyncError = msg
                _status.value = SyncStatus(false, "", error = msg)
                Result.failure(e)
            }
        }
    }

    /**
     * Migawka biblioteki z pliku (np. skopiowanego przez USB: `python -m sync39 export --out …`) – gdy NAS nie jest
     * jeszcze skonfigurowany albo jest niedostępny. Przyjmuje plik .db.gz (jak na NAS) albo rozpakowany .db.
     */
    suspend fun importFromUri(uri: android.net.Uri): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = SyncStatus(true, "Wczytywanie biblioteki z pliku…")
            val dir = library.snapshotDir()
            val fresh = File(dir, "incoming.db")
            try {
                val raw = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Nie można otworzyć pliku")
                java.io.BufferedInputStream(raw, 1 shl 16).use { input ->
                    input.mark(4)
                    val gz = input.read() == 0x1f && input.read() == 0x8b
                    input.reset()
                    val stream = if (gz) GZIPInputStream(input, 1 shl 16) else input
                    fresh.outputStream().use { stream.copyTo(it, 1 shl 16) }
                }
                library.install(fresh)                      // sprawdza format; zły plik – wyjątek z opisem
                settings.snapshotSha = ""                   // następna synchronizacja z NAS podmieni ją na aktualną
                settings.stateSha = ""
                settings.snapshotCreated = ((library.withSnap { it.createdAt } ?: 0.0) * 1000).toLong()
                files.recomputeAvailable(library)
                val n = library.withSnap { it.trackCount } ?: 0
                val msg = "Wczytano bibliotekę z pliku: $n utworów"
                _status.value = SyncStatus(false, msg)
                onSnapshotInstalled?.invoke()
                Result.success(msg)
            } catch (e: Exception) {
                fresh.delete()
                val msg = when {
                    e is android.database.sqlite.SQLiteException -> "To nie jest plik biblioteki 39droid (library-….db.gz)"
                    else -> e.message ?: e.javaClass.simpleName
                }
                _status.value = SyncStatus(false, "", error = msg)
                Result.failure(e)
            }
        }
    }

    /**
     * Szybkie sprawdzenie (co kilkadziesiąt sekund, gdy aplikacja jest na ekranie albo gra): pełna synchronizacja
     * tylko wtedy, gdy na NAS jest nowa migawka z PC albo telefon ma niewysłane zmiany. Zwraca true, gdy była.
     */
    suspend fun quickSync(allowFull: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        if (mutex.isLocked || !settings.nasConfigured) return@withContext false
        val manifest = try {
            Protocol.Manifest.parse(String(nas.use { it.read(RemoteFs.join(syncDir(), "manifest.json")) }, Charsets.UTF_8))
        } catch (e: FileNotFoundException) {
            return@withContext false
        }
        val sha = manifest.sha256
        val newSnapshot = sha.isNotEmpty() && sha != settings.snapshotSha
        val stateSha = manifest.stateSha256
        val newState = !newSnapshot && stateSha.isNotEmpty() && stateSha != settings.stateSha &&
            manifest.stateBase == settings.snapshotSha
        val unsent = library.local.maxSeq() > settings.uploadedSeq
        // w sieci komórkowej pełna biblioteka czeka na Wi-Fi – bez ponawiania co kilkadziesiąt sekund
        if (!(newSnapshot && allowFull) && !newState && !unsent) return@withContext false
        syncNow(allowFull)
        true
    }

    private var pushFailures = 0

    /**
     * Wysłanie dziennika z opóźnieniem (zbiera kilka szybkich zmian w jeden plik). Po nieudanej próbie – ponowienie
     * po 30 s, 1, 2 i najwyżej 5 min; status pokazuje, co poszło nie tak i kiedy będzie następna próba.
     */
    fun schedulePush(delayMs: Long = 20_000) {
        pushJob?.cancel()
        pushJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (!settings.nasConfigured) return@launch
            if (!mutex.tryLock()) {                               // trwa pełna synchronizacja – ona też wysyła
                schedulePush(15_000)
                return@launch
            }
            try {
                val sent = pushLocked()
                pushFailures = 0
                if (sent > 0) {
                    val at = java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(java.util.Date())
                    _status.value = SyncStatus(false, "Wysłano zmiany do PC: $sent ($at)")
                }
            } catch (e: Exception) {
                pushFailures++
                val wait = longArrayOf(30_000, 60_000, 120_000, 300_000)[minOf(pushFailures, 4) - 1]
                _status.value = SyncStatus(false, "", error = "Nie wysłano zmian: ${e.message ?: e.javaClass.simpleName}" +
                    " – ponowna próba za ${if (wait < 60_000) "${wait / 1000} s" else "${wait / 60_000} min"}")
                schedulePush(wait)
            } finally {
                mutex.unlock()
            }
        }
    }

    // ---------------------------------------------------------------- dziennik zmian → NAS
    /** Wysyła niewysłane zmiany w częściach po [CHUNK] (duża zaległość nie idzie jednym wielkim plikiem). */
    private fun pushLocked(): Int {
        val ops = library.local.ops(settings.uploadedSeq)
        if (ops.isEmpty()) {
            nas.get()                                             // sprawdzenie połączenia
            return 0
        }
        val parts = ops.chunked(CHUNK)
        val dir = RemoteFs.join(syncDir(), "inbox", settings.deviceId)
        for ((i, part) in parts.withIndex()) {
            val json = Protocol.journal(part, settings.deviceId, settings.deviceName, "39droid ${BuildConfig.VERSION_NAME}",
                System.currentTimeMillis() / 1000.0).toString(1).toByteArray(Charsets.UTF_8)
            val partInfo = if (parts.size > 1) " · część ${i + 1}/${parts.size}" else ""
            _status.value = SyncStatus(true, "Wysyłanie zmian do PC: ${describe(ops)} · ${kb(json.size)}$partInfo…",
                if (parts.size > 1) i.toFloat() / parts.size else null)
            val name = String.format(Locale.ROOT, "j-%010d-%010d.json", part.first().seq, part.last().seq)
            nas.use { fs ->
                val tmp = RemoteFs.join(dir, "$name.tmp")
                fs.write(tmp, json)
                fs.rename(tmp, RemoteFs.join(dir, name))
            }
            settings.uploadedSeq = part.last().seq                // wysłana część nie pójdzie drugi raz
        }
        return ops.size
    }

    /** „5 (odtworzenia: 3, serduszka: 2)” – co jest wysyłane. */
    private fun describe(ops: List<LocalDb.Op>): String {
        val names = linkedMapOf("play" to "odtworzenia", "heart" to "serduszka", "mark" to "oznaczenia")
        val counts = ops.groupingBy { names[it.type] ?: "playlisty" }.eachCount()
        return "${ops.size} (" + counts.entries.joinToString(", ") { "${it.key}: ${it.value}" } + ")"
    }

    private fun kb(bytes: Int): String =
        if (bytes < 1024 * 1024) String.format(Locale.ROOT, "%.1f kB", bytes / 1024.0)
        else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

    // ---------------------------------------------------------------- zmiany nieprzyjęte przez PC
    /** Odczyt state/rejected-<urządzenie>.json. Zwraca, ile odrzuconych zmian jest nowych od poprzedniego odczytu. */
    private fun fetchRejected(): Int {
        val json = try {
            String(nas.use { it.read(RemoteFs.join(syncDir(), "state", "rejected-${settings.deviceId}.json")) }, Charsets.UTF_8)
        } catch (e: FileNotFoundException) {
            null
        } ?: return 0
        val before = RejectedChange.parseAll(library.local.get(KV_REJECTED)).maxOfOrNull { it.seq } ?: 0L
        library.local.put(KV_REJECTED, json)
        showRejected(json)
        return RejectedChange.parseAll(json).count { it.seq > before && it.seq > settings.rejectedSeen }
    }

    private fun showRejected(json: String?) {
        val seen = settings.rejectedSeen
        _rejected.value = RejectedChange.parseAll(json).filter { it.seq > seen }.map { c ->
            val t = c.trackId?.let { id -> library.track(id) }?.takeIf { c.rel == null || it.rel.equals(c.rel, ignoreCase = true) }
            c.copy(what = RejectedChange.describe(c, t?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" – ") }))
        }
    }

    /** „Ukryj” – widziane odrzucone zmiany znikają z listy (nowe pojawią się przy następnej synchronizacji). */
    fun dismissRejected() {
        val max = _rejected.value.maxOfOrNull { it.seq } ?: return
        settings.rejectedSeen = maxOf(settings.rejectedSeen, max)
        _rejected.value = emptyList()
    }

    // ---------------------------------------------------------------- migawka z PC ← NAS
    /**
     * Nowa biblioteka z PC. sync39 publikuje ją w dwóch częściach: pełna biblioteka (kilkanaście MB, tylko po zmianach
     * w utworach, VocaDB, kategoriach) i stan (kilkaset kB: serduszka, odtworzenia, playlisty, „Do odsłuchania”,
     * potwierdzenia). Gdy pełna biblioteka na telefonie jest aktualna, pobierany jest tylko stan.
     * [allowFull] = false (sieć komórkowa w tle) – pełna biblioteka czeka na Wi-Fi, stan jest pobierany od razu.
     */
    private fun pullLocked(allowFull: Boolean = true): Boolean {
        val manifest = try {
            Protocol.Manifest.parse(String(nas.use { it.read(RemoteFs.join(syncDir(), "manifest.json")) }, Charsets.UTF_8))
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("Na NAS nie ma jeszcze migawki – uruchom na PC: python -m sync39 export")
        }
        val format = manifest.format
        if (format != 1) throw IllegalStateException("Migawka w formacie $format – zaktualizuj aplikację")
        val sha = manifest.sha256
        val stateRel = manifest.state
        val stateSha = manifest.stateSha256
        val stateBase = manifest.stateBase
        deferredFullBytes = 0
        if (!(sha.isNotEmpty() && sha == settings.snapshotSha && library.ready)) {
            if (!allowFull) {
                deferredFullBytes = manifest.size.coerceAtLeast(1)
                return false
            }
            val dir = library.snapshotDir()
            val fresh = File(dir, "incoming.db")
            val got = downloadGz(manifest.snapshot, manifest.size, "Pobieranie biblioteki…", fresh)
            if (sha.isNotEmpty() && got != sha) {
                fresh.delete()
                throw IllegalStateException("Uszkodzona migawka (suma kontrolna) – spróbuj ponownie")
            }
            _status.value = SyncStatus(true, "Wczytywanie biblioteki…")
            library.install(fresh)
            settings.snapshotSha = sha
            // pełna biblioteka zawiera stan z chwili eksportu – plik stanu z tego samego eksportu nie jest potrzebny
            settings.stateSha = if (stateBase == sha) stateSha else ""
            settings.snapshotCreated = (manifest.createdSec * 1000).toLong()
            files.recomputeAvailable(library)
            return true
        }
        if (stateRel.isEmpty() || stateSha.isEmpty() || stateSha == settings.stateSha || stateBase != settings.snapshotSha) {
            return false
        }
        applyState(stateRel, stateSha, manifest.stateSize)
        settings.stateSha = stateSha
        settings.snapshotCreated = (manifest.stateCreatedSec * 1000).toLong()
        return true
    }

    /**
     * Pozostałości przerwanej synchronizacji (np. telefon wyłączony w trakcie pobierania biblioteki – do kilkudziesięciu
     * MB na karcie). Wołane przy starcie aplikacji, zanim ruszy synchronizacja.
     */
    fun cleanLeftovers(): Int {
        if (mutex.isLocked) return 0
        val dir = library.snapshotDir()
        return listOf("incoming.db", "incoming.db-journal", "state.db", "state.db-journal")
            .count { File(dir, it).let { f -> f.exists() && f.delete() } }
    }

    /** Ile bajtów ma pełna biblioteka odłożona do Wi-Fi (0 – nic nie czeka). */
    @Volatile private var deferredFullBytes = 0L

    /**
     * Mały plik stanu nałożony na kopię obecnej biblioteki (te same zapytania sprawdza test sync39
     * `test_publish_full_then_state_only`), potem zwykła podmiana – widoki odświeżają się jak po pełnej bibliotece.
     */
    private fun applyState(rel: String, sha: String, total: Long) {
        val dir = library.snapshotDir()
        val state = File(dir, "state.db")
        val fresh = File(dir, "incoming.db")
        try {
            val got = downloadGz(rel, total, "Pobieranie zmian z PC…", state)
            if (got != sha) throw IllegalStateException("Uszkodzony plik zmian z PC (suma kontrolna) – spróbuj ponownie")
            _status.value = SyncStatus(true, "Nakładanie zmian z PC…")
            library.snapFile.copyTo(fresh, overwrite = true)
            val db = SQLiteDatabase.openDatabase(fresh.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.execSQL("ATTACH DATABASE ? AS st", arrayOf(state.path))
                val stateFormat = db.rawQuery("SELECT value FROM st.info WHERE key = 'state_format'", null).use {
                    if (it.moveToFirst()) it.getString(0).toIntOrNull() ?: 0 else 0
                }
                if (stateFormat != 1) throw IllegalStateException("Plik zmian z PC w formacie $stateFormat – zaktualizuj aplikację")
                db.beginTransaction()
                try {
                    for (q in Protocol.APPLY_STATE) db.execSQL(q)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.execSQL("DETACH DATABASE st")
            } finally {
                db.close()
            }
            File(fresh.path + "-journal").delete()
            library.install(fresh)
        } catch (e: Exception) {
            fresh.delete()
            throw e
        } finally {
            state.delete()
            File(state.path + "-journal").delete()
        }
    }

    /** Pobiera plik .gz z folderu synchronizacji, rozpakowuje do [dest]. Zwraca sha256 pobranego (spakowanego) pliku. */
    private fun downloadGz(rel: String, total: Long, label: String, dest: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        _status.value = SyncStatus(true, label, if (total > 0) 0f else null)
        nas.use { fs ->
            fs.openStream(RemoteFs.join(syncDir(), rel)).use { raw ->
                val counting = object : java.io.FilterInputStream(raw) {
                    var n = 0L
                    var lastReport = 0L
                    private fun progress() {
                        if (total > 0 && n - lastReport > 256 * 1024) {
                            lastReport = n
                            _status.value = SyncStatus(true, label, (n.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val r = super.read(b, off, len)
                        if (r > 0) {
                            digest.update(b, off, r)
                            n += r
                            progress()
                        }
                        return r
                    }
                    override fun read(): Int {
                        val r = super.read()
                        if (r >= 0) {
                            digest.update(r.toByte())
                            n++
                        }
                        return r
                    }
                    override fun skip(count: Long): Long {
                        val tmp = ByteArray(minOf(count, 8192L).toInt().coerceAtLeast(1))
                        val r = read(tmp, 0, tmp.size)
                        return if (r < 0) 0 else r.toLong()
                    }
                }
                GZIPInputStream(counting, 1 shl 16).use { gz -> dest.outputStream().use { gz.copyTo(it, 1 shl 16) } }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
