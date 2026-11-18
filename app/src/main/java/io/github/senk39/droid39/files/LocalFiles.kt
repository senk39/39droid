package io.github.senk39.droid39.files

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
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
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Pliki dostępne bez sieci:
 *  * „dl” – utwory pobrane do folderu aplikacji (na karcie SD albo w pamięci telefonu),
 *  * „sd” – kopia biblioteki na karcie (np. /storage/XXXX-XXXX/Music): odczytywana przez MediaStore, a gdy folder
 *    jest przejęty (Dostęp do wszystkich plików) – bezpośrednio; wtedy trafiają do niego też pobierane utwory.
 * Dopasowanie po ścieżce względnej wobec folderu muzyki (bez wielkości liter, Unicode NFC).
 *
 * Miejsce na dane (ustawienie „storage”): biblioteka z PC (migawka), okładki, pamięć podręczna strumieni
 * i pobrane – w folderze aplikacji na karcie SD albo w pamięci wewnętrznej. Dziennik zmian (LocalDb) zostaje zawsze
 * w pamięci wewnętrznej.
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
        if (!f.path.startsWith("content://") && !File(f.path).exists()) {
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

    // ---------------------------------------------------------------- woluminy i miejsce na dane
    fun volumes(): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        return dirs.mapIndexed { i, d ->
            val v = sm?.getStorageVolume(d)
            val id = if (v == null || v.isPrimary) "primary" else (v.uuid ?: "sd$i")
            Volume(id, v?.getDescription(context) ?: if (i == 0) "Pamięć wewnętrzna" else "Karta SD", d, v?.isRemovable ?: (i > 0))
        }
    }

    fun primaryVolume(): Volume = volumes().firstOrNull { it.id == "primary" }
        ?: Volume("primary", "Pamięć wewnętrzna", context.getExternalFilesDir(null) ?: context.filesDir, false)

    /** Karta pamięci (folder aplikacji na niej), jeśli jest włożona. */
    fun sdVolume(): Volume? = volumes().firstOrNull { it.removable }

    @Volatile private var dataCache: Pair<Long, Volume>? = null

    /** Wolumin na dane aplikacji wg ustawienia (karta SD; bez karty – pamięć wewnętrzna). */
    fun dataVolume(): Volume {
        dataCache?.let { (at, v) -> if (System.currentTimeMillis() - at < 30_000) return v }
        val v = (if (settings.storage == "internal") null else sdVolume()) ?: primaryVolume()
        dataCache = System.currentTimeMillis() to v
        return v
    }

    fun dataDir(): File = dataVolume().dir

    /** Wołane po zmianie ustawienia miejsca na dane. */
    fun invalidateDataDir() {
        dataCache = null
    }

    fun snapshotDir(): File = File(dataDir(), "snapshot").apply { mkdirs() }
    fun artDir(): File = File(dataDir(), "art")
    fun streamCacheDir(): File = File(dataDir(), "stream-cache")

    /**
     * Po zmianie miejsca na dane (albo pierwszym starcie tej wersji): biblioteka z PC przenoszona do bieżącego folderu
     * danych (najnowsza z kopii), pamięci podręczne (okładki, strumienie) w innych miejscach – usuwane. Pobrane
     * utwory zostają tam, gdzie są (są zapamiętane z pełną ścieżką). Wołać przy zamkniętej bibliotece.
     * `cleanCaches` = false (zmiana w trakcie działania): pamięć strumieni jest w użyciu – sprzątanie przy starcie.
     */
    fun migrateData(cleanCaches: Boolean = true): String? {
        invalidateDataDir()
        val target = dataDir()
        val dest = File(snapshotDir(), "library.db")
        val places = (listOf(File(context.filesDir, "snapshot")) + volumes().map { File(it.dir, "snapshot") })
            .filter { it.canonicalPath != dest.parentFile!!.canonicalPath }
        val others = places.map { File(it, "library.db") }.filter { it.exists() }
        val newest = (others + listOf(dest).filter { it.exists() }).maxByOrNull { it.lastModified() }
        var msg: String? = null
        if (newest != null && newest != dest) {
            val tmp = File(dest.path + ".tmp")
            newest.copyTo(tmp, overwrite = true)
            tmp.setLastModified(newest.lastModified())
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("Nie można przenieść biblioteki do ${dest.parent}")
            File(dest.path + "-journal").delete()
            msg = "Biblioteka przeniesiona: ${dest.parent}"
        }
        for (p in places) {
            File(p, "library.db").delete()
            File(p, "library.db-journal").delete()
            File(p, "incoming.db").delete()
        }
        if (!cleanCaches) return msg
        // pamięci podręczne w innych miejscach (poprzedni folder danych, a przy pierwszym starcie – wszystkie woluminy)
        val old = settings.dataDirUsed
        val stale = if (old.isEmpty()) volumes().map { it.dir } else listOf(File(old))
        for (d in stale) {
            if (d.canonicalPath == target.canonicalPath) continue
            File(d, "art").deleteRecursively()
            File(d, "stream-cache").deleteRecursively()
        }
        settings.dataDirUsed = target.path
        return msg
    }

    // ---------------------------------------------------------------- przejęty folder muzyki na karcie
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Przejęty folder muzyki, gdy jest ustawiony, dostępny i aplikacja ma Dostęp do wszystkich plików. */
    fun managedRoot(): File? {
        val path = settings.managedFolder.trim().trimEnd('/')
        if (path.isEmpty() || !hasAllFilesAccess()) return null
        return File(path).takeIf { it.isDirectory }
    }

    fun isManaged(f: LocalDb.LocalFile, root: File? = managedRoot()): Boolean =
        root != null && !f.path.startsWith("content://") && f.path.startsWith(root.path + "/")

    fun managedFiles(): List<LocalDb.LocalFile> {
        val root = managedRoot() ?: return emptyList()
        return index.values.filter { isManaged(it, root) }
    }

    fun volumeOf(file: File): Volume? {
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        val v = sm.getStorageVolume(file) ?: return null
        val dir = v.directory ?: return null
        return Volume(if (v.isPrimary) "primary" else (v.uuid ?: "sd"), v.getDescription(context), dir, v.isRemovable)
    }

    /** Wolumin, na który trafiają pobierane utwory (przejęty folder albo folder danych). */
    fun downloadVolume(): Volume = managedRoot()?.let { volumeOf(it) } ?: dataVolume()

    /** Wolne miejsce, którego aplikacja nie zajmie nigdy (pobrane i pamięć podręczna). */
    fun reserveBytes(): Long = RESERVE

    fun downloadRoot(): File = managedRoot() ?: File(dataDir(), "Muzyka").apply { mkdirs() }

    fun downloadTarget(rel: String): File = File(downloadRoot(), rel.replace('\\', '/'))

    /**
     * Plik tymczasowy pobierania: w przejętym folderze – w folderze aplikacji na tej samej karcie (w folderze muzyki
     * nie zostają niedokończone pliki), a gotowy plik jest przenoszony na miejsce.
     */
    fun tempFor(target: File): File {
        val root = managedRoot()
        if (root != null && target.path.startsWith(root.path + "/")) {
            val sm = context.getSystemService(StorageManager::class.java)
            val vol = sm?.getStorageVolume(root)
            val appDir = context.getExternalFilesDirs(null).filterNotNull().firstOrNull { sm?.getStorageVolume(it) == vol }
            if (appDir != null) {
                val tmpDir = File(appDir, "tmp").apply { mkdirs() }
                return File(tmpDir, "dl-" + Integer.toHexString(target.path.hashCode()) + ".part")
            }
        }
        return File(target.path + ".part")
    }

    /** Usuwa niedokończone pobrania (np. po przerwaniu aplikacji). */
    fun cleanTemp() {
        for (v in volumes()) File(v.dir, "tmp").listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
    }

    /**
     * Przenosi gotowy plik na miejsce: zmiana nazwy (podmienia istniejący plik w jednym kroku), a gdy system jej nie
     * pozwala (inny folder na karcie) – kopia obok jako plik ukryty i dopiero wtedy podmiana. Stary plik znika dopiero,
     * gdy nowy jest w całości na karcie.
     */
    fun moveInto(tmp: File, target: File) {
        target.parentFile?.mkdirs()
        if (tmp.renameTo(target)) return
        val side = File(target.parentFile, ".${target.name}.39part")
        try {
            tmp.copyTo(side, overwrite = true)
        } catch (e: IOException) {
            side.delete()
            throw e
        } finally {
            tmp.delete()
        }
        if (side.renameTo(target)) return
        if (target.exists() && !target.delete()) {
            side.delete()
            throw IOException("Nie można zastąpić ${target.name}")
        }
        if (!side.renameTo(target)) throw IOException("Nie można zapisać ${target.name}")
    }

    fun registerDownload(rel: String, file: File) {
        val source = if (managedRoot()?.let { file.path.startsWith(it.path + "/") } == true) "sd" else "dl"
        val f = LocalDb.LocalFile(Text.relKey(rel), file.path, source, file.length())
        db.putFile(f)
        index = index + (f.relKey to f)
        if (source == "sd") notifyMedia(listOf(file.path))
    }

    fun removeDownload(rel: String) {
        val f = find(rel) ?: return
        if (f.source != "dl") return                              // kopii na karcie nie ruszamy
        File(f.path).delete()
        db.removeFile(f.relKey)
        index = index - f.relKey
    }

    /**
     * Usuwa pliki z przejętego folderu (po potwierdzeniu albo przy zwalnianiu miejsca) i pobrane do folderu aplikacji.
     * Pliki spoza tych miejsc nie są ruszane. Zwraca (liczba, bajty).
     */
    fun deleteLocal(list: List<LocalDb.LocalFile>): Pair<Int, Long> {
        val root = managedRoot()
        var n = 0
        var bytes = 0L
        val gone = ArrayList<String>()
        val dirs = HashSet<File>()
        for (f in list) {
            if (f.source != "dl" && !isManaged(f, root)) continue
            val file = File(f.path)
            if (!file.exists() || file.delete()) {
                db.removeFile(f.relKey)
                n++
                bytes += f.size
                gone += file.path
                file.parentFile?.let { dirs += it }
            }
        }
        // puste foldery po usuniętych plikach (do folderu muzyki włącznie – bez niego)
        val stop = (root ?: File(dataDir(), "Muzyka")).path
        for (d in dirs.sortedByDescending { it.path.length }) {
            var cur: File? = d
            while (cur != null && cur.path.startsWith("$stop/") && (cur.list()?.isEmpty() == true)) {
                cur.delete()
                cur = cur.parentFile
            }
        }
        load()
        notifyMedia(gone)
        return n to bytes
    }

    /** MediaStore (inne aplikacje muzyczne) – informacja o dodanych i usuniętych plikach. */
    fun notifyMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        paths.chunked(400).forEach { MediaScannerConnection.scanFile(context, it.toTypedArray(), null, null) }
    }

    fun downloaded(): List<LocalDb.LocalFile> = index.values.filter { it.source == "dl" }

    fun downloadedBytes(): Long = downloaded().sumOf { it.size }

    /** Odtwarza indeks pobranych plików z dysku (np. po wyczyszczeniu danych aplikacji). */
    fun scanDownloads() {
        val found = ArrayList<LocalDb.LocalFile>()
        for (v in volumes()) {
            val root = File(v.dir, "Muzyka")
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { f ->
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                found += LocalDb.LocalFile(Text.relKey(rel), f.path, "dl", f.length())
            }
        }
        db.replaceSource("dl", found)
        load()
    }

    // ---------------------------------------------------------------- kopia biblioteki na karcie
    fun hasAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Skanuje foldery z kopią biblioteki. Zwraca liczbę plików albo komunikat błędu. */
    fun scanMirrors(): Result<Int> {
        val managed = managedRoot()
        val folders = (settings.mirrorList() + listOfNotNull(managed?.path)).distinct()
        if (folders.isEmpty()) {
            db.replaceSource("sd", emptyList())
            load()
            return Result.success(0)
        }
        if (managed == null && !hasAudioPermission()) {
            return Result.failure(SecurityException("Brak uprawnienia do odczytu muzyki"))
        }
        val sm = context.getSystemService(StorageManager::class.java)
        val found = ArrayList<LocalDb.LocalFile>()
        for (folder in folders) {
            val dir = File(folder)
            if (managed != null && dir.path == managed.path) {
                found += scanDirect(dir)
                continue
            }
            if (!hasAudioPermission()) continue
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

    /** Przejęty folder – odczyt bezpośrednio z karty (ścieżki plików, aktualne rozmiary). */
    private fun scanDirect(root: File): List<LocalDb.LocalFile> {
        val out = ArrayList<LocalDb.LocalFile>()
        val base = root.toPath()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != base && dir.fileName.toString().startsWith(".")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val name = file.fileName.toString()
                if (attrs.isRegularFile && name.substringAfterLast('.', "").lowercase() in AUDIO_EXT) {
                    val rel = base.relativize(file).toString().replace(File.separatorChar, '/')
                    out += LocalDb.LocalFile(Text.relKey(rel), file.toString(), "sd", attrs.size())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return out
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
        val AUDIO_EXT = setOf("mp3", "flac", "m4a", "mp4", "aac", "wav", "ogg", "oga", "opus", "wma", "aif", "aiff", "ape", "wv", "alac")
    }
}
