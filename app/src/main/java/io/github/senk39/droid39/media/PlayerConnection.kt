package io.github.senk39.droid39.media

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.senk39.droid39.data.TrackRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUi(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val currentId: Int? = null,
    val index: Int = 0,
    val queue: List<Int> = emptyList(),
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
)

/** Odtwarzany utwór (bez pozycji) – dla list, które nie muszą odświeżać się co pół sekundy. */
data class NowPlaying(val id: Int? = null, val playing: Boolean = false)

/** Połączenie interfejsu z usługą odtwarzania (MediaController) i stan do wyświetlenia. */
@UnstableApi
class PlayerConnection(private val context: Context) {
    private var controller: MediaController? = null
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui
    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now
    private var queueCache: List<Int> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    /** Etykieta źródła odtwarzania (np. nazwa playlisty) – do nagłówka odtwarzacza. */
    val source = MutableStateFlow("")

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh()
            pending.forEach { it(c) }
            pending.clear()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        poll?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) =
            refresh(events.contains(Player.EVENT_TIMELINE_CHANGED))
        override fun onPlayerError(error: PlaybackException) {
            _ui.value = _ui.value.copy(error = error.message ?: "Błąd odtwarzania")
        }
    }

    private fun refresh(timelineChanged: Boolean = true) {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (timelineChanged || queueCache.size != n) {
            val queue = ArrayList<Int>(n)
            for (i in 0 until n) c.getMediaItemAt(i).mediaId.toIntOrNull()?.let { queue += it }
            queueCache = queue
        }
        val queue = queueCache
        _now.value = NowPlaying(c.currentMediaItem?.mediaId?.toIntOrNull(), c.isPlaying)
        _ui.value = PlayerUi(
            connected = true,
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            currentId = c.currentMediaItem?.mediaId?.toIntOrNull(),
            index = c.currentMediaItemIndex,
            queue = queue,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            bufferedMs = c.bufferedPosition,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            error = c.playerError?.message,
        )
        poll?.cancel()
        if (c.isPlaying) poll = scope.launch {
            while (isActive) {
                delay(500)
                val cc = controller ?: break
                _ui.value = _ui.value.copy(positionMs = cc.currentPosition.coerceAtLeast(0), bufferedMs = cc.bufferedPosition,
                    durationMs = cc.duration.takeIf { it != C.TIME_UNSET } ?: _ui.value.durationMs)
            }
        }
    }

    private fun items(rows: List<TrackRow>) = rows.map { MediaItem.Builder().setMediaId(it.id.toString()).build() }

    /** Odtwarza listę od utworu `start`; `shuffle` – losowa kolejność (kolejka do 10 000 utworów). */
    fun play(rows: List<TrackRow>, start: Int = 0, shuffle: Boolean = false, label: String = "") {
        if (rows.isEmpty()) return
        var list = rows
        var startIdx = start.coerceIn(0, rows.size - 1)
        if (list.size > MAX_QUEUE) {
            if (shuffle) {
                val first = list[startIdx]
                list = listOf(first) + (list - first).shuffled().take(MAX_QUEUE - 1)
                startIdx = 0
            } else {
                val from = (startIdx - 100).coerceAtLeast(0)
                list = list.subList(from, (from + MAX_QUEUE).coerceAtMost(list.size))
                startIdx -= from
            }
        }
        if (shuffle && start == 0) startIdx = list.indices.random()
        source.value = label
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items(list), startIdx, 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(rows: List<TrackRow>) = withController { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items(rows)); c.prepare(); c.play()
        } else c.addMediaItems(c.currentMediaItemIndex + 1, items(rows))
    }

    fun enqueue(rows: List<TrackRow>) = withController { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items(rows)); c.prepare()
        } else c.addMediaItems(items(rows))
    }

    fun toggle() = withController { c ->
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { if (it.currentPosition > 4000) it.seekTo(0) else it.seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index); it.play() }
    fun remove(index: Int) = withController { it.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { it.moveMediaItem(from, to) }
    fun clearQueue() = withController { it.clearMediaItems() }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Odświeża przycisk ♥ w powiadomieniu po zmianie serduszka w aplikacji. */
    fun heartChanged() = withController { c ->
        c.sendCustomCommand(SessionCommand(PlaybackService.CMD_HEART_REFRESH, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
    }

    companion object {
        const val MAX_QUEUE = 10_000
    }
}
