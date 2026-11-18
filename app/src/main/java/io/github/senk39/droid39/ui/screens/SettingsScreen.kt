package io.github.senk39.droid39.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.senk39.droid39.App
import io.github.senk39.droid39.BuildConfig
import io.github.senk39.droid39.core.Permissions
import io.github.senk39.droid39.core.Text as T
import io.github.senk39.droid39.media.ScrobbleBroadcaster
import io.github.senk39.droid39.sync.Jobs
import io.github.senk39.droid39.sync.Offline
import io.github.senk39.droid39.sync.RemoteFs
import io.github.senk39.droid39.ui.Icons39
import io.github.senk39.droid39.ui.Nav
import io.github.senk39.droid39.ui.Route
import io.github.senk39.droid39.ui.components.SectionTitle
import io.github.senk39.droid39.ui.components.SmallIconButton
import io.github.senk39.droid39.ui.components.rememberLoad
import io.github.senk39.droid39.ui.theme.C39
import io.github.senk39.droid39.ui.theme.LocalAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sekcje ustawień (ekran główny ustawień to ich lista – mniej przewijania). */
private data class Section(val id: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val SECTIONS = listOf(
    Section("nas", "Połączenie z NAS", Icons39.Cloud),
    Section("sync", "Synchronizacja z 39player", Icons39.Sync),
    Section("card", "Muzyka na karcie pamięci", Icons39.SdCard),
    Section("offline", "Zawsze na telefonie (offline)", Icons39.DownloadDone),
    Section("storage", "Miejsce na dane", Icons39.Folder),
    Section("ui", "Wygląd i interfejs", Icons39.Grid),
    Section("playback", "Odtwarzanie", Icons39.Play),
    Section("scrobbler", "Simple Scrobbler (Last.fm)", Icons39.Equalizer),
)

@Composable
fun SettingsScreen(section: String?) {
    if (section == null) {
        SettingsHome()
        return
    }
    val g = App.graph
    val s = g.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by s.changes.collectAsState()
    val sync by g.sync.status.collectAsState()
    val filesV by g.files.version.collectAsState()
    val accent = LocalAccent.current
    val fmt = remember { SimpleDateFormat("d.MM.yyyy HH:mm", Locale.ROOT) }
    var showReview by remember { mutableStateOf(false) }
    if (showReview) ManagedReviewDialog { showReview = false }

    Column(Modifier.fillMaxSize()) {
        TopBar(SECTIONS.firstOrNull { it.id == section }?.title ?: "Ustawienia")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 120.dp)) {
          // ------------------------------------------------------------------ NAS
          if (section == "nas") {
            NasStatusLine()
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("SMB (udział sieciowy)", s.protocol == "smb") { s.protocol = "smb"; g.nas.invalidate() }
                Choice("WebDAV", s.protocol == "webdav") { s.protocol = "webdav"; g.nas.invalidate() }
            }
            Field("Adresy NAS (po przecinku: LAN, potem Tailscale)", s.hosts, hint = "192.168.1.20, dxp4800plus.tail1234.ts.net") { s.hosts = it; g.nas.invalidate() }
            Field(if (s.protocol == "smb") "Udział SMB" else "Ścieżka bazowa WebDAV", s.share, hint = "Muzyka") { s.share = it; g.nas.invalidate() }
            Field("Folder biblioteki w udziale (kopia F:\\MUZYKA)", s.musicPath, hint = "MUZYKA") { s.musicPath = it }
            Field("Folder synchronizacji w udziale", s.syncPath, hint = "39sync") { s.syncPath = it }
            Field("Użytkownik", s.user) { s.user = it; g.nas.invalidate() }
            var pass by remember { mutableStateOf(s.password) }
            Field("Hasło", pass, password = true) { pass = it; s.password = it; g.nas.invalidate() }
            Field("Port (0 – domyślny: SMB 445, WebDAV 5005/5006)", if (s.port == 0) "" else s.port.toString(), number = true) {
                s.port = it.toIntOrNull() ?: 0; g.nas.invalidate()
            }
            if (s.protocol == "webdav") Toggle("HTTPS (port 5006)", "Wymaga certyfikatu zaufanego przez Androida", s.https) { s.https = it; g.nas.invalidate() }
            if (s.protocol == "smb") {
                var guid by remember { mutableStateOf(s.nasServerGuid) }
                Text(
                    if (guid.isEmpty()) "Tożsamość NAS: zostanie zapamiętana przy pierwszym połączeniu (połącz się w domu)."
                    else "Tożsamość NAS zapamiętana – hasło nie trafi do innego serwera pod tym samym adresem (np. w obcej sieci Wi-Fi).",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = C39.Text3
                )
                if (guid.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp)) {
                    Button39("Zapomnij tożsamość NAS") { s.nasServerGuid = ""; guid = ""; g.nas.invalidate() }
                }
            }
            var test by remember { mutableStateOf<String?>(null) }
            var lanGranted by remember { mutableStateOf(Permissions.localNetworkGranted(ctx)) }
            val lanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                lanGranted = ok
                if (ok) g.nas.invalidate()
                else test = "Bez zgody na sieć lokalną NAS w LAN jest niedostępny. " + Permissions.LOCAL_NETWORK_HINT
            }
            if (!lanGranted) {
                Text("Android 17: brak zgody na dostęp do sieci lokalnej – bez niej połączenie z NAS w LAN się nie uda.",
                    Modifier.padding(horizontal = 16.dp), color = C39.Gold, fontSize = 13.sp)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!lanGranted) Button39("Zezwól na sieć lokalną") { lanLauncher.launch(Permissions.LOCAL_NETWORK) }
                Button39("Testuj połączenie") {
                    if (!lanGranted) {
                        lanLauncher.launch(Permissions.LOCAL_NETWORK)
                        return@Button39
                    }
                    test = "Łączenie…"
                    scope.launch {
                        test = withContext(Dispatchers.IO) {
                            runCatching {
                                g.nas.invalidate()
                                val fs = g.nas.get()
                                val manifest = fs.exists(RemoteFs.join(s.syncPath, "manifest.json"))
                                val music = runCatching { fs.exists(s.musicPath) || fs.size(s.musicPath) != null }.getOrDefault(false)
                                buildString {
                                    append("Połączono: ${fs.label}\n")
                                    append(if (manifest) "✔ migawka biblioteki z PC jest na NAS" else "✘ brak ${s.syncPath}/manifest.json – uruchom na PC: python -m sync39 export")
                                    if (s.protocol == "webdav") append(if (music) "\n✔ folder muzyki" else "\n? folder muzyki „${s.musicPath}” – sprawdź ścieżkę")
                                }
                            }.getOrElse { "Błąd: ${it.message}" }
                        }
                    }
                }
            }
            test?.let { Text(it, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = if (it.startsWith("Błąd")) C39.Warn else C39.Text2) }
            Hint("Poza domem NAS jest dostępny przez Tailscale (aplikacja Tailscale na NAS i telefonie) – dopisz jego adres " +
                "jako drugi. Ugreen Link działa tylko w aplikacjach UGREEN.")
          }

          // ------------------------------------------------------------------ synchronizacja
          if (section == "sync") {
            val lines = buildList {
                if (s.snapshotCreated > 0) add("Biblioteka z PC: ${fmt.format(Date(s.snapshotCreated))}")
                if (s.lastSyncAt > 0) add("Ostatnia synchronizacja: ${fmt.format(Date(s.lastSyncAt))}")
                add("Zmiany z telefonu czekające na PC: ${g.library.pendingOps} (wysłane do #${s.uploadedSeq})")
                g.library.openError?.let { add("Błąd biblioteki: $it") }
            }
            Text(lines.joinToString("\n"), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = C39.Text2)
            if (sync.running) {
                Text(sync.message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = accent, fontSize = 13.sp)
                val p = sync.progress
                if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = accent)
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = accent)
            } else if (sync.error != null) {
                Text(sync.error!!, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = C39.Warn, fontSize = 13.sp)
            } else if (sync.message.isNotEmpty()) {
                Text(sync.message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = C39.Green, fontSize = 13.sp)
            }
            RejectedList()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button39("Synchronizuj teraz") { scope.launch { g.sync.syncNow() } }
                if (g.library.pendingOps > 0 || s.uploadedSeq > g.library.ackedSeq()) {
                    // np. gdy plik dziennika zginął na NAS – PC pomija zmiany już zastosowane, więc to bezpieczne
                    Button39("Wyślij ponownie niepotwierdzone", outlined = true) {
                        s.uploadedSeq = g.library.ackedSeq()
                        scope.launch { g.sync.syncNow() }
                    }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch { g.sync.importFromUri(uri) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                Button39("Wczytaj bibliotekę z pliku…", outlined = true) { importLauncher.launch(arrayOf("*/*")) }
            }
            Hint("Bez NAS: na PC python -m sync39 export --out D:\\39droid.db.gz, skopiuj plik na telefon i wybierz go tutaj.")
            Toggle("Synchronizuj automatycznie", "Przy starcie (w Wi-Fi) i co 6 godzin", s.autoSync) { s.autoSync = it }
            Field("Nazwa telefonu (widoczna na PC)", s.deviceName) { s.deviceName = it }
          }

          // ------------------------------------------------------------------ miejsce na dane
          if (section == "storage") {
            val sd = remember(changes) { g.files.sdVolume() }
            val internal = remember(changes) { g.files.primaryVolume() }
            val dataVol = remember(changes) { g.files.dataVolume() }
            StorageChoice("Karta SD", sd?.let { "${it.label} · wolne ${T.size(it.free)} z ${T.size(it.total)}" } ?: "karta niewłożona",
                s.storage != "internal") { g.changeStorage("sd") }
            StorageChoice("Pamięć wewnętrzna", "wolne ${T.size(internal.free)} z ${T.size(internal.total)}", s.storage == "internal") {
                g.changeStorage("internal")
            }
            if (s.storage != "internal" && sd == null) {
                Text("Brak karty pamięci – dane są w pamięci wewnętrznej, wrócą na kartę po jej włożeniu.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = C39.Gold)
            }
            if (dataVol.free < 10L * 1024 * 1024 * 1024) {
                Text(
                    "Na „${dataVol.label}” zostało tylko ${T.size(dataVol.free)} wolnego. Aplikacja zawsze zostawia " +
                        "${T.size(g.files.reserveBytes())} rezerwy – pobierze niewiele.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = C39.Gold,
                )
            }
            Hint("Biblioteka z PC, okładki, pamięć podręczna strumieni i pobrane utwory (dziennik zmian zostaje w pamięci " +
                "telefonu). Biblioteka przenosi się od razu, pamięć strumieni – po ponownym uruchomieniu aplikacji.")
            var cache by remember { mutableIntStateOf(s.cacheSizeMb) }
            Text("Pamięć podręczna strumienia: ${T.size(cache.toLong() * 1048576)} (zmiana po ponownym uruchomieniu)", Modifier.padding(start = 16.dp, top = 10.dp), fontSize = 13.sp)
            Slider(
                cache.toFloat(), { cache = (it / 512).toInt() * 512 }, Modifier.padding(horizontal = 16.dp),
                valueRange = 512f..65536f, onValueChangeFinished = { s.cacheSizeMb = cache },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
            )
          }

          // ------------------------------------------------------------------ muzyka na karcie
          if (section == "card") {
            val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            var scanMsg by remember { mutableStateOf<String?>(null) }
            val rescan = {
                scanMsg = "Skanowanie…"
                scope.launch {
                    scanMsg = withContext(Dispatchers.IO) {
                        g.files.scanMirrors().fold({ n ->
                            g.files.recomputeAvailable(g.library)
                            "Znaleziono plików: $n · w bibliotece na telefonie: ${g.files.availableIds.size}"
                        }, { "Błąd: ${it.message}" })
                    }
                }
                Unit
            }
            // przejęcie folderu: zgoda „Dostęp do wszystkich plików” w ustawieniach systemu, potem skan
            var pendingTakeover by remember { mutableStateOf<String?>(null) }
            var allFiles by remember { mutableStateOf(g.files.hasAllFilesAccess()) }
            val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                allFiles = g.files.hasAllFilesAccess()
                val folder = pendingTakeover
                pendingTakeover = null
                if (allFiles && folder != null) {
                    s.managedFolder = folder
                    rescan()
                } else if (!allFiles) scanMsg = "Bez „Dostępu do wszystkich plików” folder na karcie zostaje tylko do odczytu."
            }
            val askAllFiles = {
                allFilesLauncher.launch(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}"))
                )
            }
            val takeOver = { folder: String ->
                if (folder !in s.mirrorList()) s.mirrorFolders = (s.mirrorList() + folder).joinToString("\n")
                if (g.files.hasAllFilesAccess()) {
                    allFiles = true
                    s.managedFolder = folder
                    rescan()
                } else {
                    pendingTakeover = folder
                    askAllFiles()
                }
            }
            val managed = s.managedFolder.trim()
            if (managed.isNotEmpty()) {
                val ok = remember(changes, allFiles) { g.files.managedRoot() != null }
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons39.SdCard, null, Modifier.size(18.dp), tint = if (ok) accent else C39.Gold)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(managed, fontSize = 13.sp)
                        Text(
                            when {
                                ok -> "przejęty: pobrane trafiają tutaj, w układzie folderów z PC"
                                !allFiles -> "brak „Dostępu do wszystkich plików” – folder tylko do odczytu"
                                else -> "folder niedostępny (karta wyjęta?)"
                            },
                            fontSize = 12.sp, color = if (ok) C39.Text3 else C39.Gold,
                        )
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!allFiles) Button39("Zezwól na dostęp do plików") { askAllFiles() }
                    if (ok) Button39("Porządkuj folder…") { showReview = true }
                    Button39("Oddaj folder", outlined = true) { s.managedFolder = "" }
                }
                Toggle("Zwalniaj miejsce na pobierane", "Gdy brakuje miejsca, usuwa z folderu najrzadziej słuchane utwory spoza reguł " +
                    "offline (są na NAS i PC). Plików spoza biblioteki PC nie rusza.", s.evictRarelyPlayed) { s.evictRarelyPlayed = it }
            } else {
                Text("Kopia biblioteki na karcie (tylko odczyt – aplikacja nic w niej nie zmienia):", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp)
            }
            s.mirrorList().filter { it != managed }.forEach { m ->
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons39.SdCard, null, Modifier.size(18.dp), tint = accent)
                    Text(m, Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp)
                    SmallIconButton(Icons39.Close, "Usuń") { s.mirrorFolders = (s.mirrorList() - m).joinToString("\n") }
                }
                if (managed.isEmpty() && !m.startsWith("/storage/emulated")) {
                    Row(Modifier.padding(start = 42.dp, bottom = 4.dp)) {
                        Button39("Przejmij folder", outlined = true) { takeOver(m) }
                    }
                }
            }
            if (managed.isEmpty()) {
                Hint("Przejęty folder (np. /storage/XXXX-XXXX/Music – kopia F:\\MUZYKA) aplikacja utrzymuje sama: pobrane utwory " +
                    "trafiają do niego w układzie folderów z PC, pliki zmienione na PC są pobierane ponownie, a pliki usunięte na PC " +
                    "– usuwane dopiero po Twoim potwierdzeniu. Wymaga „Dostępu do wszystkich plików”.")
            }
            var newMirror by remember { mutableStateOf("") }
            Field("Dodaj folder (np. /storage/XXXX-XXXX/MUZYKA)", newMirror) { newMirror = it }
            val suggestions = remember(changes) { g.files.suggestMirrors().filter { it !in s.mirrorList() && it != managed } }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button39("Dodaj") {
                    if (newMirror.isNotBlank()) { s.mirrorFolders = (s.mirrorList() + newMirror.trim()).joinToString("\n"); newMirror = "" }
                }
            }
            if (suggestions.isNotEmpty()) {
                Text("Znalezione foldery z muzyką:", Modifier.padding(start = 16.dp, top = 8.dp), fontSize = 12.sp, color = C39.Text3)
                suggestions.take(4).forEach { sug ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                        Button39("+ $sug", outlined = true) { s.mirrorFolders = (s.mirrorList() + sug).joinToString("\n") }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!g.files.hasAudioPermission() && !allFiles) Button39("Zezwól na odczyt muzyki") { permLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) }
                Button39("Skanuj kartę") { rescan() }
            }
            scanMsg?.let { Text(it, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = C39.Text2) }
            val dlBytes = remember(filesV) { g.files.downloadedBytes() }
            Hint("Pobrane do folderu aplikacji: ${T.size(dlBytes)} · ${g.files.downloaded().size} plików (usuwane przy odinstalowaniu aplikacji).")
          }

          // ------------------------------------------------------------------ offline
          if (section == "offline") {
            val plan = rememberLoad(changes, filesV, g.library.snapVersion.collectAsState().value) { g.offline.plan() }
            val counts = rememberLoad(changes, g.library.snapVersion.collectAsState().value, g.library.overlayVersion.collectAsState().value) {
                Triple(g.library.bestTracks(), g.library.likedTracks(), g.library.unlistenedTracks())
            }
            Toggle("Najlepsze ♥♥", counts?.first?.let { "${T.tracks(it.size)} · ${T.size(it.sumOf { r -> r.size })}" }, s.offlineBest) { s.offlineBest = it }
            Toggle("Polubione ♥", counts?.second?.let { "${T.tracks(it.size)} · ${T.size(it.sumOf { r -> r.size })}" }, s.offlineLiked) { s.offlineLiked = it }
            Toggle("Do odsłuchania", counts?.third?.let { "${T.tracks(it.size)} · ${T.size(it.sumOf { r -> r.size })}" }, s.offlineUnlistened) { s.offlineUnlistened = it }
            (s.offlinePlaylists.map { "pl" to it } + s.offlineNodes.map { "node" to it }).forEach { (kind, key) ->
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == "pl") Icons39.PlaylistPlay else Icons39.Folder, null, Modifier.size(18.dp), tint = accent)
                    val label = if (kind == "pl") key.substringBeforeLast('.') else g.library.nodeByPath(key)?.let { n -> g.library.nodePathNames(n).joinToString(" › ") { it.name } } ?: key
                    Text(label, Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp)
                    SmallIconButton(Icons39.Close, "Usuń") { if (kind == "pl") g.offline.togglePlaylist(key) else g.offline.toggleNode(key) }
                }
            }
            Hint("Playlisty i kategorie dodasz z ich menu (⋮ → Zawsze na telefonie).")
            plan?.let {
                Text("Razem: ${T.tracks(it.wanted.size)} (${T.size(it.wantedBytes)}) · do pobrania: ${T.tracks(it.toDownload.size)} (${T.size(it.downloadBytes)})" +
                    (if (it.changed > 0) ", w tym zmienione na PC: ${it.changed}" else "") +
                    if (it.toRemove.isNotEmpty()) " · do usunięcia: ${it.toRemove.size}" else "",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 13.sp, color = C39.Text2)
            }
            Toggle("Pobieraj tylko przez Wi-Fi", null, s.wifiOnlyDownloads) { s.wifiOnlyDownloads = it }
            Toggle("Pobieraj automatycznie po synchronizacji", "Nowe utwory z reguł (np. świeże ♥♥ z PC) trafiają na telefon same", s.autoDownload) { s.autoDownload = it }
            Toggle("Usuwaj pobrane, gdy przestaną pasować do reguł", "Tylko pliki pobrane przez aplikację", s.removeUnwanted) { s.removeUnwanted = it }
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val progress by remember { Jobs.downloadProgress(ctx) }.collectAsState(initial = null)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button39("Pobierz teraz") {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Jobs.startDownloads(ctx, s.wifiOnlyDownloads)
                }
                if (progress != null) Button39("Zatrzymaj", outlined = true) { Jobs.stopDownloads(ctx) }
            }
            progress?.let { (done, total, title) ->
                Text(if (total > 0) "Pobieranie $done/$total: $title" else title, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = accent)
                if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = accent)
            }
          }

          // ------------------------------------------------------------------ wygląd
          if (section == "ui") {
            Text("Motyw", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp), fontSize = 16.sp)
            var theme by remember { mutableStateOf(s.theme) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dark" to "Ciemny", "light" to "Jasny", "system" to "Jak w telefonie").forEach { (v, label) ->
                    Choice(label, theme == v) { theme = v; s.theme = v }
                }
            }
            Spacer(Modifier.height(8.dp))
            Toggle("Kolor akcentu z okładki", "Jak w PowerAmp – kolor przycisków i pasków wg okładki odtwarzanego utworu", s.accentFromArt) { s.accentFromArt = it }
            Hint("Przeciągnij palcem w prawo (od lewej połowy ekranu), aby wrócić do poprzedniego ekranu.")
          }

          // ------------------------------------------------------------------ odtwarzanie
          if (section == "playback") {
            Toggle("Strumieniuj przez sieć komórkową", "Po wyłączeniu poza Wi-Fi grają tylko utwory na telefonie", s.allowMobileStreaming) { s.allowMobileStreaming = it }
          }

          // ------------------------------------------------------------------ Simple Scrobbler
          if (section == "scrobbler") {
            val sls = remember { ScrobbleBroadcaster.installedVersion(ctx) }
            Text(
                if (sls != null) "Simple Scrobbler $sls jest zainstalowany." else "Nie znaleziono Simple Scrobblera (com.adam.aslfms).",
                Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = if (sls != null) C39.Green else C39.Text2,
            )
            Hint("Simple Scrobbler 1.7.0 odczytuje sesję multimediów (dostęp do powiadomień), tak jak przy PowerAmp: przy pierwszym " +
                "odtworzeniu zezwól na 39droid w jego liście aplikacji muzycznych. Wykonawca jest wysyłany dokładnie jak w tagu " +
                "(np. „Kai feat. Hatsune Miku”), więc scrobble łączą się z historią z 39player.")
            Toggle("Wysyłaj też broadcast SLS API", "Tylko gdy Simple Scrobbler nie widzi sesji multimediów (inaczej mogą być podwójne scrobble)", s.scrobbleBroadcast) { s.scrobbleBroadcast = it }
            Toggle("Wysyłaj Wykonawcę albumu", "W bibliotece to zwykle wokalista – domyślnie wyłączone, jak w 39player", s.sendAlbumArtist) { s.sendAlbumArtist = it }
            Toggle("Wykonawca bez „feat. …”", null, s.artistWithoutFeat) { s.artistWithoutFeat = it }
          }
        }
    }
}

/** Ekran główny ustawień: sekcje z krótkim podsumowaniem bieżącego stanu (jak w PowerAmp). */
@Composable
private fun SettingsHome() {
    val g = App.graph
    val s = g.settings
    val ctx = LocalContext.current
    val changes by s.changes.collectAsState()
    val nas by g.nas.state.collectAsState()
    val rejected by g.sync.rejected.collectAsState()
    val fmt = remember { SimpleDateFormat("d.MM.yyyy HH:mm", Locale.ROOT) }
    val summary = remember(changes, nas, rejected) {
        mapOf(
            "nas" to when {
                !s.nasConfigured -> "Nie skonfigurowano"
                nas is io.github.senk39.droid39.sync.NasState.Online -> "Połączono: ${(nas as io.github.senk39.droid39.sync.NasState.Online).label}"
                nas is io.github.senk39.droid39.sync.NasState.Offline -> "Niedostępny · ${s.hostList().joinToString(", ")}"
                else -> s.hostList().joinToString(", ")
            },
            "sync" to buildList {
                if (s.snapshotCreated > 0) add("Biblioteka z PC: ${fmt.format(Date(s.snapshotCreated))}")
                if (g.library.pendingOps > 0) add("czeka na wysłanie: ${g.library.pendingOps}")
                if (rejected.isNotEmpty()) add("PC nie przyjął zmian: ${rejected.size}")
            }.joinToString(" · ").ifEmpty { "Biblioteka z PC i zmiany z telefonu" },
            "card" to (s.managedFolder.ifBlank { s.mirrorList().firstOrNull() ?: "" }.ifBlank { "Kopia biblioteki na karcie SD" }),
            "offline" to listOfNotNull("♥♥".takeIf { s.offlineBest }, "♥".takeIf { s.offlineLiked }, "Do odsłuchania".takeIf { s.offlineUnlistened })
                .joinToString(", ").ifEmpty { "Reguły pobierania na telefon" },
            "storage" to if (s.storage == "internal") "Pamięć wewnętrzna" else "Karta SD",
            "ui" to "Motyw: " + when (s.theme) { "light" -> "jasny"; "system" -> "jak w telefonie"; else -> "ciemny" },
            "playback" to "Sieć komórkowa: " + if (s.allowMobileStreaming) "strumieniowanie włączone" else "tylko utwory na telefonie",
            "scrobbler" to (ScrobbleBroadcaster.installedVersion(ctx)?.let { "Simple Scrobbler $it" } ?: "Nie znaleziono Simple Scrobblera"),
        )
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Ustawienia")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
            SECTIONS.forEach { sec ->
                Row(
                    Modifier.fillMaxWidth().clickable { Nav.go(Route.SettingsSection(sec.id)) }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(sec.icon, null, Modifier.size(22.dp), tint = LocalAccent.current)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sec.title, fontSize = 16.sp)
                        Text(summary[sec.id].orEmpty(), fontSize = 13.sp, color = C39.Text3, maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            Hint("39droid ${BuildConfig.VERSION_NAME} · urządzenie ${s.deviceId}\nMobilny port 39player. Edycja tagów i podpinanie VocaDB – w 39player na PC.")
        }
    }
}

/** Stan połączenia na górze sekcji NAS (szczegóły do chmurki z ekranu głównego). */
@Composable
private fun NasStatusLine() {
    val g = App.graph
    val nas by g.nas.state.collectAsState()
    val (color, text) = when (val n = if (g.settings.nasConfigured) nas else io.github.senk39.droid39.sync.NasState.NotConfigured) {
        is io.github.senk39.droid39.sync.NasState.Online -> C39.Green to "Połączono: ${n.label}"
        is io.github.senk39.droid39.sync.NasState.Offline -> C39.Warn to "Niedostępny: ${n.reason}"
        io.github.senk39.droid39.sync.NasState.NotConfigured -> C39.Gold to "Nie skonfigurowano – grają tylko utwory na telefonie"
        io.github.senk39.droid39.sync.NasState.Unknown -> C39.Text3 to "Jeszcze nie sprawdzono – „Testuj połączenie”"
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (color == C39.Green) Icons39.Cloud else Icons39.CloudOff, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = C39.Text2)
    }
}

@Composable
private fun Field(label: String, value: String, hint: String? = null, password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    var v by remember(label) { mutableStateOf(value) }
    OutlinedTextField(
        v, { v = it; onChange(it) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        placeholder = hint?.let { { Text(it, color = C39.Text3) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when {
            password -> KeyboardType.Password
            number -> KeyboardType.Number
            else -> KeyboardType.Uri
        }),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = C39.Text3, lineHeight = 17.sp)
        }
        Switch39(checked)
    }
}

/**
 * Mały przełącznik jak w PowerAmp: wąski tor i wystająca okrągła gałka (Material3 Switch jest za duży na listę
 * ustawień). Całym wierszem steruje [Toggle] – sam przełącznik tylko pokazuje stan.
 */
@Composable
private fun Switch39(checked: Boolean) {
    val accent = LocalAccent.current
    val pos by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    val trackOff = if (C39.palette.light) Color(0xFFBDC1C7) else Color(0xFF4A505A)
    val thumbOff = if (C39.palette.light) Color(0xFFF7F7F8) else Color(0xFFB4B9C1)
    val track = androidx.compose.ui.graphics.lerp(trackOff, accent.copy(alpha = 0.5f), pos)
    val thumb = androidx.compose.ui.graphics.lerp(thumbOff, accent, pos)
    androidx.compose.foundation.Canvas(Modifier.size(width = 40.dp, height = 24.dp)) {
        val th = 14.dp.toPx()
        val r = 10.dp.toPx()
        val left = r
        val right = size.width - r
        drawRoundRect(track, topLeft = androidx.compose.ui.geometry.Offset(left - 4.dp.toPx(), (size.height - th) / 2),
            size = androidx.compose.ui.geometry.Size(right - left + 8.dp.toPx(), th),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(th / 2, th / 2))
        val cx = left + (right - left) * pos
        drawCircle(Color.Black.copy(alpha = 0.18f), r + 1.dp.toPx(), androidx.compose.ui.geometry.Offset(cx, size.height / 2 + 1.dp.toPx()))
        drawCircle(thumb, r, androidx.compose.ui.geometry.Offset(cx, size.height / 2))
    }
}

@Composable
private fun StorageChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Column {
            Text(title, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = C39.Text3)
        }
    }
}

/**
 * Przejęty folder na karcie: pliki, których nie ma w bibliotece PC (usuwane dopiero po dwukrotnym potwierdzeniu),
 * zmienione na PC i brakujące z reguł offline (pobieranie w tle).
 */
@Composable
private fun ManagedReviewDialog(onClose: () -> Unit) {
    val g = App.graph
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    var review by remember { mutableStateOf<Offline.Review?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { review = withContext(Dispatchers.IO) { g.offline.review() } }
    val root = remember { g.settings.managedFolder.trim().trimEnd('/') + "/" }
    val r = review
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C39.Surface,
        title = { Text("Porządkuj folder na karcie", fontSize = 18.sp) },
        text = {
            if (r == null) Text("Sprawdzanie folderu…", fontSize = 13.sp) else Column {
                Text("W folderze: ${T.tracks(r.managed)}", fontSize = 13.sp)
                Text("Zmienione na PC (do ponownego pobrania): ${r.changed}", fontSize = 13.sp)
                Text("Brakujące z reguł offline: ${r.missing}", fontSize = 13.sp)
                Text("Nie ma ich w bibliotece PC: ${r.stale.size} (${T.size(r.staleBytes)})", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                r.problem?.let { Text(it, color = C39.Warn, fontSize = 12.sp) }
                if (r.stale.isNotEmpty()) {
                    Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                        r.stale.take(300).forEach { f -> Text(f.path.removePrefix(root), fontSize = 11.sp, color = C39.Text3, maxLines = 2) }
                        if (r.stale.size > 300) Text("… i ${r.stale.size - 300} więcej", fontSize = 11.sp, color = C39.Text3)
                    }
                    Text("Zwykle to pliki przemianowane albo usunięte na PC. Nowe wersje z reguł offline aplikacja pobierze sama; " +
                        "pozostałe grają z NAS.", fontSize = 12.sp, color = C39.Text2)
                }
                msg?.let { Text(it, color = accent, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            if (r != null && r.stale.isNotEmpty() && r.problem == null && !busy) {
                Button39(if (confirm) "Na pewno usunąć ${r.stale.size}?" else "Usuń ${r.stale.size} plików") {
                    if (!confirm) {
                        confirm = true
                        return@Button39
                    }
                    busy = true
                    scope.launch {
                        val (n, bytes) = withContext(Dispatchers.IO) {
                            g.files.deleteLocal(r.stale).also { g.files.recomputeAvailable(g.library) }
                        }
                        msg = "Usunięto $n plików (${T.size(bytes)})"
                        confirm = false
                        review = withContext(Dispatchers.IO) { g.offline.review() }
                        busy = false
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r != null && r.changed + r.missing > 0) {
                    Button39("Pobierz ${r.changed + r.missing}", outlined = true) {
                        Jobs.startDownloads(ctx, g.settings.wifiOnlyDownloads)
                        msg = "Pobieranie w tle – postęp w powiadomieniu"
                    }
                }
                Button39("Zamknij", outlined = true) { onClose() }
            }
        },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    // jak w PowerAmp: wybrana opcja wypełniona akcentem, pozostałe obrysowane
    Text(
        label, Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else Color.Transparent)
            .border(1.5.dp, if (selected) accent else C39.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        color = if (selected) C39.OnAccent else C39.Text2, fontSize = 14.sp,
    )
}

@Composable
private fun Button39(label: String, outlined: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(
        label, Modifier.clip(RoundedCornerShape(50)).background(if (outlined) C39.Surface2 else accent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        color = if (outlined) C39.Text else C39.OnAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** Zmiany z telefonu, których PC nie przyjął – co, dlaczego i kiedy; „Ukryj” chowa widziane. */
@Composable
private fun RejectedList() {
    val g = App.graph
    val rejected by g.sync.rejected.collectAsState()
    if (rejected.isEmpty()) return
    val fmt = remember { SimpleDateFormat("d.MM HH:mm", Locale.ROOT) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(C39.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("PC nie przyjął zmian z telefonu: ${rejected.size}", color = C39.Warn, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Pozostałe zmiany trafiły do 39player. Te pominięto – zwykle dlatego, że na PC czegoś już nie ma.",
            color = C39.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        for (c in rejected.takeLast(50).asReversed()) {
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(c.what, color = C39.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val at = if (c.tsMs > 0) " · " + fmt.format(Date(c.tsMs)) else ""
                Text(c.reason + at, color = C39.Text2, fontSize = 12.sp)
            }
        }
        if (rejected.size > 50) Text("…i ${rejected.size - 50} starszych", color = C39.Text3, fontSize = 12.sp)
        Row(Modifier.padding(top = 6.dp)) {
            Button39("Ukryj", outlined = true) { g.sync.dismissRejected() }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), fontSize = 12.sp, color = C39.Text3, lineHeight = 16.sp)
}
