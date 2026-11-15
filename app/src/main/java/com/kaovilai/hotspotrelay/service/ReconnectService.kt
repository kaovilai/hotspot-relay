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
import com.kaovilai.hotspotrelay.core.WorldSnapshot
import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.SettingsStore
import com.kaovilai.hotspotrelay.platform.AndroidBluetoothLink
import com.kaovilai.hotspotrelay.platform.AndroidWifiLink
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

    private lateinit var settingsStore: SettingsStore
    private lateinit var bluetooth: AndroidBluetoothLink
    private lateinit var wifi: AndroidWifiLink
    private val engine = ReconnectEngine()
    private var lastPhase: Phase? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore.get(this)
        bluetooth = AndroidBluetoothLink(this).also { it.start() }
        wifi = AndroidWifiLink(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settingsStore.update { it.copy(serviceEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely(buildNotification("Starting…"))
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
        loop?.cancel()
        scope.cancel()
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
        val bt = bluetooth.snapshot(settings.iphoneSelector)
        val snapshot = WorldSnapshot(
            nowMs = System.currentTimeMillis(),
            configured = settings.isConfigured,
            targetSsid = settings.hotspotSsid,
            bluetoothEnabled = bt.enabled,
            wifiEnabled = wifi.isWifiEnabled(),
            iphoneBonded = bt.bonded,
            iphoneLink = bt.link,
            connectedSsid = wifi.connectedSsid(),
        )

        val action = engine.onTick(snapshot)
        val state = engine.state()

        val message = when (action) {
            EngineAction.ConnectBluetooth -> {
                withWakeLock { bluetooth.requestConnection(settings.iphoneSelector) }
                "Asking iPhone to connect over Bluetooth"
            }

            EngineAction.ConnectHotspot -> {
                wifi.requestHotspot(settings.hotspotSsid, settings.hotspotPassword)
                "Joining ${settings.hotspotSsid}…"
            }

            is EngineAction.Blocked -> blockMessage(action.reason)
            EngineAction.Idle -> idleMessage(state.phase, snapshot.connectedSsid, settings)
        }

        val status = ServiceStatus(
            running = true,
            phase = state.phase,
            failures = state.failures,
            message = message,
            lastTickMs = snapshot.nowMs,
        )
        publish(status)
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(message))

        // The trigger link has done its job once the hotspot is joined. Dropping
        // it restores the phone's own HID host and un-attaches the iPhone keyboard.
        if (state.phase == Phase.CONNECTED && lastPhase != Phase.CONNECTED) {
            bluetooth.disconnectTrigger()
        }
        lastPhase = state.phase
    }

    private fun idleMessage(phase: Phase, connectedSsid: String?, settings: AppSettings): String =
        when (phase) {
            Phase.CONNECTED -> "On ${settings.hotspotSsid}"
            Phase.ON_OTHER_WIFI -> "On ${connectedSsid ?: "another network"} — staying idle"
            Phase.WAITING_FOR_IPHONE -> "Waiting for the next Bluetooth attempt"
            Phase.WAITING_FOR_HOTSPOT -> "Bluetooth up — waiting for the hotspot"
            Phase.CONNECTING_HOTSPOT -> "Waiting for the next hotspot attempt"
            Phase.BLOCKED -> "Idle"
        }

    private fun blockMessage(reason: BlockReason): String = when (reason) {
        BlockReason.NOT_CONFIGURED -> "Add the iPhone name and hotspot SSID in the app"
        BlockReason.WIFI_OFF -> "Wi-Fi is off"
        BlockReason.BLUETOOTH_OFF -> "Bluetooth is off"
        BlockReason.IPHONE_NOT_PAIRED -> "iPhone not paired — pair it in system Bluetooth settings"
    }

    private fun withWakeLock(block: () -> Unit) {
        val power = getSystemService(PowerManager::class.java) ?: return block()
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:attempt")
        runCatching { lock.acquire(WAKE_LOCK_MS) }
        try {
            block()
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { Log.e(TAG, "startForeground failed", it) }
    }

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
        _status.value = status
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
    }
}
