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
    val library = Library(app, settings, local)
    val files = LocalFiles(app, settings, local)
    val nas = Nas(app, settings)
    val art = ArtLoader(app, settings, nas, files)
    val sync = SyncManager(app, settings, library, nas, files, scope)
    val offline = Offline(settings, library, files)
    val player by lazy { PlayerConnection(app) }

    /** Pamięć podręczna strumieniowanych utworów (LRU) – na woluminie wybranym do pobierania (zwykle karta SD). */
    val streamCache: SimpleCache by lazy {
        val dir = File(files.downloadVolume().dir, "stream-cache")
        SimpleCache(dir, LeastRecentlyUsedCacheEvictor(settings.cacheSizeMb.toLong() * 1024 * 1024), StandaloneDatabaseProvider(app))
    }

    fun start() {
        library.onLocalChange = { sync.schedulePush() }
        sync.onSnapshotInstalled = {
            if (settings.autoDownload && hasOfflineRules()) Jobs.startDownloads(app, settings.wifiOnlyDownloads)
        }
        offline.onPinned = { Jobs.startDownloads(app, settings.wifiOnlyDownloads) }
        scope.launch(Dispatchers.IO) {
            library.open()
            files.load()
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

    fun hasOfflineRules(): Boolean = settings.offlineBest || settings.offlineLiked || settings.offlineUnlistened ||
        settings.offlinePlaylists.isNotEmpty() || settings.offlineNodes.isNotEmpty()
}
