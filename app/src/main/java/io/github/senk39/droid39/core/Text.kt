package io.github.senk39.droid39.core

import java.text.Normalizer
import java.util.Locale

/** Normalizacja tekstu zgodna z 39player (names.sort_key): bez wielkości liter, polskich znaków i akcentów. */
object Text {
    fun sortKey(s: String?): String {
        if (s.isNullOrEmpty()) return "￿"
        // jak str.casefold() w Pythonie dla znaków spotykanych w bibliotece (ß → ss, ς → σ)
        val lower = s.lowercase(Locale.ROOT).replace('ł', 'l').replace("ß", "ss").replace('ς', 'σ')
        val nfkd = Normalizer.normalize(lower, Normalizer.Form.NFKD)
        val sb = StringBuilder(nfkd.length)
        for (ch in nfkd) {
            val t = Character.getType(ch)
            if (t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.COMBINING_SPACING_MARK.toInt()
            ) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Słowa zapytania wyszukiwania (każde musi wystąpić w tekście utworu). */
    fun queryTokens(q: String): List<String> =
        sortKey(q.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() && it != "￿" }

    /** Klucz ścieżki względnej: NFC i małe litery (porównanie plików z karty, pobranych i z NAS). */
    fun relKey(rel: String): String {
        val s = rel.replace('\\', '/')
        // ścieżki z migawki są już w NFC – normalizacja (kosztowna dla 63 tys. ścieżek) tylko w razie potrzeby
        val n = if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)
        return n.lowercase(Locale.ROOT)
    }

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    fun duration(seconds: Double?): String {
        val s = (seconds ?: 0.0).toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    fun durationMs(ms: Long): String = duration(ms / 1000.0)

    fun totalTime(seconds: Double): String {
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "$h godz. $m min" else "$m min"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** „1 utwór”, „3 utwory”, „5 utworów”. */
    fun tracks(n: Int): String = "$n " + plural(n, "utwór", "utwory", "utworów")

    fun plural(n: Int, one: String, few: String, many: String): String {
        if (n == 1) return one
        val d = n % 10
        val t = n % 100
        return if (d in 2..4 && t !in 12..14) few else many
    }

    /** ID wpisu VocaDB / UtaiteDB / TouhouDB do wyświetlenia (jak vocadb.fmt_id w 39player). */
    fun vdbId(id: Long?): String {
        if (id == null) return ""
        return when {
            id >= 2_000_000_000L -> "T${id - 2_000_000_000L}"
            id >= 1_000_000_000L -> "U${id - 1_000_000_000L}"
            else -> id.toString()
        }
    }
}
