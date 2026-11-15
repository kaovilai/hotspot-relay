package com.kaovilai.hotspotrelay.core

/** State of the Bluetooth link to the iPhone, as far as Android can tell. */
enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Everything the engine needs to know about the world at one instant.
 *
 * Kept free of Android types so the decision logic can be unit tested on the JVM.
 */
data class WorldSnapshot(
    val nowMs: Long,
    /** True when an iPhone selector and hotspot SSID have been configured. */
    val configured: Boolean,
    /** SSID of the iPhone Personal Hotspot. */
    val targetSsid: String,
    val bluetoothEnabled: Boolean,
    val wifiEnabled: Boolean,
    /** True when the configured iPhone is present in the bonded device list. */
    val iphoneBonded: Boolean,
    val iphoneLink: LinkStatus,
    /** SSID Android is currently associated with, or null when on no Wi-Fi. */
    val connectedSsid: String?,
)

sealed interface EngineAction {
    /** Nothing to do this tick. */
    data object Idle : EngineAction

    /** Ask the platform to bring up a Bluetooth link to the iPhone. */
    data object ConnectBluetooth : EngineAction

    /** Ask the platform to join the iPhone Personal Hotspot. */
    data object ConnectHotspot : EngineAction

    /** A precondition cannot be met; [reason] says which. */
    data class Blocked(val reason: BlockReason) : EngineAction
}

enum class BlockReason { NOT_CONFIGURED, WIFI_OFF, BLUETOOTH_OFF, IPHONE_NOT_PAIRED }

enum class Phase {
    /** Associated with the iPhone hotspot. */
    CONNECTED,

    /** On some other Wi-Fi network; the automation deliberately stays out of the way. */
    ON_OTHER_WIFI,

    /** No Wi-Fi; working on the Bluetooth link that triggers the iPhone shortcut. */
    WAITING_FOR_IPHONE,

    /** Bluetooth is up; giving the iPhone a moment to raise the hotspot. */
    WAITING_FOR_HOTSPOT,

    /** Asking Android to join the hotspot. */
    CONNECTING_HOTSPOT,

    /** A radio or configuration precondition is missing. */
    BLOCKED,
}

data class RetryPolicy(
    /**
     * Delay after Bluetooth connects before asking for the hotspot, so the
     * iPhone shortcut has time to switch Personal Hotspot on.
     */
    val hotspotGraceMs: Long = 8_000,
    /** Backoff schedule, in order; the last entry repeats. */
    val retryDelaysMs: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000),
) {
    fun delayFor(failures: Int): Long {
        require(retryDelaysMs.isNotEmpty()) { "retryDelaysMs must not be empty" }
        val index = (failures - 1).coerceIn(0, retryDelaysMs.lastIndex)
        return retryDelaysMs[index]
    }
}

data class EngineState(
    val phase: Phase = Phase.BLOCKED,
    val failures: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val blockedReason: BlockReason? = null,
    val connectedSinceMs: Long? = null,
)

/**
 * Decides, one tick at a time, what the app should do to get onto the iPhone
 * hotspot:
 *
 *  1. Already on the hotspot -> nothing.
 *  2. On another Wi-Fi network -> nothing (do not fight the user's home Wi-Fi).
 *  3. No Wi-Fi at all -> make sure Bluetooth is connected to the iPhone, which
 *     is the trigger for the iPhone shortcut that turns Personal Hotspot on.
 *  4. Bluetooth up -> after a short grace period, ask Android to join the
 *     hotspot, retrying with exponential backoff.
 *
 * All timing comes from [WorldSnapshot.nowMs], so tests drive it deterministically.
 */
class ReconnectEngine(private val policy: RetryPolicy = RetryPolicy()) {

    private var phase: Phase = Phase.BLOCKED
    private var failures = 0
    private var nextAttemptAtMs: Long? = null
    private var blockedReason: BlockReason? = null
    private var connectedSinceMs: Long? = null
    private var lastLink: LinkStatus? = null
    private var linkConnectedAtMs: Long? = null

    fun state(): EngineState = EngineState(
        phase = phase,
        failures = failures,
        nextAttemptAtMs = nextAttemptAtMs,
        blockedReason = blockedReason,
        connectedSinceMs = connectedSinceMs,
    )

    /** Forget retry bookkeeping; used when the user restarts the service. */
    fun reset() {
        phase = Phase.BLOCKED
        failures = 0
        nextAttemptAtMs = null
        blockedReason = null
        connectedSinceMs = null
        lastLink = null
        linkConnectedAtMs = null
    }

    fun onTick(snapshot: WorldSnapshot): EngineAction {
        trackLink(snapshot)

        if (snapshot.configured && sameSsid(snapshot.connectedSsid, snapshot.targetSsid)) {
            phase = Phase.CONNECTED
            blockedReason = null
            failures = 0
            nextAttemptAtMs = null
            if (connectedSinceMs == null) connectedSinceMs = snapshot.nowMs
            return EngineAction.Idle
        }
        connectedSinceMs = null

        if (snapshot.connectedSsid != null) {
            phase = Phase.ON_OTHER_WIFI
            blockedReason = null
            failures = 0
            nextAttemptAtMs = null
            return EngineAction.Idle
        }

        if (!snapshot.configured) return block(BlockReason.NOT_CONFIGURED)
        if (!snapshot.wifiEnabled) return block(BlockReason.WIFI_OFF)
        if (!snapshot.bluetoothEnabled) return block(BlockReason.BLUETOOTH_OFF)
        if (!snapshot.iphoneBonded) return block(BlockReason.IPHONE_NOT_PAIRED)

        blockedReason = null

        when (snapshot.iphoneLink) {
            LinkStatus.CONNECTING -> {
                phase = Phase.WAITING_FOR_IPHONE
                return EngineAction.Idle
            }

            LinkStatus.DISCONNECTED -> {
                phase = Phase.WAITING_FOR_IPHONE
                if (waitingForRetry(snapshot.nowMs)) return EngineAction.Idle
                failures++
                nextAttemptAtMs = snapshot.nowMs + policy.delayFor(failures)
                return EngineAction.ConnectBluetooth
            }

            LinkStatus.CONNECTED -> Unit
        }

        val connectedAt = linkConnectedAtMs ?: snapshot.nowMs.also { linkConnectedAtMs = it }
        if (snapshot.nowMs - connectedAt < policy.hotspotGraceMs) {
            phase = Phase.WAITING_FOR_HOTSPOT
            return EngineAction.Idle
        }

        phase = Phase.CONNECTING_HOTSPOT
        if (waitingForRetry(snapshot.nowMs)) return EngineAction.Idle
        failures++
        nextAttemptAtMs = snapshot.nowMs + policy.delayFor(failures)
        return EngineAction.ConnectHotspot
    }

    private fun trackLink(snapshot: WorldSnapshot) {
        val previous = lastLink
        lastLink = snapshot.iphoneLink
        if (snapshot.iphoneLink == LinkStatus.CONNECTED && previous != LinkStatus.CONNECTED) {
            // Fresh link: the iPhone shortcut has not run yet, and Bluetooth
            // retries should start from the shortest delay again.
            linkConnectedAtMs = snapshot.nowMs
            failures = 0
            nextAttemptAtMs = null
        } else if (snapshot.iphoneLink == LinkStatus.DISCONNECTED) {
            linkConnectedAtMs = null
        }
    }

    private fun waitingForRetry(nowMs: Long): Boolean {
        val next = nextAttemptAtMs ?: return false
        return nowMs < next
    }

    private fun block(reason: BlockReason): EngineAction.Blocked {
        phase = Phase.BLOCKED
        blockedReason = reason
        return EngineAction.Blocked(reason)
    }

    private fun sameSsid(a: String?, b: String): Boolean =
        a != null && b.isNotBlank() && a.trim().equals(b.trim(), ignoreCase = true)
}
