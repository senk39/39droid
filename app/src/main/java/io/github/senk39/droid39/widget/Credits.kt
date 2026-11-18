package io.github.senk39.droid39.widget

/**
 * Druga i trzecia linia widżetu. Vocaloid (folder „Vocaloid/” albo dane VocaDB): kompozytor i wokaliści – z ról VocaDB,
 * a bez nich z wykonawcy „Kompozytor feat. Wokaliści”. Pozostała muzyka: wykonawca (trzeciej linii brak).
 */
object Credits {
    data class Lines(val line2: String, val line3: String?)

    /** [vdbArtists] – artyści VocaDB z rolami (jak w migawce: „kompozycja”, „producent”, „wokal”…). */
    fun of(rel: String, artist: String, composerTag: String?, vdbArtists: List<Pair<String, List<String>>>): Lines {
        val vocaloid = rel.startsWith("Vocaloid/", ignoreCase = true) || vdbArtists.any { "wokal" in it.second }
        if (!vocaloid) return Lines(artist.ifBlank { "Nieznany wykonawca" }, null)
        val (featBy, featVocals) = splitFeat(artist)
        fun withRole(role: String) = vdbArtists.filter { role in it.second }.map { clean(it.first) }.distinct()
        val composers = withRole("kompozycja").ifEmpty { withRole("producent") }.ifEmpty {
            listOfNotNull(featBy.ifBlank { null } ?: composerTag?.ifBlank { null })
        }
        val vocals = withRole("wokal").ifEmpty { featVocals }
        val line2 = composers.joinToString(", ").ifBlank { artist.ifBlank { "Nieznany kompozytor" } }
        return Lines(line2, vocals.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "feat. "))
    }

    /** „DECO*27 feat. 初音ミク, GUMI” → („DECO*27”, [初音ミク, GUMI]). Bez „feat.” – (wykonawca, []). */
    fun splitFeat(artist: String): Pair<String, List<String>> {
        val m = Regex("""\s+(?:feat\.?|ft\.)\s+""", RegexOption.IGNORE_CASE).find(artist) ?: return artist.trim() to emptyList()
        val vocals = artist.substring(m.range.last + 1).split(Regex("""\s*(?:,|、|&| and | x )\s*"""))
            .map { it.trim() }.filter { it.isNotEmpty() }
        return artist.substring(0, m.range.first).trim() to vocals
    }

    /** „Hatsune Miku NT (Unknown)” → „Hatsune Miku NT”. */
    private fun clean(name: String) = name.replace(Regex("""\s*\((?:Unknown|unknown)\)$"""), "").trim()
}
