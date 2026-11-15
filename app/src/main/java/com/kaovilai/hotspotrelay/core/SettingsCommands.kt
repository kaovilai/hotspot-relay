package com.kaovilai.hotspotrelay.core

import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.IphoneTarget

/**
 * How an external command points at one iPhone. Ids are stable across reorders;
 * the other forms exist because automation usually has a friendlier handle.
 */
sealed interface TargetSelector {
    data class ById(val id: String) : TargetSelector
    data class BySsid(val ssid: String) : TargetSelector
    data class ByLabel(val label: String) : TargetSelector
    data class ByIndex(val index: Int) : TargetSelector
}

/** Commands an external automation may issue. None of them touch credentials. */
sealed interface SettingsCommand {
    data class SetEnabled(val selector: TargetSelector, val enabled: Boolean) : SettingsCommand

    /** Move the selected iPhone to [toIndex] (0-based, clamped). */
    data class Move(val selector: TargetSelector, val toIndex: Int) : SettingsCommand

    /** Reorder by a list of SSIDs, labels or ids; unlisted entries keep their order at the end. */
    data class SetOrder(val order: List<String>) : SettingsCommand

    data class SetRotateAfter(val attempts: Int) : SettingsCommand

    data object Start : SettingsCommand
    data object Stop : SettingsCommand
    data object Query : SettingsCommand
}

data class CommandOutcome(
    val settings: AppSettings,
    val ok: Boolean,
    val message: String,
    /** Set for Start/Stop, so the caller knows whether to touch the service. */
    val serviceAction: ServiceAction? = null,
) {
    enum class ServiceAction { START, STOP }
}

/**
 * Applies a [SettingsCommand] to [AppSettings]. Pure and Android-free so the
 * whole external control surface is unit tested; the receiver only maps intents
 * onto these commands.
 */
object SettingsCommands {

    fun apply(settings: AppSettings, command: SettingsCommand): CommandOutcome = when (command) {
        is SettingsCommand.SetEnabled -> setEnabled(settings, command)
        is SettingsCommand.Move -> move(settings, command)
        is SettingsCommand.SetOrder -> setOrder(settings, command)
        is SettingsCommand.SetRotateAfter -> setRotateAfter(settings, command)
        SettingsCommand.Start -> CommandOutcome(
            settings,
            ok = true,
            message = "start requested",
            serviceAction = CommandOutcome.ServiceAction.START,
        )

        SettingsCommand.Stop -> CommandOutcome(
            settings,
            ok = true,
            message = "stop requested",
            serviceAction = CommandOutcome.ServiceAction.STOP,
        )

        SettingsCommand.Query -> CommandOutcome(settings, ok = true, message = "state")
    }

    private fun setEnabled(
        settings: AppSettings,
        command: SettingsCommand.SetEnabled,
    ): CommandOutcome {
        val target = resolve(settings.targets, command.selector)
            ?: return notFound(settings, command.selector)
        val updated = settings.targets.map { if (it.id == target.id) it.copy(enabled = command.enabled) else it }
        return CommandOutcome(
            settings.copy(targets = updated),
            ok = true,
            message = "${target.displayName} ${if (command.enabled) "enabled" else "disabled"}",
        )
    }

    private fun move(settings: AppSettings, command: SettingsCommand.Move): CommandOutcome {
        val target = resolve(settings.targets, command.selector)
            ?: return notFound(settings, command.selector)
        if (settings.targets.size < 2) {
            return CommandOutcome(settings, ok = true, message = "only one iPhone configured")
        }
        val from = settings.targets.indexOfFirst { it.id == target.id }
        val to = command.toIndex.coerceIn(0, settings.targets.lastIndex)
        val reordered = settings.targets.toMutableList().apply { add(to, removeAt(from)) }
        return CommandOutcome(
            settings.copy(targets = reordered),
            ok = true,
            message = "${target.displayName} moved to ${to + 1}",
        )
    }

    private fun setOrder(settings: AppSettings, command: SettingsCommand.SetOrder): CommandOutcome {
        val keys = command.order.map { it.trim() }.filter { it.isNotEmpty() }
        if (keys.isEmpty()) return CommandOutcome(settings, ok = false, message = "order is empty")

        val remaining = settings.targets.toMutableList()
        val ordered = mutableListOf<IphoneTarget>()
        val missing = mutableListOf<String>()
        keys.forEach { key ->
            // Same precedence as the single-target selectors: id, then ssid, then
            // label/display name. A key must match something.
            val match = remaining.firstOrNull { it.id == key }
                ?: remaining.firstOrNull { it.ssid == key }
                ?: remaining.firstOrNull { it.label == key }
                ?: remaining.firstOrNull { it.displayName == key }
            if (match != null) {
                ordered += match
                remaining.remove(match)
            } else {
                missing += key
            }
        }
        if (missing.isNotEmpty()) {
            // Reject the whole order instead of applying a partial one to a
            // different set of targets and reporting success.
            return CommandOutcome(
                settings,
                ok = false,
                message = "order not applied; unknown entries: ${missing.joinToString(", ")}",
            )
        }
        if (ordered.isEmpty()) {
            return CommandOutcome(settings, ok = false, message = "no iPhone matched the order list")
        }
        ordered += remaining
        return CommandOutcome(
            settings.copy(targets = ordered),
            ok = true,
            message = "order set to ${ordered.joinToString(", ") { it.displayName }}",
        )
    }

    private fun setRotateAfter(
        settings: AppSettings,
        command: SettingsCommand.SetRotateAfter,
    ): CommandOutcome {
        val value = command.attempts.coerceIn(
            AppSettings.MIN_ROTATE_AFTER_ATTEMPTS,
            AppSettings.MAX_ROTATE_AFTER_ATTEMPTS,
        )
        return CommandOutcome(
            settings.copy(rotateAfterAttempts = value),
            ok = true,
            message = "rotate after $value attempt(s)",
        )
    }

    private fun notFound(settings: AppSettings, selector: TargetSelector): CommandOutcome =
        CommandOutcome(
            settings = settings,
            ok = false,
            message = "no iPhone matched $selector",
        )

    /** Position of the selected target, for callers that need relative moves. */
    fun resolveIndex(targets: List<IphoneTarget>, selector: TargetSelector): Int? =
        resolve(targets, selector)?.let { target -> targets.indexOfFirst { it.id == target.id } }

    private fun resolve(targets: List<IphoneTarget>, selector: TargetSelector): IphoneTarget? =
        when (selector) {
            is TargetSelector.ById -> targets.firstOrNull { it.id == selector.id }
            is TargetSelector.BySsid -> targets.firstOrNull { it.ssid == selector.ssid }
            is TargetSelector.ByLabel -> targets.firstOrNull { it.label == selector.label }
            is TargetSelector.ByIndex -> targets.getOrNull(selector.index)
        }.let { resolved ->
            // A blank label is common; fall back to whatever the user sees.
            resolved ?: when (selector) {
                is TargetSelector.ByLabel ->
                    targets.firstOrNull { it.displayName == selector.label }
                is TargetSelector.BySsid ->
                    targets.firstOrNull { it.displayName == selector.ssid }
                is TargetSelector.ById ->
                    targets.firstOrNull { it.displayName == selector.id }
                is TargetSelector.ByIndex -> null
            }
        }
}
