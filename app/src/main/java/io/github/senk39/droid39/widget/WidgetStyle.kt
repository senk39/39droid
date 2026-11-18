package io.github.senk39.droid39.widget

import android.content.Context
import org.json.JSONObject

/** Rozmiar (sp) i styl jednego tekstu widżetu. */
data class TextSpec(val sizeSp: Int, val style: String) {
    fun toJson(): JSONObject = JSONObject().put("size", sizeSp).put("style", style)

    companion object {
        fun from(o: JSONObject?, def: TextSpec) = if (o == null) def
        else TextSpec(o.optInt("size", def.sizeSp).coerceIn(SIZE_MIN, SIZE_MAX), o.optString("style", def.style).takeIf { it in STYLES } ?: def.style)

        const val SIZE_MIN = 9
        const val SIZE_MAX = 30

        /** Style tekstu: klucz → nazwa w ustawieniach. */
        val STYLES = linkedMapOf(
            "normal" to "Zwykły", "bold" to "Pogrubiony", "italic" to "Kursywa", "bold_italic" to "Pogrubiona kursywa",
            "light" to "Cienki", "condensed" to "Wąski", "caps" to "WIELKIE LITERY",
        )
    }
}

/**
 * Wygląd jednego widżetu (każdy widżet na ekranie ma własny): układ, tło, trzy teksty (tytuł; kompozytor albo
 * wykonawca; wokaliści) i styl przycisków.
 */
data class WidgetStyle(
    /** "left" – okładka po lewej, "right" – po prawej, "top" – nad tekstem (wyśrodkowana). */
    val layout: String = "left",
    /** "dark", "light", "system" (jak motyw telefonu), "art" (kolor z okładki). */
    val theme: String = "dark",
    /** Krycie tła w procentach (0 – przezroczyste). */
    val opacity: Int = 85,
    val radiusDp: Int = 24,
    val title: TextSpec = TextSpec(16, "bold"),
    val line2: TextSpec = TextSpec(13, "normal"),
    val line3: TextSpec = TextSpec(12, "italic"),
    /** Styl przycisków – klucz z [BUTTONS]. */
    val buttons: String = "filled_play",
    /** Wokaliści z „feat.” na początku (false – same nazwy, np. „Hatsune Miku”). */
    val featPrefix: Boolean = true,
) {
    fun toJson(): String = JSONObject()
        .put("layout", layout).put("theme", theme).put("opacity", opacity).put("radius", radiusDp)
        .put("title", title.toJson()).put("line2", line2.toJson()).put("line3", line3.toJson())
        .put("buttons", buttons).put("feat", featPrefix).toString()

    companion object {
        val LAYOUTS = linkedMapOf("left" to "Okładka po lewej", "right" to "Okładka po prawej", "top" to "Okładka nad tekstem")
        val THEMES = linkedMapOf("dark" to "Ciemne", "light" to "Jasne", "system" to "Jak w telefonie", "art" to "Kolor okładki")
        val BUTTONS = linkedMapOf(
            "filled_play" to "Wypełniony środek", "filled_all" to "Wszystkie w kółkach", "outline" to "Obrysowane",
            "plain" to "Same ikony", "accent" to "Ikony w kolorze okładki",
        )
        const val RADIUS_MAX = 40

        /** Domyślny wygląd dla rodzaju widżetu (duża okładka – nad tekstem). */
        fun defaultFor(kind: String) = if (kind == PlayerWidget.KIND_COVER) WidgetStyle(layout = "top") else WidgetStyle()

        fun fromJson(json: String?, def: WidgetStyle): WidgetStyle {
            if (json.isNullOrBlank()) return def
            val o = try {
                JSONObject(json)
            } catch (e: org.json.JSONException) {
                return def
            }
            return WidgetStyle(
                layout = o.optString("layout", def.layout).takeIf { it in LAYOUTS } ?: def.layout,
                theme = o.optString("theme", def.theme).takeIf { it in THEMES } ?: def.theme,
                opacity = o.optInt("opacity", def.opacity).coerceIn(0, 100),
                radiusDp = o.optInt("radius", def.radiusDp).coerceIn(0, RADIUS_MAX),
                title = TextSpec.from(o.optJSONObject("title"), def.title),
                line2 = TextSpec.from(o.optJSONObject("line2"), def.line2),
                line3 = TextSpec.from(o.optJSONObject("line3"), def.line3),
                buttons = o.optString("buttons", def.buttons).takeIf { it in BUTTONS } ?: def.buttons,
                featPrefix = o.optBoolean("feat", def.featPrefix),
            )
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

        fun load(ctx: Context, widgetId: Int, kind: String): WidgetStyle =
            fromJson(prefs(ctx).getString("w$widgetId", null), defaultFor(kind))

        fun save(ctx: Context, widgetId: Int, style: WidgetStyle) {
            prefs(ctx).edit().putString("w$widgetId", style.toJson()).apply()
        }

        fun delete(ctx: Context, widgetId: Int) {
            prefs(ctx).edit().remove("w$widgetId").apply()
        }
    }
}
