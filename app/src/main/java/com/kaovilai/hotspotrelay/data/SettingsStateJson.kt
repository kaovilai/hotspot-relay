package com.kaovilai.hotspotrelay.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Public JSON view of the configuration, used by the external-control broadcast.
 *
 * Passwords are deliberately excluded: automation needs to toggle and reorder
 * iPhones, not read credentials.
 */
object SettingsStateJson {

    fun encode(settings: AppSettings, running: Boolean): String {
        val targets = JSONArray()
        settings.targets.forEachIndexed { index, target ->
            targets.put(
                JSONObject().apply {
                    put("index", index)
                    put("id", target.id)
                    put("label", target.label)
                    put("name", target.displayName)
                    put("selector", target.btSelector)
                    put("ssid", target.ssid)
                    put("enabled", target.enabled)
                    put("complete", target.isComplete)
                },
            )
        }
        return JSONObject().apply {
            put("running", running)
            put("rotateAfterAttempts", settings.rotateAfterAttempts)
            put("tickIntervalMs", settings.tickIntervalMs)
            put("targets", targets)
        }.toString()
    }
}
