package com.kaovilai.hotspotrelay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetsCodecTest {

    @Test
    fun `round trips every field`() {
        val targets = listOf(
            IphoneTarget(
                id = "one",
                label = "Personal",
                btSelector = "AA:BB:CC:DD:EE:FF",
                ssid = "Personal iPhone",
                password = "hunter22",
                enabled = true,
            ),
            IphoneTarget(
                id = "two",
                label = "Work",
                btSelector = "Work iPhone",
                ssid = "Work iPhone",
                password = "letmein",
                enabled = false,
            ),
        )

        val decoded = TargetsCodec.decode(TargetsCodec.encode(targets))

        assertEquals(targets, decoded)
    }

    @Test
    fun `blank or malformed json decodes to an empty list`() {
        assertTrue(TargetsCodec.decode(null).isEmpty())
        assertTrue(TargetsCodec.decode("").isEmpty())
        assertTrue(TargetsCodec.decode("not json").isEmpty())
        assertTrue(TargetsCodec.decode("{}").isEmpty())
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val decoded = TargetsCodec.decode("""[{"ssid":"Only SSID"}]""")

        assertEquals(1, decoded.size)
        val target = decoded.single()
        assertEquals("Only SSID", target.ssid)
        assertEquals("", target.btSelector)
        assertEquals("", target.password)
        assertTrue(target.enabled)
        assertTrue(target.id.isNotBlank())
    }

    @Test
    fun `an empty list round trips and stays empty`() {
        assertEquals(emptyList<IphoneTarget>(), TargetsCodec.decode(TargetsCodec.encode(emptyList())))
    }

    @Test
    fun `active targets require enabled and complete fields`() {
        val complete = IphoneTarget("a", btSelector = "Phone", ssid = "SSID", password = "pw")
        val disabled = complete.copy(id = "b", enabled = false)
        val noPassword = complete.copy(id = "c", password = "")
        val settings = AppSettings(targets = listOf(complete, disabled, noPassword))

        assertEquals(listOf(complete), settings.activeTargets)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun `no active targets means not configured`() {
        val settings = AppSettings(targets = listOf(IphoneTarget.empty()))
        assertTrue(settings.activeTargets.isEmpty())
        assertEquals(false, settings.isConfigured)
    }
}
