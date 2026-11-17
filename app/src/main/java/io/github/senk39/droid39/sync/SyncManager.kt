package io.github.senk39.droid39.sync

import android.content.Context
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

    /** Callback po zainstalowaniu nowej migawki (np. planowanie pobierania offline). */
    var onSnapshotInstalled: (() -> Unit)? = null

    private fun syncDir() = settings.syncPath.ifBlank { "39sync" }

    /** Pełna synchronizacja. Zwraca opis wyniku. */
    suspend fun syncNow(): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            _status.value = SyncStatus(true, "Łączenie z NAS…")
            try {
                val sent = pushLocked()
                _status.value = SyncStatus(true, "Sprawdzanie migawki biblioteki…")
                val pulled = pullLocked()
                settings.lastSyncAt = System.currentTimeMillis()
                settings.lastSyncError = ""
                val msg = buildString {
                    append(if (pulled) "Pobrano nową bibliotekę z PC" else "Biblioteka aktualna")
                    if (sent > 0) append(" · wysłano zmian: $sent")
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
            val dir = File(context.filesDir, "snapshot").apply { mkdirs() }
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

    /** Wysłanie dziennika z opóźnieniem (zbiera kilka szybkich zmian w jeden plik). */
    fun schedulePush(delayMs: Long = 20_000) {
        pushJob?.cancel()
        pushJob = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            if (!settings.nasConfigured || nas.recentlyFailed(60_000)) return@launch
            runCatching { mutex.withLock { pushLocked() } }
        }
    }

    // ---------------------------------------------------------------- dziennik zmian → NAS
    private fun pushLocked(): Int {
        val ops = library.local.ops(settings.uploadedSeq)
        if (ops.isEmpty()) {
            nas.get()                                             // sprawdzenie połączenia
            return 0
        }
        _status.value = SyncStatus(true, "Wysyłanie zmian (${ops.size})…")
        val first = ops.first().seq
        val last = ops.last().seq
        val json = journal(ops)
        val name = String.format(Locale.ROOT, "j-%010d-%010d.json", first, last)
        val dir = RemoteFs.join(syncDir(), "inbox", settings.deviceId)
        nas.use { fs ->
            val tmp = RemoteFs.join(dir, "$name.tmp")
            fs.write(tmp, json.toByteArray(Charsets.UTF_8))
            fs.rename(tmp, RemoteFs.join(dir, name))
        }
        settings.uploadedSeq = last
        return ops.size
    }

    private fun journal(ops: List<LocalDb.Op>): String {
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
                "pl_create" -> e.put("playlist", op.playlist)
            }
            events.put(e)
        }
        return JSONObject()
            .put("format", 1)
            .put("device", settings.deviceId)
            .put("device_name", settings.deviceName)
            .put("app", "39droid ${BuildConfig.VERSION_NAME}")
            .put("created", System.currentTimeMillis() / 1000.0)
            .put("first_seq", ops.first().seq)
            .put("last_seq", ops.last().seq)
            .put("events", events)
            .toString(1)
    }

    // ---------------------------------------------------------------- migawka z PC ← NAS
    private fun pullLocked(): Boolean {
        val manifest = try {
            JSONObject(String(nas.use { it.read(RemoteFs.join(syncDir(), "manifest.json")) }, Charsets.UTF_8))
        } catch (e: FileNotFoundException) {
            throw IllegalStateException("Na NAS nie ma jeszcze migawki – uruchom na PC: python -m sync39 export")
        }
        val format = manifest.optInt("format", 0)
        if (format != 1) throw IllegalStateException("Migawka w formacie $format – zaktualizuj aplikację")
        val sha = manifest.optString("sha256")
        if (sha.isNotEmpty() && sha == settings.snapshotSha && library.ready) return false
        val rel = manifest.getString("snapshot")
        val total = manifest.optLong("size", 0)
        val dir = File(context.filesDir, "snapshot").apply { mkdirs() }
        val fresh = File(dir, "incoming.db")
        val digest = MessageDigest.getInstance("SHA-256")
        _status.value = SyncStatus(true, "Pobieranie biblioteki…", 0f)
        nas.use { fs ->
            fs.openStream(RemoteFs.join(syncDir(), rel)).use { raw ->
                val counting = object : java.io.FilterInputStream(raw) {
                    var n = 0L
                    var lastReport = 0L
                    private fun progress() {
                        if (total > 0 && n - lastReport > 256 * 1024) {
                            lastReport = n
                            _status.value = SyncStatus(true, "Pobieranie biblioteki…", (n.toFloat() / total).coerceIn(0f, 1f))
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
                GZIPInputStream(counting, 1 shl 16).use { gz -> fresh.outputStream().use { gz.copyTo(it, 1 shl 16) } }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha.isNotEmpty() && got != sha) {
            fresh.delete()
            throw IllegalStateException("Uszkodzona migawka (suma kontrolna) – spróbuj ponownie")
        }
        _status.value = SyncStatus(true, "Wczytywanie biblioteki…")
        library.install(fresh)
        settings.snapshotSha = sha
        settings.snapshotCreated = (manifest.optDouble("created", 0.0) * 1000).toLong()
        files.recomputeAvailable(library)
        return true
    }
}
