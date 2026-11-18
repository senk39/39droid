package io.github.senk39.droid39.data

/** Wiersz utworu z migawki (serduszko z migawki – aktualne z nakładką zmian daje Library.heart). */
data class TrackRow(
    val id: Int,
    val rel: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val duration: Double,
    val size: Long,
    val snapHeart: Int,
    val artKey: String?,
    val trackNo: Int,
    val discNo: Int,
    val year: Int,
    val playCount: Int,
    val dateAdded: Double,
    val lastPlayed: Double,
    val songId: Long?,
    val songType: String?,
    val vdbIssue: Boolean,
    val genre: String?,
    val composer: String?,
)

data class NodeRow(
    val id: Int,
    val parent: Int?,
    val pos: Int,
    val path: String,
    val name: String,
    val kind: String,
    val count: Int,
    val likes: Int,
    val hasTracks: Boolean,
    val vdbArtistId: Long?,
    val vdbArtistName: String?,
    val vdbArtistType: String?,
    val engines: List<String>,
    val art: String?,
) {
    val isAlbum get() = kind == "album"
    val isAggregate get() = !hasTracks
}

data class PlaylistRow(
    val id: Int,
    val rel: String,
    val name: String,
    val folder: String,
    val kind: String,          // pl, dir, best, liked, liked_tel, unlistened, local (utworzona na telefonie)
    val hidden: Boolean,
    val count: Int,
    val missing: Int,
)

data class TagRow(val id: Long, val name: String, val category: String, val count: Int, val likes: Int)

data class VdbInfo(
    val songId: Long?,
    val albumId: Long?,
    val songType: String,
    val nameJa: String,
    val nameRomaji: String,
    val nameEn: String,
    val albumJa: String,
    val albumRomaji: String,
    val albumEn: String,
    val roles: String,
    val artists: List<Pair<String, List<String>>>,
    val publishDate: String,
    val issue: String,
    val tagsFetched: Boolean,
    val tags: List<TagRow>,
)

data class LlTrack(
    val groups: Set<String>,
    val chars: Set<String>,
    val voices: Set<String>,
    val units: Set<String>,
    val solo: Boolean,
    val inst: Boolean,
    val ost: Boolean,
)

data class LlEntry(val name: String, val group: String?, val members: List<String>, val icon: String?, val count: Int)

data class LlRoster(
    val groups: List<LlEntry>,
    val chars: List<LlEntry>,
    val units: List<LlEntry>,
    /** ścieżka węzła folderu grupy → nazwa grupy (np. …/Aqours → Aqours). */
    val folders: Map<String, String>,
    val nodePath: String?,
)

/** Stan filtrów Love Live! (jak lovelive.DEFAULT_STATE w 39player). */
data class LlFilter(
    val group: String = "",
    val char: String = "",
    val unit: String = "",
    val solo: Boolean = false,
    val inst: String = "tak",     // tak / nie / tylko
    val ost: String = "tak",
) {
    val active get() = this != LlFilter()

    fun matches(x: LlTrack?): Boolean {
        if (x == null) return true
        if (group.isNotEmpty() && group !in x.groups) return false
        if (char.isNotEmpty() && char !in x.voices) return false
        if (unit.isNotEmpty() && unit !in x.units) return false
        if (solo && !x.solo) return false
        for ((mode, flag) in listOf(inst to x.inst, ost to x.ost)) {
            if (mode == "nie" && flag) return false
            if (mode == "tylko" && !flag) return false
        }
        return true
    }
}

data class FranchiseItem(val label: String, val nodePaths: List<String>, val count: Int, val art: String?)

data class Franchise(
    val id: Int,
    val label: String,
    val nodePath: String?,
    val count: Int,
    val art: String?,
    val items: List<FranchiseItem>,
)

data class Star(val kind: String, val ident: String, val name: String)

/** Oznaczenia „Do odsłuchania”: najlepsze / polubione / przesłuchane (jak w 39player). */
data class Marks(val best: Boolean, val liked: Boolean, val listened: Boolean) {
    val bits get() = (if (best) 1 else 0) or (if (liked) 2 else 0) or (if (listened) 4 else 0)
    val heart get() = if (best) 2 else if (liked) 1 else 0

    companion object {
        fun of(bits: Int) = Marks(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
    }
}

enum class SortMode(val label: String) {
    DEFAULT("Domyślnie"), TITLE("Tytuł"), ARTIST("Wykonawca"), ALBUM("Album"), DURATION("Długość"),
    ADDED("Data dodania"), PLAYS("Odtworzenia"), YEAR("Rok"), RANDOM("Losowo")
}

/** Podfolder w przeglądarce folderów: nazwa, pełna ścieżka względna, liczba utworów w całym poddrzewie. */
data class FolderEntry(val name: String, val path: String, val count: Int)

data class FolderListing(val folders: List<FolderEntry>, val tracks: List<TrackRow>) {
    val total: Int get() = folders.sumOf { it.count } + tracks.size
}
