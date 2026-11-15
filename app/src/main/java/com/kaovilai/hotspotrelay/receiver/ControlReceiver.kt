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
 * [ACTION_STATE_CHANGED], which an automation can listen for. Commands can only
 * toggle, reorder, change the rotation limit, and start/stop the loop; they can
 * never read or change SSIDs, passwords or Bluetooth selectors.
 */
class ControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!action.startsWith(ACTION_PREFIX)) return

        val store = SettingsStore.get(context)
        val settings = store.settings.value
        val command = commandFor(action, intent, settings)
        if (command == null) {
            respond(context, settings, ok = false, message = "unsupported command: $action")
            return
        }

        val outcome = SettingsCommands.apply(settings, command)
        if (outcome.settings != settings) {
            store.update { outcome.settings }
        }

        var message = outcome.message
        when (outcome.serviceAction) {
            CommandOutcome.ServiceAction.START -> {
                // Android 12+ may refuse a foreground-service start from a
                // background broadcast; report that instead of pretending.
                val started = runCatching { ReconnectService.start(context) }.isSuccess
                if (!started) message = "$message (blocked by Android; open the app to start)"
            }

            CommandOutcome.ServiceAction.STOP -> ReconnectService.stop(context)
            null -> Unit
        }

        respond(context, store.settings.value, outcome.ok, message)
    }

    private fun commandFor(
        action: String,
        intent: Intent,
        settings: AppSettings,
    ): SettingsCommand? = when (action) {
        ACTION_QUERY -> SettingsCommand.Query
        ACTION_START -> SettingsCommand.Start
        ACTION_STOP -> SettingsCommand.Stop
        ACTION_SET_ROTATE_AFTER -> SettingsCommand.SetRotateAfter(
            intExtra(intent, "value") ?: intExtra(intent, "attempts")
                ?: settings.rotateAfterAttempts,
        )

        ACTION_SET_ENABLED -> selectorFrom(intent)?.let { selector ->
            SettingsCommand.SetEnabled(selector, enabledFrom(intent))
        }

        ACTION_MOVE -> selectorFrom(intent)?.let { selector ->
            when {
                intent.hasExtra("to") -> SettingsCommand.Move(selector, intExtra(intent, "to") ?: 0)
                intent.hasExtra("delta") -> {
                    val index = SettingsCommands.resolveIndex(settings.targets, selector)
                        ?: return null
                    SettingsCommand.Move(selector, index + (intExtra(intent, "delta") ?: 0))
                }

                else -> null
            }
        }

        ACTION_SET_ORDER -> SettingsCommand.SetOrder(
            intent.getStringExtra("order")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
        )

        else -> null
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
        Log.i(TAG, "control: $message")

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

    @Suppress("DEPRECATION")
    private fun enabledFrom(intent: Intent): Boolean {
        if (!intent.hasExtra("enabled")) return true
        return when (val raw = intent.extras?.get("enabled")) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> true
        }
    }

    /** `am broadcast` may deliver numeric extras as strings; accept both. */
    @Suppress("DEPRECATION")
    private fun intExtra(intent: Intent, key: String): Int? {
        val raw = intent.extras?.get(key) ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
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
