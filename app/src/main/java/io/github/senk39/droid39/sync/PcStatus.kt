package io.github.senk39.droid39.sync

import org.json.JSONObject

/**
 * Stan sync39 na PC z pliku `state/pc-status.json` (zapisuje go sync39 w tle co kilka minut i od razu po zmianie).
 * [warnings] – to, o czym ostrzega chmurka w 39droid.
 */
data class PcStatus(
    val tsMs: Long,
    val state: String,
    val message: String,
    val reason: String,
    val pendingDeletions: Int,
    val lastMirrorMs: Long,
    val lastExportMs: Long,
    val exportPendingSinceMs: Long,
    val mirror: Boolean,
) {
    /** [serious] – coś wymaga działania (czerwony), inaczej informacja (żółty). */
    data class Warning(val text: String, val serious: Boolean)

    fun warnings(nowMs: Long = System.currentTimeMillis()): List<Warning> {
        val age = nowMs - tsMs
        if (age > STALE_MS) {
            // stan sprzed kilku dni nic już nie mówi – tylko to, że PC (albo sync39) długo się nie odzywa
            return listOf(Warning("PC (sync39) nie odzywa się od ${ago(age)} – zmiany z telefonu i nowa biblioteka " +
                "czekają, aż PC będzie włączony", serious = false))
        }
        val out = ArrayList<Warning>()
        when (state) {
            "waiting_confirmation" -> out += Warning(
                "Lustro muzyki na NAS czeka na potwierdzenie usunięcia $pendingDeletions plików – potwierdź w 39player " +
                    "(przycisk „NAS: … do usunięcia” na pasku stanu)", serious = true)
            "error" -> out += Warning("sync39 na PC: ${message.ifBlank { "błąd" }}", serious = true)
        }
        if (exportPendingSinceMs > 0 && nowMs - exportPendingSinceMs > EXPORT_LATE_MS) {
            out += Warning("PC nie eksportował biblioteki od ${ago(nowMs - exportPendingSinceMs)} (biblioteka w 39player " +
                "się zmieniła) – zob. dziennik sync39 na PC", serious = true)
        }
        return out
    }

    companion object {
        /** Po takim czasie bez wieści z PC chmurka ostrzega. */
        const val STALE_MS = 3L * 24 * 3600_000
        /** Eksport po zmianie biblioteki trwa zwykle kilkanaście minut (z ponowieniami) – dłużej to problem. */
        const val EXPORT_LATE_MS = 2L * 3600_000

        fun parse(json: String?): PcStatus? {
            if (json.isNullOrBlank()) return null
            val o = try {
                JSONObject(json)
            } catch (e: org.json.JSONException) {
                return null
            }
            if (o.optInt("format", 0) != 1) return null
            fun ms(key: String) = (o.optDouble(key, 0.0).takeIf { !it.isNaN() } ?: 0.0).times(1000).toLong()
            return PcStatus(
                tsMs = ms("ts"),
                state = o.optString("state"),
                message = o.optString("message"),
                reason = o.optString("reason"),
                pendingDeletions = o.optInt("pending_deletions"),
                lastMirrorMs = ms("last_mirror"),
                lastExportMs = ms("last_export"),
                exportPendingSinceMs = ms("export_pending_since"),
                mirror = o.optBoolean("mirror"),
            )
        }

        /** „5 min”, „3 godz.”, „4 dni” (poniżej 48 godzin – w godzinach). */
        fun ago(ms: Long): String {
            val min = (ms / 60_000).coerceAtLeast(0)
            return when {
                min < 60 -> "$min min"
                min < 48 * 60 -> "${min / 60} godz."
                else -> "${min / (24 * 60)} dni"
            }
        }
    }
}
