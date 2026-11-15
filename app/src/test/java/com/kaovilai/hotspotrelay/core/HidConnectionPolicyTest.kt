package com.kaovilai.hotspotrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidConnectionPolicyTest {

    @Test
    fun `window opens for its duration`() {
        val until = HidConnectionPolicy.allowedUntil(nowMs = 1_000L, durationMs = 60_000L)

        assertEquals(61_000L, until)
        assertTrue(HidConnectionPolicy.isAllowed(nowMs = 1_000L, allowedUntilMs = until))
        assertTrue(HidConnectionPolicy.isAllowed(nowMs = 61_000L, allowedUntilMs = until))
        assertFalse(HidConnectionPolicy.isAllowed(nowMs = 61_001L, allowedUntilMs = until))
    }

    @Test
    fun `an expired window drops the link immediately`() {
        assertEquals(
            0L,
            HidConnectionPolicy.nextCheckDelayMs(nowMs = 10_000L, allowedUntilMs = 9_000L),
        )
    }

    @Test
    fun `an open window checks after the window plus grace`() {
        // 60 s window, 2 s grace: the link may live to 62 s, then it is dropped.
        assertEquals(
            62_000L,
            HidConnectionPolicy.nextCheckDelayMs(nowMs = 10_000L, allowedUntilMs = 70_000L),
        )
    }

    @Test
    fun `checks are never shorter than the minimum interval`() {
        assertEquals(
            HidConnectionPolicy.MIN_CHECK_INTERVAL_MS,
            HidConnectionPolicy.nextCheckDelayMs(nowMs = 10_000L, allowedUntilMs = 10_500L),
        )
    }

    @Test
    fun `a link cannot outlive the window even without new events`() {
        // Simulate the enforcement loop: repeated checks must eventually see the
        // expired window, which is what guarantees no long-lived keyboard.
        val until = HidConnectionPolicy.allowedUntil(
            nowMs = 0L,
            durationMs = HidConnectionPolicy.PAIRING_WINDOW_MS,
        )
        var now = 0L
        var checks = 0
        while (HidConnectionPolicy.isAllowed(now, until) && checks < 100) {
            now += HidConnectionPolicy.nextCheckDelayMs(now, until)
            checks++
        }

        assertFalse(HidConnectionPolicy.isAllowed(now, until))
        assertTrue("enforcement loop must terminate", checks < 100)
    }
}
