package com.kaovilai.hotspotrelay.platform

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/** A Bluetooth device the user can pick as an iPhone target. */
data class PickableDevice(val name: String?, val address: String) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

/**
 * Short-lived Bluetooth discovery for the "scan nearby" picker. Discovery needs
 * BLUETOOTH_SCAN on Android 12+ (and location on older releases); callers ask
 * for those permissions before starting.
 */
class BluetoothDiscovery(private val context: Context) {

    private val manager = context.applicationContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var receiver: BroadcastReceiver? = null

    /** Whether this helper is the one currently running discovery. */
    private var discoveryStarted = false

    fun start(
        onFound: (PickableDevice) -> Unit,
        onFinished: () -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        if (!hasDiscoveryPermissions()) {
            onUnavailable()
            return
        }
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            onUnavailable()
            return
        }
        if (receiver != null) return

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.device() ?: return
                        onFound(PickableDevice(device.name, device.address))
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        stop()
                        onFinished()
                    }
                }
            }
        }
        receiver = listener
        runCatching {
            // EXPORTED on purpose: ACTION_FOUND / ACTION_DISCOVERY_FINISHED are
            // protected broadcasts sent by the Bluetooth stack process, not by
            // this app, so RECEIVER_NOT_EXPORTED would drop every one of them and
            // the picker would spin forever. Apps cannot spoof protected actions.
            ContextCompat.registerReceiver(
                context.applicationContext,
                listener,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure {
            Log.w(TAG, "cannot register discovery receiver", it)
            receiver = null
            onUnavailable()
            return
        }

        val started = runCatching { adapter.startDiscovery() }
            .onFailure { Log.w(TAG, "startDiscovery failed", it) }
            .getOrDefault(false)
        if (!started) {
            stop()
            onUnavailable()
        } else {
            discoveryStarted = true
        }
    }

    fun stop() {
        // cancelDiscovery() is adapter-wide, so only cancel when this helper
        // actually started discovery; otherwise leave another app's scan alone.
        if (discoveryStarted) {
            discoveryStarted = false
            runCatching { adapter?.cancelDiscovery() }
        }
        receiver?.let { listener -> runCatching { context.applicationContext.unregisterReceiver(listener) } }
        receiver = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun hasDiscoveryPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // SCAN starts discovery; CONNECT is required for adapter.isEnabled and
            // device.name, so holding only one of them would throw mid-scan.
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private companion object {
        const val TAG = "HotspotRelay/Discovery"
    }
}
