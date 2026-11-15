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
import android.os.Build
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

    /** Currently associated Wi-Fi networks. */
    private val trackedWifi: MutableSet<Network> = ConcurrentHashMap.newKeySet()

    private data class RequestHandle(val ssid: String, val release: () -> Unit)

    /** Live hotspot requests, keyed by callback, so they can be cancelled. */
    private val activeRequests =
        ConcurrentHashMap<ConnectivityManager.NetworkCallback, RequestHandle>()

    @Volatile
    private var trackingRegistered = false

    /** True until start(); guards against a tick that races past service teardown. */
    @Volatile
    private var stopped = true

    @Volatile
    private var hotspotRequestSatisfiedAtMs: Long = 0L

    @Volatile
    private var satisfiedNetwork: Network? = null

    @Volatile
    private var satisfiedSsid: String? = null

    private val wifiRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trackedWifi.add(network)
        }

        override fun onLost(network: Network) {
            trackedWifi.remove(network)
        }
    }

    fun start() {
        stopped = false
        if (trackingRegistered) return
        val manager = connectivity ?: return
        // The flags overload that would include location info in capabilities is a
        // system API; instead the SSID comes from WifiManager.connectionInfo below.
        trackingRegistered = runCatching {
            manager.registerNetworkCallback(wifiRequest, wifiCallback)
        }.onFailure { Log.w(TAG, "cannot track Wi-Fi networks", it) }
            .isSuccess
    }

    fun stop() {
        stopped = true
        if (trackingRegistered) {
            trackingRegistered = false
            runCatching { connectivity?.unregisterNetworkCallback(wifiCallback) }
        }
        trackedWifi.clear()
        // Cancel any hotspot request still in flight; a stopped service must not
        // keep a connection (or its system prompt) alive.
        activeRequests.entries.toList().forEach { (callback, handle) ->
            activeRequests.remove(callback)
            runCatching { handle.release() }
        }
        satisfiedNetwork = null
        satisfiedSsid = null
        hotspotRequestSatisfiedAtMs = 0L
    }

    @Suppress("DEPRECATION")
    fun isWifiEnabled(): Boolean = wifi?.isWifiEnabled == true

    @Suppress("DEPRECATION")
    fun association(): WifiAssociation {
        if (trackingRegistered) {
            if (trackedWifi.isNotEmpty()) return WifiAssociation(true, currentSsid())
            // Callbacks may not have arrived yet; an empty set does not prove there
            // is no Wi-Fi. Ask synchronously before letting the engine touch radios.
            val observed = runCatching {
                val manager = connectivity ?: return@runCatching false
                manager.allNetworks.any { network ->
                    manager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
            }.getOrNull()
            return when (observed) {
                true -> WifiAssociation(true, currentSsid())
                false -> WifiAssociation(false, null)
                // Observation failed: fail safe and stay idle rather than start an
                // unnecessary Bluetooth trigger.
                null -> WifiAssociation(true, null)
            }
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
     * SSID of the current connection. `getConnectionInfo` is deprecated but still
     * the only public call that returns an unredacted SSID once location has been
     * granted; capabilities delivered without the system-only location flag do not.
     */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = runCatching {
        val info = wifi?.connectionInfo ?: return@runCatching null
        val ssid = info.ssid?.removeSurrounding("\"")
        ssid?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
    }.getOrNull()

    /**
     * True when our own targeted request for [ssid] recently came up. This covers
     * the case where the hotspot is not the device's default network (no
     * internet), so [association] alone would never report it. Bound to the SSID
     * so a request for an old setting cannot satisfy a new one.
     */
    fun isHotspotRequestSatisfied(
        ssid: String,
        nowMs: Long,
        windowMs: Long = REQUEST_SATISFIED_WINDOW_MS,
    ): Boolean {
        if (ssid.isBlank() || satisfiedSsid != ssid) return false
        val satisfiedAt = hotspotRequestSatisfiedAtMs
        return satisfiedAt != 0L && nowMs - satisfiedAt in 0..windowMs
    }

    /** `getNetworkSuggestions()` only exists from API 30. */
    fun hasSuggestion(ssid: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching { wifi?.getNetworkSuggestions().orEmpty().any { it.ssid == ssid } }
            .getOrDefault(false)
    }

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
        // Without a password this would register an OPEN network, and an open AP
        // advertising the same SSID would then be auto-joined.
        if (ssid.isBlank() || passphrase.isEmpty()) return SuggestionOutcome.ERROR
        val manager = wifi ?: return SuggestionOutcome.ERROR

        val suggestion = runCatching {
            WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
        }.onFailure { Log.w(TAG, "cannot build suggestion", it) }
            .getOrNull() ?: return SuggestionOutcome.ERROR

        if (hasSuggestion(ssid)) {
            if (!replaceExisting) return SuggestionOutcome.ALREADY_REGISTERED
            removeSuggestions(ssid)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 29 cannot enumerate suggestions; removing the same-credential
            // entry is the best available way to clear a stale duplicate.
            runCatching { manager.removeNetworkSuggestions(listOf(suggestion)) }
        }

        val status = runCatching { manager.addNetworkSuggestions(listOf(suggestion)) }
            .onFailure { Log.w(TAG, "addNetworkSuggestions threw", it) }
            .getOrNull() ?: return SuggestionOutcome.ERROR

        return when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> SuggestionOutcome.ADDED
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> SuggestionOutcome.DUPLICATE
            else -> SuggestionOutcome.REJECTED
        }
    }

    /**
     * Revokes the auto-join suggestion for an SSID that is no longer configured.
     * Only possible where suggestions can be enumerated (API 30+); on API 29 the
     * platform offers no way to look them up, so this is a no-op there.
     */
    fun forgetSuggestion(ssid: String) {
        if (ssid.isBlank()) return
        removeSuggestions(ssid)
    }

    private fun removeSuggestions(ssid: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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
    fun requestHotspot(ssid: String, passphrase: String, holdMs: Long = REQUEST_HOLD_MS): Boolean {
        val manager = connectivity ?: return false
        if (stopped) {
            Log.i(TAG, "ignoring hotspot request after stop")
            return false
        }
        if (ssid.isBlank() || passphrase.isEmpty()) {
            Log.w(TAG, "refusing hotspot request without an SSID and password")
            return false
        }

        // Last-moment guard: never pull the phone off a network it is happily on.
        val current = association()
        if (current.associated && current.ssid != null && current.ssid != ssid) {
            Log.i(TAG, "not requesting hotspot while associated with ${current.ssid}")
            return false
        }

        // Obsolete requests for other targets must not keep running.
        cancelRequests(exceptSsid = ssid)

        val specifier = runCatching {
            WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
        }.onFailure { Log.w(TAG, "cannot build specifier", it) }
            .getOrNull() ?: return false

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
                // Late delivery after stop() must not resurrect the association.
                if (!activeRequests.containsKey(this)) return
                hotspotRequestSatisfiedAtMs = SystemClock.elapsedRealtime()
                satisfiedNetwork = network
                satisfiedSsid = ssid
                Log.i(TAG, "hotspot request satisfied")
                // Hold the association briefly so the system's own auto-join can
                // take over from the suggestion, then release the app-scoped one.
                mainHandler.removeCallbacks(releaseRunnable)
                mainHandler.postDelayed(releaseRunnable, holdMs)
            }

            override fun onLost(network: Network) {
                if (network == satisfiedNetwork) {
                    satisfiedNetwork = null
                    satisfiedSsid = null
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
                mainHandler.removeCallbacks(releaseRunnable)
                activeRequests.remove(callback)
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }

        // Register before the call so an immediate callback is not treated as late.
        val directRelease = requireNotNull(release)
        activeRequests[callback] = RequestHandle(ssid, directRelease)
        val accepted = runCatching { manager.requestNetwork(request, callback) }
            .onFailure { Log.w(TAG, "requestNetwork failed", it) }
            .isSuccess
        if (!accepted) {
            activeRequests.remove(callback)
            return false
        }

        // The no-timeout overload exists on all supported API levels; enforce the
        // timeout ourselves. onAvailable reschedules this to the shorter hold.
        mainHandler.postDelayed(releaseRunnable, REQUEST_TIMEOUT_MS)
        return true
    }

    /**
     * Cancels every live hotspot request. Used when the engine stops trying to
     * reach the hotspot, so a pending app-scoped request cannot connect (or keep
     * a system prompt up) while the app reports idle.
     */
    fun cancelHotspotRequests() {
        cancelRequests(exceptSsid = null)
        satisfiedNetwork = null
        satisfiedSsid = null
        hotspotRequestSatisfiedAtMs = 0L
    }

    private fun cancelRequests(exceptSsid: String?) {
        activeRequests.entries.toList().forEach { (callback, handle) ->
            if (exceptSsid == null || handle.ssid != exceptSsid) {
                activeRequests.remove(callback)
                runCatching { handle.release() }
            }
        }
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
