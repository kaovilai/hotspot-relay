package com.kaovilai.hotspotrelay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    /** Bonded iPhone name, or its Bluetooth MAC address. */
    val iphoneSelector: String = "",
    /** SSID of the iPhone Personal Hotspot. */
    val hotspotSsid: String = "",
    /** WPA2 password of the iPhone Personal Hotspot. */
    val hotspotPassword: String = "",
    /** How often the service re-evaluates the world. */
    val tickIntervalMs: Long = 15_000,
    /** Restart the service after a reboot when it was running before. */
    val autoStartOnBoot: Boolean = true,
    /** Persisted so the boot receiver knows whether the service should come back. */
    val serviceEnabled: Boolean = false,
) {
    val isConfigured: Boolean
        get() = iphoneSelector.isNotBlank() && hotspotSsid.isNotBlank()
}

/**
 * Settings live in app-private SharedPreferences. The hotspot password is
 * stored in plain text inside the app sandbox, which is the usual trade-off for
 * a personal automation app; see the README.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_IPHONE_SELECTOR, next.iphoneSelector)
            .putString(KEY_HOTSPOT_SSID, next.hotspotSsid)
            .putString(KEY_HOTSPOT_PASSWORD, next.hotspotPassword)
            .putLong(KEY_TICK_INTERVAL, next.tickIntervalMs)
            .putBoolean(KEY_AUTO_START, next.autoStartOnBoot)
            .putBoolean(KEY_SERVICE_ENABLED, next.serviceEnabled)
            .apply()
        _settings.value = next
        return next
    }

    private fun read(): AppSettings = AppSettings(
        iphoneSelector = prefs.getString(KEY_IPHONE_SELECTOR, "").orEmpty(),
        hotspotSsid = prefs.getString(KEY_HOTSPOT_SSID, "").orEmpty(),
        hotspotPassword = prefs.getString(KEY_HOTSPOT_PASSWORD, "").orEmpty(),
        tickIntervalMs = prefs.getLong(KEY_TICK_INTERVAL, DEFAULT_TICK_MS),
        autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, true),
        serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false),
    )

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_IPHONE_SELECTOR = "iphone_selector"
        private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
        private const val KEY_HOTSPOT_PASSWORD = "hotspot_password"
        private const val KEY_TICK_INTERVAL = "tick_interval_ms"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val DEFAULT_TICK_MS = 15_000L

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
