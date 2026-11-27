package io.github.senk39.droid39

import io.github.senk39.droid39.core.Text
import io.github.senk39.droid39.data.LlFilter
import io.github.senk39.droid39.data.LlTrack
import io.github.senk39.droid39.data.Marks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    /** Wartości oczekiwane policzone przez player39.names.sort_key (Python) – wyszukiwanie musi dawać te same klucze. */
    @Test
    fun sortKeyMatchesPython() {
        val expected = mapOf(
            "Łódź Żółć" to "lodz zolc",
            "Hatsune Miku" to "hatsune miku",
            "DECO*27 feat. 初音ミク" to "deco*27 feat. 初音ミク",
            "Café Noir" to "cafe noir",
            "ＦＵＬＬ　ＷＩＤＴＨ" to "full width",
            "Pokémon" to "pokemon",
            "Straße" to "strasse",
            "Yoisaki Kanade (CV. Kusunoki Tomori)" to "yoisaki kanade (cv. kusunoki tomori)",
            "鏡音リン・レン" to "鏡音リン・レン",
            "Ⅳ" to "iv",
            "ﾊﾟｰﾌｪｸﾄ" to "ハーフェクト",
        )
        for ((input, out) in expected) assertEquals(input, out, Text.sortKey(input))
    }

    @Test
    fun queryTokensAndRelKey() {
        assertEquals(listOf("miku", "zolc"), Text.queryTokens("  Miku   Żółć "))
        assertEquals(emptyList<String>(), Text.queryTokens("   "))
        assertEquals("vocaloid/! single/a.mp3", Text.relKey("Vocaloid\\! Single\\A.mp3"))
        // ta sama nazwa w postaci rozłożonej (NFD) i złożonej (NFC) daje ten sam klucz
        assertEquals(Text.relKey("Pokémon.mp3"), Text.relKey("Pokémon.mp3"))
    }

    @Test
    fun formatting() {
        assertEquals("3:05", Text.duration(185.4))
        assertEquals("1:01:01", Text.duration(3661.0))
        assertEquals("1 utwór", Text.tracks(1))
        assertEquals("3 utwory", Text.tracks(3))
        assertEquals("12 utworów", Text.tracks(12))
        assertEquals("22 utwory", Text.tracks(22))
        assertEquals("U39", Text.vdbId(1_000_000_039L))
        assertEquals("T4454", Text.vdbId(2_000_004_454L))
        assertEquals("365099", Text.vdbId(365099L))
    }

    @Test
    fun marksBits() {
        val m = Marks(best = true, liked = true, listened = false)
        assertEquals(3, m.bits)
        assertEquals(2, m.heart)
        assertEquals(m, Marks.of(3))
        assertEquals(1, Marks.of(2 or 4).heart)
        assertTrue(Marks.of(4).listened)
    }

    @Test
    fun loveLiveFilter() {
        // stare zachowanie (wszystkie rodzaje pokazane) – filtry grupy / postaci / subunitu
        fun LlFilter(group: String = "", char: String = "", unit: String = "", solo: Boolean = false, ost: String = "tak") =
            io.github.senk39.droid39.data.LlFilter(group, char, unit, solo, "tak", ost, "tak", "tak", "tak", "tak")
        val riko = LlTrack(setOf("Aqours"), setOf("Riko Sakurauchi"), setOf("Riko Sakurauchi"), emptySet(), solo = true, inst = false, ost = false)
        val gk = LlTrack(setOf("Aqours"), emptySet(), setOf("Riko Sakurauchi", "Yoshiko Tsushima", "Mari Ohara"), setOf("Guilty Kiss"), solo = false, inst = false, ost = false)
        val ost = LlTrack(emptySet(), emptySet(), emptySet(), emptySet(), solo = false, inst = false, ost = true)
        assertTrue(LlFilter(group = "Aqours").matches(riko))
        assertFalse(LlFilter(group = "Liella!").matches(riko))
        // postać obejmuje piosenki jej subunitów
        assertTrue(LlFilter(char = "Riko Sakurauchi").matches(gk))
        assertTrue(LlFilter(char = "Riko Sakurauchi", solo = true).matches(riko))
        assertFalse(LlFilter(solo = true).matches(gk))
        assertTrue(LlFilter(unit = "Guilty Kiss").matches(gk))
        assertFalse(LlFilter(ost = "nie").matches(ost))
        assertTrue(LlFilter(ost = "tylko").matches(ost))
        assertFalse(LlFilter(ost = "tylko").matches(riko))
        assertTrue(LlFilter().matches(null))
        assertFalse(io.github.senk39.droid39.data.LlFilter().active)
    }

    @Test
    fun loveLiveDefaultsHideVersions() {
        fun t(variant: Boolean = false, ost: Boolean = false, ostUnique: Boolean = false, inst: Boolean = false,
              short: Boolean = false, live: Boolean = false, remix: Boolean = false, solo: Boolean = false) =
            LlTrack(setOf("μ's"), emptySet(), emptySet(), emptySet(), solo, inst, ost, variant, ostUnique, short, live, remix)
        val d = LlFilter()
        assertTrue(d.matches(t()))
        assertFalse(d.matches(t(variant = true, solo = true)))
        assertFalse(d.matches(t(ost = true)))
        assertTrue(d.matches(t(ost = true, ostUnique = true)))          // piosenka tylko z OST zostaje
        assertFalse(d.matches(t(inst = true)))
        assertFalse(d.matches(t(short = true)))
        assertFalse(d.matches(t(live = true)))
        assertFalse(d.matches(t(remix = true)))
        // ręczne włączenie rodzaju
        assertTrue(d.copy(remix = "tak").matches(t(remix = true)))
        assertFalse(d.copy(remix = "tylko").matches(t()))
        assertTrue(d.copy(ost = "tylko").matches(t(ost = true)))
        // „Tylko solowe” pokazuje też solowe wersje utworów grupy
        assertTrue(d.copy(solo = true).matches(t(variant = true, solo = true)))
        assertTrue(LlFilter(inst = "tak").active)
    }
}
