package com.kaovilai.hotspotrelay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kaovilai.hotspotrelay.data.SettingsStore

/** Brings the loop back after a reboot if it was running when the phone shut down. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore.get(context).settings.value
        if (!settings.serviceEnabled || !settings.autoStartOnBoot) return
        runCatching { ReconnectService.start(context) }
            .onFailure { Log.w(TAG, "could not restart reconnect service", it) }
    }

    private companion object {
        const val TAG = "HotspotRelay/Boot"
    }
}
