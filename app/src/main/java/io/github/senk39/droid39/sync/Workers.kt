package io.github.senk39.droid39.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.senk39.droid39.App
import io.github.senk39.droid39.R
import io.github.senk39.droid39.core.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Okresowa synchronizacja z NAS (gdy telefon jest w sieci). */
@UnstableApi
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = App.graph
        if (!g.settings.nasConfigured || !g.settings.autoSync) return Result.success()
        return if (g.sync.syncNow().isSuccess) Result.success() else Result.retry()
    }
}

/** Pobieranie utworów „offline” na telefon (usługa pierwszoplanowa z postępem w powiadomieniu). */
@UnstableApi
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Przygotowanie…", 0, 0)

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pobieranie offline", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_39)
            .setContentTitle("39droid – pobieranie offline")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val g = App.graph
        if (!g.library.ready) return@withContext Result.success()
        runCatching { setForeground(foreground("Przygotowanie…", 0, 0)) }
        val plan = g.offline.plan()
        for (f in plan.toRemove) {
            val rel = f.relKey
            File(f.path).delete()
            g.local.removeFile(rel)
        }
        if (plan.toRemove.isNotEmpty()) g.files.load()
        val todo = plan.toDownload
        var done = 0
        var failed = 0
        var bytes = 0L
        val music = g.settings.musicPath
        for (t in todo) {
            if (isStopped) break
            if (g.settings.wifiOnlyDownloads && g.nas.metered()) break
            val pct = "${done + 1}/${todo.size} · ${Text.size(bytes)} z ${Text.size(plan.downloadBytes)}"
            runCatching { setForeground(foreground("$pct · ${t.title}", done, todo.size)) }
            setProgress(workDataOf("done" to done, "total" to todo.size, "title" to t.title))
            val target = g.files.downloadTarget(t.rel)
            val part = File(target.path + ".part")
            try {
                target.parentFile?.mkdirs()
                g.nas.use { fs ->
                    fs.openStream(RemoteFs.join(music, t.rel)).use { input ->
                        part.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("Nie można zapisać ${target.name}")
                g.files.registerDownload(t.rel, target)
                bytes += target.length()
                runCatching { g.art.prefetch(t.artKey, t.rel) }
            } catch (e: Exception) {
                part.delete()
                failed++
                if (e is RemoteUnavailable || e is RemoteNotConfigured) break
            }
            done++
        }
        g.files.recomputeAvailable(g.library)
        if (failed > 0 && done < todo.size) Result.retry() else Result.success()
    }

    companion object {
        const val CHANNEL = "offline"
        const val NOTIF_ID = 3901
    }
}

@UnstableApi
object Jobs {
    private const val SYNC = "sync"
    private const val OFFLINE = "offline"

    fun schedulePeriodicSync(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun startDownloads(context: Context, wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(OFFLINE, ExistingWorkPolicy.REPLACE, req)
    }

    fun stopDownloads(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(OFFLINE)
    }

    /** (wykonane, wszystkie, tytuł) bieżącego pobierania albo null. */
    fun downloadProgress(context: Context): Flow<Triple<Int, Int, String>?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(OFFLINE).map { list ->
            val w = list.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                ?: return@map null
            if (w.state == WorkInfo.State.ENQUEUED) return@map Triple(0, 0, "czeka na sieć Wi-Fi…")
            Triple(w.progress.getInt("done", 0), w.progress.getInt("total", 0), w.progress.getString("title") ?: "")
        }
}
