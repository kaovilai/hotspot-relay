package com.kaovilai.hotspotrelay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kaovilai.hotspotrelay.data.SettingsStore

/**
 * Brings the loop back after a reboot, or after Obtainium replaces the package
 * (an update kills the process and START_STICKY does not survive that).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val boot = action == Intent.ACTION_BOOT_COMPLETED
        val replaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!boot && !replaced) return

        val settings = SettingsStore.get(context).settings.value
        if (!settings.serviceEnabled) return
        if (boot && !settings.autoStartOnBoot) return

        runCatching { ReconnectService.start(context) }
            .onFailure { Log.w(TAG, "could not restart reconnect service", it) }
    }

    private companion object {
        const val TAG = "HotspotRelay/Boot"
    }
}
