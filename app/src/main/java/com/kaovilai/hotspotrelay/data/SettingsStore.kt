package com.kaovilai.hotspotrelay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Settings live in app-private SharedPreferences. Hotspot passwords are stored
 * in plain text inside the app sandbox, which is the usual trade-off for a
 * personal automation app; see the README.
 *
 * Targets are one JSON array; legacy single-iPhone keys are migrated on first
 * read so existing installs keep working.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: android.content.SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_TARGETS, TargetsCodec.encode(next.targets))
            .putInt(KEY_ROTATE_AFTER, next.rotateAfterAttempts)
            .putLong(KEY_TICK_INTERVAL, next.tickIntervalMs)
            .putBoolean(KEY_AUTO_START, next.autoStartOnBoot)
            .putBoolean(KEY_SERVICE_ENABLED, next.serviceEnabled)
            .apply()
        _settings.value = next
        return next
    }

    private fun read(): AppSettings {
        val hasStoredTargets = prefs.contains(KEY_TARGETS)
        val targets = if (hasStoredTargets) {
            TargetsCodec.decode(prefs.getString(KEY_TARGETS, null))
        } else {
            migrateLegacyTarget().also { migrated ->
                // Persist the migration so deleting every target later is not
                // mistaken for "never configured".
                prefs.edit().putString(KEY_TARGETS, TargetsCodec.encode(migrated)).apply()
            }
        }

        return AppSettings(
            targets = targets,
            rotateAfterAttempts = prefs
                .getInt(KEY_ROTATE_AFTER, AppSettings.DEFAULT_ROTATE_AFTER_ATTEMPTS)
                .coerceIn(
                    AppSettings.MIN_ROTATE_AFTER_ATTEMPTS,
                    AppSettings.MAX_ROTATE_AFTER_ATTEMPTS,
                ),
            tickIntervalMs = prefs.getLong(KEY_TICK_INTERVAL, DEFAULT_TICK_MS),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, true),
            serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, false),
        )
    }

    private fun migrateLegacyTarget(): List<IphoneTarget> {
        val selector = prefs.getString(KEY_LEGACY_SELECTOR, "").orEmpty()
        val ssid = prefs.getString(KEY_LEGACY_SSID, "").orEmpty()
        val password = prefs.getString(KEY_LEGACY_PASSWORD, "").orEmpty()
        val label = prefs.getString(KEY_LEGACY_LABEL, "").orEmpty()
        if (selector.isBlank() && ssid.isBlank() && password.isBlank()) return emptyList()
        return listOf(
            IphoneTarget(
                id = UUID.randomUUID().toString(),
                label = label,
                btSelector = selector,
                ssid = ssid,
                password = password,
                enabled = true,
            ),
        )
    }

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_TARGETS = "targets_json"
        private const val KEY_ROTATE_AFTER = "rotate_after_attempts"
        private const val KEY_TICK_INTERVAL = "tick_interval_ms"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_SERVICE_ENABLED = "service_enabled"

        // Removed single-iPhone keys, read once for migration only.
        private const val KEY_LEGACY_SELECTOR = "iphone_selector"
        private const val KEY_LEGACY_SSID = "hotspot_ssid"
        private const val KEY_LEGACY_PASSWORD = "hotspot_password"
        private const val KEY_LEGACY_LABEL = "iphone_label"

        private const val DEFAULT_TICK_MS = 15_000L

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
