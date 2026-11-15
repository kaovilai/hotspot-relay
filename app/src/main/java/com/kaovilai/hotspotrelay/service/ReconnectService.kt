package com.kaovilai.hotspotrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kaovilai.hotspotrelay.MainActivity
import com.kaovilai.hotspotrelay.R
import com.kaovilai.hotspotrelay.core.BlockReason
import com.kaovilai.hotspotrelay.core.EngineAction
import com.kaovilai.hotspotrelay.core.Phase
import com.kaovilai.hotspotrelay.core.ReconnectEngine
import com.kaovilai.hotspotrelay.core.TargetRotation
import com.kaovilai.hotspotrelay.core.WorldSnapshot
import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.IphoneTarget
import com.kaovilai.hotspotrelay.data.SettingsStateJson
import com.kaovilai.hotspotrelay.data.SettingsStore
import com.kaovilai.hotspotrelay.platform.AndroidBluetoothLink
import com.kaovilai.hotspotrelay.platform.AndroidWifiLink
import com.kaovilai.hotspotrelay.receiver.ControlReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ServiceStatus(
    val running: Boolean = false,
    val phase: Phase? = null,
    val failures: Int = 0,
    val message: String = "",
    val lastTickMs: Long? = null,
    /** Which configured iPhone the loop is currently working on. */
    val targetLabel: String? = null,
)

/**
 * Foreground service that runs the reconnect loop.
 *
 * Every tick it samples the world (Bluetooth link, current SSID, radio state),
 * asks [ReconnectEngine] what to do, and performs that one action. Keeping the
 * loop this small is deliberate: each tick is cheap, and the engine owns all
 * retry/backoff decisions so they stay unit-testable.
 */
class ReconnectService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /**
     * Set before the shutdown status is published. A tick already running on
     * [Dispatchers.Default] can reach `finishTick()` after `onDestroy()`; without
     * this guard it would publish `running = true` again (and broadcast a
     * spurious "service started"), leaving queries reporting a stopped service
     * as running.
     */
    @Volatile
    private var destroyed = false

    /** Serializes [publish] with the destroyed flag so shutdown stays terminal. */
    private val statusLock = Any()

    private lateinit var settingsStore: SettingsStore
    private lateinit var bluetooth: AndroidBluetoothLink
    private lateinit var wifi: AndroidWifiLink
    private val engine = ReconnectEngine()
    private val rotation = TargetRotation()
    private var lastPhase: Phase? = null
    private var lastTargetId: String? = null
    /** Attempts issued to the current target; drives rotation. */
    private var attemptsOnTarget = 0
    private var lastNotificationMessage: String? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore.get(this)
        bluetooth = AndroidBluetoothLink.get(this).also { it.start() }
        wifi = AndroidWifiLink(this).also { it.start() }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settingsStore.update { it.copy(serviceEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafely(buildNotification("Starting…"))) {
            Log.e(TAG, "cannot become a foreground service; stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        settingsStore.update { it.copy(serviceEnabled = true) }
        publish(ServiceStatus(running = true, message = "Starting…"))

        if (loop?.isActive != true) {
            engine.reset()
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(statusLock) { destroyed = true }
        loop?.cancel()
        scope.cancel()
        wifi.stop()
        bluetooth.stop()
        publish(ServiceStatus(running = false, message = "Stopped"))
        super.onDestroy()
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val settings = settingsStore.settings.value
            runCatching { tick(settings) }
                .onFailure { Log.e(TAG, "tick failed", it) }
            delay(settings.tickIntervalMs.coerceIn(MIN_TICK_MS, MAX_TICK_MS))
        }
    }

    private fun tick(settings: AppSettings) {
        // Monotonic clock: wall-clock jumps must not stretch or skip the backoff.
        val nowMs = SystemClock.elapsedRealtime()
        val association = wifi.association()
        val targets = settings.activeTargets

        // Settings may have changed (entity disabled/removed): drop requests that
        // no longer belong to an active target instead of letting them run to
        // their timeout and keep the phone attached to a retired hotspot.
        wifi.retainOnlyHotspotRequests(targets.map { it.ssid }.toSet())

        if (targets.isEmpty()) {
            finishTick(
                phase = Phase.BLOCKED,
                failures = 0,
                message = blockMessage(BlockReason.NOT_CONFIGURED),
                target = null,
                nowMs = nowMs,
            )
            return
        }

        // Already on one of the configured hotspots? Then that one is active, and
        // nothing should rotate away from it.
        val joined = targets.firstOrNull {
            sameSsid(association.ssid, it.ssid) || wifi.isHotspotRequestSatisfied(it.ssid, nowMs)
        }
        if (joined != null) {
            rotation.select(joined)
            engine.reset()
            finishTick(
                phase = Phase.CONNECTED,
                failures = 0,
                message = "On ${joined.displayName}",
                target = joined,
                nowMs = nowMs,
            )
            lastTargetId = joined.id
            attemptsOnTarget = 0
            return
        }

        var target = rotation.current(targets) ?: targets.first()
        // A target that disappeared or was disabled is replaced by the rotation;
        // that replacement must not inherit the previous phone's retry deadline.
        if (lastTargetId != target.id) {
            engine.reset()
            attemptsOnTarget = 0
        }

        var action: EngineAction = EngineAction.Idle
        var rotations = 0
        while (true) {
            val bt = bluetooth.snapshot(target.btSelector)
            val snapshot = WorldSnapshot(
                nowMs = nowMs,
                configured = true,
                targetSsid = target.ssid,
                bluetoothEnabled = bt.enabled,
                wifiEnabled = wifi.isWifiEnabled(),
                iphoneBonded = bt.bonded,
                iphoneLink = bt.link,
                connectedSsid = association.ssid,
                wifiAssociated = association.associated,
                hotspotRequestSatisfied = wifi.isHotspotRequestSatisfied(target.ssid, nowMs),
            )
            action = engine.onTick(snapshot)

            val notPaired = action is EngineAction.Blocked &&
                action.reason == BlockReason.IPHONE_NOT_PAIRED
            val attemptDue = action == EngineAction.ConnectBluetooth ||
                action == EngineAction.ConnectHotspot
            // The engine's failure count is not a per-target attempt counter (it
            // resets whenever the link changes), so count the requests we issued.
            // A Bluetooth attempt is the start of a cycle and may rotate at N; a
            // hotspot request gets one extra slot so the handoff that follows a
            // successful trigger is never abandoned. Flapping links keep adding to
            // the counter, so a phone that connects but never yields a hotspot
            // still hands over eventually.
            val neededAttempts = if (action == EngineAction.ConnectBluetooth) {
                settings.rotateAfterAttempts
            } else {
                settings.rotateAfterAttempts + 1
            }
            val exhausted = attemptDue && attemptsOnTarget >= neededAttempts

            if ((notPaired || exhausted) && rotations < targets.lastIndex) {
                rotation.advance(targets)
                engine.reset()
                attemptsOnTarget = 0
                rotations++
                target = rotation.current(targets) ?: target
                continue
            }
            break
        }

        val state = engine.state()
        if (state.phase == Phase.CONNECTED) {
            // Made it: the next outage gets a fresh rotation budget.
            attemptsOnTarget = 0
        }
        lastTargetId = target.id
        val message = when (action) {
            EngineAction.ConnectBluetooth -> {
                // Only a request the platform accepted counts as an attempt; a
                // request rejected while the profile is still binding must not
                // consume the target's budget.
                val accepted = withWakeLock { bluetooth.requestConnection(target.btSelector) }
                if (accepted) attemptsOnTarget++
                "Asking ${target.displayName} to connect over Bluetooth"
            }

            EngineAction.ConnectHotspot -> {
                val accepted = wifi.requestHotspot(target.ssid, target.password)
                if (accepted) attemptsOnTarget++
                "Joining ${target.displayName}…"
            }

            is EngineAction.Blocked -> blockMessage(action.reason)
            EngineAction.Idle -> idleMessage(state.phase, association.ssid, target)
        }

        finishTick(state.phase, state.failures, message, target, nowMs)
    }

    private fun finishTick(
        phase: Phase,
        failures: Int,
        message: String,
        target: IphoneTarget?,
        nowMs: Long,
    ) {
        publish(
            ServiceStatus(
                running = true,
                phase = phase,
                failures = failures,
                message = message,
                lastTickMs = nowMs,
                targetLabel = target?.displayName,
            ),
        )
        if (message != lastNotificationMessage) {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(message))
            lastNotificationMessage = message
        }

        // Windows in which there is nothing left to trigger. Any transition into
        // one of them drops the HID link, and every terminal phase except CONNECTED
        // also cancels live hotspot requests. Comparing with lastPhase (not "entering
        // the set") matters: CONNECTED -> BLOCKED must still clean up.
        if (phase in RELEASE_TRIGGER_PHASES && lastPhase != phase) {
            bluetooth.disconnectTrigger()
            // On CONNECTED the request's 30s hold is what hands the association to
            // the system's auto-join, so it is left alone there.
            if (phase != Phase.CONNECTED) wifi.cancelHotspotRequests()
        }
        lastPhase = phase
    }

    private fun sameSsid(a: String?, b: String): Boolean =
        a != null && b.isNotBlank() && a == b

    private fun idleMessage(phase: Phase, connectedSsid: String?, target: IphoneTarget): String =
        when (phase) {
            Phase.CONNECTED -> "On ${target.displayName}"
            Phase.ON_OTHER_WIFI -> "On ${connectedSsid ?: "another network"} — staying idle"
            Phase.WAITING_FOR_IPHONE -> "Waiting for the next Bluetooth attempt to ${target.displayName}"
            Phase.WAITING_FOR_HOTSPOT -> "Bluetooth up — waiting for ${target.displayName}'s hotspot"
            Phase.CONNECTING_HOTSPOT -> "Waiting for the next hotspot attempt"
            Phase.BLOCKED -> "Idle"
        }

    private fun blockMessage(reason: BlockReason): String = when (reason) {
        BlockReason.NOT_CONFIGURED -> "Add the iPhone, hotspot SSID and password in the app"
        BlockReason.WIFI_OFF -> "Wi-Fi is off"
        BlockReason.BLUETOOTH_OFF -> "Bluetooth is off"
        BlockReason.IPHONE_NOT_PAIRED -> "iPhone not paired — pair it in system Bluetooth settings"
    }

    /**
     * Keeps the CPU awake across the short binder call that starts a Bluetooth
     * attempt. The connection itself completes asynchronously; the next tick
     * observes the result.
     */
    private fun <T> withWakeLock(block: () -> T): T {
        val power = getSystemService(PowerManager::class.java) ?: return block()
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:attempt")
        runCatching { lock.acquire(WAKE_LOCK_MS) }
        return try {
            block()
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean =
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { Log.e(TAG, "startForeground failed", it) }
            .isSuccess

    private fun buildNotification(message: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ReconnectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reconnect status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows what the hotspot reconnect loop is doing"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun publish(status: ServiceStatus) {
        synchronized(statusLock) {
            // A tick that outlived shutdown must not resurrect `running = true`;
            // the stop status published from onDestroy() is terminal. The lock
            // makes the check-and-write atomic against onDestroy().
            if (destroyed && status.running) return
            val wasRunning = _status.value.running
            _status.value = status
            if (wasRunning != status.running) broadcastRunningState(status.running)
        }
    }

    /**
     * External automation (Tasker, adb) listens for STATE_CHANGED. Emit it when
     * the loop actually starts or stops — including a foreground start that is
     * refused right after the command returned — so a START/STOP command never
     * leaves listeners on the pre-transition `running` value. Routine ticks do
     * not broadcast.
     */
    private fun broadcastRunningState(running: Boolean) {
        runCatching {
            val state = SettingsStateJson.encode(SettingsStore.get(this).settings.value, running)
            sendBroadcast(
                Intent(ControlReceiver.ACTION_STATE_CHANGED)
                    .putExtra("ok", true)
                    .putExtra("message", if (running) "service started" else "service stopped")
                    .putExtra("state", state),
            )
        }.onFailure { Log.w(TAG, "cannot broadcast running state", it) }
    }

    companion object {
        private const val TAG = "HotspotRelay/Service"
        private const val CHANNEL_ID = "reconnect"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_MS = 30_000L
        private const val MIN_TICK_MS = 5_000L
        private const val MAX_TICK_MS = 300_000L

        const val ACTION_STOP = "com.kaovilai.hotspotrelay.STOP"

        private val _status = MutableStateFlow(ServiceStatus())
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReconnectService::class.java),
            )
        }

        fun stop(context: Context) {
            SettingsStore.get(context).update { it.copy(serviceEnabled = false) }
            context.stopService(Intent(context, ReconnectService::class.java))
        }

        private val RELEASE_TRIGGER_PHASES = setOf(
            Phase.CONNECTED,
            Phase.ON_OTHER_WIFI,
            Phase.BLOCKED,
        )
    }
}
