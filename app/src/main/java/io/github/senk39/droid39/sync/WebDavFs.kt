package io.github.senk39.droid39.sync

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** WebDAV (UGOS Pro: Panel sterowania → Usługi plików → WebDAV, porty 5005 HTTP / 5006 HTTPS). */
@UnstableApi
class WebDavFs(
    host: String,
    port: Int,
    https: Boolean,
    basePath: String,
    user: String,
    password: String,
) : RemoteFs {
    private val base: String = buildString {
        append(if (https) "https://" else "http://")
        append(host)
        val p = if (port > 0) port else if (https) 5006 else 5005
        append(':').append(p)
        val b = basePath.trim('/')
        if (b.isNotEmpty()) append('/').append(b.split('/').joinToString("/") { Uri.encode(it) })
    }
    override val label: String = base

    private val auth: String? = if (user.isNotBlank()) Credentials.basic(user, password, Charsets.UTF_8) else null

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(if (auth != null && r.header("Authorization") == null) r.newBuilder().header("Authorization", auth).build() else r)
        }
        .build()

    fun url(path: String): String =
        base + "/" + path.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }

    private fun call(req: Request) = client.newCall(req).execute()

    override fun ping() {
        val req = Request.Builder().url("$base/").method("PROPFIND", ByteArray(0).toRequestBody(null)).header("Depth", "0").build()
        call(req).use { r ->
            when {
                r.code == 401 || r.code == 403 -> throw RemoteUnavailable("WebDAV: błędny użytkownik lub hasło (${r.code})")
                r.code == 404 -> throw RemoteUnavailable("WebDAV: nie ma ścieżki $label")
                r.code >= 500 -> throw IOException("WebDAV: błąd serwera ${r.code}")
            }
        }
    }

    override fun exists(path: String): Boolean = size(path) != null

    override fun size(path: String): Long? {
        call(Request.Builder().url(url(path)).head().build()).use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("WebDAV HEAD ${r.code}")
            return r.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    override fun read(path: String): ByteArray {
        call(Request.Builder().url(url(path)).get().build()).use { r ->
            if (r.code == 404) throw FileNotFoundException(path)
            if (r.code == 401) throw RemoteUnavailable("WebDAV: błędny użytkownik lub hasło")
            if (!r.isSuccessful) throw IOException("WebDAV GET ${r.code}")
            return r.body!!.bytes()
        }
    }

    override fun openStream(path: String): InputStream {
        val r = call(Request.Builder().url(url(path)).get().build())
        if (r.code == 404) {
            r.close(); throw FileNotFoundException(path)
        }
        if (!r.isSuccessful) {
            r.close(); throw IOException("WebDAV GET ${r.code}")
        }
        return r.body!!.byteStream()
    }

    private fun mkcol(dir: String) {
        var cur = ""
        for (p in dir.trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) p else "$cur/$p"
            call(Request.Builder().url(url(cur) + "/").method("MKCOL", null).build()).use { r ->
                if (!(r.isSuccessful || r.code == 405 || r.code == 301)) throw IOException("WebDAV MKCOL ${r.code}")
            }
        }
    }

    override fun write(path: String, data: ByteArray) {
        val dir = path.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) mkcol(dir)
        call(Request.Builder().url(url(path)).put(data.toRequestBody("application/octet-stream".toMediaType())).build()).use { r ->
            if (!r.isSuccessful) throw IOException("WebDAV PUT ${r.code}")
        }
    }

    override fun rename(from: String, to: String) {
        val req = Request.Builder().url(url(from)).method("MOVE", null)
            .header("Destination", url(to)).header("Overwrite", "T").build()
        call(req).use { r -> if (!r.isSuccessful) throw IOException("WebDAV MOVE ${r.code}") }
    }

    override fun delete(path: String) {
        call(Request.Builder().url(url(path)).delete().build()).use { r ->
            if (!(r.isSuccessful || r.code == 404)) throw IOException("WebDAV DELETE ${r.code}")
        }
    }

    override fun dataSourceFactory(): DataSource.Factory = OkHttpDataSource.Factory(client)

    override fun uriFor(path: String): Uri = Uri.parse(url(path))

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
