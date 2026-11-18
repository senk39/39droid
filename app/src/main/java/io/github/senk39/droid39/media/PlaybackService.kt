package io.github.senk39.droid39.media

import io.github.senk39.droid39.widget.WidgetUpdater
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import io.github.senk39.droid39.App
import io.github.senk39.droid39.MainActivity
import io.github.senk39.droid39.R
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.sync.LiveLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Odtwarzanie w tle: ExoPlayer + MediaLibrarySession (powiadomienie, ekran blokady, słuchawki, Android Auto).
 * Sesja multimediów niesie metadane jak tagi z PC – Simple Scrobbler (dostęp do powiadomień) scrobbluje z niej;
 * opcjonalnie wysyłany jest też broadcast SLS API.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val g get() = App.graph
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scrobbler: ScrobbleBroadcaster
    private val tracker = ListenTracker()
    private var errorsInRow = 0
    private var lastSave = 0L

    override fun onCreate() {
        super.onCreate()
        val g = App.graph
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(this, g.settings, g.files, g.nas, g.streamCache)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        scrobbler = ScrobbleBroadcaster(this, g.settings)
        player.addListener(listener)
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(intent)
            .setBitmapLoader(ArtBitmapLoader())
            .setMediaButtonPreferences(listOf(heartButton(0)))
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_39) })
        // odtwarzanie przejęte przez PC („Kontynuuj z telefonu” w 39player) – pauza tutaj
        g.live.onPause = { who ->
            if (player.playWhenReady) {
                player.pause()
                Toast.makeText(this, "Odtwarzanie przejął $who – telefon wstrzymany", Toast.LENGTH_LONG).show()
            }
        }
        scope.launch { restoreQueue() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        saveState()
        publishState(forcePaused = true)
        WidgetUpdater.nowChanged(this, currentId, false)   // widżet: pauza
        g.live.onPause = null
        g.live.setPlaying(false)
        tracker.finish()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session.release()
        player.release()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- elementy kolejki
    private fun artist(row: TrackRow): String =
        if (g.settings.artistWithoutFeat) row.artist.split(FEAT_RE, 2)[0].trim() else row.artist

    fun item(row: TrackRow): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(row.title.ifEmpty { row.rel.substringAfterLast('/') })
            .setArtist(artist(row))
            .setAlbumTitle(row.album)
            .setAlbumArtist(if (g.settings.sendAlbumArtist) row.albumArtist else null)
            .setGenre(row.genre)
            .setTrackNumber(row.trackNo.takeIf { it > 0 })
            .setDiscNumber(row.discNo.takeIf { it > 0 })
            .setReleaseYear(row.year.takeIf { it > 0 })
            .setDurationMs((row.duration * 1000).toLong().takeIf { it > 0 })
            .setArtworkUri(row.artKey?.let { artUri(it, row.rel) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(row.id.toString())
            .setUri(RoutingDataSource.uri(row.id, row.rel, row.size))
            .setCustomCacheKey("t${row.id}-${row.size}")
            .setMediaMetadata(md)
            .build()
    }

    private fun resolve(items: List<MediaItem>): MutableList<MediaItem> {
        // polecenie głosowe (Android Auto / Asystent): element z samym zapytaniem – wyniki wyszukiwania w bibliotece
        val query = items.singleOrNull()?.takeIf { it.mediaId.isEmpty() }?.requestMetadata?.searchQuery
        if (query != null) return voiceSearch(query).map(::item).toMutableList()
        val ids = items.mapNotNull { it.mediaId.toIntOrNull() }
        val rows = g.library.tracks(ids).associateBy { it.id }
        return ids.mapNotNull { rows[it]?.let(::item) }.toMutableList()
    }

    /** „Odtwórz Miku w 39droid”: utwory pasujące do zapytania; puste zapytanie – Najlepsze w losowej kolejności. */
    private fun voiceSearch(query: String): List<TrackRow> {
        val lib = g.library
        if (query.isBlank()) return lib.bestTracks().shuffled().take(500)
        lib.searchNodes(query, 5).firstOrNull { it.kind in setOf("vocap", "vocal", "variant", "album", "folder", "root") }?.let { n ->
            return lib.nodeTracks(n).shuffled().take(1000)
        }
        return lib.search(query, 200)
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_HEART, Bundle.EMPTY))
                .add(SessionCommand(CMD_HEART_REFRESH, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(cmds)
                .setMediaButtonPreferences(listOf(heartButton(currentHeart())))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_HEART) {
                val id = player.currentMediaItem?.mediaId?.toIntOrNull()
                if (id != null) {
                    io.execute {
                        val row = g.library.track(id) ?: return@execute
                        val level = g.library.cycleHeart(row, false)
                        handler.post { session.setMediaButtonPreferences(listOf(heartButton(level))) }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_HEART_REFRESH) {
                io.execute {
                    val level = currentHeartBlocking()
                    handler.post { session.setMediaButtonPreferences(listOf(heartButton(level))) }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = io.submit(Callable { resolve(mediaItems) })

        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
            startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit(Callable {
            val resolved = resolve(mediaItems)
            val startId = mediaItems.getOrNull(startIndex)?.mediaId
            val idx = resolved.indexOfFirst { it.mediaId == startId }.let { if (it < 0) 0 else it }
            MediaSession.MediaItemsWithStartPosition(resolved, if (startIndex == C.INDEX_UNSET) C.INDEX_UNSET else idx, startPositionMs)
        })

        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit(Callable {
            val ids = g.local.loadQueue()
            val idx = g.local.get("queue_index")?.toIntOrNull() ?: 0
            val pos = g.local.get("queue_pos")?.toLongOrNull() ?: 0L
            val items = resolve(ids.map { MediaItem.Builder().setMediaId(it.toString()).build() })
            MediaSession.MediaItemsWithStartPosition(items, idx.coerceIn(0, maxOf(0, items.size - 1)), pos)
        })

        // ------------------------------------------------ przeglądanie (Android Auto i inne aplikacje)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "39droid"), params))

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = io.submit(Callable {
            val id = mediaId.toIntOrNull()
            val row = id?.let { g.library.track(it) }
            if (row != null) LibraryResult.ofItem(item(row), null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        })

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit(Callable {
            val lib = g.library
            val list: List<MediaItem> = when {
                parentId == ROOT -> listOf(
                    folder("unl", "Do odsłuchania"), folder("best", "Najlepsze ♥♥"), folder("liked", "Polubione ♥"),
                    folder("pl", "Playlisty"), folder("recent", "Ostatnio dodane"),
                )
                parentId == "pl" -> lib.playlists().filter { it.kind != "dir" }.map { folder("pl:${it.rel}", it.name) }
                parentId == "unl" -> lib.unlistenedTracks().map(::item)
                parentId == "best" -> lib.bestTracks().map(::item)
                parentId == "liked" -> lib.likedTracks().map(::item)
                parentId == "recent" -> lib.recentlyAdded().map(::item)
                parentId.startsWith("pl:") -> lib.playlistTracks(parentId.removePrefix("pl:")).map(::item)
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(list.size)
            val to = (from + pageSize).coerceAtMost(list.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(list.subList(from, to)), params)
        })
    }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).build()
        ).build()

    @Volatile private var currentId: Int? = null

    private fun currentHeart(): Int = currentHeartBlocking()

    /** Poziom serduszka bieżącego utworu (zapytanie do bazy – wołać poza wątkiem głównym, gdy to możliwe). */
    private fun currentHeartBlocking(): Int {
        val id = currentId ?: return 0
        return g.library.track(id)?.let { g.library.heart(it) } ?: 0
    }

    private fun heartButton(level: Int): CommandButton =
        CommandButton.Builder(if (level > 0) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(when (level) { 2 -> "Najlepsze ♥♥"; 1 -> "Polubione ♥"; else -> "Polub" })
            .setSessionCommand(SessionCommand(CMD_HEART, Bundle.EMPTY))
            // SLOT_OVERFLOW: jedyne miejsce, które Media3 eksportuje do powiadomienia i sesji systemowej
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    // ---------------------------------------------------------------- zdarzenia odtwarzacza
    private val tick = object : Runnable {
        override fun run() {
            tracker.tick(player.duration)
            if (player.isPlaying) handler.postDelayed(this, 1000)
            val now = SystemClock.elapsedRealtime()
            if (now - lastSave > 15_000) saveState()
            if (now - lastPublish > 30_000) publishState()
        }
    }

    /** Widżety na ekranie głównym: bieżący utwór i odtwarzanie / pauza (przycisk – wg „graj, gdy gotowy”). */
    private fun widgets() {
        val id = player.currentMediaItem?.mediaId?.toIntOrNull()
        WidgetUpdater.nowChanged(this, id,
            player.playWhenReady && player.playbackState != Player.STATE_ENDED)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            tracker.finish()
            val id = mediaItem?.mediaId?.toIntOrNull()
            currentId = id
            widgets()
            scrobbler.transition(mediaItem, player.isPlaying, reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            if (id != null) {
                scope.launch {
                    val row = withContext(Dispatchers.IO) { g.library.track(id) }
                    if (row != null && player.currentMediaItem?.mediaId == mediaItem.mediaId) {
                        tracker.start(row, player.isPlaying)
                        session.setMediaButtonPreferences(listOf(heartButton(g.library.heart(row))))
                    }
                }
            }
            saveState()
            publishSoon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            tracker.setPlaying(isPlaying)
            scrobbler.playing(player.currentMediaItem, isPlaying)
            handler.removeCallbacks(tick)
            if (isPlaying) handler.postDelayed(tick, 1000) else saveState()
            publishSoon()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateLivePlaying()
            widgets()
            publishSoon()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publishSoon()

        override fun onRepeatModeChanged(repeatMode: Int) = publishSoon()

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateLivePlaying()
            if (playbackState == Player.STATE_ENDED) widgets()
            if (playbackState == Player.STATE_ENDED) {
                tracker.finish()
                scrobbler.complete(player.currentMediaItem)
            }
            if (playbackState == Player.STATE_READY) errorsInRow = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            val blocked = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<StreamingBlockedException>()
                .firstOrNull()
            if (blocked != null) {
                // ustawienia, nie utwór – przeskakiwanie kolejki nic nie da; zatrzymaj i powiedz dlaczego
                errorsInRow = 0
                player.pause()                                     // „Odtwórz” po zmianie ustawień próbuje ponownie
                Toast.makeText(this@PlaybackService, blocked.message, Toast.LENGTH_LONG).show()
                return
            }
            // jak w 39player: uszkodzony / niedostępny utwór jest pomijany (bez zapętlenia przy braku sieci)
            errorsInRow++
            if (errorsInRow <= 5 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                val why = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.lastOrNull()
                Toast.makeText(this@PlaybackService, "Nie udało się odtworzyć: ${why ?: error.errorCodeName}",
                    Toast.LENGTH_LONG).show()
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                saveQueueSoon()
                publishSoon()
            }
        }
    }

    // ---------------------------------------------------------------- stan dla PC („Kontynuuj z telefonu”)
    private var lastPublish = 0L
    private val publishRunnable = Runnable { publishState() }

    private fun publishSoon() {
        handler.removeCallbacks(publishRunnable)
        handler.postDelayed(publishRunnable, 1500)
    }

    /** Telefon „gra” – zamiar użytkownika (bez chwilowych przerw na buforowanie i zmianę utworu). */
    private fun updateLivePlaying() {
        val st = player.playbackState
        g.live.setPlaying(player.playWhenReady && st != Player.STATE_IDLE && st != Player.STATE_ENDED)
    }

    /** Kolejka w kolejności odtwarzania (także przy losowaniu), bieżący utwór i pozycja → state/now-<telefon>.json. */
    private fun publishState(forcePaused: Boolean = false) {
        if (!::player.isInitialized || !g.settings.nasConfigured) return
        val tl = player.currentTimeline
        if (tl.isEmpty) return
        lastPublish = SystemClock.elapsedRealtime()
        val shuffle = player.shuffleModeEnabled
        val window = Timeline.Window()
        val order = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && order.size < tl.windowCount) {
            order += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        var idx = order.indexOf(player.currentMediaItemIndex).coerceAtLeast(0)
        var ids = order.map { tl.getWindow(it, window).mediaItem.mediaId.toIntOrNull() ?: -1 }
        if (ids.size > LiveLink.MAX_QUEUE) {
            val from = (idx - 500).coerceAtLeast(0)
            ids = ids.subList(from, (from + LiveLink.MAX_QUEUE).coerceAtMost(ids.size))
            idx -= from
        }
        idx -= ids.subList(0, idx).count { it < 0 }
        ids = ids.filter { it >= 0 }
        val st = player.playbackState
        val playing = !forcePaused && player.playWhenReady && st != Player.STATE_IDLE && st != Player.STATE_ENDED
        g.live.publish(LiveLink.PhoneQueue(ids, idx, player.currentMediaItem?.mediaId?.toIntOrNull(),
            player.currentPosition, playing, shuffle, player.repeatMode))
    }

    private var queueJob: kotlinx.coroutines.Job? = null

    private fun saveQueueSoon() {
        queueJob?.cancel()
        queueJob = scope.launch {
            delay(1500)
            val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toIntOrNull() }
            withContext(Dispatchers.IO) { g.local.saveQueue(ids) }
            saveState()
        }
    }

    private fun saveState() {
        if (!::player.isInitialized) return
        lastSave = SystemClock.elapsedRealtime()
        val idx = player.currentMediaItemIndex
        val pos = player.currentPosition
        io.execute {
            g.local.put("queue_index", idx.toString())
            g.local.put("queue_pos", pos.toString())
        }
    }

    private suspend fun restoreQueue() {
        // biblioteka wczytuje się w tle przy starcie aplikacji
        var waited = 0
        while (!g.library.ready && waited < 5000) {
            delay(100)
            waited += 100
        }
        if (player.mediaItemCount > 0 || !g.library.ready) return
        val (items, idx, pos) = withContext(Dispatchers.IO) {
            val ids = g.local.loadQueue()
            Triple(resolve(ids.map { MediaItem.Builder().setMediaId(it.toString()).build() }),
                g.local.get("queue_index")?.toIntOrNull() ?: 0, g.local.get("queue_pos")?.toLongOrNull() ?: 0L)
        }
        if (items.isNotEmpty() && player.mediaItemCount == 0) {
            player.setMediaItems(items, idx.coerceIn(0, items.size - 1), pos)
        }
    }

    // ---------------------------------------------------------------- liczenie odsłuchu
    /** 50% faktycznego słuchania → odtworzenie (licznik na PC); 51% → „Przesłuchane” na liście „Do odsłuchania”. */
    private inner class ListenTracker {
        private var row: TrackRow? = null
        private var played = 0L
        private var since = 0L
        private var counted = false
        private var listened = false

        fun start(r: TrackRow, playing: Boolean) {
            row = r; played = 0; counted = false; listened = false
            since = if (playing) SystemClock.elapsedRealtime() else 0
        }

        fun setPlaying(playing: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (playing && since == 0L) since = now
            if (!playing && since != 0L) {
                played += now - since; since = 0
            }
        }

        private fun playedNow() = played + if (since != 0L) SystemClock.elapsedRealtime() - since else 0

        fun tick(durationMs: Long) {
            val r = row ?: return
            val dur = if (durationMs > 0) durationMs else (r.duration * 1000).toLong()
            if (dur <= 0) return
            val p = playedNow()
            if (!counted && p >= dur * 0.5) {
                counted = true
                io.execute { g.library.recordPlay(r.id, r.rel, p, true) }
            }
            if (!listened && p >= dur * 0.51) {
                listened = true
                io.execute { g.library.markListenedAuto(r) }
            }
        }

        fun finish() {
            row = null
        }
    }

    // ---------------------------------------------------------------- okładki dla powiadomienia / Auto
    private fun artUri(key: String, rel: String): Uri =
        Uri.Builder().scheme("d39art").authority("a").appendPath(key).appendQueryParameter("rel", rel).build()

    private inner class ArtBitmapLoader : BitmapLoader {
        private val fallback = DataSourceBitmapLoader.Builder(this@PlaybackService).setMaximumOutputDimension(1024).build()
        override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            if (uri.scheme != "d39art") return fallback.loadBitmap(uri)
            return io.submit(Callable {
                val f = g.art.ensureFile(uri.pathSegments.firstOrNull(), uri.getQueryParameter("rel"))
                    ?: throw IOException("Brak okładki")
                BitmapFactory.decodeFile(f.path) ?: throw IOException("Nie można odczytać okładki")
            })
        }
    }

    companion object {
        const val CMD_HEART = "io.github.senk39.droid39.HEART"
        const val CMD_HEART_REFRESH = "io.github.senk39.droid39.HEART_REFRESH"
        const val ROOT = "root"
        val FEAT_RE = Regex("\\s*(?:\\bfeat(?:\\.|\\b)|\\bft\\.|\\bfeaturing\\b)\\s*", RegexOption.IGNORE_CASE)
    }
}
