package io.github.senk39.droid39.sync

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.common.PlaybackException
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Udział SMB2/3 (smbj). Jedno połączenie wielokrotnego użytku, odnawiane po zerwaniu. */
@UnstableApi
class SmbFs(
    private val host: String,
    private val port: Int,
    private val shareName: String,
    private val user: String,
    private val password: String,
) : RemoteFs {

    override val label: String = "smb://$host${if (port != 445 && port > 0) ":$port" else ""}/$shareName"

    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withAuthenticators(NtlmAuthenticator.Factory())
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .build()
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null

    @Synchronized
    fun share(): DiskShare {
        disk?.let { if (it.isConnected) return it }
        closeQuietly()
        try {
            val c = client.connect(host, if (port > 0) port else SMBClient.DEFAULT_PORT)
            val auth = if (user.isBlank()) AuthenticationContext.guest()
            else {
                val (domain, name) = if ('\\' in user) user.substringBefore('\\') to user.substringAfter('\\') else "" to user
                AuthenticationContext(name, password.toCharArray(), domain)
            }
            val s = c.authenticate(auth)
            val d = s.connectShare(shareName) as? DiskShare
                ?: throw RemoteUnavailable("„$shareName” nie jest udziałem plików")
            connection = c; session = s; disk = d
            return d
        } catch (e: RemoteUnavailable) {
            throw e
        } catch (e: SMBApiException) {
            throw RemoteUnavailable("SMB: ${e.status} (sprawdź udział, użytkownika i hasło)", e)
        } catch (e: IOException) {
            throw RemoteUnavailable("Brak połączenia z $host: ${e.message}", e)
        }
    }

    override fun ping() {
        share()
    }

    private fun smb(path: String) = path.trim('/').replace('/', '\\')

    fun openRead(path: String): SmbFile = try {
        share().openFile(
            smb(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null
        )
    } catch (e: SMBApiException) {
        if (e.status.name.contains("NOT_FOUND") || e.status.name.contains("NAME_INVALID")) throw FileNotFoundException(path)
        throw IOException("SMB ${e.status}: $path", e)
    }

    override fun exists(path: String): Boolean = try {
        share().fileExists(smb(path))
    } catch (e: SMBApiException) {
        false
    }

    override fun size(path: String): Long? = try {
        share().getFileInformation(smb(path)).standardInformation.endOfFile
    } catch (e: SMBApiException) {
        null
    }

    override fun read(path: String): ByteArray = openRead(path).use { f ->
        val out = ByteArrayOutputStream()
        f.read(out)
        out.toByteArray()
    }

    override fun openStream(path: String): InputStream {
        val f = openRead(path)
        val inner = f.inputStream
        return object : InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() {
                try { inner.close() } finally { f.close() }
            }
        }
    }

    private fun mkdirs(dir: String) {
        if (dir.isEmpty()) return
        val d = share()
        val parts = dir.trim('/').split('/')
        var cur = ""
        for (p in parts) {
            cur = if (cur.isEmpty()) p else "$cur\\$p"
            if (!d.folderExists(cur)) d.mkdir(cur)
        }
    }

    override fun write(path: String, data: ByteArray) {
        mkdirs(path.substringBeforeLast('/', ""))
        share().openFile(
            smb(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF, null
        ).use { f -> f.write(data, 0) }
    }

    override fun rename(from: String, to: String) {
        share().openFile(
            smb(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null
        ).use { f -> f.rename(smb(to), true) }
    }

    override fun delete(path: String) {
        try {
            share().rm(smb(path))
        } catch (e: SMBApiException) {
            if (!e.status.name.contains("NOT_FOUND")) throw IOException("SMB ${e.status}: $path", e)
        }
    }

    override fun dataSourceFactory(): DataSource.Factory = DataSource.Factory { SmbDataSource(this) }

    override fun uriFor(path: String): Uri = Uri.Builder().scheme("smb").authority(host).appendEncodedPath(
        path.trim('/').split('/').joinToString("/") { Uri.encode(it) }
    ).build()

    private fun closeQuietly() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        disk = null; session = null; connection = null
    }

    @Synchronized
    override fun close() {
        closeQuietly()
        runCatching { client.close() }
    }
}

/** Strumieniowanie pliku z udziału SMB dla ExoPlayera – odczyt z buforem (mniej zapytań przez sieć). */
@UnstableApi
class SmbDataSource(private val fs: SmbFs) : BaseDataSource(true) {
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private val buf = ByteArray(256 * 1024)
    private var bufStart = 0L
    private var bufLen = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val path = dataSpec.uri.pathSegments.joinToString("/")
        val f = try {
            fs.openRead(path)
        } catch (e: FileNotFoundException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f
        val size = f.fileInformation.standardInformation.endOfFile
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        bufLen = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("Plik zamknięty")
        if (position < bufStart || position >= bufStart + bufLen) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            var got = 0
            while (got < want) {
                val n = try {
                    f.read(buf, position + got, got, want - got)
                } catch (e: Exception) {
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                if (n <= 0) break
                got += n
            }
            if (got == 0) return C.RESULT_END_OF_INPUT
            bufStart = position
            bufLen = got
        }
        val inBuf = (position - bufStart).toInt()
        val n = minOf(length.toLong(), (bufLen - inBuf).toLong(), remaining).toInt()
        System.arraycopy(buf, inBuf, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            file?.closeNoWait()
        } catch (_: Exception) {
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
