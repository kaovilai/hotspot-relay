package com.kaovilai.hotspotrelay.core

import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.IphoneTarget

/**
 * What the platform layer must do to keep Android's Wi-Fi suggestions aligned
 * with a settings change. Pure, so the ordering and credential rules are
 * unit-tested instead of only exercised on a device.
 */
data class SuggestionPlan(
    /** SSIDs no longer used by any enabled, complete target. */
    val forget: Set<String>,
    /** Targets to (re)register, replacing an existing suggestion for the SSID. */
    val register: List<IphoneTarget>,
    /**
     * Targets to ensure without replacing anything: a missing suggestion is
     * added back, an existing one is left untouched. This keeps a priority-only
     * change from removing a suggestion the phone may be joined to.
     */
    val ensure: List<IphoneTarget>,
) {
    val isEmpty: Boolean get() = forget.isEmpty() && register.isEmpty() && ensure.isEmpty()
}

object SuggestionPlanner {

    /**
     * Splits the before/after diff into forget / replace / ensure work.
     *
     * `register` only gets targets whose SSID+password pair was not already
     * active: new, re-enabled, or credential-changed. Everything already active
     * lands in `ensure` so an unchanged suggestion is never removed — and, if an
     * earlier registration failed, is retried without touching the live one.
     */
    fun plan(before: AppSettings, after: AppSettings): SuggestionPlan {
        if (before.targets == after.targets) return SuggestionPlan(emptySet(), emptyList(), emptyList())
        val active = after.targets.filter { it.enabled && it.isComplete }
        val keep = active.map { it.ssid }.toSet()
        val forget = before.targets.map { it.ssid }
            .filter { it.isNotBlank() && it !in keep }
            .toSet()

        val activeBefore = before.targets
            .filter { it.enabled && it.isComplete }
            // Same rule as the after-state: one credential pair per SSID, taken
            // from the highest-priority target. Otherwise disabling that target
            // would leave the other target's password unregistered.
            .distinctBy { it.ssid }
            .map { it.ssid to it.password }
            .toSet()

        val register = mutableListOf<IphoneTarget>()
        val ensure = mutableListOf<IphoneTarget>()
        // One suggestion per SSID: the highest-priority enabled target wins.
        active.distinctBy { it.ssid }.forEach { target ->
            if ((target.ssid to target.password) in activeBefore) {
                ensure += target
            } else {
                register += target
            }
        }
        return SuggestionPlan(forget = forget, register = register, ensure = ensure)
    }
}
