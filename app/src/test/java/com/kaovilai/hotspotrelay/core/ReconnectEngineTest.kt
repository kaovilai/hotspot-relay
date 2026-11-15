package com.kaovilai.hotspotrelay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectEngineTest {

    private val ssid = "Passawit's iPhone"

    private fun snapshot(
        nowMs: Long,
        configured: Boolean = true,
        bluetoothEnabled: Boolean = true,
        wifiEnabled: Boolean = true,
        iphoneBonded: Boolean = true,
        iphoneLink: LinkStatus = LinkStatus.DISCONNECTED,
        connectedSsid: String? = null,
        targetSsid: String = ssid,
        wifiAssociated: Boolean = connectedSsid != null,
        hotspotRequestSatisfied: Boolean = false,
        linkDroppedByPolicy: Boolean = false,
    ) = WorldSnapshot(
        nowMs = nowMs,
        configured = configured,
        targetSsid = targetSsid,
        bluetoothEnabled = bluetoothEnabled,
        wifiEnabled = wifiEnabled,
        iphoneBonded = iphoneBonded,
        iphoneLink = iphoneLink,
        connectedSsid = connectedSsid,
        wifiAssociated = wifiAssociated,
        hotspotRequestSatisfied = hotspotRequestSatisfied,
        linkDroppedByPolicy = linkDroppedByPolicy,
    )

    @Test
    fun `policy drop waits out the bluetooth backoff before re-arming`() {
        val engine = ReconnectEngine()

        engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTED))
        engine.onTick(
            snapshot(nowMs = 10_000, iphoneLink = LinkStatus.CONNECTED, linkDroppedByPolicy = true),
        )

        assertEquals(EngineAction.Idle, engine.onTick(snapshot(nowMs = 10_001)))
        assertEquals(25_000L, engine.state().nextAttemptAtMs ?: -1L)
    }

    @Test
    fun `re-arming after a policy drop keeps the accumulated backoff`() {
        val engine = ReconnectEngine()

        engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTED))
        // Grace period over: the hotspot is requested and its retry budget grows.
        assertEquals(
            EngineAction.ConnectHotspot,
            engine.onTick(snapshot(nowMs = 9_000, iphoneLink = LinkStatus.CONNECTED)),
        )
        engine.onTick(
            snapshot(nowMs = 10_000, iphoneLink = LinkStatus.CONNECTED, linkDroppedByPolicy = true),
        )
        // Backoff paid, link re-armed: this is a retry, not a fresh trigger.
        engine.onTick(snapshot(nowMs = 26_000, iphoneLink = LinkStatus.CONNECTED))

        assertTrue(engine.state().failures >= 1)
    }

    @Test
    fun `already on the hotspot does nothing`() {
        val engine = ReconnectEngine()

        val action = engine.onTick(snapshot(nowMs = 0, connectedSsid = ssid))

        assertEquals(EngineAction.Idle, action)
        assertEquals(Phase.CONNECTED, engine.state().phase)
        assertEquals(0L, engine.state().connectedSinceMs)
    }

    @Test
    fun `ssid matching is exact because ssids are case sensitive`() {
        val engine = ReconnectEngine()

        val action = engine.onTick(
            snapshot(nowMs = 0, connectedSsid = "PASSAWIT'S IPHONE"),
        )

        assertEquals(EngineAction.Idle, action)
        assertEquals(Phase.ON_OTHER_WIFI, engine.state().phase)
    }

    @Test
    fun `on another wifi network stays out of the way`() {
        val engine = ReconnectEngine()

        val action = engine.onTick(
            snapshot(
                nowMs = 0,
                connectedSsid = "Home-5G",
                iphoneLink = LinkStatus.DISCONNECTED,
            ),
        )

        assertEquals(EngineAction.Idle, action)
        assertEquals(Phase.ON_OTHER_WIFI, engine.state().phase)
        assertEquals(0, engine.state().failures)
    }

    @Test
    fun `missing configuration blocks`() {
        val action = ReconnectEngine().onTick(snapshot(nowMs = 0, configured = false))

        assertEquals(EngineAction.Blocked(BlockReason.NOT_CONFIGURED), action)
    }

    @Test
    fun `wifi off blocks`() {
        val action = ReconnectEngine().onTick(snapshot(nowMs = 0, wifiEnabled = false))

        assertEquals(EngineAction.Blocked(BlockReason.WIFI_OFF), action)
    }

    @Test
    fun `bluetooth off blocks`() {
        val action = ReconnectEngine().onTick(snapshot(nowMs = 0, bluetoothEnabled = false))

        assertEquals(EngineAction.Blocked(BlockReason.BLUETOOTH_OFF), action)
    }

    @Test
    fun `unpaired iphone blocks instead of connecting`() {
        val action = ReconnectEngine().onTick(snapshot(nowMs = 0, iphoneBonded = false))

        assertEquals(EngineAction.Blocked(BlockReason.IPHONE_NOT_PAIRED), action)
    }

    @Test
    fun `disconnected iphone gets a bluetooth connect attempt immediately`() {
        val engine = ReconnectEngine()

        assertEquals(
            EngineAction.ConnectBluetooth,
            engine.onTick(snapshot(nowMs = 0)),
        )
        assertEquals(Phase.WAITING_FOR_IPHONE, engine.state().phase)
        assertEquals(1, engine.state().failures)
        assertEquals(15_000L, engine.state().nextAttemptAtMs)
    }

    @Test
    fun `bluetooth retries respect the backoff schedule`() {
        val engine = ReconnectEngine()

        engine.onTick(snapshot(nowMs = 0)) // attempt 1 -> retry at 15s
        assertEquals(EngineAction.Idle, engine.onTick(snapshot(nowMs = 14_999)))
        assertEquals(EngineAction.ConnectBluetooth, engine.onTick(snapshot(nowMs = 15_000)))
        assertEquals(45_000L, engine.state().nextAttemptAtMs) // +30s
        assertEquals(EngineAction.Idle, engine.onTick(snapshot(nowMs = 44_999)))
        assertEquals(EngineAction.ConnectBluetooth, engine.onTick(snapshot(nowMs = 45_000)))
        assertEquals(105_000L, engine.state().nextAttemptAtMs) // +60s
    }

    @Test
    fun `backoff stops growing at the last entry`() {
        val engine = ReconnectEngine()
        var now = 0L
        engine.onTick(snapshot(nowMs = 0)) // attempt 1, retry in 15s
        for (delayMs in listOf(15_000L, 30_000L, 60_000L, 120_000L)) {
            now += delayMs
            assertEquals(EngineAction.ConnectBluetooth, engine.onTick(snapshot(nowMs = now)))
        }

        // Fifth failure: the schedule has run out, so the 300s cap applies.
        assertEquals(300_000L, engine.state().nextAttemptAtMs!! - now)
        now += 299_999
        assertEquals(EngineAction.Idle, engine.onTick(snapshot(nowMs = now)))
        now += 1
        assertEquals(EngineAction.ConnectBluetooth, engine.onTick(snapshot(nowMs = now)))
        assertEquals(300_000L, engine.state().nextAttemptAtMs!! - now)
    }

    @Test
    fun `bluetooth mid-connect is left alone`() {
        val action = ReconnectEngine().onTick(
            snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTING),
        )

        assertEquals(EngineAction.Idle, action)
    }

    @Test
    fun `hotspot is requested only after the grace period`() {
        val engine = ReconnectEngine(RetryPolicy(hotspotGraceMs = 8_000))
        engine.onTick(snapshot(nowMs = 0)) // connect bluetooth
        engine.onTick(snapshot(nowMs = 2_000, iphoneLink = LinkStatus.CONNECTED))

        val atConnect = engine.onTick(snapshot(nowMs = 2_000, iphoneLink = LinkStatus.CONNECTED))
        assertEquals(EngineAction.Idle, atConnect)
        assertEquals(Phase.WAITING_FOR_HOTSPOT, engine.state().phase)

        assertEquals(
            EngineAction.Idle,
            engine.onTick(snapshot(nowMs = 9_999, iphoneLink = LinkStatus.CONNECTED)),
        )
        assertEquals(
            EngineAction.ConnectHotspot,
            engine.onTick(snapshot(nowMs = 10_000, iphoneLink = LinkStatus.CONNECTED)),
        )
        assertEquals(Phase.CONNECTING_HOTSPOT, engine.state().phase)
    }

    @Test
    fun `hotspot retries back off and then reset when connected`() {
        val engine = ReconnectEngine(RetryPolicy(hotspotGraceMs = 0))
        engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTED)) // grace already over
        assertEquals(1, engine.state().failures)

        assertEquals(
            EngineAction.Idle,
            engine.onTick(snapshot(nowMs = 5_000, iphoneLink = LinkStatus.CONNECTED)),
        )
        assertEquals(
            EngineAction.ConnectHotspot,
            engine.onTick(snapshot(nowMs = 15_000, iphoneLink = LinkStatus.CONNECTED)),
        )

        val connected = engine.onTick(
            snapshot(nowMs = 20_000, iphoneLink = LinkStatus.CONNECTED, connectedSsid = ssid),
        )
        assertEquals(EngineAction.Idle, connected)
        assertEquals(Phase.CONNECTED, engine.state().phase)
        assertEquals(0, engine.state().failures)
        assertNull(engine.state().nextAttemptAtMs)
        assertEquals(20_000L, engine.state().connectedSinceMs)
    }

    @Test
    fun `bluetooth connect resets the hotspot backoff and grace period`() {
        val engine = ReconnectEngine(RetryPolicy(hotspotGraceMs = 8_000))
        engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTED))

        // Link drops, reconnect is attempted, then the link comes back up.
        assertEquals(
            EngineAction.ConnectBluetooth,
            engine.onTick(snapshot(nowMs = 1_000, iphoneLink = LinkStatus.DISCONNECTED)),
        )
        engine.onTick(snapshot(nowMs = 2_000, iphoneLink = LinkStatus.CONNECTED))
        assertEquals(
            EngineAction.Idle,
            engine.onTick(snapshot(nowMs = 9_000, iphoneLink = LinkStatus.CONNECTED)),
        )
        assertEquals(
            EngineAction.ConnectHotspot,
            engine.onTick(snapshot(nowMs = 10_000, iphoneLink = LinkStatus.CONNECTED)),
        )
    }

    @Test
    fun `connectedSince is set once and cleared when the link drops`() {
        val engine = ReconnectEngine()
        engine.onTick(snapshot(nowMs = 100, connectedSsid = ssid))
        engine.onTick(snapshot(nowMs = 500, connectedSsid = ssid))
        assertEquals(100L, engine.state().connectedSinceMs)

        engine.onTick(snapshot(nowMs = 900))
        assertNull(engine.state().connectedSinceMs)
    }

    @Test
    fun `blocked reasons clear once the precondition returns`() {
        val engine = ReconnectEngine()
        engine.onTick(snapshot(nowMs = 0, iphoneBonded = false))
        assertEquals(BlockReason.IPHONE_NOT_PAIRED, engine.state().blockedReason)

        engine.onTick(snapshot(nowMs = 1_000))
        assertNull(engine.state().blockedReason)
        assertTrue(engine.state().phase == Phase.WAITING_FOR_IPHONE)
    }

    @Test
    fun `reset clears retry state`() {
        val engine = ReconnectEngine()
        engine.onTick(snapshot(nowMs = 0))
        engine.reset()

        assertEquals(0, engine.state().failures)
        assertNull(engine.state().nextAttemptAtMs)
        assertEquals(EngineAction.ConnectBluetooth, engine.onTick(snapshot(nowMs = 10)))
        assertEquals(1, engine.state().failures)
    }

    @Test
    fun `blank target ssid never matches`() {
        val engine = ReconnectEngine()
        val action = engine.onTick(
            snapshot(nowMs = 0, connectedSsid = "anything", targetSsid = "   "),
        )

        assertEquals(EngineAction.Idle, action)
        assertEquals(Phase.ON_OTHER_WIFI, engine.state().phase)
    }

    @Test
    fun `association with a hidden ssid counts as another network`() {
        val engine = ReconnectEngine()

        val action = engine.onTick(
            snapshot(nowMs = 0, connectedSsid = null, wifiAssociated = true),
        )

        assertEquals(EngineAction.Idle, action)
        assertEquals(Phase.ON_OTHER_WIFI, engine.state().phase)
        assertEquals(0, engine.state().failures)
    }

    @Test
    fun `satisfied hotspot request counts as connected`() {
        val engine = ReconnectEngine()

        val action = engine.onTick(
            snapshot(nowMs = 0, hotspotRequestSatisfied = true, iphoneLink = LinkStatus.CONNECTED),
        )

        assertEquals(EngineAction.Idle, action)
        assertEquals(Phase.CONNECTED, engine.state().phase)
        assertEquals(0L, engine.state().connectedSinceMs)
    }

    @Test
    fun `link stuck in connecting is retried after the timeout`() {
        val engine = ReconnectEngine(RetryPolicy(hotspotGraceMs = 0, connectingTimeoutMs = 45_000))

        assertEquals(
            EngineAction.Idle,
            engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTING)),
        )
        assertEquals(
            EngineAction.Idle,
            engine.onTick(snapshot(nowMs = 44_999, iphoneLink = LinkStatus.CONNECTING)),
        )
        assertEquals(
            EngineAction.ConnectBluetooth,
            engine.onTick(snapshot(nowMs = 45_000, iphoneLink = LinkStatus.CONNECTING)),
        )
    }

    @Test
    fun `connecting timeout restarts after a disconnect`() {
        val engine = ReconnectEngine(RetryPolicy(hotspotGraceMs = 0, connectingTimeoutMs = 45_000))

        engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTING))
        engine.onTick(snapshot(nowMs = 10_000, iphoneLink = LinkStatus.DISCONNECTED))
        engine.onTick(snapshot(nowMs = 30_000, iphoneLink = LinkStatus.CONNECTING))

        // The second CONNECTING episode started at 30s, so it has until 75s.
        assertEquals(
            EngineAction.Idle,
            engine.onTick(snapshot(nowMs = 74_999, iphoneLink = LinkStatus.CONNECTING)),
        )
        assertEquals(
            EngineAction.ConnectBluetooth,
            engine.onTick(snapshot(nowMs = 75_000, iphoneLink = LinkStatus.CONNECTING)),
        )
    }

    @Test
    fun `hotspot backoff does not delay a new bluetooth trigger`() {
        val engine = ReconnectEngine(RetryPolicy(hotspotGraceMs = 0))

        // Hotspot attempts back off all the way to the five minute cap.
        engine.onTick(snapshot(nowMs = 0, iphoneLink = LinkStatus.CONNECTED))
        var now = 0L
        for (delayMs in listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)) {
            now += delayMs
            engine.onTick(snapshot(nowMs = now, iphoneLink = LinkStatus.CONNECTED))
        }
        assertEquals(300_000L, engine.state().nextAttemptAtMs!! - now)

        // Link drops: the Bluetooth retry budget is independent and starts fresh.
        assertEquals(
            EngineAction.ConnectBluetooth,
            engine.onTick(snapshot(nowMs = now + 1_000, iphoneLink = LinkStatus.DISCONNECTED)),
        )
    }

    @Test
    fun `blocked state does not keep stale backoff`() {
        val engine = ReconnectEngine()
        engine.onTick(snapshot(nowMs = 0)) // first Bluetooth attempt, retry at 15s
        engine.onTick(snapshot(nowMs = 1_000, wifiEnabled = false))

        assertEquals(
            EngineAction.ConnectBluetooth,
            engine.onTick(snapshot(nowMs = 2_000, wifiEnabled = true)),
        )
    }
}
