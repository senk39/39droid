package io.github.senk39.droid39

import io.github.senk39.droid39.sync.PcStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcStatusTest {
    private val now = 1_800_000_000_000L
    private val h = 3600_000L

    private fun st(
        ageMs: Long = 60_000, state: String = "idle", message: String = "", pending: Int = 0, exportPendingSince: Long = 0,
    ) = PcStatus(now - ageMs, state, message, "", pending, now - h, now - h, exportPendingSince, true)

    @Test
    fun healthyPcHasNoWarnings() {
        assertEquals(emptyList<PcStatus.Warning>(), st().warnings(now))
        assertEquals(emptyList<PcStatus.Warning>(), st(ageMs = 2 * 24 * h).warnings(now))     // PC wyłączony 2 dni – w porządku
        assertEquals(emptyList<PcStatus.Warning>(), st(exportPendingSince = now - 30 * 60_000).warnings(now))  // eksport w toku
    }

    @Test
    fun silentPcForDays() {
        val w = st(ageMs = 4 * 24 * h, state = "waiting_confirmation", pending = 20).warnings(now)
        assertEquals(1, w.size)                                     // stary stan nie jest powtarzany
        assertTrue(w[0].text.startsWith("PC (sync39) nie odzywa się od 4 dni"))
        assertTrue(!w[0].serious)
    }

    @Test
    fun mirrorWaitingAndErrorAndLateExport() {
        val w = st(state = "waiting_confirmation", pending = 20, exportPendingSince = now - 3 * h).warnings(now)
        assertEquals(2, w.size)
        assertTrue(w[0].text.contains("usunięcia 20 plików"))
        assertTrue(w[1].text.startsWith("PC nie eksportował biblioteki od 3 godz."))
        assertTrue(w.all { it.serious })
        val e = st(state = "error", message = "Eksport dla telefonu nie powiódł się").warnings(now)
        assertEquals("sync39 na PC: Eksport dla telefonu nie powiódł się", e.single().text)
    }

    @Test
    fun agoText() {
        assertEquals("5 min", PcStatus.ago(5 * 60_000))
        assertEquals("3 godz.", PcStatus.ago(3 * h))
        assertEquals("47 godz.", PcStatus.ago(47 * h))
        assertEquals("2 dni", PcStatus.ago(48 * h))
        assertEquals("12 dni", PcStatus.ago(12 * 24 * h))
    }
}
