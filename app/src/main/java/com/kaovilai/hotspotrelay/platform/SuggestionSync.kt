package com.kaovilai.hotspotrelay.platform

import android.content.Context
import android.util.Log
import com.kaovilai.hotspotrelay.data.AppSettings

/**
 * Keeps Android's Wi-Fi network suggestions in sync with the saved targets.
 *
 * Disabling or removing an entry must also drop its suggestion, otherwise the
 * system keeps auto-joining a network the user turned off; editing credentials
 * must re-register it, or Android keeps using the old password. Shared by the
 * settings screen and the external control receiver so both paths behave alike.
 */
object SuggestionSync {

    fun reconcile(context: Context, before: AppSettings, after: AppSettings) {
        if (before.targets == after.targets) return
        val wifi = AndroidWifiLink(context)
        val active = after.targets.filter { it.enabled && it.isComplete }
        val keep = active.map { it.ssid }.toSet()

        before.targets.map { it.ssid }
            .filter { it.isNotBlank() && it !in keep }
            .toSet()
            .forEach { ssid -> runCatching { wifi.forgetSuggestion(ssid) } }

        // ensureSuggestion() removes an existing suggestion before adding it again,
        // which can drop a shared or currently-joined hotspot. Only (re)register
        // entries that were not already active with the same credentials — a new,
        // re-enabled or password-changed target — so a priority-only change (MOVE,
        // SET_ORDER) or disabling one target leaves every other suggestion alone.
        val alreadyActive = before.targets.filter { it.enabled && it.isComplete }
            .map { it.ssid to it.password }
            .toSet()
        active.distinctBy { it.ssid }
            .filter { (it.ssid to it.password) !in alreadyActive }
            .forEach { target ->
                val outcome = runCatching { wifi.ensureSuggestion(target.ssid, target.password) }
                    .getOrNull()
                if (outcome == null || outcome == SuggestionOutcome.REJECTED || outcome == SuggestionOutcome.ERROR) {
                    Log.w(TAG, "could not register a suggestion for ${target.ssid}: $outcome")
                }
            }
    }

    private const val TAG = "HotspotRelay/Wifi"
}
