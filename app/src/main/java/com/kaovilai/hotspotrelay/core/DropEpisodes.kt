package com.kaovilai.hotspotrelay.core

/**
 * Peers whose current drop episode has already been reported to the engine.
 *
 * Kept apart from [PendingDrops] on purpose: the pending batch is cleared as
 * soon as the stack confirms the teardown, but a drop must still not be reported
 * twice when enforcement re-reads its captured connection list right after that
 * clear. A peer is forgotten when it connects again, so the next drop is a new
 * episode.
 */
class DropEpisodes {
    private val peers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Marks [address] as reported.
     *
     * @return true exactly once per episode, so only the first call records a drop.
     */
    fun markReported(address: String): Boolean = peers.add(address)

    fun forget(address: String) {
        peers.remove(address)
    }

    fun isReported(address: String): Boolean = address in peers

    fun clear() {
        peers.clear()
    }
}
