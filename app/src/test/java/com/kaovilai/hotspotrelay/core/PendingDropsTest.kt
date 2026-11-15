package com.kaovilai.hotspotrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDropsTest {

    @Test
    fun `starts empty`() {
        val pending = PendingDrops()
        assertEquals(emptyList<String>(), pending.current())
        assertFalse(pending.contains("AA"))
    }

    @Test
    fun `publishes the batch enforcement asked to disconnect`() {
        val pending = PendingDrops()
        pending.publish(listOf("AA", "BB"))
        assertEquals(listOf("AA", "BB"), pending.current())
        assertTrue(pending.contains("BB"))
    }

    @Test
    fun `a verified batch is cleared when nothing replaced it`() {
        val pending = PendingDrops()
        pending.publish(listOf("AA"))
        val verified = pending.current()
        assertTrue(pending.clearIfCurrent(verified))
        assertEquals(emptyList<String>(), pending.current())
    }

    @Test
    fun `a batch published while verifying is not erased`() {
        val pending = PendingDrops()
        pending.publish(listOf("AA"))
        // Snapshot captures AA and verifies its teardown...
        val verified = pending.current()
        // ...while enforcement drops target B and publishes it as pending.
        pending.publish(listOf("BB"))

        assertFalse(pending.clearIfCurrent(verified))
        assertEquals(listOf("BB"), pending.current())
        assertTrue(pending.contains("BB"))
    }

    @Test
    fun `a republished equal batch is treated as newer`() {
        val pending = PendingDrops()
        pending.publish(listOf("AA"))
        val verified = pending.current()
        // Same content, new batch: the old verification must not clear it.
        pending.publish(listOf("AA"))

        assertFalse(pending.clearIfCurrent(verified))
        assertEquals(listOf("AA"), pending.current())
    }
}
