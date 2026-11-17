package io.github.senk39.droid39.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.files.LocalFiles
import io.github.senk39.droid39.sync.Nas
import io.github.senk39.droid39.sync.RemoteFs
import java.io.File

/**
 * Źródło danych utworu „d39://t/<id>?rel=…&size=…”: najpierw plik lokalny (pobrany albo kopia na karcie),
 * a gdy go brak – strumień z NAS przez pamięć podręczną (LRU).
 */
@UnstableApi
class RoutingDataSource(
    private val context: Context,
    private val settings: Settings,
    private val files: LocalFiles,
    private val nas: Nas,
    private val cache: Cache,
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME) return use(FileDataSource(), dataSpec)
        val rel = uri.getQueryParameter("rel") ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val id = uri.pathSegments.firstOrNull() ?: "0"
        val size = uri.getQueryParameter("size") ?: "0"
        val local = files.resolve(rel)
        if (local != null) {
            return if (local.path.startsWith("content://")) {
                use(ContentDataSource(context), dataSpec.buildUpon().setUri(Uri.parse(local.path)).build())
            } else {
                use(FileDataSource(), dataSpec.buildUpon().setUri(Uri.fromFile(File(local.path))).build())
            }
        }
        if (!settings.nasConfigured) {
            throw DataSourceException("Utwór nie jest zapisany na telefonie, a NAS nie jest skonfigurowany",
                null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        if (!nas.streamingAllowed()) {
            throw DataSourceException("Strumieniowanie przez sieć komórkową jest wyłączone (Ustawienia)",
                null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val fs = try {
            nas.get()
        } catch (e: Exception) {
            throw DataSourceException(e.message ?: "Brak połączenia z NAS", e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        val remote = fs.uriFor(RemoteFs.join(settings.musicPath, rel))
        val upstream = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(fs.dataSourceFactory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        return use(upstream, dataSpec.buildUpon().setUri(remote).setKey("t$id-$size").build())
    }

    private fun use(ds: DataSource, spec: DataSpec): Long {
        listeners.forEach { ds.addTransferListener(it) }
        delegate = ds
        return ds.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IllegalStateException("Źródło nie jest otwarte")

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    class Factory(
        private val context: Context,
        private val settings: Settings,
        private val files: LocalFiles,
        private val nas: Nas,
        private val cache: Cache,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context, settings, files, nas, cache)
    }

    companion object {
        const val SCHEME = "d39"

        fun uri(id: Int, rel: String, size: Long): Uri = Uri.Builder().scheme(SCHEME).authority("t")
            .appendPath(id.toString()).appendQueryParameter("rel", rel).appendQueryParameter("size", size.toString()).build()
    }
}
