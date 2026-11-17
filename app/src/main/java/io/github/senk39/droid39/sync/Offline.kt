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
 */
class Offline(private val settings: Settings, private val library: Library, private val files: LocalFiles) {

    data class Rule(val key: String, val label: String, val tracks: List<TrackRow>)

    data class Plan(
        val wanted: List<TrackRow>,
        val toDownload: List<TrackRow>,
        val toRemove: List<LocalDb.LocalFile>,
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
        val toDownload = wanted.values.filter { files.resolve(it.rel) == null }
        val toRemove = if (settings.removeUnwanted) files.downloaded().filter { it.relKey !in wantedKeys } else emptyList()
        return Plan(wanted.values.toList(), toDownload, toRemove)
    }

    fun isNodePinned(path: String) = path in settings.offlineNodes
    fun isPlaylistPinned(rel: String) = rel in settings.offlinePlaylists

    fun toggleNode(path: String) {
        settings.offlineNodes = settings.offlineNodes.toMutableSet().apply { if (!add(path)) remove(path) }
    }

    fun togglePlaylist(rel: String) {
        settings.offlinePlaylists = settings.offlinePlaylists.toMutableSet().apply { if (!add(rel)) remove(rel) }
    }
}
