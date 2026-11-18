package io.github.senk39.droid39.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Zmiana z telefonu, której PC nie przyjął (plik `state/rejected-<urządzenie>.json` zapisywany przez 39player albo
 * sync39), np. dodanie do playlisty usuniętej na PC albo serduszko dla utworu, którego na PC już nie ma.
 * [what] – opis zmiany z tytułem utworu z biblioteki telefonu (ustalany przy odczycie).
 */
data class RejectedChange(
    val seq: Long,
    val tsMs: Long,
    val type: String,
    val reason: String,
    val trackId: Int?,
    val rel: String?,
    val playlist: String?,
    val level: Int?,
    val what: String = "",
) {
    companion object {
        fun parseAll(json: String?): List<RejectedChange> {
            if (json.isNullOrBlank()) return emptyList()
            val items = try {
                JSONObject(json).optJSONArray("items") ?: JSONArray()
            } catch (e: org.json.JSONException) {
                return emptyList()
            }
            return List(items.length()) { items.optJSONObject(it) }.mapNotNull { o ->
                if (o == null || !o.has("seq")) return@mapNotNull null
                RejectedChange(
                    seq = o.optLong("seq"),
                    tsMs = (o.optDouble("ts", 0.0).takeIf { !it.isNaN() } ?: 0.0).times(1000).toLong(),
                    type = o.optString("type"),
                    reason = o.optString("reason").ifEmpty { "Nie zastosowano na PC" },
                    trackId = if (o.has("track") && !o.isNull("track")) o.optInt("track") else null,
                    rel = o.optString("rel").takeIf { it.isNotEmpty() && !o.isNull("rel") },
                    playlist = o.optString("playlist").takeIf { it.isNotEmpty() && !o.isNull("playlist") },
                    level = if (o.has("level") && !o.isNull("level")) o.optInt("level") else null,
                )
            }.sortedBy { it.seq }
        }

        /** „Dodanie do „Nazwa”: Tytuł – Wykonawca” – opis zmiany dla użytkownika. */
        fun describe(c: RejectedChange, track: String?): String {
            val pl = c.playlist?.substringAfterLast('/')?.removeSuffix(".m3u8")?.removeSuffix(".m3u")
            val song = track ?: c.rel?.substringAfterLast('/') ?: c.trackId?.let { "utwór #$it" }
            val action = when (c.type) {
                "heart" -> when (c.level) { 2 -> "♥♥"; 1 -> "♥"; else -> "Zdjęcie serduszka" }
                "mark" -> "Oznaczenie w „Do odsłuchania”"
                "play" -> "Odtworzenie"
                "pl_add" -> "Dodanie do „$pl”"
                "pl_remove" -> "Usunięcie z „$pl”"
                "pl_create" -> "Nowa playlista „$pl”"
                else -> "Zmiana „${c.type}”"
            }
            return if (song != null && c.type != "pl_create") "$action: $song" else action
        }
    }
}
