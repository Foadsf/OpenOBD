package io.github.foadsf.openobd.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.foadsf.openobd.BuildConfig
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.Session
import io.github.foadsf.openobd.obd.PROTOCOLS
import io.github.foadsf.openobd.obd.TestResult
import io.github.foadsf.openobd.obd.Units
import io.github.foadsf.openobd.obd.pidInfo
import kotlinx.coroutines.launch

/** Mode $01 values that describe the car rather than change while driving. */
private val STATUS_PIDS = listOf(0x1C, 0x51, 0xA6, 0x31, 0x4E, 0x30, 0x21, 0x4D, 0x1F)

@Composable
fun VehicleScreen(session: Session, r: Results, notify: (String) -> Unit, openConnect: () -> Unit) {
    val conn by session.conn.collectAsState()
    val voltage by session.voltage.collectAsState()
    val supported by session.supported.collectAsState()
    val ready = conn as? Conn.Ready
    val online = ready?.info?.carOnline == true
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(online) {
        if (!online || r.vehicle != null) return@LaunchedEffect
        loading = true
        session.request { o ->
            r.vehicle = o.vehicleInfo()
            r.status = STATUS_PIDS.filter { it in supported }.mapNotNull { pid ->
                o.readPid(pid)?.let { bytes -> val info = pidInfo(pid); pid to Units.show(info, info.decode(bytes)) }
            }
        }.onFailure { notify(it.message ?: "Read failed") }
        loading = false
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!online) item {
            Panel(Modifier.widthIn(max = 720.dp)) {
                Text("Connect to the car to read its VIN, control units and on-board test results.", color = Palette.muted)
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(onClick = openConnect) { Text("Connect") }
            }
        }
        if (online) {
            item { VinCard(r.vehicle?.vin, loading) }
            if (r.status.isNotEmpty()) {
                item { SectionHeader("Status", Modifier.widthIn(max = 720.dp)) }
                item {
                    Panel(Modifier.widthIn(max = 720.dp)) {
                        r.status.forEach { (pid, text) -> KeyValue(pidInfo(pid).name, text) }
                    }
                }
            }
            r.vehicle?.ecus?.takeIf { it.isNotEmpty() }?.let { ecus ->
                item { SectionHeader("Control units · ${ecus.size}", Modifier.widthIn(max = 720.dp)) }
                ecus.forEach { e ->
                    item {
                        Panel(Modifier.widthIn(max = 720.dp)) {
                            Text(e.name ?: "ECU ${e.ecu}", style = MaterialTheme.typography.titleMedium)
                            KeyValue("Address", e.ecu, mono = true)
                            e.calibrations.forEach { KeyValue("Calibration ID", it, mono = true) }
                            e.cvns.forEach { KeyValue("Calibration check (CVN)", it, mono = true) }
                        }
                    }
                }
            }
            item {
                SectionHeader("On-board monitor tests", Modifier.widthIn(max = 720.dp)) {
                    if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            item {
                TestsCard(r.tests, r.testsRan, testing) {
                    testing = true
                    scope.launch {
                        session.request { r.tests = it.tests() }.onFailure { notify(it.message ?: "Test read failed") }
                        r.testsRan = true
                        testing = false
                    }
                }
            }
        }
        item { SectionHeader("Adapter", Modifier.widthIn(max = 720.dp)) }
        item { AdapterCard(session, ready, voltage) }
        item { SectionHeader("Settings", Modifier.widthIn(max = 720.dp)) }
        item { SettingsCard(session) }
        item {
            Text(
                "OpenOBD ${BuildConfig.VERSION_NAME} · free software under GPL-3.0 · reads standard OBD-II only",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun VinCard(vin: String?, loading: Boolean) {
    val context = LocalContext.current
    Panel(Modifier.widthIn(max = 720.dp)) {
        Text("VIN", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp), color = Palette.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    vin != null -> vin
                    loading -> "Reading…"
                    else -> "Not reported"
                },
                style = MaterialTheme.typography.headlineSmall.merge(Numbers).copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f),
            )
            if (vin != null) IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("VIN", vin))
            }) { Icon(Icons.Copy, "Copy VIN", tint = Palette.muted) }
        }
    }
}

@Composable
private fun TestsCard(tests: List<TestResult>?, ran: Boolean, running: Boolean, onRun: () -> Unit) {
    Panel(Modifier.widthIn(max = 720.dp)) {
        when {
            !ran -> {
                Text(
                    "The car's own self-tests of catalyst, oxygen sensors, EGR, misfire and more, with the limits it judges them against (service \$06).",
                    color = Palette.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(12.dp))
                FilledTonalButton(onClick = onRun, enabled = !running) { Text("Read test results") }
            }
            tests == null -> Text("This car's bus (pre-CAN) reports tests in a format OpenOBD does not decode. Use the Terminal with service 06 to see raw results.", color = Palette.muted)
            tests.isEmpty() -> Text("The car reported no test results.", color = Palette.muted)
            else -> {
                val failed = tests.count { !it.passed }
                Text(
                    if (failed == 0) "All ${tests.size} tests within limits" else "$failed of ${tests.size} outside limits",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (failed == 0) Palette.emerald else Palette.crimson,
                )
                tests.groupBy { it.mid }.forEach { (_, group) ->
                    Spacer(Modifier.size(10.dp))
                    Text(group.first().midName, style = MaterialTheme.typography.labelLarge, color = Palette.sky)
                    group.forEach { t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.tidName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${t.show(t.value)}   (limits ${t.show(t.min)} … ${t.show(t.max)})",
                                    style = MaterialTheme.typography.bodySmall.merge(Numbers),
                                    color = Palette.muted,
                                )
                            }
                            Pill(if (t.passed) "Pass" else "Fail", if (t.passed) Palette.emerald else Palette.crimson)
                        }
                    }
                }
                TextButton(onClick = onRun, enabled = !running) { Text("Read again") }
            }
        }
    }
}

@Composable
private fun AdapterCard(session: Session, ready: Conn.Ready?, voltage: String?) {
    Panel(Modifier.widthIn(max = 720.dp)) {
        if (ready == null) {
            Text("Not connected", color = Palette.muted)
            return@Panel
        }
        val i = ready.info
        KeyValue("Device", i.target.name)
        KeyValue("Link", i.target.kind.label)
        if (i.target.address != "demo") KeyValue("Address", i.target.address, mono = true)
        KeyValue("Chip", i.version)
        i.description?.let { KeyValue("Description", it) }
        i.layout?.let { KeyValue("GATT service", it, mono = true) }
        KeyValue("Protocol", if (i.carOnline) i.protocol else "—")
        voltage?.let { KeyValue("Battery (at OBD port)", it) }
    }
}

@Composable
private fun SettingsCard(session: Session) {
    var protocol by remember { mutableIntStateOf(session.prefs.protocol) }
    var choosing by remember { mutableStateOf(false) }
    Panel(Modifier.widthIn(max = 720.dp), padding = 0) {
        Row(
            Modifier.fillMaxWidth().clickable { choosing = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Protocol")
                Text(PROTOCOLS.first { it.first == protocol }.second, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
        }
        HorizontalDivider(color = Palette.outline, modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().clickable {
                Units.imperial = !Units.imperial
                session.prefs.imperial = Units.imperial
            }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Imperial units")
                Text("mph, °F, miles, psi", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
            }
            Switch(
                checked = Units.imperial,
                onCheckedChange = {
                    Units.imperial = it
                    session.prefs.imperial = it
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.sky),
            )
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            containerColor = Palette.surface,
            title = { Text("Protocol") },
            text = {
                Column {
                    Text(
                        "Automatic suits almost every car. Applies on the next connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                    )
                    Spacer(Modifier.size(8.dp))
                    PROTOCOLS.forEach { (n, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                protocol = n
                                session.prefs.protocol = n
                                choosing = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = protocol == n, onClick = null, modifier = Modifier.padding(10.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}
