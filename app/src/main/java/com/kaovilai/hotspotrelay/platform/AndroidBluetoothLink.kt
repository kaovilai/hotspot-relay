package com.kaovilai.hotspotrelay.platform

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kaovilai.hotspotrelay.core.LinkStatus
import java.util.concurrent.ConcurrentHashMap

data class BluetoothSnapshot(
    val enabled: Boolean,
    /** True when the configured iPhone is in the bonded device list. */
    val bonded: Boolean,
    val link: LinkStatus,
)

enum class PairingModeResult {
    /** HID app registered; make the phone discoverable now and pair from iOS. */
    READY,

    /** The HID proxy is still binding; try again in a moment. */
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
 * A2DP and headset connects are also attempted as harmless extras for devices
 * that accept them.
 *
 * Side effects documented in the README: while the HID app is registered, the
 * phone's own HID *host* service is disabled (Bluetooth mice/keyboards do not
 * work), and the iPhone shows a connected keyboard. Both go away as soon as the
 * hotspot is joined, because the service calls [disconnectTrigger].
 */
class AndroidBluetoothLink(private val context: Context) {

    private val manager: BluetoothManager? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val profileIds = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var hidRegistered = false

    @Volatile
    private var hidLink: LinkStatus = LinkStatus.DISCONNECTED

    @Volatile
    private var hidTargetAddress: String? = null

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            hidRegistered = registered
            Log.i(TAG, "HID app registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (device.address != hidTargetAddress) return
            hidLink = when (state) {
                BluetoothProfile.STATE_CONNECTED -> LinkStatus.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> LinkStatus.CONNECTING
                else -> LinkStatus.DISCONNECTED
            }
            Log.i(TAG, "HID link state=$state")
        }
    }

    private val proxyListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
            if (profile == BluetoothProfile.HID_DEVICE && proxy is BluetoothHidDevice) {
                hidDevice = proxy
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                hidRegistered = false
            }
        }
    }

    fun start() {
        if (!hasConnectPermission()) return
        val adapter = adapter ?: return
        if (!adapter.isEnabled) return
        (profileIds + BluetoothProfile.HID_DEVICE).forEach { id ->
            runCatching { adapter.getProfileProxy(context.applicationContext, proxyListener, id) }
                .onFailure { Log.w(TAG, "profile proxy $id unavailable", it) }
        }
    }

    fun stop() {
        unregisterHid()
        val adapter = adapter ?: return
        proxies.forEach { (id, proxy) ->
            runCatching { adapter.closeProfileProxy(id, proxy) }
        }
        proxies.clear()
        hidDevice = null
    }

    fun snapshot(selector: String): BluetoothSnapshot {
        val adapter = adapter ?: return BluetoothSnapshot(false, false, LinkStatus.DISCONNECTED)
        if (!adapter.isEnabled) return BluetoothSnapshot(false, false, LinkStatus.DISCONNECTED)
        val device = findDevice(selector) ?: return BluetoothSnapshot(true, false, LinkStatus.DISCONNECTED)
        val link = when {
            hidTargetAddress == device.address && hidLink != LinkStatus.DISCONNECTED -> hidLink
            else -> profileState(device)
        }
        return BluetoothSnapshot(enabled = true, bonded = true, link = link)
    }

    /**
     * Asks the platform to bring up a link so the iPhone shortcut runs.
     * Returns true when the HID connect request was accepted.
     */
    fun requestConnection(selector: String): Boolean {
        if (!hasConnectPermission()) return false
        val device = findDevice(selector) ?: return false
        hidTargetAddress = device.address

        val hid = hidDevice
        if (hid == null) {
            Log.i(TAG, "HID device profile unavailable; enable Bluetooth and retry")
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

    /**
     * First-time pairing helper. iOS only treats the phone as a keyboard (HID
     * host link) if the HID SDP record is advertised while the iPhone pairs, so
     * register the HID app and let the caller make the phone discoverable.
     */
    fun enablePairingMode(): PairingModeResult {
        if (!hasConnectPermission()) return PairingModeResult.UNAVAILABLE
        val adapter = adapter ?: return PairingModeResult.UNAVAILABLE
        if (!adapter.isEnabled) return PairingModeResult.UNAVAILABLE
        start()
        val hid = hidDevice ?: return PairingModeResult.NOT_READY
        if (!hidRegistered) registerHid(hid)
        return PairingModeResult.READY
    }

    /**
     * Drops the trigger link once the hotspot is joined: unregisters the HID app
     * (which frees the phone's HID host again) and disconnects known links.
     */
    fun disconnectTrigger() {
        val device = hidTargetAddress ?: return
        val adapter = adapter
        val deviceRef = runCatching { adapter?.getRemoteDevice(device) }.getOrNull()
        if (deviceRef != null && hasConnectPermission()) {
            runCatching { hidDevice?.disconnect(deviceRef) }
                .onFailure { Log.w(TAG, "HID disconnect failed", it) }
        }
        unregisterHid()
        hidLink = LinkStatus.DISCONNECTED
    }

    private fun registerHid(hid: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Hotspot Relay",
            "Turns on the iPhone hotspot trigger",
            "Hotspot Relay",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_DESCRIPTOR,
        )
        val executor = ContextCompat.getMainExecutor(context.applicationContext)
        runCatching { hid.registerApp(sdp, null, null, executor, hidCallback) }
            .onFailure { Log.w(TAG, "HID registerApp failed", it) }
    }

    private fun unregisterHid() {
        val hid = hidDevice ?: return
        if (!hidRegistered) return
        runCatching { hid.unregisterApp() }
            .onFailure { Log.w(TAG, "HID unregisterApp failed", it) }
        hidRegistered = false
    }

    private fun profileState(device: BluetoothDevice): LinkStatus {
        if (!hasConnectPermission()) return LinkStatus.DISCONNECTED
        var sawConnecting = false
        for ((id, proxy) in proxies) {
            if (id == BluetoothProfile.HID_DEVICE) continue
            val state = runCatching { proxy.getConnectionState(device) }
                .onFailure { Log.w(TAG, "state query via profile $id failed", it) }
                .getOrDefault(BluetoothProfile.STATE_DISCONNECTED)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> return LinkStatus.CONNECTED
                BluetoothProfile.STATE_CONNECTING -> sawConnecting = true
            }
        }
        return if (sawConnecting) LinkStatus.CONNECTING else LinkStatus.DISCONNECTED
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

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "HotspotRelay/Bt"

        private val MAC_ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

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
