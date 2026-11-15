package com.kaovilai.hotspotrelay.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.kaovilai.hotspotrelay.core.HidConnectionPolicy
import com.kaovilai.hotspotrelay.core.LinkStatus
import com.kaovilai.hotspotrelay.core.PolicyDropLedger

data class BluetoothSnapshot(
    val enabled: Boolean,
    /** True when the configured iPhone is in the bonded device list. */
    val bonded: Boolean,
    val link: LinkStatus,
    /**
     * True when a link was dropped by our own HID window since the last snapshot,
     * rather than by the iPhone going away. Consumed atomically with the snapshot
     * so the caller cannot miss or double-read it.
     */
    val droppedByPolicy: Boolean = false,
)

enum class PairingModeResult {
    /** HID app registered; make the phone discoverable now and pair from iOS. */
    READY,

    /** The HID proxy is still binding or registering; try again in a moment. */
    NOT_READY,

    /** BLUETOOTH_CONNECT is missing or Bluetooth is off. */
    UNAVAILABLE,
}

/**
 * Bluetooth helper for the iPhone trigger.
 *
 * The hard part is that Android has no general "connect to that paired phone"
 * API, and stock Android ships with the HFP-client profile disabled
 * (`profile_supported_hfpclient=false` in AOSP). The one public, stock-supported
 * way to initiate a classic connection *to* an iPhone is to register this app
 * as a Bluetooth HID device ([BluetoothHidDevice], API 28+) and connect to the
 * iPhone while it acts as the HID host. iOS accepts Android as a keyboard and
 * fires its Bluetooth automation when the link comes up.
 *
 * HID registration is per-app global, so exactly one instance exists; the
 * settings screen and the service share it.
 *
 * Side effects documented in the README: while the HID app is registered, the
 * phone's own HID *host* service is disabled (Bluetooth mice/keyboards do not
 * work), and the iPhone shows a connected keyboard. Both go away as soon as the
 * hotspot is joined, because the service calls [disconnectTrigger].
 */
class AndroidBluetoothLink private constructor(private val context: Context) {

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var hidRegistered = false

    @Volatile
    private var hidLink: LinkStatus = LinkStatus.DISCONNECTED

    @Volatile
    private var hidTargetAddress: String? = null

    /** True until start(); guards against a tick that races past service teardown. */
    @Volatile
    private var stopped = true

    /**
     * A HID link is only allowed until this monotonic time. iOS hides its
     * on-screen keyboard while a hardware keyboard is connected and may
     * re-connect the remembered one by itself, so an explicit reconnect attempt
     * opens a short window and everything outside it is dropped. The bond stays,
     * so the next trigger does not need re-pairing.
     */
    @Volatile
    private var allowedUntilMs = 0L

    @Volatile
    private var pairingMode = false

    private var bondReceiver: BroadcastReceiver? = null

    @Volatile
    private var enforcement: Runnable? = null

    /**
     * Serializes [enforcement] and its wake lock. The check runs on the main
     * looper, but [disconnectTrigger] can cancel it from the service thread; a
     * repost or teardown that interleaved with a cancel would survive it as an
     * untracked loop holding no lock.
     */
    private val enforcementLock = Any()

    /** When the pending enforcement check is due (guarded by [enforcementLock]). */
    private var enforcementDueAtMs = 0L

    /** HID peers the stack currently reports as connected (main-thread callbacks). */
    private val connectedAddresses = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Keeps the CPU awake only while an enforcement check is pending. */
    private var enforcementWakeLock: PowerManager.WakeLock? = null

    /** Keeps the CPU awake for the bounded pairing session (bond wait + drop). */
    private var pairingWakeLock: PowerManager.WakeLock? = null

    /** Invalidates deferred pairing callbacks from an earlier session. */
    @Volatile
    private var pairingGeneration = 0

    /**
     * Set when enforcement dropped a link on purpose (not an iPhone-side drop).
     * [PolicyDropLedger] delivers each drop once, whatever order the snapshot
     * and the enforcement batch interleave in.
     */
    private val policyDrops = PolicyDropLedger()

    /**
     * Addresses enforcement asked to disconnect. Until the stack reports them
     * gone, snapshots must say DISCONNECTED: otherwise a tick can read the stale
     * CONNECTED state, count it as a fresh trigger and clear the backoff.
     */
    @Volatile
    private var pendingDisconnectAddresses: List<String> = emptyList()

    /** Device currently probed by "Check iPhone pairing" (not the service target). */
    @Volatile
    private var probeAddress: String? = null

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            hidRegistered = registered
            Log.i(TAG, "HID app registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (device.address == hidTargetAddress || device.address == probeAddress) {
                hidLink = when (state) {
                    BluetoothProfile.STATE_CONNECTED -> LinkStatus.CONNECTED
                    BluetoothProfile.STATE_CONNECTING -> LinkStatus.CONNECTING
                    else -> LinkStatus.DISCONNECTED
                }
            }
            Log.i(TAG, "HID link state=$state for ${device.address}")
            // Enforce the window even for a device we did not target yet: during
            // pairing there is no configured selector, and the iPhone's own
            // reconnect of a remembered keyboard must never linger either.
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedAddresses.add(device.address)
                    scheduleEnforcement()
                }

                BluetoothProfile.STATE_DISCONNECTED -> connectedAddresses.remove(device.address)
            }
        }
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                hidDevice = proxy
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                hidRegistered = false
            }
        }
    }

    fun start() {
        stopped = false
        if (!hasConnectPermission()) return
        val adapter = adapter ?: return
        if (!adapter.isEnabled) return
        if (hidDevice != null) return
        runCatching { adapter.getProfileProxy(context.applicationContext, proxyListener, BluetoothProfile.HID_DEVICE) }
            .onFailure { Log.w(TAG, "HID profile proxy unavailable", it) }
    }

    fun stop() {
        stopped = true
        probeAddress = null
        endPairingMode()
        allowedUntilMs = 0
        connectedAddresses.clear()
        pendingDisconnectAddresses = emptyList()
        policyDrops.clear()
        unregisterHid()
        val hid = hidDevice
        if (hid != null) {
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        }
        hidDevice = null
        hidRegistered = false
        hidLink = LinkStatus.DISCONNECTED
    }

    fun snapshot(selector: String): BluetoothSnapshot {
        if (!hasConnectPermission()) {
            return BluetoothSnapshot(false, false, LinkStatus.DISCONNECTED)
        }
        val adapter = adapter ?: return BluetoothSnapshot(false, false, LinkStatus.DISCONNECTED)
        if (!adapter.isEnabled) {
            // Radio cycled: cached HID state is meaningless now.
            hidLink = LinkStatus.DISCONNECTED
            hidTargetAddress = null
            pendingDisconnectAddresses = emptyList()
            return BluetoothSnapshot(false, false, LinkStatus.DISCONNECTED)
        }

        if (pendingDisconnectAddresses.isNotEmpty()) {
            val stillConnected = pendingDisconnectAddresses.any { address ->
                val peer = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                    ?: return@any false
                val hid = hidDevice ?: return@any false
                runCatching { hid.getConnectionState(peer) }
                    .getOrDefault(BluetoothProfile.STATE_DISCONNECTED) ==
                    BluetoothProfile.STATE_CONNECTED
            }
            if (!stillConnected) pendingDisconnectAddresses = emptyList()
        }

        val device = findDevice(selector)
            ?: return BluetoothSnapshot(true, false, LinkStatus.DISCONNECTED)
        val pendingForDevice = device.address in pendingDisconnectAddresses

        if (hidTargetAddress != device.address) {
            // The configured iPhone changed: drop the old link and its registration
            // instead of leaving a stale HID link attached to the previous device.
            if (hidTargetAddress != null) disconnectTrigger()
            hidTargetAddress = device.address
            hidLink = LinkStatus.DISCONNECTED
        }
        if (hidDevice == null) start()

        // The callback cache can miss events (adapter cycle, process timing);
        // always confirm against the live profile state when possible.
        val hid = hidDevice
        val link = if (hid != null) {
            when (runCatching { hid.getConnectionState(device) }
                .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)) {
                BluetoothProfile.STATE_CONNECTED -> LinkStatus.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> LinkStatus.CONNECTING
                else -> LinkStatus.DISCONNECTED
            }
        } else {
            hidLink
        }
        hidLink = link
        // While a policy drop is still settling, report DISCONNECTED regardless of
        // the live state so the engine never mistakes it for a fresh link.
        val reported = if (pendingForDevice) LinkStatus.DISCONNECTED else link
        // Each drop is delivered once: enforcement records it before publishing the
        // pending set, so a snapshot that reports the pending teardown always sees
        // (or already saw) its drop. Repeating the flag on every later tick would
        // push the engine's retry deadline out while the teardown settles.
        val justDropped = policyDrops.consume()
        return BluetoothSnapshot(
            enabled = true,
            bonded = true,
            link = reported,
            droppedByPolicy = justDropped,
        )
    }

    /**
     * Asks the platform to bring up a link so the iPhone shortcut runs.
     * Returns true when the HID connect request was accepted.
     */
    fun requestConnection(selector: String): Boolean {
        if (stopped) {
            Log.i(TAG, "ignoring Bluetooth request after stop")
            return false
        }
        if (!hasConnectPermission()) return false
        val adapter = adapter ?: return false
        if (!adapter.isEnabled) return false

        // Every attempt re-opens the window; the enforcement loop drops the link
        // once the last attempt's window has passed.
        allowedUntilMs = HidConnectionPolicy.allowedUntil(
            SystemClock.elapsedRealtime(),
            HidConnectionPolicy.TRIGGER_WINDOW_MS,
        )

        val device = findDevice(selector) ?: return false
        hidTargetAddress = device.address

        if (hidDevice == null) start()
        val hid = hidDevice
        if (hid == null) {
            Log.i(TAG, "HID device profile not bound yet; retrying next tick")
            return false
        }
        if (!hidRegistered) registerHid(hid)

        val accepted = runCatching { hid.connect(device) }
            .onFailure { Log.w(TAG, "HID connect failed", it) }
            .getOrDefault(false)
        if (!accepted) {
            Log.i(TAG, "HID connect not accepted yet (registering); retry on the next tick")
        }
        return accepted
    }

    /** Bonded devices, for the "pick a paired iPhone" picker. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PickableDevice> {
        if (!hasConnectPermission()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        return devices
            .map { PickableDevice(it.name, it.address) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * First-time pairing helper. iOS only treats the phone as a keyboard (HID
     * host link) if the HID SDP record is advertised while the iPhone pairs, so
     * register the HID app and let the caller make the phone discoverable.
     */
    fun enablePairingMode(): PairingModeResult {
        if (!hasConnectPermission()) return PairingModeResult.UNAVAILABLE
        val adapter = adapter ?: return PairingModeResult.UNAVAILABLE
        if (!adapter.isEnabled) return PairingModeResult.UNAVAILABLE

        pairingMode = true
        pairingGeneration++
        acquirePairingWakeLock()
        allowedUntilMs = HidConnectionPolicy.allowedUntil(
            SystemClock.elapsedRealtime(),
            HidConnectionPolicy.PAIRING_WINDOW_MS,
        )
        watchBondState()
        // Every pairing session expires, including the NOT_READY tap the user
        // never retries; otherwise the bond receiver and pairing state would leak.
        schedulePairingTimeout()

        start()
        val hid = hidDevice ?: return PairingModeResult.NOT_READY
        if (!hidRegistered) {
            registerHid(hid)
            // Registration lands in onAppStatusChanged; the caller retries.
            return PairingModeResult.NOT_READY
        }
        return PairingModeResult.READY
    }

    /**
     * Drops the trigger link once the hotspot is joined: unregisters the HID app
     * (which frees the phone's HID host again) and disconnects the link.
     */
    fun disconnectTrigger() {
        allowedUntilMs = 0
        cancelEnforcement()
        val address = hidTargetAddress
        val hid = hidDevice
        if (hid != null && address != null && hasConnectPermission()) {
            val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            if (device != null) {
                runCatching { hid.disconnect(device) }
                    .onFailure { Log.w(TAG, "HID disconnect failed", it) }
            }
        }
        unregisterHid()
        hidLink = LinkStatus.DISCONNECTED
    }

    /**
     * Opens one short window and reports whether iOS really accepts a keyboard
     * link. Diagnoses an iPhone that was paired before the app's keyboard-mode
     * pairing: iOS never added the HID service then, so `connect()` can be
     * accepted but never come up.
     */
    fun probePairing(
        selector: String,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
        onResult: (Boolean) -> Unit,
    ) {
        if (!hasConnectPermission()) {
            onResult(false)
            return
        }
        start()
        val adapter = adapter
        val device = findDevice(selector)
        if (adapter == null || !adapter.isEnabled || device == null) {
            onResult(false)
            return
        }
        // Do not touch hidTargetAddress: the service may be targeting another
        // iPhone, and a target change would tear this probe (and its link) down.
        probeAddress = device.address
        allowedUntilMs = HidConnectionPolicy.allowedUntil(
            SystemClock.elapsedRealtime(),
            HidConnectionPolicy.TRIGGER_WINDOW_MS,
        )
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var connectRequested = false
        val poll = object : Runnable {
            override fun run() {
                // The HID proxy binds asynchronously; keep polling for it instead
                // of reporting a false negative on the first tap.
                val hid = hidDevice
                if (hid == null) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        onResult(false)
                        return
                    }
                    mainHandler.postDelayed(this, PROBE_POLL_MS)
                    return
                }
                if (!hidRegistered) registerHid(hid)
                if (!connectRequested) {
                    // connect() returns false when the command was not sent (e.g.
                    // registration still settling); retry within the budget
                    // instead of failing the probe on one rejected call.
                    connectRequested = runCatching { hid.connect(device) }
                        .onFailure { Log.w(TAG, "probe HID connect failed", it) }
                        .getOrDefault(false)
                }

                val state = runCatching { hid.getConnectionState(device) }
                    .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
                when {
                    state == BluetoothProfile.STATE_CONNECTED -> {
                        // Drop before reporting: the UI re-enables the button on
                        // the result, and a second probe must not be killed by a
                        // delayed cleanup from this one.
                        probeAddress = null
                        dropLink(device)
                        onResult(true)
                    }

                    SystemClock.elapsedRealtime() >= deadline -> {
                        probeAddress = null
                        dropLink(device)
                        onResult(false)
                    }

                    else -> mainHandler.postDelayed(this, PROBE_POLL_MS)
                }
            }
        }
        mainHandler.postDelayed(poll, PROBE_POLL_MS)
    }

    /**
     * A peer may keep its link only while the global window is open *and* it is
     * the peer that window was opened for: the service's current target, a
     * device being probed, or any peer during explicit pairing. A remembered
     * keyboard reconnecting on its own must not inherit another iPhone's window.
     */
    private fun isPeerAllowed(address: String, nowMs: Long): Boolean {
        if (!HidConnectionPolicy.isAllowed(nowMs, allowedUntilMs)) return false
        if (pairingMode) return true
        return address == hidTargetAddress || address == probeAddress
    }

    private fun isLiveConnected(address: String): Boolean {
        if (!hasConnectPermission()) return false
        val hid = hidDevice ?: return false
        val peer = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return false
        return runCatching { hid.getConnectionState(peer) }
            .getOrDefault(BluetoothProfile.STATE_DISCONNECTED) ==
            BluetoothProfile.STATE_CONNECTED
    }

    /**
     * Re-checks the allowed window while any HID peer is connected. Every attempt
     * extends the window; once it has passed (plus a short grace) every connected
     * peer is dropped and the HID app unregistered, so no keyboard can stay
     * attached. Keyed by the connected set, never by one device: a delayed
     * disconnect from a previous target must not disarm the check for the next.
     */
    private fun scheduleEnforcement() {
        synchronized(enforcementLock) {
            acquireEnforcementWakeLock()
            val existing = enforcement
            if (existing != null) {
                // A peer that just connected must be checked within the grace, not
                // after the pending check's (possibly far) deadline. Only ever move
                // the check earlier; an already-due check is not postponed.
                val remaining = (enforcementDueAtMs - SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
                mainHandler.removeCallbacks(existing)
                postEnforcement(existing, minOf(remaining, HidConnectionPolicy.GRACE_MS))
                return
            }
            val check = object : Runnable {
                override fun run() {
                    val now = SystemClock.elapsedRealtime()
                    val addresses = connectedAddresses.toList()
                    if (addresses.isEmpty()) {
                        finishEnforcement(this)
                        unregisterHid()
                        return
                    }
                    val denied = addresses.filterNot { isPeerAllowed(it, now) }
                    if (denied.isNotEmpty()) {
                        // Record before publishing the pending set: a snapshot that
                        // sees the pending teardown must also see its policy drop.
                        // A peer that is still pending on a later check (teardown not
                        // confirmed) is the same drop, so it is not reported again.
                        // Only a drop of the service's own target counts; a stray
                        // remembered peer must not make the engine keep its backoff.
                        if (hidTargetAddress in denied &&
                            hidTargetAddress !in pendingDisconnectAddresses
                        ) {
                            policyDrops.record()
                        }
                        pendingDisconnectAddresses = denied
                        denied.forEach { address ->
                            val device = runCatching { adapter?.getRemoteDevice(address) }
                                .getOrNull() ?: return@forEach
                            dropLink(device)
                        }
                        // disconnect()/unregisterApp() only report command submission.
                        // Peers still connected stay tracked so the check keeps running
                        // instead of leaving an attached keyboard with no enforcement.
                        val stillConnected = denied.filter { isLiveConnected(it) }
                        connectedAddresses.clear()
                        (addresses - denied).forEach { connectedAddresses.add(it) }
                        stillConnected.forEach { connectedAddresses.add(it) }
                        if (stillConnected.isNotEmpty()) {
                            repostEnforcement(this, HidConnectionPolicy.MIN_CHECK_INTERVAL_MS)
                            return
                        }
                    }
                    if (connectedAddresses.isEmpty()) {
                        finishEnforcement(this)
                    } else {
                        repostEnforcement(
                            this,
                            HidConnectionPolicy.nextCheckDelayMs(now, allowedUntilMs),
                        )
                    }
                }
            }
            enforcement = check
            postEnforcement(check, HidConnectionPolicy.GRACE_MS)
        }
    }

    private fun cancelEnforcement() {
        synchronized(enforcementLock) {
            enforcement?.let { mainHandler.removeCallbacks(it) }
            enforcement = null
            releaseEnforcementWakeLock()
        }
    }

    /**
     * Re-arms [check] only while it is still the tracked runnable. A cancel from
     * the service thread may land while the check is mid-run (it is not queued
     * then, so `removeCallbacks` cannot reach it); posting under the same lock
     * means the cancel either wins and nothing is reposted, or removes the post.
     */
    private fun repostEnforcement(check: Runnable, delayMs: Long) {
        synchronized(enforcementLock) {
            if (enforcement !== check) return
            postEnforcement(check, delayMs)
        }
    }

    /** Posts [check] and remembers when it is due. Caller holds the lock. */
    private fun postEnforcement(check: Runnable, delayMs: Long) {
        enforcementDueAtMs = SystemClock.elapsedRealtime() + delayMs
        mainHandler.postDelayed(check, delayMs)
    }

    /** Ends [check] only if a cancel/new session has not already replaced it. */
    private fun finishEnforcement(check: Runnable) {
        synchronized(enforcementLock) {
            if (enforcement !== check) return
            enforcement = null
            releaseEnforcementWakeLock()
        }
    }

    /**
     * Handler delays do not advance while the device is suspended, so keep the CPU
     * awake for the (bounded) lifetime of a pending window check. The lock covers
     * the longest window plus grace; it is released as soon as the check ends.
     */
    private fun acquireEnforcementWakeLock() {
        if (enforcementWakeLock != null) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val lock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotspotRelay:hid-window")
        }.getOrNull() ?: return
        // No timeout: window refreshes can extend the check past any fixed budget.
        // The lock is released by cancelEnforcement()/the drop path, and the OS
        // releases it if the process dies.
        runCatching { lock.acquire() }
        enforcementWakeLock = lock
    }

    private fun acquirePairingWakeLock() {
        if (pairingWakeLock != null) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        val lock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotspotRelay:pairing")
        }.getOrNull() ?: return
        // Session-bounded: released by endPairingMode() (bond, timeout or stop);
        // a timed lock could expire before a second pairing tap extends the window.
        runCatching { lock.acquire() }
        pairingWakeLock = lock
    }

    private fun releasePairingWakeLock() {
        pairingWakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        pairingWakeLock = null
    }

    private fun releaseEnforcementWakeLock() {
        enforcementWakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        enforcementWakeLock = null
    }

    private fun dropLink(device: BluetoothDevice) {
        val hid = hidDevice
        if (hid != null) {
            runCatching { hid.disconnect(device) }
                .onFailure { Log.w(TAG, "HID disconnect failed", it) }
        }
        hidLink = LinkStatus.DISCONNECTED
        unregisterHid()
    }

    private var pairingTimeout: Runnable? = null

    private fun schedulePairingTimeout() {
        pairingTimeout?.let(mainHandler::removeCallbacks)
        val generation = pairingGeneration
        val timeout = Runnable {
            if (generation == pairingGeneration) endPairingMode()
        }
        pairingTimeout = timeout
        mainHandler.postDelayed(timeout, PAIRING_REGISTRATION_TTL_MS)
    }

    /**
     * Leaves pairing mode without leaving a keyboard attached. The bond itself is
     * kept — iOS remembers it, so the next trigger connects without re-pairing.
     */
    private fun endPairingMode(device: BluetoothDevice? = null) {
        pairingMode = false
        // Any deferred bond/timeout callback from this session is now stale.
        pairingGeneration++
        allowedUntilMs = 0
        pairingTimeout?.let(mainHandler::removeCallbacks)
        pairingTimeout = null
        cancelEnforcement()
        releasePairingWakeLock()
        stopBondWatch()
        if (device != null) dropLink(device) else unregisterHid()
    }

    /**
     * Ends pairing mode as soon as the iPhone completes the bond, instead of
     * waiting out the discoverability window with a keyboard attached.
     */
    private fun watchBondState() {
        if (bondReceiver != null) return
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!pairingMode) return
                val state = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE,
                )
                if (state != BluetoothDevice.BOND_BONDED) return
                val device = intent.deviceExtra() ?: return
                // A new pairing session may start during this delay; only the
                // session that saw the bond may end pairing mode.
                val generation = pairingGeneration
                mainHandler.postDelayed(
                    { if (generation == pairingGeneration) endPairingMode(device) },
                    HidConnectionPolicy.PAIRING_DROP_DELAY_MS,
                )
            }
        }
        val registered = runCatching {
            // BOND_STATE_CHANGED is sent by the Bluetooth process, so the receiver
            // has to be exported; the action is protected, apps cannot spoof it.
            ContextCompat.registerReceiver(
                context.applicationContext,
                listener,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "cannot watch bond state", it) }
            .isSuccess
        if (registered) bondReceiver = listener
    }

    private fun stopBondWatch() {
        bondReceiver?.let {
            runCatching { context.applicationContext.unregisterReceiver(it) }
        }
        bondReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.deviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun registerHid(hid: BluetoothHidDevice) {
        if (stopped) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Hotspot Relay",
            "Turns on the iPhone hotspot trigger",
            "Hotspot Relay",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_DESCRIPTOR,
        )
        val executor = ContextCompat.getMainExecutor(context.applicationContext)
        val started = runCatching { hid.registerApp(sdp, null, null, executor, hidCallback) }
            .onFailure { Log.w(TAG, "HID registerApp failed", it) }
            .getOrDefault(false)
        Log.i(TAG, "HID registerApp requested=$started")
    }

    private fun unregisterHid() {
        val hid = hidDevice ?: return
        // Attempt even when the cached flag says "not registered": the flag can
        // miss an event, and a live registration must never be left behind.
        runCatching { hid.unregisterApp() }
            .onFailure { Log.w(TAG, "HID unregisterApp failed", it) }
        hidRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun findDevice(selector: String): BluetoothDevice? {
        if (selector.isBlank() || !hasConnectPermission()) return null
        val adapter = adapter ?: return null
        val devices = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        val wantedAddress = selector.trim().takeIf { MAC_ADDRESS.matches(it) }
        return devices.firstOrNull { device ->
            if (wantedAddress != null) {
                device.address.equals(wantedAddress, ignoreCase = true)
            } else {
                device.name?.equals(selector.trim(), ignoreCase = true) == true
            }
        }
    }

    /**
     * Whether the Bluetooth permission needed to list bonded devices is held.
     * Callers use this to explain an empty picker instead of guessing.
     */
    fun canReadPairedDevices(): Boolean = hasConnectPermission()

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT only exists from Android 12; on API 29-30 the legacy
        // BLUETOOTH permission is what grants access to paired devices.
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_CONNECT
        } else {
            Manifest.permission.BLUETOOTH
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "HotspotRelay/Bt"

        @Volatile
        private var instance: AndroidBluetoothLink? = null

        /** One instance for the whole process: HID registration is per-app global. */
        fun get(context: Context): AndroidBluetoothLink =
            instance ?: synchronized(this) {
                instance ?: AndroidBluetoothLink(context.applicationContext).also { instance = it }
            }

        private val MAC_ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        /** How long a pairing-mode registration may keep the HID host borrowed. */
        private const val PAIRING_REGISTRATION_TTL_MS = 120_000L

        /** How long "Check iPhone pairing" waits for a HID link. */
        private const val PROBE_TIMEOUT_MS = 8_000L

        private const val PROBE_POLL_MS = 500L

        /** Standard boot-keyboard HID report descriptor; the iPhone just needs a valid HID device. */
        private val KEYBOARD_DESCRIPTOR: ByteArray = hexBytes(
            "05 01 09 06 A1 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 " +
                "95 01 75 08 81 01 95 05 75 01 05 08 19 01 29 05 91 02 95 01 " +
                "75 03 91 01 95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00 C0",
        )

        private fun hexBytes(hex: String): ByteArray =
            hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }
}
