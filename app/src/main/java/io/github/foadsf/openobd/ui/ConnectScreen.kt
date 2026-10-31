package io.github.foadsf.openobd.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.LinkKind
import io.github.foadsf.openobd.Session
import io.github.foadsf.openobd.Target
import kotlinx.coroutines.delay

private fun permissionsNeeded(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(context: Context) = permissionsNeeded().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Android 11 and older deliver no BLE scan results while Location is switched off. */
private fun locationBlocksScan(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 31) return false
    val lm = context.getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled
    else !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

private val OBD_NAMES = listOf("VEEPEAK", "OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "OBDLINK")

private fun looksLikeObd(name: String) = OBD_NAMES.any { name.uppercase().contains(it) }

private data class Found(val target: Target, val rssi: Int?)

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(session: Session, conn: Conn) {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    var btOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    var locationOff by remember { mutableStateOf(locationBlocksScan(context)) }
    var scanRound by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    val nearby = remember { mutableStateMapOf<String, Found>() }

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(context)
        locationOff = locationBlocksScan(context)
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = adapter?.isEnabled == true
    }
    val openLocation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        locationOff = locationBlocksScan(context)
        scanRound++
    }

    // Track Bluetooth being switched on or off from the quick settings.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                btOn = adapter?.isEnabled == true
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val busy = conn is Conn.Busy
    LaunchedEffect(scanRound, granted, btOn, busy) {
        if (!granted || !btOn || busy || adapter == null) return@LaunchedEffect
        val scanner = adapter.bluetoothLeScanner ?: return@LaunchedEffect
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                if (name.isNullOrBlank()) return
                nearby[result.device.address] = Found(Target(LinkKind.BLE, result.device.address, name), result.rssi)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        try {
            scanner.startScan(null, settings, callback)
            delay(15_000)
        } finally {
            runCatching { scanner.stopScan(callback) }
            scanning = false
        }
    }

    val paired = remember(granted, btOn) {
        if (!granted || !btOn) emptyList()
        else runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { Found(Target(LinkKind.CLASSIC, it.address, it.name ?: it.address), null) }
            .sortedByDescending { looksLikeObd(it.target.name) }
    }
    val nearbySorted = nearby.values.sortedWith(compareByDescending<Found> { looksLikeObd(it.target.name) }.thenByDescending { it.rssi ?: -200 })
    val last = session.prefs.lastTarget

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text("Connect your adapter", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(12.dp))
                Step(1, "Plug the adapter into the OBD-II port. Its blue light turns on.")
                Step(2, "Switch the ignition on. The engine can stay off.")
                Step(3, "Pick the adapter below. Bluetooth LE needs no pairing.")
            }
        }
        if (conn is Conn.Failed) item {
            Panel(Modifier.widthIn(max = 640.dp)) {
                Text("Couldn't connect", style = MaterialTheme.typography.titleMedium, color = Palette.crimson)
                Spacer(Modifier.size(4.dp))
                Text(conn.message, color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Check the blue light, close other OBD apps (the adapter serves one app at a time), and keep the phone close.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                )
            }
        }
        if (last != null && granted && (btOn || last.kind == LinkKind.DEMO)) item {
            Button(
                onClick = { session.connect(last) },
                enabled = !busy,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) { Text("Connect to ${last.name}") }
        }
        when {
            adapter == null -> item { Notice("This device has no Bluetooth.", null) {} }
            !granted -> item {
                Notice(
                    if (Build.VERSION.SDK_INT >= 31) "Allow \"Nearby devices\" so OpenOBD can find and talk to the adapter."
                    else "Allow location access. Android 11 and older require it for Bluetooth LE scanning; OpenOBD never reads your location.",
                    "Allow",
                ) { askPermissions.launch(permissionsNeeded()) }
            }
            !btOn -> item {
                Notice("Bluetooth is off.", "Turn on") { askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
            else -> {
                if (locationOff) item {
                    Notice("Switch Location on to see Bluetooth LE devices (Android 11 and older).", "Settings") {
                        openLocation.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
                item {
                    SectionHeader("Nearby · Bluetooth LE", Modifier.widthIn(max = 640.dp)) {
                        if (scanning) CircularProgressIndicator(Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { scanRound++ }) { Icon(Icons.Refresh, "Scan again", tint = Palette.muted) }
                    }
                }
                if (nearbySorted.isEmpty()) item {
                    Text(
                        if (scanning) "Looking for adapters…" else "Nothing found. Is the adapter's light on?",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 4.dp),
                    )
                }
                items(nearbySorted, key = { "ble-" + it.target.address }) { f ->
                    DeviceRow(f, enabled = !busy) { session.connect(f.target) }
                }
                if (paired.isNotEmpty()) {
                    item { SectionHeader("Paired · Bluetooth Classic", Modifier.widthIn(max = 640.dp)) }
                    items(paired, key = { "classic-" + it.target.address }) { f ->
                        DeviceRow(f, enabled = !busy) { session.connect(f.target) }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { session.connect(Target(LinkKind.DEMO, "demo", "Demo car")) }, enabled = !busy) {
                Text("No adapter? Try the demo")
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill("$n", Palette.sky)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Panel(Modifier.widthIn(max = 640.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun DeviceRow(found: Found, enabled: Boolean, onClick: () -> Unit) {
    val t = found.target
    val likely = looksLikeObd(t.name)
    Panel(Modifier.widthIn(max = 640.dp), onClick = if (enabled) onClick else null, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Bluetooth, null, tint = if (likely) Palette.sky else Palette.muted)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (likely) FontWeight.SemiBold else null)
                Text(
                    t.address + (found.rssi?.let { " · $it dBm" } ?: ""),
                    style = MaterialTheme.typography.bodySmall.merge(Numbers),
                    color = Palette.muted,
                )
            }
            if (likely) Pill("OBD", Palette.emerald)
        }
    }
}
