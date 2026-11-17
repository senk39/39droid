package io.github.senk39.droid39.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.files.LocalFiles
import io.github.senk39.droid39.sync.Nas
import io.github.senk39.droid39.sync.RemoteFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Miniatury okładek: pamięć → plik w folderze aplikacji → miniatura z NAS (<sync>/art/xx/klucz.jpg, tworzona
 * przez sync39) → okładka wbudowana w lokalny plik utworu.
 */
@UnstableApi
class ArtLoader(
    private val context: Context,
    private val settings: Settings,
    private val nas: Nas,
    private val files: LocalFiles,
) {
    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "art").apply { mkdirs() }
    private val mem = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = ConcurrentHashMap<String, Long>()
    private val net = Semaphore(4)

    /** Ścieżki utworów z daną okładką – dla kategorii i półek (bez ścieżki), gdy NAS jest niedostępny. */
    var relsFor: ((String) -> List<String>)? = null

    private fun extractAny(key: String, rel: String?): Boolean {
        if (extractLocal(key, rel)) return true
        if (rel != null) return false
        val rels = relsFor?.invoke(key) ?: return false
        return rels.any { files.find(it) != null && extractLocal(key, it) }
    }

    fun file(key: String) = File(dir, "${key.take(2)}/$key.jpg")

    private fun memKey(key: String, px: Int) = "$key@${if (px <= 200) "s" else "l"}"

    fun peek(key: String?, px: Int): Bitmap? = key?.let { mem.get(memKey(it, px)) }

    suspend fun load(key: String?, rel: String?, px: Int): Bitmap? {
        if (key == null) return null
        mem.get(memKey(key, px))?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(key)
            if (!f.exists()) {
                val miss = missing[key]
                if (miss != null && System.currentTimeMillis() - miss < 10 * 60_000) return@withContext null
                if (!fetchRemote(key) && !extractAny(key, rel)) {
                    missing[key] = System.currentTimeMillis()
                    return@withContext null
                }
            }
            decode(f, px)?.also { mem.put(memKey(key, px), it) }
        }
    }

    /**
     * Duża okładka do odtwarzacza: z pliku na telefonie w pełnej rozdzielczości (jak w PowerAmp),
     * a dla utworów strumieniowanych – miniatura.
     */
    suspend fun loadLarge(key: String?, rel: String?, px: Int): Bitmap? {
        if (rel != null) {
            val mk = "L:$rel"
            mem.get(mk)?.let { return it }
            val big = withContext(Dispatchers.IO) {
                val lf = files.resolve(rel) ?: return@withContext null
                runCatching {
                    val mmr = MediaMetadataRetriever()
                    try {
                        if (lf.path.startsWith("content://")) mmr.setDataSource(context, Uri.parse(lf.path)) else mmr.setDataSource(lf.path)
                        val pic = mmr.embeddedPicture ?: return@runCatching null
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
                        var sample = 1
                        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
                        BitmapFactory.decodeByteArray(pic, 0, pic.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    } finally {
                        mmr.release()
                    }
                }.getOrNull()
            }
            if (big != null) {
                mem.put(mk, big)
                return big
            }
        }
        return load(key, rel, px)
    }

    /** Ścieżka pliku miniatury (pobiera ją w razie potrzeby) – np. dla powiadomienia odtwarzacza. */
    fun ensureFile(key: String?, rel: String?): File? {
        if (key == null) return null
        val f = file(key)
        if (f.exists() || fetchRemote(key) || extractAny(key, rel)) return f.takeIf { it.exists() }
        return null
    }

    suspend fun prefetch(key: String?, rel: String?) {
        if (key == null || file(key).exists()) return
        withContext(Dispatchers.IO) { if (!fetchRemote(key)) extractAny(key, rel) }
    }

    private fun decode(f: File, px: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun fetchRemote(key: String): Boolean {
        if (!settings.nasConfigured) return false
        if (nas.peek() == null && nas.recentlyFailed()) return false
        if (!nas.streamingAllowed()) return false
        return runCatching {
            val bytes = kotlinx.coroutines.runBlocking {
                net.withPermit { nas.use { it.read(RemoteFs.join(settings.syncPath, "art", key.take(2), "$key.jpg")) } }
            }
            save(key, bytes)
            true
        }.getOrElse { e -> if (e is FileNotFoundException) false else false }
    }

    private fun extractLocal(key: String, rel: String?): Boolean {
        if (rel == null) return false
        val lf = files.resolve(rel) ?: return false
        return runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                if (lf.path.startsWith("content://")) mmr.setDataSource(context, Uri.parse(lf.path)) else mmr.setDataSource(lf.path)
                val pic = mmr.embeddedPicture ?: return@runCatching false
                val bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size) ?: return@runCatching false
                val scaled = scaleDown(bmp, 512)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                save(key, out.toByteArray())
                true
            } finally {
                mmr.release()
            }
        }.getOrDefault(false)
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val s = maxOf(b.width, b.height)
        if (s <= max) return b
        val f = max.toFloat() / s
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun save(key: String, bytes: ByteArray) {
        val f = file(key)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        missing.remove(key)
    }

    fun cacheBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
