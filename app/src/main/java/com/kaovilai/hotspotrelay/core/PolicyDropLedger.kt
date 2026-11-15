package com.kaovilai.hotspotrelay.core

import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot delivery of "enforcement dropped the trigger link".
 *
 * Enforcement (main thread) and snapshots (service thread) interleave: a
 * snapshot can observe the teardown of a drop before, during or after the drop
 * is recorded. A monotonic sequence gives every recorded drop exactly one
 * delivery — never lost because a snapshot came first, and never repeated
 * because a snapshot already reported the pending teardown.
 */
class PolicyDropLedger {
    private val recorded = AtomicLong(0)
    private val delivered = AtomicLong(0)

    /** Records one policy drop of the trigger link. Safe on any thread. */
    fun record() {
        recorded.incrementAndGet()
    }

    /**
     * @return true exactly once per [record] call, no matter how [record] and
     * [consume] interleave.
     */
    fun consume(): Boolean = advance()

    /** Marks everything recorded so far as delivered (the loop is stopping). */
    fun clear() {
        advance()
    }

    private fun advance(): Boolean {
        while (true) {
            val seen = delivered.get()
            val current = recorded.get()
            if (current <= seen) return false
            if (delivered.compareAndSet(seen, current)) return true
        }
    }
}
