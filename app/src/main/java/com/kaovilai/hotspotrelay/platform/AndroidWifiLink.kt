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
import android.util.Log

enum class SuggestionOutcome { ALREADY_REGISTERED, ADDED, DUPLICATE, REJECTED, ERROR }

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

    @Suppress("DEPRECATION")
    fun isWifiEnabled(): Boolean = wifi?.isWifiEnabled == true

    /** SSID of the Wi-Fi Android is associated with, without surrounding quotes. */
    fun connectedSsid(): String? {
        val manager = connectivity ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = capabilities.transportInfo as? WifiInfo ?: return null
        val ssid = info.ssid?.trim('"')?.trim()
        if (ssid.isNullOrBlank() || ssid == UNKNOWN_SSID) return null
        return ssid
    }

    fun hasSuggestion(ssid: String): Boolean =
        runCatching { wifi?.getNetworkSuggestions().orEmpty().any { it.ssid == ssid } }
            .getOrDefault(false)

    /**
     * Registers the hotspot as an auto-join suggestion. Android shows a
     * one-time approval dialog; once approved the system joins the hotspot by
     * itself whenever it is in range.
     */
    fun ensureSuggestion(ssid: String, passphrase: String): SuggestionOutcome {
        if (ssid.isBlank()) return SuggestionOutcome.ERROR
        val manager = wifi ?: return SuggestionOutcome.ERROR
        if (hasSuggestion(ssid)) return SuggestionOutcome.ALREADY_REGISTERED

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
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED,
            -> SuggestionOutcome.REJECTED
            else -> SuggestionOutcome.REJECTED
        }
    }

    /**
     * Requests a one-off connection to the hotspot. The request is released
     * after [holdMs] so the system falls back to its own auto-join decision
     * (the suggestion registered above).
     */
    fun requestHotspot(ssid: String, passphrase: String, holdMs: Long = 8_000) {
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

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                Log.i(TAG, "hotspot request satisfied")
            }

            override fun onUnavailable() {
                Log.i(TAG, "hotspot request unavailable (out of range or not up yet)")
            }
        }

        val accepted = runCatching { manager.requestNetwork(request, callback, REQUEST_TIMEOUT_MS) }
            .onFailure { Log.w(TAG, "requestNetwork failed", it) }
            .isSuccess
        if (!accepted) return

        mainHandler.postDelayed({
            runCatching { manager.unregisterNetworkCallback(callback) }
        }, holdMs)
    }

    companion object {
        private const val TAG = "HotspotRelay/Wifi"
        private const val UNKNOWN_SSID = "<unknown ssid>"
        private const val REQUEST_TIMEOUT_MS = 20_000
    }
}
