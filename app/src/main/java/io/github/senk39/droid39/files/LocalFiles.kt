package io.github.senk39.droid39.files

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.core.Text
import io.github.senk39.droid39.data.Library
import io.github.senk39.droid39.data.LocalDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Pliki dostępne bez sieci:
 *  * „dl” – utwory pobrane przez aplikację (folder aplikacji na karcie SD albo w pamięci telefonu),
 *  * „sd” – istniejąca kopia biblioteki na karcie (np. /storage/XXXX-XXXX/MUZYKA), odczytywana przez MediaStore.
 * Dopasowanie po ścieżce względnej wobec folderu muzyki (bez wielkości liter, Unicode NFC).
 */
class LocalFiles(private val context: Context, private val settings: Settings, private val db: LocalDb) {
    @Volatile private var index: Map<String, LocalDb.LocalFile> = emptyMap()
    @Volatile var availableIds: Set<Int> = emptySet()
        private set
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    data class Volume(val id: String, val label: String, val dir: File, val removable: Boolean) {
        val free: Long get() = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)
        val total: Long get() = runCatching { StatFs(dir.path).totalBytes }.getOrDefault(0L)
    }

    fun load() {
        index = db.files().associateBy { it.relKey }
    }

    fun find(rel: String): LocalDb.LocalFile? = index[Text.relKey(rel)]

    /** Pobrany plik albo kopia na karcie, jeśli nadal istnieje. */
    fun resolve(rel: String): LocalDb.LocalFile? {
        val f = find(rel) ?: return null
        if (f.source == "dl" && !File(f.path).exists()) {
            db.removeFile(f.relKey)
            index = index - f.relKey
            return null
        }
        return f
    }

    fun recomputeAvailable(library: Library) {
        val idx = index
        availableIds = library.withSnap { s ->
            val out = HashSet<Int>()
            for ((id, rel, _) in s.allIdsWithSize()) if (idx.containsKey(Text.relKey(rel))) out += id
            out
        } ?: emptySet()
        _version.value++
    }

    fun isLocal(id: Int) = id in availableIds

    // ---------------------------------------------------------------- foldery pobierania
    fun volumes(): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        return dirs.mapIndexed { i, d ->
            val v = sm?.getStorageVolume(d)
            val id = if (v == null || v.isPrimary) "primary" else (v.uuid ?: "sd$i")
            Volume(id, v?.getDescription(context) ?: if (i == 0) "Pamięć wewnętrzna" else "Karta SD", d, v?.isRemovable ?: (i > 0))
        }
    }

    /** Wolumin na pobrane: wybrany w ustawieniach, a „auto” – ten z największą ilością wolnego miejsca. */
    fun downloadVolume(): Volume {
        val vols = volumes()
        val want = settings.downloadVolume
        return vols.firstOrNull { it.id == want }
            ?: vols.maxByOrNull { it.free }
            ?: Volume("primary", "Pamięć wewnętrzna", context.filesDir, false)
    }

    /** Wolne miejsce, którego aplikacja nie zajmie nigdy (pobrane i pamięć podręczna). */
    fun reserveBytes(): Long = RESERVE

    fun downloadRoot(): File = File(downloadVolume().dir, "Muzyka").apply { mkdirs() }

    fun downloadTarget(rel: String): File = File(downloadRoot(), rel.replace('\\', '/'))

    fun registerDownload(rel: String, file: File) {
        val f = LocalDb.LocalFile(Text.relKey(rel), file.path, "dl", file.length())
        db.putFile(f)
        index = index + (f.relKey to f)
    }

    fun removeDownload(rel: String) {
        val f = find(rel) ?: return
        if (f.source != "dl") return                              // kopii użytkownika na karcie nie ruszamy
        File(f.path).delete()
        db.removeFile(f.relKey)
        index = index - f.relKey
    }

    fun downloaded(): List<LocalDb.LocalFile> = index.values.filter { it.source == "dl" }

    fun downloadedBytes(): Long = downloaded().sumOf { it.size }

    /** Odtwarza indeks pobranych plików z dysku (np. po wyczyszczeniu danych aplikacji). */
    fun scanDownloads() {
        val root = downloadRoot()
        val found = ArrayList<LocalDb.LocalFile>()
        root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { f ->
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            found += LocalDb.LocalFile(Text.relKey(rel), f.path, "dl", f.length())
        }
        db.replaceSource("dl", found)
        load()
    }

    // ---------------------------------------------------------------- kopia biblioteki na karcie (MediaStore)
    fun hasAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Skanuje foldery z kopią biblioteki. Zwraca liczbę plików albo komunikat błędu. */
    fun scanMirrors(): Result<Int> {
        val folders = settings.mirrorList()
        if (folders.isEmpty()) {
            db.replaceSource("sd", emptyList())
            load()
            return Result.success(0)
        }
        if (!hasAudioPermission()) return Result.failure(SecurityException("Brak uprawnienia do odczytu muzyki"))
        val sm = context.getSystemService(StorageManager::class.java)
        val found = ArrayList<LocalDb.LocalFile>()
        for (folder in folders) {
            val dir = File(folder)
            val vol = sm?.getStorageVolume(dir) ?: continue
            val volDir = vol.directory ?: continue
            val volumeName = vol.mediaStoreVolumeName ?: continue
            val relPrefix = dir.path.removePrefix(volDir.path).trim('/').let { if (it.isEmpty()) "" else "$it/" }
            val uri = MediaStore.Audio.Media.getContentUri(volumeName)
            val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE)
            val sel = if (relPrefix.isEmpty()) null else "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            val args = if (relPrefix.isEmpty()) null else arrayOf("$relPrefix%")
            context.contentResolver.query(uri, proj, sel, args, null)?.use { c ->
                val prefixKey = relPrefix.lowercase()
                while (c.moveToNext()) {
                    val relPath = c.getString(1) ?: continue
                    if (!relPath.lowercase().startsWith(prefixKey)) continue
                    val rel = relPath.substring(relPrefix.length) + (c.getString(2) ?: continue)
                    val contentUri = ContentUris.withAppendedId(uri, c.getLong(0))
                    found += LocalDb.LocalFile(Text.relKey(rel), contentUri.toString(), "sd", c.getLong(3))
                }
            }
        }
        db.replaceSource("sd", found)
        load()
        settings.lastMirrorScan = System.currentTimeMillis()
        return Result.success(found.size)
    }

    /** Proponowane foldery kopii: foldery główne kart pamięci (np. /storage/XXXX/MUZYKA, jeśli istnieje). */
    fun suggestMirrors(): List<String> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.mapNotNull { it.directory }.flatMap { root ->
            listOf("MUZYKA", "Muzyka", "Music/MUZYKA", "Music").map { File(root, it) }.filter { it.isDirectory }.map { it.path }
        }.distinct()
    }

    companion object {
        const val RESERVE = 3L * 1024 * 1024 * 1024

        fun primaryMusicDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
    }
}
