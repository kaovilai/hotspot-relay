package com.kaovilai.hotspotrelay.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PolicyDropLedgerTest {

    @Test
    fun `nothing is delivered before a drop is recorded`() {
        assertFalse(PolicyDropLedger().consume())
    }

    @Test
    fun `a recorded drop is delivered once and only once`() {
        val ledger = PolicyDropLedger()
        ledger.record()
        assertTrue(ledger.consume())
        assertFalse(ledger.consume())
        assertFalse(ledger.consume())
    }

    @Test
    fun `drops recorded between consumes are delivered once each batch`() {
        val ledger = PolicyDropLedger()
        ledger.record()
        ledger.record()
        assertTrue(ledger.consume())
        assertFalse(ledger.consume())
        ledger.record()
        assertTrue(ledger.consume())
        assertFalse(ledger.consume())
    }

    @Test
    fun `consuming before a concurrent record does not lose the drop`() {
        val ledger = PolicyDropLedger()
        // Snapshot observes the pending teardown before enforcement records it:
        // the delivery happens on the next snapshot instead of being lost.
        assertFalse(ledger.consume())
        ledger.record()
        assertTrue(ledger.consume())
        assertFalse(ledger.consume())
    }

    @Test
    fun `no drop is lost or left undelivered under contention`() {
        val ledger = PolicyDropLedger()
        val producers = 4
        val perProducer = 500
        val start = CountDownLatch(1)
        val producersDone = CountDownLatch(producers)
        val deliveries = AtomicInteger(0)
        val consumer = Thread {
            start.await()
            while (true) {
                while (ledger.consume()) deliveries.incrementAndGet()
                // Records can land during this wait; stop only after the
                // producers are done, then drain whatever that wait missed.
                if (producersDone.await(1, TimeUnit.MILLISECONDS)) break
            }
            while (ledger.consume()) deliveries.incrementAndGet()
        }
        consumer.start()
        val workers = List(producers) {
            Thread {
                start.await()
                repeat(perProducer) { ledger.record() }
                producersDone.countDown()
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join() }
        consumer.join()
        assertTrue(deliveries.get() >= 1)
        // Whatever the interleaving, nothing recorded is left undelivered.
        assertFalse(ledger.consume())
    }
}
