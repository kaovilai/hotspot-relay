package com.kaovilai.hotspotrelay.platform

import android.content.Context
import android.net.ConnectivityManager
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
 */
class AndroidWifiLink(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifi: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val connectivity: ConnectivityManager? =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hotspotRequestSatisfiedAtMs: Long = 0L

    @Suppress("DEPRECATION")
    fun isWifiEnabled(): Boolean = wifi?.isWifiEnabled == true

    fun association(): WifiAssociation {
        val manager = connectivity ?: return WifiAssociation(false, null)
        val network = manager.activeNetwork ?: return WifiAssociation(false, null)
        val capabilities = manager.getNetworkCapabilities(network) ?: return WifiAssociation(false, null)
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiAssociation(false, null)
        }
        val info = capabilities.transportInfo as? WifiInfo ?: return WifiAssociation(true, null)
        val ssid = info.ssid?.trim('"')?.trim()
        return WifiAssociation(
            associated = true,
            ssid = ssid?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID },
        )
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

        val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
        if (passphrase.isNotEmpty()) builder.setWpa2Passphrase(passphrase)
        val suggestion = runCatching { builder.build() }
            .onFailure { Log.w(TAG, "cannot build suggestion", it) }
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
     * though the network is app-scoped and not the device default.
     *
     * The request is released after [holdMs] so the system falls back to its own
     * auto-join decision (the suggestion registered above).
     */
    fun requestHotspot(ssid: String, passphrase: String, holdMs: Long = REQUEST_HOLD_MS) {
        val manager = connectivity ?: return
        if (ssid.isBlank()) return

        val specifier = runCatching {
            WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
                .build()
        }.onFailure { Log.w(TAG, "cannot build specifier", it) }
            .getOrNull() ?: return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val released = java.util.concurrent.atomic.AtomicBoolean(false)

        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun release() {
                if (released.compareAndSet(false, true)) {
                    runCatching { manager.unregisterNetworkCallback(this) }
                }
            }

            override fun onAvailable(network: android.net.Network) {
                hotspotRequestSatisfiedAtMs = SystemClock.elapsedRealtime()
                Log.i(TAG, "hotspot request satisfied")
                // Hold the association briefly so the system's own auto-join can
                // take over from the suggestion, then release the app-scoped one.
                mainHandler.postDelayed({ release() }, holdMs)
            }

            override fun onUnavailable() {
                Log.i(TAG, "hotspot request unavailable (out of range or not up yet)")
                release()
            }
        }

        val accepted = runCatching { manager.requestNetwork(request, callback) }
            .onFailure { Log.w(TAG, "requestNetwork failed", it) }
            .isSuccess
        if (!accepted) return

        // No-timeout overload is available on all supported API levels; enforce
        // the timeout ourselves so an unanswered request cannot linger.
        mainHandler.postDelayed({
            if (released.compareAndSet(false, true)) {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }, REQUEST_TIMEOUT_MS)
    }

    companion object {
        private const val TAG = "HotspotRelay/Wifi"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val REQUEST_HOLD_MS = 30_000L
        private const val REQUEST_SATISFIED_WINDOW_MS = 120_000L
    }
}
