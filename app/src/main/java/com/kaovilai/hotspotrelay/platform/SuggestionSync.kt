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

    /**
     * Applies the diff. With [retryMissing], every currently active target is also
     * `ensure`d even if the settings did not change: a previous registration may
     * have failed, and Save/Start would otherwise never retry it because the
     * before/after targets are identical.
     */
    fun reconcile(
        context: Context,
        before: AppSettings,
        after: AppSettings,
        retryMissing: Boolean = false,
    ) {
        val plan = SuggestionPlanner.plan(before, after)
        if (plan.isEmpty && !retryMissing) return
        val wifi = AndroidWifiLink(context)

        plan.forget.forEach { target ->
            runCatching { wifi.forgetSuggestion(target.ssid, target.password) }
        }

        plan.register.forEach { target ->
            register(wifi, target.ssid, target.password, replaceExisting = true)
        }

        val ensure = if (retryMissing) {
            (plan.ensure + after.targets.filter { it.enabled && it.isComplete })
                .distinctBy { it.ssid }
        } else {
            plan.ensure
        }
        // API 29 cannot enumerate suggestions, so a normal "ensure" there would
        // remove and re-add an identical entry for no reason. An explicit retry
        // (Save/Start) is different: it exists to repair a failed registration,
        // and remove-then-add is the only way to do that below API 30.
        if (retryMissing || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ensure.forEach { target ->
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
