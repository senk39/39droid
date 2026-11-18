package io.github.senk39.droid39

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import io.github.senk39.droid39.art.ArtLoader
import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.data.Library
import io.github.senk39.droid39.data.LocalDb
import io.github.senk39.droid39.files.LocalFiles
import io.github.senk39.droid39.media.PlayerConnection
import io.github.senk39.droid39.sync.Jobs
import io.github.senk39.droid39.sync.LiveLink
import io.github.senk39.droid39.sync.Nas
import io.github.senk39.droid39.sync.Offline
import io.github.senk39.droid39.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

@UnstableApi
class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = Graph(this)
        graph.start()
    }

    companion object {
        lateinit var instance: App
            private set
        val graph: Graph get() = instance.graph
    }
}

/** Obiekty aplikacji (jedna instancja na proces). */
@UnstableApi
class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val local = LocalDb(app)
    val files = LocalFiles(app, settings, local)
    val library = Library(app, settings, local) { files.snapshotDir() }
    val nas = Nas(app, settings)
    val art = ArtLoader(app, settings, nas, files)
    val sync = SyncManager(app, settings, library, nas, files, scope)
    val offline = Offline(settings, library, files)
    val live = LiveLink(settings, nas, sync, library, scope)
    val player by lazy { PlayerConnection(app) }

    /**
     * Pamięć podręczna strumieniowanych utworów (LRU) – w miejscu na dane (karta SD albo pamięć wewnętrzna). Rozmiar
     * z ustawień, ale najwyżej tyle, ile się zmieści (połowa wolnego miejsca ponad rezerwę), żeby nie zapełnić karty.
     * Zmiana miejsca na dane działa dla niej od następnego uruchomienia aplikacji.
     */
    val streamCache: SimpleCache by lazy {
        val vol = files.dataVolume()
        val dir = files.streamCacheDir()
        val used = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val room = ((vol.free + used - LocalFiles.RESERVE) / 2).coerceAtLeast(256L * 1024 * 1024)
        val max = minOf(settings.cacheSizeMb.toLong() * 1024 * 1024, room)
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(max), StandaloneDatabaseProvider(app))
    }

    fun start() {
        library.onLocalChange = { sync.schedulePush() }
        art.relsFor = { key -> library.withSnap { it.relsForArt(key) } ?: emptyList() }
        sync.onSnapshotInstalled = {
            if (settings.autoDownload && hasOfflineRules()) Jobs.startDownloads(app, settings.wifiOnlyDownloads)
        }
        offline.onPinned = { Jobs.startDownloads(app, settings.wifiOnlyDownloads) }
        scope.launch(Dispatchers.IO) {
            runCatching { files.migrateData() }                    // miejsce na dane zmienione / pierwszy start wersji
            library.open()
            files.load()
            files.cleanTemp()
            if (files.downloaded().isEmpty()) files.scanDownloads()   // np. po wyczyszczeniu bazy aplikacji
            files.recomputeAvailable(library)
            if (settings.mirrorList().isNotEmpty() && System.currentTimeMillis() - settings.lastMirrorScan > 24 * 3600_000L) {
                files.scanMirrors()
                files.recomputeAvailable(library)
            }
            if (settings.nasConfigured && settings.autoSync && !nas.metered()) sync.syncNow()
        }
        scope.launch {
            library.snapVersion.drop(1).collect { files.recomputeAvailable(library) }
        }
        Jobs.schedulePeriodicSync(app)
    }

    /**
     * Zmiana miejsca na dane (karta SD / pamięć wewnętrzna): biblioteka przenoszona od razu, okładki pobierane od nowa
     * w nowym miejscu, pamięć podręczna strumieni – od następnego uruchomienia (stare pamięci są wtedy usuwane).
     */
    fun changeStorage(value: String) = scope.launch(Dispatchers.IO) {
        if (settings.storage == value) return@launch
        settings.storage = value
        files.invalidateDataDir()
        library.close()
        runCatching { files.migrateData(cleanCaches = false) }    // stare pamięci podręczne – przy następnym starcie
        library.open()
        files.recomputeAvailable(library)
    }

    fun hasOfflineRules(): Boolean = settings.offlineBest || settings.offlineLiked || settings.offlineUnlistened ||
        settings.offlinePlaylists.isNotEmpty() || settings.offlineNodes.isNotEmpty()
}
