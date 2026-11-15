package com.kaovilai.hotspotrelay.core

/**
 * Peers enforcement asked to disconnect whose teardown the stack has not
 * confirmed yet.
 *
 * Enforcement publishes a batch (main thread) while a snapshot (service thread)
 * verifies the published batch is gone and then clears it. Clearing must only
 * happen for the exact batch that was verified: a blind clear erases a batch
 * published in between, after which enforcement records the still-connected peer
 * a second time and the engine's retry deadline moves on every tick.
 */
class PendingDrops {
    @Volatile
    private var peers: List<String> = emptyList()

    fun publish(addresses: List<String>) {
        synchronized(this) { peers = addresses }
    }

    /** The batch as published; hand the result to [clearIfCurrent] after checking. */
    fun current(): List<String> = peers

    fun contains(address: String): Boolean = address in peers

    /**
     * Marks [verified] as settled only if it is still the published batch.
     *
     * @return true when this call cleared it, false when a newer batch replaced it.
     */
    fun clearIfCurrent(verified: List<String>): Boolean {
        synchronized(this) {
            if (peers !== verified) return false
            peers = emptyList()
            return true
        }
    }
}
