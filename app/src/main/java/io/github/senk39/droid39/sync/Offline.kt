package io.github.senk39.droid39.sync

import io.github.senk39.droid39.core.Settings
import io.github.senk39.droid39.core.Text
import io.github.senk39.droid39.data.Library
import io.github.senk39.droid39.data.LocalDb
import io.github.senk39.droid39.data.TrackRow
import io.github.senk39.droid39.files.LocalFiles

/**
 * Reguły „offline”: co ma być zawsze na telefonie (Najlepsze, Polubione, Do odsłuchania, wybrane playlisty
 * i kategorie). Utwory już obecne na karcie (kopia biblioteki) nie są pobierane drugi raz.
 *
 * Przejęty folder na karcie: pobierane trafiają do niego, pliki zmienione na PC (inny rozmiar – np. nowe tagi) są
 * pobierane ponownie, a przy braku miejsca mogą zniknąć najrzadziej słuchane (spoza reguł; są na NAS i PC).
 * Pliki, których nie ma w bibliotece PC, usuwa tylko użytkownik („Porządkuj folder”).
 */
class Offline(private val settings: Settings, private val library: Library, private val files: LocalFiles) {

    data class Rule(val key: String, val label: String, val tracks: List<TrackRow>)

    data class Plan(
        val wanted: List<TrackRow>,
        val toDownload: List<TrackRow>,
        val toRemove: List<LocalDb.LocalFile>,
        /** Z `toDownload`: pliki w przejętym folderze zmienione na PC. */
        val changed: Int = 0,
    ) {
        val downloadBytes: Long get() = toDownload.sumOf { it.size }
        val wantedBytes: Long get() = wanted.sumOf { it.size }
    }

    fun rules(): List<Rule> {
        val out = ArrayList<Rule>()
        if (settings.offlineBest) out += Rule("best", "Najlepsze ♥♥", library.bestTracks())
        if (settings.offlineLiked) out += Rule("liked", "Polubione ♥", library.likedTracks())
        if (settings.offlineUnlistened) out += Rule("unl", "Do odsłuchania", library.unlistenedTracks())
        for (rel in settings.offlinePlaylists.sorted()) {
            out += Rule("pl:$rel", rel.substringAfterLast('/').substringBeforeLast('.'), library.playlistTracks(rel))
        }
        for (path in settings.offlineNodes.sorted()) {
            val node = library.nodeByPath(path) ?: continue
            out += Rule("node:$path", node.name, library.nodeTracks(node))
        }
        return out
    }

    fun plan(): Plan {
        val wanted = LinkedHashMap<Int, TrackRow>()
        for (r in rules()) for (t in r.tracks) wanted.putIfAbsent(t.id, t)
        val wantedKeys = wanted.values.mapTo(HashSet()) { Text.relKey(it.rel) }
        val missing = wanted.values.filter { files.resolve(it.rel) == null }
        val missingIds = missing.mapTo(HashSet()) { it.id }
        val changed = changedTracks().filter { it.id !in missingIds }
        val toRemove = if (settings.removeUnwanted) files.downloaded().filter { it.relKey !in wantedKeys } else emptyList()
        return Plan(wanted.values.toList(), missing + changed, toRemove, changed.size)
    }

    /** Przejęty folder: utwory, których plik na karcie ma inny rozmiar niż na PC (zmienione tagi, nowa wersja). */
    fun changedTracks(): List<TrackRow> {
        val managed = files.managedFiles().associateBy { it.relKey }
        if (managed.isEmpty()) return emptyList()
        val ids = library.withSnap { s ->
            s.allIdsWithSize().filter { (_, rel, size) ->
                size > 0 && managed[Text.relKey(rel)]?.let { it.size != size } == true
            }.map { it.first }
        } ?: emptyList()
        return library.tracks(ids)
    }

    /** Przegląd przejętego folderu: pliki spoza biblioteki PC (do usunięcia po potwierdzeniu), zmienione, brakujące. */
    data class Review(
        val managed: Int,
        val stale: List<LocalDb.LocalFile>,
        val changed: Int,
        val missing: Int,
        val problem: String?,
    ) {
        val staleBytes: Long get() = stale.sumOf { it.size }
    }

    fun review(): Review {
        if (files.managedRoot() == null) return Review(0, emptyList(), 0, 0, "Folder na karcie nie jest przejęty")
        val managed = files.managedFiles()
        val keys = library.withSnap { s -> s.allIdsWithSize().mapTo(HashSet()) { Text.relKey(it.second) } }
            ?: return Review(managed.size, emptyList(), 0, 0, "Brak biblioteki z PC")
        val stale = managed.filter { it.relKey !in keys }.sortedBy { it.path }
        val p = plan()
        val problem = when {
            keys.size < 1000 -> "Biblioteka z PC wygląda na niekompletną (${keys.size} utworów) – najpierw zsynchronizuj"
            managed.isNotEmpty() && stale.size * 2 > managed.size ->
                "Ponad połowa plików na karcie nie pasuje do biblioteki – sprawdź, czy wybrano właściwy folder"
            else -> null
        }
        return Review(managed.size, stale, p.changed, p.toDownload.size - p.changed, problem)
    }

    /**
     * Zwalnianie miejsca w przejętym folderze: usuwa najrzadziej słuchane pliki (najmniej odtworzeń, potem najdawniej
     * słuchane), które są w bibliotece PC (wrócą z NAS) i nie są chronione regułami offline. Zwraca zwolnione bajty.
     */
    fun evict(bytes: Long, protect: Set<String>): Long {
        if (bytes <= 0 || !settings.evictRarelyPlayed) return 0
        val stats = library.playStats()
        val candidates = files.managedFiles()
            .filter { it.relKey !in protect && stats.containsKey(it.relKey) }
            .sortedWith(compareBy<LocalDb.LocalFile>({ stats[it.relKey]?.first ?: 0 }, { stats[it.relKey]?.second ?: 0.0 }))
        val picked = ArrayList<LocalDb.LocalFile>()
        var sum = 0L
        for (f in candidates) {
            if (sum >= bytes) break
            picked += f
            sum += f.size
        }
        return files.deleteLocal(picked).second
    }

    fun isNodePinned(path: String) = path in settings.offlineNodes
    fun isPlaylistPinned(rel: String) = rel in settings.offlinePlaylists

    /** Wołane, gdy użytkownik przypnie playlistę / kategorię – pobieranie startuje od razu. */
    var onPinned: (() -> Unit)? = null

    fun toggleNode(path: String) {
        val set = settings.offlineNodes.toMutableSet()
        val added = set.add(path)
        if (!added) set.remove(path)
        settings.offlineNodes = set
        if (added) onPinned?.invoke()
    }

    /** Playlista przemianowana / usunięta na telefonie – przypięcie „offline” idzie za nią. */
    fun playlistRenamed(old: String, new: String?) {
        val set = settings.offlinePlaylists.toMutableSet()
        if (!set.remove(old)) return
        if (new != null) set += new
        settings.offlinePlaylists = set
    }

    fun togglePlaylist(rel: String) {
        val set = settings.offlinePlaylists.toMutableSet()
        val added = set.add(rel)
        if (!added) set.remove(rel)
        settings.offlinePlaylists = set
        if (added) onPinned?.invoke()
    }
}
