package com.kaovilai.hotspotrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.kaovilai.hotspotrelay.core.CommandOutcome
import com.kaovilai.hotspotrelay.core.SettingsCommand
import com.kaovilai.hotspotrelay.core.SettingsCommands
import com.kaovilai.hotspotrelay.core.TargetSelector
import com.kaovilai.hotspotrelay.data.AppSettings
import com.kaovilai.hotspotrelay.data.SettingsStateJson
import com.kaovilai.hotspotrelay.data.SettingsStore
import com.kaovilai.hotspotrelay.platform.SuggestionSync
import com.kaovilai.hotspotrelay.service.ReconnectService

/**
 * External control surface for Tasker, adb and other automation.
 *
 * Send an explicit broadcast to this receiver (recommended, always delivered):
 *
 * ```
 * adb shell am broadcast -n com.kaovilai.hotspotrelay/.receiver.ControlReceiver \
 *   -a com.kaovilai.hotspotrelay.action.SET_ENABLED \
 *   --es ssid "Work iPhone" --ez enabled false
 * ```
 *
 * Every command answers with the resulting state JSON as the ordered-broadcast
 * result data (`state` extra, `ok`, `message`) and also emits
 * [ACTION_STATE_CHANGED], which an automation can listen for. Malformed commands
 * are rejected (`ok=false`) instead of being guessed at. Commands can only
 * toggle, reorder, change the rotation limit, and start/stop the loop; they can
 * never read or change SSIDs, passwords or Bluetooth selectors.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!action.startsWith(ACTION_PREFIX)) return

        val store = SettingsStore.get(context)
        val before = store.settings.value
        val parsed = parseCommand(action, intent, before)
        if (parsed.error != null) {
            respond(context, before, ok = false, message = parsed.error)
            return
        }
        val command = parsed.command
        if (command == null) {
            respond(context, before, ok = false, message = "unsupported command: $action")
            return
        }

        val outcome = SettingsCommands.apply(before, command)
        val after = if (outcome.settings != before) {
            val stored = store.update { outcome.settings }
            // Keep Android's network suggestions in step with the new state, the
            // same way the settings screen does: a disabled or removed iPhone
            // must stop being auto-joined, and a re-enabled one is re-registered.
            SuggestionSync.reconcile(context, before, stored)
            stored
        } else {
            before
        }

        var ok = outcome.ok
        var message = outcome.message
        when (outcome.serviceAction) {
            CommandOutcome.ServiceAction.START -> {
                // Android 12+ may refuse a foreground-service start from a
                // background broadcast; report that instead of pretending.
                val started = runCatching { ReconnectService.start(context) }.isSuccess
                if (!started) {
                    ok = false
                    message = "$message (blocked by Android; open the app to start)"
                }
            }

            CommandOutcome.ServiceAction.STOP -> ReconnectService.stop(context)
            null -> Unit
        }

        respond(context, after, ok, message)
        // START/STOP settle asynchronously, so the snapshot above is only the
        // request acknowledgement. ReconnectService broadcasts its own
        // STATE_CHANGED whenever `running` flips, including the case where a
        // foreground start is refused after this handler returned.
    }

    private data class Parsed(val command: SettingsCommand? = null, val error: String? = null)

    private fun parseCommand(action: String, intent: Intent, settings: AppSettings): Parsed {
        // A selector extra that is present but not a number is a bug in the
        // caller, not an instruction to use index 0.
        if (intent.hasExtra("index") && intExtra(intent, "index") == null) {
            return Parsed(error = "index must be a number")
        }
        return when (action) {
            ACTION_QUERY -> Parsed(SettingsCommand.Query)
            ACTION_START -> Parsed(SettingsCommand.Start)
            ACTION_STOP -> Parsed(SettingsCommand.Stop)

            ACTION_SET_ROTATE_AFTER -> {
                // Reject a present-but-unparseable alias instead of falling back to
                // the other one: `value=invalid attempts=2` is a caller bug, not a
                // request to use 2.
                if (intent.hasExtra("value") && intExtra(intent, "value") == null) {
                    return Parsed(error = "SET_ROTATE_AFTER 'value' must be a number")
                }
                if (intent.hasExtra("attempts") && intExtra(intent, "attempts") == null) {
                    return Parsed(error = "SET_ROTATE_AFTER 'attempts' must be a number")
                }
                val value = intExtra(intent, "value") ?: intExtra(intent, "attempts")
                if (value == null) {
                    Parsed(error = "SET_ROTATE_AFTER needs a numeric value or attempts")
                } else {
                    Parsed(SettingsCommand.SetRotateAfter(value))
                }
            }

            ACTION_SET_ENABLED -> {
                val selector = selectorFrom(intent)
                    ?: return Parsed(error = "SET_ENABLED needs one selector: id, ssid, label or index")
                val enabled = enabledFrom(intent)
                    ?: return Parsed(error = "SET_ENABLED needs enabled=true|false")
                Parsed(SettingsCommand.SetEnabled(selector, enabled))
            }

            ACTION_MOVE -> {
                val selector = selectorFrom(intent)
                    ?: return Parsed(error = "MOVE needs one selector: id, ssid, label or index")
                when {
                    intent.hasExtra("to") -> {
                        val to = intExtra(intent, "to")
                            ?: return Parsed(error = "MOVE 'to' must be a number")
                        Parsed(SettingsCommand.Move(selector, to))
                    }

                    intent.hasExtra("delta") -> {
                        val delta = intExtra(intent, "delta")
                            ?: return Parsed(error = "MOVE 'delta' must be a number")
                        val index = SettingsCommands.resolveIndex(settings.targets, selector)
                            ?: return Parsed(error = "MOVE found no target for that selector")
                        // Add in Long so a huge delta cannot wrap into a negative
                        // index; Move clamps the result to the real range.
                        val destination = (index.toLong() + delta)
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                            .toInt()
                        Parsed(SettingsCommand.Move(selector, destination))
                    }

                    else -> Parsed(error = "MOVE needs 'to' or 'delta'")
                }
            }

            ACTION_SET_ORDER -> Parsed(
                SettingsCommand.SetOrder(
                    intent.getStringExtra("order")
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                ),
            )

            else -> Parsed()
        }
    }

    private fun respond(context: Context, settings: AppSettings, ok: Boolean, message: String) {
        val state = SettingsStateJson.encode(
            settings = settings,
            running = ReconnectService.status.value.running,
        )
        setResultData(state)
        setResultExtras(
            Bundle().apply {
                putBoolean("ok", ok)
                putString("message", message)
                putString("state", state)
            },
        )
        broadcastState(context, ok, message, state)
    }

    private fun broadcastState(context: Context, ok: Boolean, message: String, state: String) {
        Log.i(TAG, "control: ok=$ok $message")
        // Listenable by other apps (Tasker "Intent Received"); no package filter.
        context.sendBroadcast(
            Intent(ACTION_STATE_CHANGED)
                .putExtra("ok", ok)
                .putExtra("message", message)
                .putExtra("state", state),
        )
    }

    private fun selectorFrom(intent: Intent): TargetSelector? = when {
        intent.getStringExtra("id")?.isNotBlank() == true ->
            TargetSelector.ById(intent.getStringExtra("id")!!)

        intent.getStringExtra("ssid")?.isNotBlank() == true ->
            TargetSelector.BySsid(intent.getStringExtra("ssid")!!)

        intent.getStringExtra("label")?.isNotBlank() == true ->
            TargetSelector.ByLabel(intent.getStringExtra("label")!!)

        intExtra(intent, "index") != null ->
            TargetSelector.ByIndex(intExtra(intent, "index")!!)

        else -> null
    }

    /**
     * `null` when the extra is absent or its value is not a recognized boolean —
     * callers reject those instead of silently disabling an entry.
     */
    @Suppress("DEPRECATION")
    private fun enabledFrom(intent: Intent): Boolean? {
        if (!intent.hasExtra("enabled")) return null
        return when (val raw = intent.extras?.get("enabled")) {
            is Boolean -> raw
            // Only 0 and 1 are accepted numeric aliases. Anything else (2, 0.5)
            // is a caller bug and must not be truncated into a boolean.
            is Number -> when (raw.toDouble()) {
                1.0 -> true
                0.0 -> false
                else -> null
            }
            is String -> when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }

            else -> null
        }
    }

    /** `am broadcast` may deliver numeric extras as strings; accept both. */
    @Suppress("DEPRECATION")
    private fun intExtra(intent: Intent, key: String): Int? {
        val raw = intent.extras?.get(key) ?: return null
        return when (raw) {
            is Number -> integralInt(raw)
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    /**
     * Rejects fractional and out-of-range values: `toInt()` would truncate 0.5 to
     * 0 and wrap 2^32 to 0, silently acting on a different entry than the caller
     * asked for (`--ef index 0.5`).
     */
    private fun integralInt(raw: Number): Int? {
        val value = raw.toDouble()
        if (!value.isFinite() || value < Int.MIN_VALUE || value > Int.MAX_VALUE) return null
        val asInt = value.toInt()
        return asInt.takeIf { it.toDouble() == value }
    }

    companion object {
        private const val TAG = "HotspotRelay/Control"
        private const val ACTION_PREFIX = "com.kaovilai.hotspotrelay.action."

        const val ACTION_STATE_CHANGED = ACTION_PREFIX + "STATE_CHANGED"
        const val ACTION_QUERY = ACTION_PREFIX + "QUERY"
        const val ACTION_START = ACTION_PREFIX + "START"
        const val ACTION_STOP = ACTION_PREFIX + "STOP"
        const val ACTION_SET_ENABLED = ACTION_PREFIX + "SET_ENABLED"
        const val ACTION_MOVE = ACTION_PREFIX + "MOVE"
        const val ACTION_SET_ORDER = ACTION_PREFIX + "SET_ORDER"
        const val ACTION_SET_ROTATE_AFTER = ACTION_PREFIX + "SET_ROTATE_AFTER"
    }
}
