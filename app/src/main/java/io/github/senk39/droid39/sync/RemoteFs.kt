package io.github.senk39.droid39.sync

import android.net.Uri
import androidx.media3.datasource.DataSource
import java.io.Closeable
import java.io.InputStream

/**
 * Dostęp do udziału na NAS. Ścieżki względne wobec udziału, z separatorem „/”
 * (np. „MUZYKA/Vocaloid/…/01 - x.mp3”, „39sync/manifest.json”).
 */
interface RemoteFs : Closeable {
    /** Opis połączenia do wyświetlenia, np. „smb://192.168.1.20/Muzyka”. */
    val label: String

    /** Sprawdza połączenie i logowanie (wyjątek z opisem, gdy się nie udało). */
    fun ping()

    fun exists(path: String): Boolean
    fun size(path: String): Long?
    fun read(path: String): ByteArray
    fun openStream(path: String): InputStream
    /** Zapis całego pliku (nadpisuje, tworzy brakujące foldery). */
    fun write(path: String, data: ByteArray)
    fun rename(from: String, to: String)
    /** Usuwa plik (brak pliku – bez błędu). */
    fun delete(path: String)

    /** Źródło danych dla odtwarzacza (strumieniowanie plików muzyki). */
    fun dataSourceFactory(): DataSource.Factory
    fun uriFor(path: String): Uri

    companion object {
        fun join(vararg parts: String): String =
            parts.map { it.replace('\\', '/').trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    }
}

class RemoteNotConfigured : Exception("Nie skonfigurowano połączenia z NAS (Ustawienia → NAS)")
class RemoteUnavailable(msg: String, cause: Throwable? = null) : Exception(msg, cause)
