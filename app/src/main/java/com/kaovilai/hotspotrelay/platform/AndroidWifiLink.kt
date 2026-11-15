package com.kaovilai.hotspotrelay.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class SuggestionOutcome { ALREADY_REGISTERED, ADDED, DUPLICATE, REJECTED, ERROR }

/**
 * Result of looking at the current Wi-Fi association.
 *
 * [ssid] is null when the phone is on no Wi-Fi, or when Android hides the name
 * because location permission was denied. [associated] distinguishes those two
 * cases so the engine can fail safe instead of fighting an unknown network.
 */
data class WifiAssociation(val associated: Boolean, val ssid: String?)

/**
 * Wi-Fi helper built on the modern (API 29+) suggestion API.
 *
 * Android no longer lets apps toggle Wi-Fi or pick a network directly. Instead
 * the app registers the hotspot SSID as a network suggestion (approved once by
 * the user) and, when it wants the connection now, issues a short
 * [ConnectivityManager.requestNetwork] for that exact SSID.
 *
 * Association is tracked with a Wi-Fi transport callback rather than
 * `activeNetwork`: when home Wi-Fi has no validated Internet, cellular becomes
 * the default route and `activeNetwork` would claim we are on no Wi-Fi.
 */
class AndroidWifiLink(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Currently associated Wi-Fi networks and their SSIDs (null when hidden). */
    private val trackedWifi = ConcurrentHashMap<Network, String?>()

    @Volatile
    private var trackingRegistered = false

    @Volatile
    private var hotspotRequestSatisfiedAtMs: Long = 0L

    @Volatile
    private var satisfiedNetwork: Network? = null

    private val wifiRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trackedWifi[network] = ssidOf(capabilities)
        }

        override fun onLost(network: Network) {
            trackedWifi.remove(network)
        }
    }

    fun start() {
        if (trackingRegistered) return
        val manager = connectivity ?: return
        trackingRegistered = runCatching {
            manager.registerNetworkCallback(wifiRequest, wifiCallback)
        }.onFailure { Log.w(TAG, "cannot track Wi-Fi networks", it) }
            .isSuccess
    }

    fun stop() {
        if (!trackingRegistered) return
        trackingRegistered = false
        runCatching { connectivity?.unregisterNetworkCallback(wifiCallback) }
        trackedWifi.clear()
    }

    @Suppress("DEPRECATION")
    fun isWifiEnabled(): Boolean = wifi?.isWifiEnabled == true

    fun association(): WifiAssociation {
        if (trackingRegistered) {
            if (trackedWifi.isEmpty()) return WifiAssociation(false, null)
            val ssid = trackedWifi.values.firstOrNull { it != null }
            return WifiAssociation(true, ssid)
        }
        // Tracking registration failed; fall back to the default route.
        val manager = connectivity ?: return WifiAssociation(false, null)
        val network = manager.activeNetwork ?: return WifiAssociation(false, null)
        val capabilities = manager.getNetworkCapabilities(network) ?: return WifiAssociation(false, null)
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiAssociation(false, null)
        }
        return WifiAssociation(true, ssidOf(capabilities))
    }

    /**
     * True when our own targeted request to the hotspot recently came up. This
     * covers the case where the hotspot is not the device's default network
     * (no internet), so [association] alone would never report it.
     */
    fun isHotspotRequestSatisfied(
        nowMs: Long,
        windowMs: Long = REQUEST_SATISFIED_WINDOW_MS,
    ): Boolean {
        val satisfiedAt = hotspotRequestSatisfiedAtMs
        return satisfiedAt != 0L && nowMs - satisfiedAt in 0..windowMs
    }

    fun hasSuggestion(ssid: String): Boolean =
        runCatching { wifi?.getNetworkSuggestions().orEmpty().any { it.ssid == ssid } }
            .getOrDefault(false)

    /**
     * Registers the hotspot as an auto-join suggestion. Android shows a
     * one-time approval dialog; once approved the system joins the hotspot by
     * itself whenever it is in range.
     *
     * An existing suggestion for the SSID is replaced so a corrected password
     * actually takes effect (suggestions are opaque once added).
     */
    fun ensureSuggestion(
        ssid: String,
        passphrase: String,
        replaceExisting: Boolean = true,
    ): SuggestionOutcome {
        if (ssid.isBlank()) return SuggestionOutcome.ERROR
        val manager = wifi ?: return SuggestionOutcome.ERROR

        if (hasSuggestion(ssid)) {
            if (!replaceExisting) return SuggestionOutcome.ALREADY_REGISTERED
            removeSuggestions(ssid)
        }

        val suggestion = runCatching {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (passphrase.isNotEmpty()) builder.setWpa2Passphrase(passphrase)
            builder.build()
        }.onFailure { Log.w(TAG, "cannot build suggestion", it) }
            .getOrNull() ?: return SuggestionOutcome.ERROR

        val status = runCatching { manager.addNetworkSuggestions(listOf(suggestion)) }
            .onFailure { Log.w(TAG, "addNetworkSuggestions threw", it) }
            .getOrNull() ?: return SuggestionOutcome.ERROR

        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> SuggestionOutcome.ADDED
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> SuggestionOutcome.DUPLICATE
            else -> SuggestionOutcome.REJECTED
        }
    }

    private fun removeSuggestions(ssid: String) {
        val existing = runCatching { wifi?.getNetworkSuggestions().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.ssid == ssid }
        if (existing.isNotEmpty()) {
            runCatching { wifi?.removeNetworkSuggestions(existing) }
                .onFailure { Log.w(TAG, "removeNetworkSuggestions failed", it) }
        }
    }

    /**
     * Requests a one-off connection to the hotspot. A success is remembered for
     * [REQUEST_SATISFIED_WINDOW_MS] so the engine can count it as "joined" even
     * though the network is app-scoped and not the device default; the flag is
     * cleared as soon as that network is lost.
     *
     * The request is released after [holdMs] so the system falls back to its own
     * auto-join decision (the suggestion registered above).
     */
    fun requestHotspot(ssid: String, passphrase: String, holdMs: Long = REQUEST_HOLD_MS) {
        val manager = connectivity ?: return
        if (ssid.isBlank()) return

        val specifier = runCatching {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (passphrase.isNotEmpty()) builder.setWpa2Passphrase(passphrase)
            builder.build()
        }.onFailure { Log.w(TAG, "cannot build specifier", it) }
            .getOrNull() ?: return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val released = AtomicBoolean(false)
        // Assigned once the callback exists; both the timeout and the hold reuse it.
        var release: (() -> Unit)? = null
        val releaseRunnable = Runnable { release?.invoke() }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                hotspotRequestSatisfiedAtMs = SystemClock.elapsedRealtime()
                satisfiedNetwork = network
                Log.i(TAG, "hotspot request satisfied")
                // Hold the association briefly so the system's own auto-join can
                // take over from the suggestion, then release the app-scoped one.
                mainHandler.removeCallbacks(releaseRunnable)
                mainHandler.postDelayed(releaseRunnable, holdMs)
            }

            override fun onLost(network: Network) {
                if (network == satisfiedNetwork) {
                    satisfiedNetwork = null
                    hotspotRequestSatisfiedAtMs = 0L
                }
            }

            override fun onUnavailable() {
                Log.i(TAG, "hotspot request unavailable (out of range or not up yet)")
                mainHandler.removeCallbacks(releaseRunnable)
                release?.invoke()
            }
        }

        release = {
            if (released.compareAndSet(false, true)) {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }

        val accepted = runCatching { manager.requestNetwork(request, callback) }
            .onFailure { Log.w(TAG, "requestNetwork failed", it) }
            .isSuccess
        if (!accepted) return

        // The no-timeout overload exists on all supported API levels; enforce the
        // timeout ourselves. onAvailable reschedules this to the shorter hold.
        mainHandler.postDelayed(releaseRunnable, REQUEST_TIMEOUT_MS)
    }

    private fun ssidOf(capabilities: NetworkCapabilities): String? {
        val info = capabilities.transportInfo as? WifiInfo ?: return null
        // Only strip the quotes Android wraps around SSIDs; spaces are significant.
        val ssid = info.ssid?.removeSurrounding("\"")
        return ssid?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    }

    companion object {
        private const val TAG = "HotspotRelay/Wifi"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val REQUEST_HOLD_MS = 30_000L
        private const val REQUEST_SATISFIED_WINDOW_MS = 120_000L
    }
}
