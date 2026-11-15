package com.kaovilai.hotspotrelay.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaovilai.hotspotrelay.core.Phase
import com.kaovilai.hotspotrelay.data.SettingsStore
import com.kaovilai.hotspotrelay.platform.AndroidBluetoothLink
import com.kaovilai.hotspotrelay.platform.AndroidWifiLink
import com.kaovilai.hotspotrelay.platform.PairingModeResult
import com.kaovilai.hotspotrelay.platform.SuggestionOutcome
import com.kaovilai.hotspotrelay.service.ReconnectService
import com.kaovilai.hotspotrelay.service.ServiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore) {
    val saved by store.settings.collectAsStateWithLifecycle()
    val status by ReconnectService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bluetooth = remember { AndroidBluetoothLink.get(context) }

    LaunchedEffect(Unit) { bluetooth.start() }

    var iphoneSelector by rememberSaveable { mutableStateOf(saved.iphoneSelector) }
    var hotspotSsid by rememberSaveable { mutableStateOf(saved.hotspotSsid) }
    var hotspotPassword by rememberSaveable { mutableStateOf(saved.hotspotPassword) }
    var tickSeconds by rememberSaveable { mutableStateOf((saved.tickIntervalMs / 1000).toString()) }
    var autoStartOnBoot by rememberSaveable { mutableStateOf(saved.autoStartOnBoot) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val dirty = iphoneSelector != saved.iphoneSelector ||
        hotspotSsid != saved.hotspotSsid ||
        hotspotPassword != saved.hotspotPassword ||
        tickSeconds != (saved.tickIntervalMs / 1000).toString() ||
        autoStartOnBoot != saved.autoStartOnBoot

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val save: () -> Unit = {
        val seconds = tickSeconds.toLongOrNull()?.coerceIn(MIN_TICK_SECONDS, MAX_TICK_SECONDS)
            ?: (saved.tickIntervalMs / 1000)
        val stored = store.update {
            it.copy(
                iphoneSelector = iphoneSelector.trim(),
                hotspotSsid = hotspotSsid.trim(),
                hotspotPassword = hotspotPassword,
                tickIntervalMs = seconds * 1000,
                autoStartOnBoot = autoStartOnBoot,
            )
        }
        // Mirror what was persisted (trimmed) so the form is not left "dirty".
        iphoneSelector = stored.iphoneSelector
        hotspotSsid = stored.hotspotSsid
        hotspotPassword = stored.hotspotPassword
        tickSeconds = (stored.tickIntervalMs / 1000).toString()
        autoStartOnBoot = stored.autoStartOnBoot
        // Keep Android's suggestion in sync with edited credentials; otherwise the
        // system would keep auto-joining with the old password.
        if (stored.hotspotSsid.isNotBlank() && stored.hotspotPassword.isNotBlank()) {
            AndroidWifiLink(context).ensureSuggestion(stored.hotspotSsid, stored.hotspotPassword)
        }
        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Hotspot Relay") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status)

            Text(
                text = "When Android is on no Wi-Fi, it connects Bluetooth to your iPhone. " +
                    "The iPhone shortcut sees that connection and turns Personal Hotspot on. " +
                    "Then this app joins the hotspot, retrying until it works.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = iphoneSelector,
                onValueChange = { iphoneSelector = it },
                label = { Text("iPhone Bluetooth name or MAC") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hotspotSsid,
                onValueChange = { hotspotSsid = it },
                label = { Text("Hotspot SSID (e.g. iPhone name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = hotspotPassword,
                onValueChange = { hotspotPassword = it },
                label = { Text("Hotspot password") },
                singleLine = true,
                visualTransformation = if (showPassword) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tickSeconds,
                onValueChange = { tickSeconds = it.filter(Char::isDigit) },
                label = { Text("Check every (seconds)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("$MIN_TICK_SECONDS–$MAX_TICK_SECONDS") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoStartOnBoot, onCheckedChange = { autoStartOnBoot = it })
                Spacer(Modifier.width(12.dp))
                Text("Restart after reboot")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = save, enabled = dirty) { Text("Save") }
                OutlinedButton(
                    onClick = { permissionLauncher.launch(requiredPermissions()) },
                ) { Text("Grant permissions") }
            }

            FilledTonalButton(
                onClick = {
                    val outcome = AndroidWifiLink(context)
                        .ensureSuggestion(hotspotSsid.trim(), hotspotPassword)
                    Toast.makeText(context, suggestionMessage(outcome), Toast.LENGTH_LONG).show()
                },
                enabled = hotspotSsid.isNotBlank() && hotspotPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow hotspot auto-join") }

            FilledTonalButton(
                onClick = {
                    when (bluetooth.enablePairingMode()) {
                        PairingModeResult.READY -> {
                            val discoverable = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                            val launched = runCatching { context.startActivity(discoverable) }.isSuccess
                            Toast.makeText(
                                context,
                                if (launched) {
                                    "On the iPhone: Settings → Bluetooth → tap this phone to pair"
                                } else {
                                    "Could not open the pairing dialog — grant Nearby devices permission"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                        }

                        PairingModeResult.NOT_READY ->
                            Toast.makeText(context, "Try again in a second", Toast.LENGTH_SHORT).show()

                        PairingModeResult.UNAVAILABLE -> Toast.makeText(
                            context,
                            "Enable Bluetooth and grant Nearby devices permission first",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair with iPhone (keyboard mode)") }

            OutlinedButton(
                onClick = { openBatterySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow background battery use") }

            Button(
                onClick = {
                    if (status.running) {
                        ReconnectService.stop(context)
                    } else {
                        save()
                        ReconnectService.start(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (status.running) "Stop" else "Start") }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "On the iPhone: pair with this phone, then create a Shortcuts " +
                    "automation whose trigger is Bluetooth → this phone connecting, " +
                    "running the \"Turn on Personal Hotspot\" shortcut. See the README " +
                    "for the exact steps and the shortcut file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(status: ServiceStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = when {
                                !status.running -> MaterialTheme.colorScheme.outline
                                status.phase == Phase.CONNECTED -> Color(0xFF16A34A)
                                status.phase == Phase.BLOCKED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                            shape = CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (status.running) "Running" else "Stopped",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = status.phase.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = status.message.ifBlank { "No status yet" },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.running && status.failures > 0) {
                Text(
                    text = "Retries so far: ${status.failures}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Phase?.label(): String = when (this) {
    null -> "Not started"
    Phase.CONNECTED -> "Connected"
    Phase.ON_OTHER_WIFI -> "On other Wi-Fi"
    Phase.WAITING_FOR_IPHONE -> "Waiting for Bluetooth"
    Phase.WAITING_FOR_HOTSPOT -> "Waiting for hotspot"
    Phase.CONNECTING_HOTSPOT -> "Joining hotspot"
    Phase.BLOCKED -> "Needs attention"
}

private fun suggestionMessage(outcome: SuggestionOutcome): String = when (outcome) {
    SuggestionOutcome.ADDED -> "Hotspot registered — approve the system prompt if shown"
    SuggestionOutcome.ALREADY_REGISTERED -> "Hotspot already registered"
    SuggestionOutcome.DUPLICATE -> "Hotspot already registered"
    SuggestionOutcome.REJECTED -> "System rejected the suggestion (check Wi-Fi permissions)"
    SuggestionOutcome.ERROR -> "Could not register the hotspot (SSID and password required)"
}

private fun requiredPermissions(): Array<String> {
    // Android 12+ ignores a fine-location request that is not paired with coarse.
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_CONNECT
        permissions += Manifest.permission.BLUETOOTH_ADVERTISE
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += Manifest.permission.POST_NOTIFICATIONS
        permissions += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    return permissions.toTypedArray()
}

private fun openBatterySettings(context: Context) {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(request) }
        .onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
}

private const val MIN_TICK_SECONDS = 5L
private const val MAX_TICK_SECONDS = 300L
