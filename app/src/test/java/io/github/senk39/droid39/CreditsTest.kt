package io.github.senk39.droid39

import io.github.senk39.droid39.widget.Credits
import org.junit.Assert.assertEquals
import org.junit.Test

class CreditsTest {
    @Test
    fun vocaloidFromVocaDbRoles() {
        val artists = listOf(
            "kemu" to listOf("kompozycja"), "Rockwell" to listOf("aranżacja"), "DECO*27" to listOf("tekst"),
            "Hoshino Ichika" to listOf("wokal"), "Hatsune Miku NT (Unknown)" to listOf("wokal"),
        )
        val l = Credits.of("Vocaloid/kemu/x.mp3", "kemu feat. Hoshino Ichika", null, artists)
        assertEquals("kemu", l.line2)
        assertEquals("feat. Hoshino Ichika, Hatsune Miku NT", l.line3)
    }

    @Test
    fun producerWhenNoComposerRole() {
        val artists = listOf("JesusP" to listOf("producent"), "KAITO" to listOf("wokal", "wsparcie"))
        assertEquals(Credits.Lines("JesusP", "feat. KAITO"), Credits.of("Vocaloid/a.mp3", "", null, artists))
    }

    @Test
    fun vocaloidWithoutVocaDbUsesFeat() {
        assertEquals(Credits.Lines("DECO*27", "feat. 初音ミク, GUMI"),
            Credits.of("Vocaloid/DECO*27/a.mp3", "DECO*27 feat. 初音ミク, GUMI", null, emptyList()))
        assertEquals(Credits.Lines("ryo", null), Credits.of("Vocaloid/ryo/a.mp3", "ryo", null, emptyList()))
    }

    @Test
    fun otherMusicShowsArtist() {
        assertEquals(Credits.Lines("Aqours", null), Credits.of("Anime/Love Live!/a.flac", "Aqours", null, emptyList()))
        assertEquals(Credits.Lines("Nieznany wykonawca", null), Credits.of("Rock/a.mp3", "", null, emptyList()))
    }
}
