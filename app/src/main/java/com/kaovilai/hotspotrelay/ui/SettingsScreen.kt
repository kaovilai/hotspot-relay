package com.kaovilai.hotspotrelay.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaovilai.hotspotrelay.core.Phase
import com.kaovilai.hotspotrelay.data.AppSettings
import org.json.JSONObject
import com.kaovilai.hotspotrelay.data.IphoneTarget
import com.kaovilai.hotspotrelay.data.SettingsStore
import com.kaovilai.hotspotrelay.data.TargetsCodec
import com.kaovilai.hotspotrelay.platform.AndroidBluetoothLink
import com.kaovilai.hotspotrelay.platform.AndroidWifiLink
import com.kaovilai.hotspotrelay.platform.BluetoothDiscovery
import com.kaovilai.hotspotrelay.platform.PairingModeResult
import com.kaovilai.hotspotrelay.platform.PickableDevice
import com.kaovilai.hotspotrelay.platform.SuggestionOutcome
import com.kaovilai.hotspotrelay.platform.SuggestionSync
import com.kaovilai.hotspotrelay.platform.WifiAssociation
import com.kaovilai.hotspotrelay.service.ReconnectService
import com.kaovilai.hotspotrelay.service.ServiceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore) {
    val saved by store.settings.collectAsStateWithLifecycle()
    val status by ReconnectService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bluetooth = remember { AndroidBluetoothLink.get(context) }
    val wifiLink = remember { AndroidWifiLink(context) }
    val discovery = remember { BluetoothDiscovery(context) }

    // association() reports associated=true with a null SSID when Android hides
    // the name (no Location permission); keep both so the helper can say why.
    var wifiAssociation by remember { mutableStateOf(WifiAssociation(associated = false, ssid = null)) }
    var pickerTargetId by remember { mutableStateOf<String?>(null) }
    var pickerDevices by remember { mutableStateOf(emptyList<PickableDevice>()) }
    var pickerScanning by remember { mutableStateOf(false) }
    var pickerIsScan by remember { mutableStateOf(false) }
    var pickerNotice by remember { mutableStateOf<String?>(null) }
    var pairingCheckRunning by remember { mutableStateOf(false) }
    val scanPermissionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "Nearby devices"
    } else {
        "Location"
    }

    LaunchedEffect(Unit) { bluetooth.start() }

    // Read the current SSID for "Use current Wi-Fi" and keep it fresh: it changes
    // when a network comes or goes, and granting location un-redacts it.
    DisposableEffect(Unit) {
        wifiLink.start()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val refresh = {
            main.post { wifiAssociation = wifiLink.association() }
        }
        // Wi-Fi transport, not the default network: a hotspot without validated
        // Internet never becomes the default route while cellular is up, but
        // joining or leaving it must still refresh the SSID.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                refresh()
            }

            override fun onLost(network: Network) {
                refresh()
            }
        }
        val registered = runCatching { manager?.registerNetworkCallback(request, callback) }.isSuccess
        wifiAssociation = wifiLink.association()
        onDispose {
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
            main.removeCallbacksAndMessages(null)
            wifiLink.stop()
            discovery.stop()
        }
    }

    // Returning to the screen (or a grant in another app) can change the SSID too.
    LifecycleResumeEffect(Unit) {
        wifiAssociation = wifiLink.association()
        onPauseOrDispose { }
    }

    var rows by rememberSaveable(stateSaver = TargetsSaver) { mutableStateOf(saved.targets) }
    var rotateAfter by rememberSaveable { mutableStateOf(saved.rotateAfterAttempts.toString()) }
    var tickSeconds by rememberSaveable { mutableStateOf((saved.tickIntervalMs / 1000).toString()) }
    var autoStartOnBoot by rememberSaveable { mutableStateOf(saved.autoStartOnBoot) }
    var revealed by remember { mutableStateOf(setOf<String>()) }
    // The persisted values the form was last synced with. Comparing against this
    // rather than against `saved` means an external change (Tasker, adb) does not
    // look like a local edit: it can be adopted while the form is untouched, and
    // Save cannot silently revert it. It is saved across activity recreation as
    // well, otherwise a draft that outlives the rotation loses the baseline it
    // was edited against.
    var synced by rememberSaveable(stateSaver = SyncedSaver) { mutableStateOf(saved) }

    val dirty = rows != synced.targets ||
        rotateAfter != synced.rotateAfterAttempts.toString() ||
        tickSeconds != (synced.tickIntervalMs / 1000).toString() ||
        autoStartOnBoot != synced.autoStartOnBoot

    // Settings can also change from the external control receiver (Tasker, adb);
    // reflect those, but never clobber in-progress edits. `dirty` is a key so an
    // external change that arrived while the form was edited is still adopted
    // once the edits are reverted (saved does not change again in that case).
    LaunchedEffect(saved, dirty) {
        val untouched = rows == synced.targets &&
            rotateAfter == synced.rotateAfterAttempts.toString() &&
            tickSeconds == (synced.tickIntervalMs / 1000).toString() &&
            autoStartOnBoot == synced.autoStartOnBoot
        if (untouched) {
            synced = saved
            rows = saved.targets
            rotateAfter = saved.rotateAfterAttempts.toString()
            tickSeconds = (saved.tickIntervalMs / 1000).toString()
            autoStartOnBoot = saved.autoStartOnBoot
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Granting location removes Android's SSID redaction; read it right away.
        wifiAssociation = wifiLink.association()
    }

    fun persist(): AppSettings {
        val seconds = tickSeconds.toLongOrNull()
            ?.coerceIn(MIN_TICK_SECONDS, MAX_TICK_SECONDS)
            ?: (saved.tickIntervalMs / 1000)
        val rotate = rotateAfter.toIntOrNull()
            ?.coerceIn(AppSettings.MIN_ROTATE_AFTER_ATTEMPTS, AppSettings.MAX_ROTATE_AFTER_ATTEMPTS)
            ?: AppSettings.DEFAULT_ROTATE_AFTER_ATTEMPTS
        val stored = store.update {
            it.copy(
                targets = rows,
                rotateAfterAttempts = rotate,
                tickIntervalMs = seconds * 1000,
                autoStartOnBoot = autoStartOnBoot,
            )
        }
        // Keep Android's suggestions in sync: a deleted, cleared or disabled
        // entry must stop being auto-joined, and edited credentials must be
        // re-registered or the system keeps using the old password. Retry any
        // registration that failed earlier, even when nothing changed now.
        SuggestionSync.reconcile(context, saved, stored, retryMissing = true)

        synced = stored
        rows = stored.targets
        rotateAfter = stored.rotateAfterAttempts.toString()
        tickSeconds = (stored.tickIntervalMs / 1000).toString()
        autoStartOnBoot = stored.autoStartOnBoot
        return stored
    }

    fun updateTarget(id: String, transform: (IphoneTarget) -> IphoneTarget) {
        rows = rows.map { if (it.id == id) transform(it) else it }
    }

    fun moveTarget(id: String, delta: Int) {
        val index = rows.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in rows.indices) return
        rows = rows.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun startPickerScan() {
        pickerScanning = true
        pickerNotice = null
        var found = 0
        discovery.start(
            onFound = { device ->
                found++
                // Replace an existing entry for the same MAC so a later scan can
                // fill in a name that was still unknown on the first sighting
                // (Bluetooth often resolves names only after that).
                pickerDevices = (pickerDevices.filterNot { it.address == device.address } + device)
                    .sortedBy { it.label.lowercase() }
            },
            onFinished = {
                pickerScanning = false
                if (found == 0) {
                    pickerNotice = "No new devices found. Keep the iPhone on Settings → Bluetooth and try again."
                }
            },
            onUnavailable = {
                pickerScanning = false
                pickerNotice = "Couldn't start scanning — grant $scanPermissionName permission and turn Bluetooth on."
            },
        )
    }

    fun openPicker(id: String, scan: Boolean) {
        pickerTargetId = id
        pickerIsScan = scan
        pickerNotice = null
        pickerDevices = if (scan) emptyList() else bluetooth.pairedDevices()
        if (scan) startPickerScan()
    }

    fun chooseDevice(device: PickableDevice) {
        val id = pickerTargetId ?: return
        updateTarget(id) {
            it.copy(
                btSelector = device.address,
                label = it.label.ifBlank { device.name.orEmpty() },
            )
        }
        discovery.stop()
        pickerTargetId = null
        pickerScanning = false
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
                text = "When Android is on no Wi-Fi, it connects Bluetooth to the first eligible " +
                    "iPhone. That iPhone's shortcut turns Personal Hotspot on, then this app " +
                    "joins the hotspot, retrying until it works before moving on to the next iPhone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            rows.forEachIndexed { index, target ->
                TargetCard(
                    index = index,
                    total = rows.size,
                    target = target,
                    currentSsid = wifiAssociation.ssid,
                    wifiAssociated = wifiAssociation.associated,
                    revealed = target.id in revealed,
                    onToggleReveal = {
                        revealed = if (target.id in revealed) revealed - target.id else revealed + target.id
                    },
                    onChange = { transform -> updateTarget(target.id, transform) },
                    onMoveUp = { moveTarget(target.id, -1) },
                    onMoveDown = { moveTarget(target.id, +1) },
                    onDelete = { rows = rows.filterNot { it.id == target.id } },
                    onPickDevice = { openPicker(target.id, scan = false) },
                    onScanNearby = { openPicker(target.id, scan = true) },
                    onUseCurrentSsid = {
                        wifiAssociation.ssid?.let { ssid ->
                            updateTarget(target.id) { it.copy(ssid = ssid) }
                        }
                    },
                )
            }

            OutlinedButton(
                onClick = { rows = rows + IphoneTarget.empty() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add iPhone") }

            OutlinedTextField(
                value = rotateAfter,
                onValueChange = { rotateAfter = it.filter(Char::isDigit) },
                label = { Text("Move to the next iPhone after N failed attempts") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        "${AppSettings.MIN_ROTATE_AFTER_ATTEMPTS}–" +
                            "${AppSettings.MAX_ROTATE_AFTER_ATTEMPTS}. Set 1 for round-robin; " +
                            "higher values give the first iPhone a longer turn (priority).",
                    )
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
                Button(onClick = { persist(); Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show() }, enabled = dirty) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = { permissionLauncher.launch(requiredPermissions()) },
                ) { Text("Grant permissions") }
            }

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
                onClick = {
                    val selector = rows.firstOrNull { it.enabled && it.btSelector.isNotBlank() }
                        ?.btSelector
                        ?: rows.firstOrNull { it.btSelector.isNotBlank() }?.btSelector
                    if (selector.isNullOrBlank()) {
                        Toast.makeText(
                            context,
                            "Add an iPhone entry with its Bluetooth name or MAC first",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@OutlinedButton
                    }
                    if (status.running) {
                        // The loop shares the single HID registration; its own
                        // trigger/release would tear the probe down mid-check.
                        Toast.makeText(
                            context,
                            "Stop the reconnect loop first — it can interrupt the pairing check",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@OutlinedButton
                    }
                    pairingCheckRunning = true
                    bluetooth.probePairing(selector) { connected ->
                        pairingCheckRunning = false
                        Toast.makeText(
                            context,
                            if (connected) {
                                "Keyboard link works — it was disconnected again so the iPhone keyboard stays visible"
                            } else {
                                "No keyboard link. If this iPhone was paired before keyboard mode: " +
                                    "Settings → Bluetooth → forget this phone, then pair again here."
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                enabled = !pairingCheckRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (pairingCheckRunning) "Checking pairing…" else "Check iPhone pairing")
            }

            FilledTonalButton(
                onClick = {
                    val stored = persist()
                    val wifi = AndroidWifiLink(context)
                    // Enabled only: a disabled entry must not win the per-SSID
                    // deduplication and overwrite an enabled target's password.
                    val complete = stored.targets.filter { it.enabled && it.isComplete }
                    if (complete.isEmpty()) {
                        Toast.makeText(
                            context,
                            "Add an SSID and password for at least one iPhone first",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@FilledTonalButton
                    }
                    // One suggestion per SSID: keep the highest-priority target,
                    // matching what the reconnect loop and SuggestionSync use.
                    val unique = complete.distinctBy { it.ssid }
                    val results = unique.map { it to wifi.ensureSuggestion(it.ssid, it.password) }
                    val failed = results
                        .filter { (_, outcome) ->
                            outcome == SuggestionOutcome.ERROR || outcome == SuggestionOutcome.REJECTED
                        }
                        .map { (target, _) -> target.displayName }
                    val registered = results.size - failed.size
                    val message = buildString {
                        append("Registered $registered of ${results.size} hotspot(s)")
                        if (failed.isNotEmpty()) {
                            append(" — failed: ${failed.joinToString(", ")}")
                            append(" (check SSID and WPA2 password)")
                        }
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow hotspot auto-join for all iPhones") }

            OutlinedButton(
                onClick = { openBatterySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow background battery use") }

            Button(
                onClick = {
                    if (status.running) {
                        ReconnectService.stop(context)
                    } else {
                        persist()
                        ReconnectService.start(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (status.running) "Stop" else "Start") }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "On each iPhone: pair with this phone, then create a Shortcuts automation " +
                    "whose trigger is Bluetooth → this phone connecting, running the \"Set Personal " +
                    "Hotspot: On\" shortcut. See the README for the exact steps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pickerTargetId?.let { targetId ->
        val target = rows.firstOrNull { it.id == targetId }
        DevicePickerDialog(
            title = if (pickerIsScan) {
                "Nearby devices${target?.let { " for ${it.displayName}" } ?: ""}"
            } else {
                "Paired devices${target?.let { " for ${it.displayName}" } ?: ""}"
            },
            devices = pickerDevices,
            scanning = pickerScanning,
            notice = pickerNotice,
            emptyHint = when {
                pickerIsScan ->
                    "No devices yet. Grant $scanPermissionName permission, make the iPhone discoverable, and keep this dialog open."

                // pairedDevices() returns empty without the permission, so do not
                // tell the user to pair an iPhone that is already bonded.
                !bluetooth.canReadPairedDevices() ->
                    "Bluetooth permission is missing, so paired devices can't be listed. Tap Grant permissions on the main screen, then reopen this picker."

                else -> "No paired devices. Pair the iPhone first (Pair with iPhone, keyboard mode)."
            },
            onSelect = ::chooseDevice,
            onScan = if (pickerScanning) {
                null
            } else {
                {
                    // Keep the paired list; nearby results are merged into it by
                    // startPickerScan, so a bonded iPhone that is not discoverable
                    // stays selectable.
                    pickerIsScan = true
                    startPickerScan()
                }
            },
            onDismiss = {
                discovery.stop()
                pickerScanning = false
                pickerTargetId = null
            },
        )
    }
}

@Composable
private fun TargetCard(
    index: Int,
    total: Int,
    target: IphoneTarget,
    currentSsid: String?,
    wifiAssociated: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onChange: ((IphoneTarget) -> IphoneTarget) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onPickDevice: () -> Unit,
    onScanNearby: () -> Unit,
    onUseCurrentSsid: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = target.enabled,
                    onCheckedChange = { enabled -> onChange { it.copy(enabled = enabled) } },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${index + 1}. ${target.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMoveUp, enabled = index > 0) { Text("↑") }
                TextButton(onClick = onMoveDown, enabled = index < total - 1) { Text("↓") }
                TextButton(onClick = onDelete) { Text("Remove") }
            }

            OutlinedTextField(
                value = target.label,
                onValueChange = { value -> onChange { it.copy(label = value) } },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = target.btSelector,
                onValueChange = { value -> onChange { it.copy(btSelector = value) } },
                label = { Text("iPhone Bluetooth name or MAC") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickDevice) { Text("Pick paired") }
                OutlinedButton(onClick = onScanNearby) { Text("Scan nearby") }
            }
            OutlinedTextField(
                value = target.ssid,
                onValueChange = { value -> onChange { it.copy(ssid = value) } },
                label = { Text("Hotspot SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onUseCurrentSsid, enabled = currentSsid != null) {
                Text(
                    currentSsid?.let { "Use current Wi-Fi: $it" }
                        ?: if (wifiAssociated) {
                            // Android redacts the name without Location permission.
                            "Use current Wi-Fi (name hidden — grant Location)"
                        } else {
                            "Use current Wi-Fi (not connected)"
                        },
                )
            }
            OutlinedTextField(
                value = target.password,
                onValueChange = { value -> onChange { it.copy(password = value) } },
                label = { Text("Hotspot password") },
                singleLine = true,
                visualTransformation = if (revealed) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = onToggleReveal) {
                        Text(if (revealed) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DevicePickerDialog(
    title: String,
    devices: List<PickableDevice>,
    scanning: Boolean,
    notice: String?,
    emptyHint: String,
    onSelect: (PickableDevice) -> Unit,
    onScan: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                // Shown next to the list as well: a failed or empty scan used to be
                // silent whenever paired devices were already listed.
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (devices.isEmpty()) {
                    Text(emptyHint, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(devices, key = { it.address }) { device ->
                            TextButton(
                                onClick = { onSelect(device) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(device.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = onScan?.let { scan -> { TextButton(onClick = scan) { Text("Scan nearby") } } },
    )
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
            if (status.running) {
                val details = buildList {
                    status.targetLabel?.let { add("Target: $it") }
                    if (status.failures > 0) add("Retries so far: ${status.failures}")
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

private fun requiredPermissions(): Array<String> {
    // Android 12+ ignores a fine-location request that is not paired with coarse.
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_CONNECT
        permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        permissions += Manifest.permission.BLUETOOTH_SCAN
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

/** Keeps unsaved target edits across activity recreation (rotation, process death). */
private val TargetsSaver = Saver<List<IphoneTarget>, String>(
    save = { TargetsCodec.encode(it) },
    restore = { TargetsCodec.decode(it) },
)

/**
 * Saves the form's synced baseline across activity recreation. Without it a
 * draft restored by [rememberSaveable] would be compared against the latest
 * persisted settings instead of the values it was edited from, making a
 * reverted edit look dirty and letting Start/Save overwrite an external change.
 */
private val SyncedSaver = Saver<AppSettings, String>(
    save = { settings ->
        JSONObject().apply {
            put("targets", TargetsCodec.encode(settings.targets))
            put("rotate", settings.rotateAfterAttempts)
            put("tick", settings.tickIntervalMs)
            put("boot", settings.autoStartOnBoot)
            put("service", settings.serviceEnabled)
        }.toString()
    },
    restore = { raw ->
        runCatching {
            val json = JSONObject(raw)
            AppSettings(
                targets = TargetsCodec.decode(json.getString("targets")),
                rotateAfterAttempts = json.getInt("rotate"),
                tickIntervalMs = json.getLong("tick"),
                autoStartOnBoot = json.getBoolean("boot"),
                serviceEnabled = json.getBoolean("service"),
            )
        }.getOrNull()
    },
)
