package io.github.senk39.droid39.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Ustawienia aplikacji (SharedPreferences). `changes` rośnie przy każdej zmianie – do odświeżania widoków. */
class Settings(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun bump() {
        _changes.value++
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply(); bump()
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply(); bump()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply(); bump()
        }
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Long) {
            sp.edit().putLong(key, value).apply()                // stan synchronizacji – bez odświeżania widoków
        }
    }

    private fun set(key: String) = object : ReadWriteProperty<Any, Set<String>> {
        override fun getValue(thisRef: Any, property: KProperty<*>): Set<String> =
            sp.getStringSet(key, emptySet())?.toSet() ?: emptySet()
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Set<String>) {
            sp.edit().putStringSet(key, value).apply(); bump()
        }
    }

    // ---------------------------------------------------------------- NAS
    /** „smb” albo „webdav”. */
    var protocol by str("nas_protocol", "smb")
    /** Adresy NAS sprawdzane po kolei (np. adres w LAN, potem nazwa w Tailscale), oddzielone przecinkami. */
    var hosts by str("nas_hosts", "")
    var port by int("nas_port", 0)
    var https by bool("nas_https", false)
    /** SMB: nazwa udziału (np. „Muzyka”); WebDAV: ścieżka bazowa (np. „/Muzyka”). */
    var share by str("nas_share", "")
    /** Folder biblioteki w udziale (odpowiednik F:\MUZYKA), np. „MUZYKA”. */
    var musicPath by str("nas_music_path", "MUZYKA")
    /** Folder synchronizacji w udziale, np. „39sync”. */
    var syncPath by str("nas_sync_path", "39sync")
    var user by str("nas_user", "")
    private var passwordEnc by str("nas_password", "")
    var password: String
        get() = Secrets.decrypt(passwordEnc)
        set(value) {
            passwordEnc = Secrets.encrypt(value)
        }

    val nasConfigured: Boolean get() = hosts.isNotBlank() && share.isNotBlank()

    // ---------------------------------------------------------------- pliki lokalne
    /** Foldery z kopią biblioteki na telefonie/karcie (tylko odczyt), np. „/storage/1234-ABCD/MUZYKA”. */
    var mirrorFolders by str("mirror_folders", "")
    /** Wolumin na pobrane utwory: „auto” (karta SD, jeśli jest), „primary” albo UUID woluminu. */
    var downloadVolume by str("download_volume", "auto")
    var cacheSizeMb by int("cache_size_mb", 4096)

    // ---------------------------------------------------------------- offline
    var offlineBest by bool("offline_best", true)
    var offlineLiked by bool("offline_liked", false)
    var offlineUnlistened by bool("offline_unlistened", true)
    var offlinePlaylists by set("offline_playlists")
    var offlineNodes by set("offline_nodes")
    var removeUnwanted by bool("offline_remove_unwanted", true)
    var wifiOnlyDownloads by bool("wifi_only_downloads", true)
    var allowMobileStreaming by bool("allow_mobile_streaming", true)
    var autoSync by bool("auto_sync", true)

    // ---------------------------------------------------------------- Simple Scrobbler
    /** Dodatkowo wysyłaj broadcast SLS API (com.adam.aslfms.notify.playstatechanged). */
    var scrobbleBroadcast by bool("scrobble_broadcast", false)
    /** Wykonawca albumu w metadanych sesji (Simple Scrobbler wysyła go na Last.fm). */
    var sendAlbumArtist by bool("send_album_artist", false)
    /** Wykonawca bez części „feat. …” (jak opcja Last.fm w 39player). */
    var artistWithoutFeat by bool("artist_without_feat", false)

    // ---------------------------------------------------------------- wygląd / odtwarzanie
    var accentFromArt by bool("accent_from_art", true)
    var resumeOnStart by bool("resume_on_start", false)

    // ---------------------------------------------------------------- urządzenie i stan synchronizacji
    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            sp.edit().putString("device_id", it).apply()
        }
    var deviceName by str("device_name", Build.MODEL ?: "Android")
    var uploadedSeq by long("uploaded_seq", 0)
    var lastSyncAt by long("last_sync_at", 0)
    var lastSyncError by str("last_sync_error", "")
    var snapshotSha by str("snapshot_sha", "")
    var snapshotCreated by long("snapshot_created", 0)
    var lastMirrorScan by long("last_mirror_scan", 0)

    fun hostList(): List<String> = hosts.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun mirrorList(): List<String> = mirrorFolders.split('\n').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
}
