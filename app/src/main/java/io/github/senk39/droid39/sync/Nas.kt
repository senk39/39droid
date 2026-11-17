package io.github.senk39.droid39.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

sealed class NasState {
    data object Unknown : NasState()
    data object NotConfigured : NasState()
    data class Online(val label: String) : NasState()
    data class Offline(val reason: String) : NasState()
}

/**
 * Połączenie z NAS: adresy z ustawień sprawdzane po kolei (np. LAN, potem Tailscale), pierwszy odpowiadający
 * zostaje użyty, dopóki działa.
 */
@UnstableApi
class Nas(private val context: Context, private val settings: Settings) {
    private var fs: RemoteFs? = null
    private var fsKey: String? = null
    private val _state = MutableStateFlow<NasState>(NasState.Unknown)
    val state: StateFlow<NasState> = _state
    @Volatile private var lastFailAt = 0L

    private fun key() = listOf(
        settings.protocol, settings.hosts, settings.port, settings.https, settings.share, settings.user,
        settings.password.hashCode()
    ).joinToString("|")

    private fun defaultPort() = when {
        settings.port > 0 -> settings.port
        settings.protocol == "webdav" -> if (settings.https) 5006 else 5005
        else -> 445
    }

    private fun create(host: String): RemoteFs = if (settings.protocol == "webdav")
        WebDavFs(host, settings.port, settings.https, settings.share, settings.user, settings.password)
    else SmbFs(host, settings.port, settings.share.trim('/', '\\'), settings.user, settings.password)

    /** Aktywne połączenie (łączy się w razie potrzeby). Nie wołać z wątku głównego. */
    @Synchronized
    fun get(): RemoteFs {
        if (!settings.nasConfigured) {
            _state.value = NasState.NotConfigured
            throw RemoteNotConfigured()
        }
        val k = key()
        fs?.let { if (fsKey == k) return it }
        fs?.close()
        fs = null
        if (!networkAvailable()) {
            _state.value = NasState.Offline("Brak sieci")
            throw RemoteUnavailable("Brak połączenia z siecią")
        }
        val errors = ArrayList<String>()
        for (host in settings.hostList()) {
            if (!reachable(host, defaultPort(), 1500)) {
                errors += "$host: brak odpowiedzi"
                continue
            }
            val candidate = create(host)
            try {
                candidate.ping()
                fs = candidate
                fsKey = k
                _state.value = NasState.Online(candidate.label)
                return candidate
            } catch (e: Exception) {
                candidate.close()
                errors += "$host: ${e.message}"
            }
        }
        val msg = errors.joinToString("; ").ifEmpty { "Brak adresów NAS w ustawieniach" }
        lastFailAt = System.currentTimeMillis()
        _state.value = NasState.Offline(msg)
        throw RemoteUnavailable(msg)
    }

    /** Połączenie, jeśli już nawiązane – bez prób łączenia (np. do decyzji w interfejsie). */
    fun peek(): RemoteFs? = fs

    /** Ostatnia próba połączenia nie powiodła się niedawno – nie próbuj ponownie przy każdym utworze. */
    fun recentlyFailed(ms: Long = 20_000): Boolean = System.currentTimeMillis() - lastFailAt < ms

    @Synchronized
    fun invalidate() {
        fs?.close()
        fs = null
    }

    /** Operacja z jednym ponowieniem po zerwanym połączeniu (np. NAS rozłączył bezczynną sesję SMB). */
    fun <T> use(block: (RemoteFs) -> T): T {
        val first = get()
        return try {
            block(first)
        } catch (e: java.io.FileNotFoundException) {
            throw e
        } catch (e: RemoteUnavailable) {
            throw e
        } catch (e: Exception) {
            invalidate()
            block(get())
        }
    }

    fun networkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Sieć taryfowa (zwykle komórkowa) – strumieniowanie zgodnie z ustawieniem. */
    fun metered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    fun streamingAllowed(): Boolean = settings.allowMobileStreaming || !metered()

    companion object {
        fun reachable(host: String, port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (e: Exception) {
            false
        }
    }
}
