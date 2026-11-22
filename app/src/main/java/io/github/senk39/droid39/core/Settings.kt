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
    /** Identyfikator serwera SMB zapamiętany przy pierwszym udanym połączeniu (ochrona hasła w obcych sieciach). */
    var nasServerGuid by str("nas_server_guid", "")
    private var passwordEnc by str("nas_password", "")
    var password: String
        get() = Secrets.decrypt(passwordEnc)
        set(value) {
            passwordEnc = Secrets.encrypt(value)
        }

    val nasConfigured: Boolean get() = hosts.isNotBlank() && share.isNotBlank()

    // ---------------------------------------------------------------- pliki lokalne
    /** Foldery z kopią biblioteki na telefonie/karcie, np. „/storage/1234-ABCD/Music”. */
    var mirrorFolders by str("mirror_folders", "")
    /**
     * Miejsce na dane aplikacji: biblioteka z PC, okładki, pamięć podręczna strumieni i pobrane utwory –
     * „sd” (karta pamięci; bez karty – pamięć wewnętrzna) albo „internal”.
     */
    var storage by str("storage", "sd")
    /** Folder danych używany ostatnio – po zmianie miejsca dane są przenoszone, a stare pamięci podręczne usuwane. */
    var dataDirUsed by str("data_dir_used", "")
    /**
     * Przejęty folder muzyki na karcie (np. „/storage/0123-4567/Music” – kopia F:\MUZYKA): pobrane utwory trafiają
     * tam w układzie folderów z PC, nieaktualne pliki są usuwane po potwierdzeniu. Wymaga „Dostępu do wszystkich
     * plików”. Pusty – pobrane trafiają do folderu aplikacji.
     */
    var managedFolder by str("managed_folder", "")
    /** Przejęty folder: gdy brakuje miejsca na pobierane, usuwaj najrzadziej słuchane (spoza reguł offline). */
    var evictRarelyPlayed by bool("evict_rarely_played", true)
    var cacheSizeMb by int("cache_size_mb", 4096)

    // ---------------------------------------------------------------- offline
    var offlineBest by bool("offline_best", true)
    var offlineLiked by bool("offline_liked", false)
    var offlineUnlistened by bool("offline_unlistened", true)
    var offlinePlaylists by set("offline_playlists")
    var offlineNodes by set("offline_nodes")
    var removeUnwanted by bool("offline_remove_unwanted", true)
    /** Po każdej synchronizacji pobieraj brakujące utwory z reguł offline (domyślnie ręcznie – „Pobierz teraz”). */
    var autoDownload by bool("offline_auto_download", false)
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
    /** Motyw: „dark”, „light” albo „system” (jak w ustawieniach telefonu). */
    var theme by str("theme", "dark")
    var resumeOnStart by bool("resume_on_start", false)
    /** Po zakończeniu kolejki: "context" (dalej poprzednia lista), "unlistened", "repeat", "stop". */
    var queueEndAction by str("queue_end_action", "context")

    // ---------------------------------------------------------------- urządzenie i stan synchronizacji
    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            sp.edit().putString("device_id", it).apply()
        }
    var deviceName by str("device_name", Build.MODEL ?: "Android")
    var uploadedSeq by long("uploaded_seq", 0)

    // ---------------------------------------------------------------- odstępy czasu synchronizacji
    /** Nowa biblioteka / stan z PC i niewysłane zmiany – sprawdzane co tyle s (aplikacja na ekranie albo gra). */
    var syncCheckS by int("iv_sync_check_s", 45)
    /** Stan odtwarzania PC („Kontynuuj z PC”) i stan sync39 – odczyt co tyle s (aplikacja na ekranie). */
    var pcStateS by int("iv_pc_state_s", 30)
    /** Polecenie pauzy od PC – sprawdzane co tyle s (gdy telefon gra). */
    var cmdCheckS by int("iv_cmd_check_s", 10)
    /** Zmiany z telefonu wysyłane po tylu s od ostatniej (kilka szybkich zmian – jeden plik). */
    var pushDelayS by int("iv_push_delay_s", 20)
    /** Synchronizacja w tle (Wi-Fi) co tyle godzin. */
    var bgSyncHours by int("iv_bg_sync_h", 6)
    /** Ostrzeżenie chmurki: PC (sync39) nie odzywa się od tylu dni. */
    var pcSilentDays by int("iv_pc_silent_days", 3)
    /** Ostrzeżenie chmurki: eksport biblioteki spóźniony o tyle godzin od zmiany w 39player. */
    var exportLateHours by int("iv_export_late_h", 2)
    /** Zmiany nieprzyjęte przez PC do tego numeru użytkownik już widział („Ukryj”). */
    var rejectedSeen by long("rejected_seen", 0)
    var lastSyncAt by long("last_sync_at", 0)
    var lastSyncError by str("last_sync_error", "")
    var snapshotSha by str("snapshot_sha", "")
    /** Plik stanu z PC (serduszka, odtworzenia, playlisty) nałożony na bibliotekę [snapshotSha]. */
    var stateSha by str("state_sha", "")
    var snapshotCreated by long("snapshot_created", 0)
    var lastMirrorScan by long("last_mirror_scan", 0)

    fun hostList(): List<String> = hosts.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    fun mirrorList(): List<String> = mirrorFolders.split('\n').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }
}
