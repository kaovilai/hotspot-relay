package com.kaovilai.hotspotrelay.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropEpisodesTest {

    @Test
    fun `a peer is reported once per episode`() {
        val episodes = DropEpisodes()
        assertTrue(episodes.markReported("AA"))
        assertFalse(episodes.markReported("AA"))
        assertTrue(episodes.isReported("AA"))
    }

    @Test
    fun `peers are tracked independently`() {
        val episodes = DropEpisodes()
        assertTrue(episodes.markReported("AA"))
        assertTrue(episodes.markReported("BB"))
        assertFalse(episodes.markReported("AA"))
        assertFalse(episodes.markReported("BB"))
    }

    @Test
    fun `reconnecting starts a new episode`() {
        val episodes = DropEpisodes()
        assertTrue(episodes.markReported("AA"))
        episodes.forget("AA")
        assertFalse(episodes.isReported("AA"))
        assertTrue(episodes.markReported("AA"))
    }

    @Test
    fun `clear ends every episode`() {
        val episodes = DropEpisodes()
        episodes.markReported("AA")
        episodes.clear()
        assertTrue(episodes.markReported("AA"))
    }

    @Test
    fun `a teardown confirmed during the check does not start a second episode`() {
        // Enforcement captured peer A, a snapshot verified and cleared A's pending
        // batch, and enforcement then re-reads its captured list. The same drop
        // must not be recorded again.
        val pending = PendingDrops()
        val episodes = DropEpisodes()

        pending.publish(listOf("AA"))
        assertTrue(episodes.markReported("AA"))

        val verified = pending.current()
        assertTrue(pending.clearIfCurrent(verified))

        assertFalse(episodes.markReported("AA"))
    }
}
