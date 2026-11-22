package io.github.senk39.droid39.media

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Kolejka użytkownika: utwory dodane ręcznie („Dodaj do kolejki”, „Odtwórz następny”). Odtwarzacz ma jedną listę –
 * wybraną listę (album, kompozytor, „Ostatnio dodane”…) i wstawione w nią utwory kolejki, oznaczone w MediaMetadata
 * (extras [EXTRA], widoczne też dla interfejsu). Oznaczone utwory grają zaraz po bieżącym, także przy losowaniu
 * (kolejność losowania jest ustawiana tak, by szły po kolei), a po odegraniu tracą oznaczenie. Ekran „Kolejka”
 * pokazuje tylko oznaczone. Co po końcu kolejki – decyduje usługa odtwarzania (ustawienie „Po zakończeniu kolejki”).
 */
@UnstableApi
class UserQueue(private val player: ExoPlayer) {
    companion object {
        const val EXTRA = "39droid.queued"

        fun isMarked(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(EXTRA) == true

        fun mark(item: MediaItem, on: Boolean = true): MediaItem {
            val ex = Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY)
            if (on) ex.putBoolean(EXTRA, true) else ex.remove(EXTRA)
            return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(ex).build()).build()
        }
    }

    /** Utwory dodane do kolejki od chwili, gdy była pusta – dla „odtwórz kolejkę od nowa”. */
    private val round = ArrayList<MediaItem>()

    /** Czy bieżący utwór był z kolejki (przy następnej zmianie utworu – sprawdzenie, czy kolejka się skończyła). */
    private var currentWasQueued = false

    /** Zatrzymanie na końcu ostatniego utworu kolejki (ustawienia inne niż „wróć do poprzedniej listy”). */
    var pauseAtQueueEnd = false
        set(value) {
            field = value
            update()
        }

    fun isQueued(i: Int) = i in 0 until player.mediaItemCount && isMarked(player.getMediaItemAt(i))

    /** Indeksy po bieżącym utworze w kolejności odtwarzania (z losowaniem, bez powtarzania). */
    fun upcoming(): List<Int> {
        val tl = player.currentTimeline
        if (tl.isEmpty) return emptyList()
        val sh = player.shuffleModeEnabled
        val out = ArrayList<Int>()
        var i = tl.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, sh)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, sh)
        }
        return out
    }

    /** Utwory kolejki jeszcze do odegrania (bez bieżącego). */
    fun pendingCount(): Int = upcoming().count { isQueued(it) }

    /** Gra ostatni utwór kolejki (po nim nic z kolejki). */
    fun isLastQueued(): Boolean = isQueued(player.currentMediaItemIndex) && pendingCount() == 0

    private fun shuffleSequence(): List<Int> {
        val tl = player.currentTimeline
        val out = ArrayList<Int>(tl.windowCount)
        var i = tl.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && out.size < tl.windowCount) {
            out += i
            i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out
    }

    /**
     * Dodanie do kolejki: [next] – zaraz po bieżącym („Odtwórz następny”), inaczej na końcu kolejki („Dodaj do
     * kolejki”), przed dalszą częścią wybranej listy. [remember] – do listy „od nowa” (nie przy powtórce kolejki
     * ani przy przenoszeniu kolejki na nową listę).
     */
    fun add(items: List<MediaItem>, next: Boolean, remember: Boolean = true) {
        if (items.isEmpty()) return
        if (remember && pendingCount() == 0 && !isQueued(player.currentMediaItemIndex)) round.clear()
        if (remember) round += items
        val marked = items.map { mark(it) }
        if (player.mediaItemCount == 0) {
            player.setMediaItems(marked)
            player.prepare()
            currentWasQueued = true
            update()
            return
        }
        var anchor = player.currentMediaItemIndex
        if (!next) for (i in upcoming()) if (isQueued(i)) anchor = i else break
        if (!player.shuffleModeEnabled) {
            player.addMediaItems(anchor + 1, marked)
        } else {
            val seq = shuffleSequence().toMutableList()
            val start = player.mediaItemCount
            player.addMediaItems(marked)
            seq.addAll(seq.indexOf(anchor) + 1, (start until start + marked.size).toList())
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(seq.toIntArray(), Random.nextLong()))
        }
        update()
    }

    /** Usunięcie całej kolejki (bez bieżącego utworu) – „Wyczyść kolejkę”. */
    fun clear() {
        val cur = player.currentMediaItemIndex
        for (i in player.mediaItemCount - 1 downTo 0) if (i != cur && isQueued(i)) player.removeMediaItem(i)
        round.clear()
        update()
    }

    /**
     * Po zmianie losowania: włączone – kolejka zaraz po bieżącym, reszta losowo; wyłączone – utwory kolejki
     * przenoszone za bieżący (wstawione przy losowaniu trafiały na koniec listy).
     */
    fun onShuffleChanged(enabled: Boolean) {
        val n = player.mediaItemCount
        if (n == 0) return
        val cur = player.currentMediaItemIndex
        val queued = (0 until n).filter { it != cur && isQueued(it) }
        if (enabled) {
            val rest = (0 until n).filter { it != cur && it !in queued }.shuffled()
            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder((listOf(cur) + queued + rest).toIntArray(), Random.nextLong()))
        } else if (queued.isNotEmpty()) {
            val items = queued.map { player.getMediaItemAt(it) }
            for (i in queued.sortedDescending()) player.removeMediaItem(i)
            player.addMediaItems(player.currentMediaItemIndex + 1, items)
        }
        update()
    }

    /**
     * Po zmianie utworu: odegrane utwory kolejki tracą oznaczenie. Zwraca true, gdy właśnie skończyła się kolejka
     * (poprzedni utwór był z kolejki, bieżący już nie i nic z kolejki nie zostało).
     */
    fun onTransition(): Boolean {
        val cur = player.currentMediaItemIndex
        val ahead = upcoming().toHashSet()
        for (i in 0 until player.mediaItemCount) {
            if (i != cur && i !in ahead && isQueued(i)) player.replaceMediaItem(i, mark(player.getMediaItemAt(i), false))
        }
        val nowQueued = isQueued(cur)
        val finished = currentWasQueued && !nowQueued && pendingCount() == 0
        currentWasQueued = nowQueued
        update()
        return finished
    }

    /** Kolejka od nowa: te same utwory jeszcze raz, zaraz po bieżącym. */
    fun replay(): Boolean {
        if (round.isEmpty()) return false
        add(round.toList(), next = false, remember = false)
        return true
    }

    fun forgetRound() = round.clear()

    fun update() {
        player.pauseAtEndOfMediaItems = pauseAtQueueEnd && isLastQueued()
    }

    /** Oznaczenia po wczytaniu zapisanej listy (indeksy utworów kolejki). */
    fun restoreMarks(indices: Collection<Int>) {
        for (i in indices) if (i in 0 until player.mediaItemCount && !isQueued(i)) {
            player.replaceMediaItem(i, mark(player.getMediaItemAt(i)))
        }
        currentWasQueued = isQueued(player.currentMediaItemIndex)
        update()
    }

    fun markedIndices(): List<Int> = (0 until player.mediaItemCount).filter { isQueued(it) }
}
