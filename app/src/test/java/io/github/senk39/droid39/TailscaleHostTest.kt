package io.github.senk39.droid39

import androidx.media3.common.util.UnstableApi
import io.github.senk39.droid39.sync.Nas
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class TailscaleHostTest {
    @Test
    fun recognisesTailscaleAddresses() {
        assertTrue(Nas.isTailscaleHost("100.74.186.88"))
        assertTrue(Nas.isTailscaleHost(" 100.64.0.1 "))
        assertTrue(Nas.isTailscaleHost("100.127.255.254"))
        assertTrue(Nas.isTailscaleHost("nas-39.tail1234.ts.net"))
        assertTrue(Nas.isTailscaleHost("[fd7a:115c:a1e0::1]"))
    }

    @Test
    fun ignoresLanAndOtherAddresses() {
        assertFalse(Nas.isTailscaleHost("192.168.1.20"))
        assertFalse(Nas.isTailscaleHost("100.63.0.1"))
        assertFalse(Nas.isTailscaleHost("100.128.0.1"))
        assertFalse(Nas.isTailscaleHost("nas.local"))
        assertFalse(Nas.isTailscaleHost(""))
    }
}
