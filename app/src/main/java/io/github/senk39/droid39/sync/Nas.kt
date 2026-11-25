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
    data class Offline(val reason: String, val vpn: VpnHint = VpnHint.NONE) : NasState()
}

/** Podpowiedź, gdy NAS nie odpowiada, a w adresach jest adres Tailscale (poza domem jedyna droga do NAS). */
enum class VpnHint {
    NONE,
    /** Na telefonie nie działa żadna sieć VPN – Tailscale wyłączony albo zamknięty przez system. */
    TAILSCALE_OFF,
    /** VPN działa, ale adres Tailscale nie odpowiada – inna sieć VPN (np. Proton) albo NAS poza Tailscale. */
    TAILSCALE_NO_ANSWER,
}

/**
 * Połączenie z NAS: adresy z ustawień sprawdzane po kolei (np. LAN, potem Tailscale), pierwszy odpowiadający
 * zostaje użyty, dopóki działa.
 */
@UnstableApi
class Nas(private val context: Context, private val settings: Settings) {
    private var fs: RemoteFs? = null
    private var fsKey: String? = null
    private val _state = MutableStateFlow<NasState>(if (settings.nasConfigured) NasState.Unknown else NasState.NotConfigured)
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
    else SmbFs(host, settings.port, settings.share.trim('/', '\\'), settings.user, settings.password,
        settings.nasServerGuid) { guid -> settings.nasServerGuid = guid }

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
        var msg = errors.joinToString("; ").ifEmpty { "Brak adresów NAS w ustawieniach" }
        val hint = vpnHint(settings.hostList())
        when (hint) {
            VpnHint.TAILSCALE_OFF -> msg = "Tailscale nie jest połączony na telefonie (brak sieci VPN) – poza domowym Wi-Fi " +
                "NAS jest dostępny tylko przez Tailscale. $msg"
            VpnHint.TAILSCALE_NO_ANSWER -> msg = "Działa sieć VPN, ale NAS nie odpowiada pod adresem Tailscale – sprawdź, czy " +
                "połączony jest Tailscale (nie inna sieć VPN) i czy działa na NAS. $msg"
            VpnHint.NONE -> {}
        }
        if (!io.github.senk39.droid39.core.Permissions.localNetworkGranted(context)) {
            msg += ". " + io.github.senk39.droid39.core.Permissions.LOCAL_NETWORK_HINT
        }
        lastFailAt = System.currentTimeMillis()
        _state.value = NasState.Offline(msg, hint)
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

    /** Czy któraś aktywna sieć to VPN (Tailscale, Proton…). */
    private fun vpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    private fun vpnHint(hosts: List<String>): VpnHint {
        if (hosts.none { isTailscaleHost(it) }) return VpnHint.NONE
        return if (vpnActive()) VpnHint.TAILSCALE_NO_ANSWER else VpnHint.TAILSCALE_OFF
    }

    init {
        // po włączeniu Tailscale (lub powrocie do Wi-Fi) – od razu ponowna próba, bez czekania na synchronizację
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val req = android.net.NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            cm?.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = retryIfOffline()
            })
        }
    }

    private fun retryIfOffline() {
        if (_state.value !is NasState.Offline) return
        lastFailAt = 0
        Thread { runCatching { get() } }.start()
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
        /** Adres w Tailscale: nazwa *.ts.net, IPv4 100.64.0.0/10 albo IPv6 fd7a:115c:a1e0::/48. */
        fun isTailscaleHost(host: String): Boolean {
            val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
            if (h.endsWith(".ts.net")) return true
            if (h.startsWith("fd7a:115c:a1e0:")) return true
            val p = h.split('.')
            if (p.size != 4) return false
            val a = p[0].toIntOrNull() ?: return false
            val b = p[1].toIntOrNull() ?: return false
            return a == 100 && b in 64..127
        }

        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

        /** Otwiera aplikację Tailscale (fałsz, gdy nie jest zainstalowana). */
        fun openTailscale(context: Context): Boolean {
            val i = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) ?: return false
            context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }

        fun reachable(host: String, port: Int, timeoutMs: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (e: Exception) {
            false
        }
    }
}
