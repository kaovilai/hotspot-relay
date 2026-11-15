package com.kaovilai.hotspotrelay.platform

import android.content.Context
import android.os.Build
import android.util.Log
import com.kaovilai.hotspotrelay.core.SuggestionPlanner
import com.kaovilai.hotspotrelay.data.AppSettings

/**
 * Applies the [SuggestionPlanner] diff to Android's Wi-Fi suggestions.
 *
 * Disabling or removing an entry must also drop its suggestion, otherwise the
 * system keeps auto-joining a network the user turned off; editing credentials
 * must re-register it, or Android keeps using the old password. Unchanged
 * entries are only *ensured* (never removed first), so a priority-only change,
 * or disabling a different target, cannot drop the hotspot currently joined.
 * Shared by the settings screen and the external control receiver.
 */
object SuggestionSync {

    fun reconcile(context: Context, before: AppSettings, after: AppSettings) {
        val plan = SuggestionPlanner.plan(before, after)
        if (plan.isEmpty) return
        val wifi = AndroidWifiLink(context)

        plan.forget.forEach { target ->
            runCatching { wifi.forgetSuggestion(target.ssid, target.password) }
        }

        plan.register.forEach { target ->
            register(wifi, target.ssid, target.password, replaceExisting = true)
        }
        // API 29 cannot enumerate suggestions, so "ensure" would remove and
        // re-add an identical entry there; on 30+ it is a true no-op when the
        // suggestion exists and repairs it when an earlier add failed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            plan.ensure.forEach { target ->
                register(wifi, target.ssid, target.password, replaceExisting = false)
            }
        }
    }

    private fun register(
        wifi: AndroidWifiLink,
        ssid: String,
        password: String,
        replaceExisting: Boolean,
    ) {
        val outcome = runCatching { wifi.ensureSuggestion(ssid, password, replaceExisting) }
            .getOrNull()
        if (outcome == null || outcome == SuggestionOutcome.REJECTED || outcome == SuggestionOutcome.ERROR) {
            Log.w(TAG, "could not register a suggestion for $ssid: $outcome")
        }
    }

    private const val TAG = "HotspotRelay/Wifi"
}
