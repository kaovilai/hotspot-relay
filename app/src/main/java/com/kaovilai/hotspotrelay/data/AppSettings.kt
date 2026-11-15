package com.kaovilai.hotspotrelay.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One iPhone (one Persona Hotspot) the app may fall back to. */
data class IphoneTarget(
    val id: String,
    /** Optional friendly name shown in the UI and status notification. */
    val label: String = "",
    /** Bonded iPhone name or Bluetooth MAC. */
    val btSelector: String = "",
    /** SSID of that iPhone's Personal Hotspot. */
    val ssid: String = "",
    /** WPA2 password of that hotspot. */
    val password: String = "",
    val enabled: Boolean = true,
) {
    /** All fields needed to use it, regardless of the enable switch. */
    val isComplete: Boolean
        get() = btSelector.isNotBlank() && ssid.isNotBlank() && password.isNotBlank()

    val displayName: String
        get() = label.ifBlank { ssid.ifBlank { btSelector.ifBlank { "iPhone" } } }

    companion object {
        fun empty(): IphoneTarget = IphoneTarget(id = UUID.randomUUID().toString())
    }
}

/** JSON round-trip for the target list; kept separate so it can be unit tested. */
object TargetsCodec {

    fun encode(targets: List<IphoneTarget>): String {
        val array = JSONArray()
        targets.forEach { target ->
            array.put(
                JSONObject().apply {
                    put("id", target.id)
                    put("label", target.label)
                    put("selector", target.btSelector)
                    put("ssid", target.ssid)
                    put("password", target.password)
                    put("enabled", target.enabled)
                },
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<IphoneTarget> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    add(
                        IphoneTarget(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            label = obj.optString("label"),
                            btSelector = obj.optString("selector"),
                            ssid = obj.optString("ssid"),
                            password = obj.optString("password"),
                            enabled = obj.optBoolean("enabled", true),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

data class AppSettings(
    /** Ordered by priority; the first enabled, complete target is tried first. */
    val targets: List<IphoneTarget> = emptyList(),
    /**
     * How many failed attempts a target gets before the loop moves to the next
     * one. 1 is effectively round-robin; larger values make the order a real
     * priority list.
     */
    val rotateAfterAttempts: Int = DEFAULT_ROTATE_AFTER_ATTEMPTS,
    /** How often the service re-evaluates the world. */
    val tickIntervalMs: Long = 15_000,
    /** Restart the service after a reboot when it was running before. */
    val autoStartOnBoot: Boolean = true,
    /** Persisted so the boot receiver knows whether the service should come back. */
    val serviceEnabled: Boolean = false,
) {
    /** Targets eligible for rotation, in priority order. */
    val activeTargets: List<IphoneTarget>
        get() = targets.filter { it.enabled && it.isComplete }

    val isConfigured: Boolean
        get() = activeTargets.isNotEmpty()

    companion object {
        const val DEFAULT_ROTATE_AFTER_ATTEMPTS = 3
        const val MIN_ROTATE_AFTER_ATTEMPTS = 1
        const val MAX_ROTATE_AFTER_ATTEMPTS = 10
    }
}
