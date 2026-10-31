package io.github.foadsf.openobd.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.Session
import io.github.foadsf.openobd.obd.Dtc
import io.github.foadsf.openobd.obd.DtcKind
import io.github.foadsf.openobd.obd.Readiness
import io.github.foadsf.openobd.obd.Units
import kotlinx.coroutines.launch

private suspend fun scan(session: Session, r: Results): String? {
    val supported = session.supported.value
    val result = session.request { o ->
        r.readiness = o.readiness()
        r.readinessDrive = if (0x41 in supported) o.readiness(thisDrive = true) else null
        val codes = mutableListOf<Dtc>()
        var error: String? = null
        for (kind in DtcKind.entries) {
            val (found, e) = o.readDtcs(kind)
            codes += found
            if (kind == DtcKind.STORED) error = e
        }
        r.codes = codes
        r.codesError = error
        r.freeze = o.freezeFrame()
        r.scanned = true
    }
    return result.exceptionOrNull()?.message
}

@Composable
fun CodesScreen(session: Session, r: Results, notify: (String) -> Unit, openConnect: () -> Unit) {
    val conn by session.conn.collectAsState()
    val ready = (conn as? Conn.Ready)?.info?.carOnline == true
    if (!ready) {
        EmptyState(
            Icons.Warning, "Trouble codes",
            "Connect to the car to read and clear check-engine codes, see freeze-frame data and emissions readiness.",
            "Connect", openConnect,
        )
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun rescan() {
        if (busy) return
        busy = true
        scope.launch {
            scan(session, r)?.let { notify(it) }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (!r.scanned) rescan() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Summary(r, busy, onScan = ::rescan) {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OpenOBD report")
                    putExtra(Intent.EXTRA_TEXT, report(r))
                }, "Share report"))
            }
        }
        r.codesError?.let { e ->
            item { Panel(Modifier.widthIn(max = 720.dp)) { Text("The car answered: $e", style = Mono, color = Palette.amber) } }
        }
        for (kind in DtcKind.entries) {
            val list = r.codes.filter { it.kind == kind }
            if (list.isEmpty()) continue
            item { SectionHeader("${kind.title} · ${list.size}", Modifier.widthIn(max = 720.dp)) }
            item {
                Text(kind.note, style = MaterialTheme.typography.bodySmall, color = Palette.muted, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 4.dp))
            }
            items(list, key = { "${it.kind}-${it.ecu}-${it.code}" }) { CodeCard(it) }
        }
        r.freeze?.let { ff ->
            item { SectionHeader("Freeze frame", Modifier.widthIn(max = 720.dp)) }
            item {
                Panel(Modifier.widthIn(max = 720.dp)) {
                    Text(
                        ff.dtc?.let { "Conditions when $it was recorded" } ?: "Conditions when the fault was recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.muted,
                    )
                    Spacer(Modifier.size(4.dp))
                    ff.values.forEach { (pid, reading) -> KeyValue(pid.name, Units.show(pid, reading)) }
                }
            }
        }
        r.readiness?.let { rd ->
            item { SectionHeader("Emissions readiness", Modifier.widthIn(max = 720.dp)) }
            item { ReadinessCard(rd, r.readinessDrive) }
        }
        if (r.scanned) item {
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.crimson),
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 8.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Clear codes and reset check-engine light")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Palette.surface,
            title = { Text("Clear trouble codes?") },
            text = {
                Text(
                    "This erases stored and pending codes and the freeze frame, and turns the check-engine light off.\n\n" +
                        "• It does not fix anything: the light returns if the fault is still there.\n" +
                        "• Readiness monitors reset, so the car may fail an emissions inspection until it has been driven enough to re-test.\n" +
                        "• Permanent codes stay until the car re-tests and passes.\n\n" +
                        "Ignition ON, engine OFF.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClear = false
                        busy = true
                        scope.launch {
                            val outcome = session.request { it.clearDtcs() }
                            val reason = outcome.getOrElse { it.message }
                            scan(session, r)
                            busy = false
                            notify(reason ?: "Codes cleared. ${r.codes.count { it.kind != DtcKind.PERMANENT }} remain.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.crimson),
                ) { Text("Clear codes") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Summary(r: Results, busy: Boolean, onScan: () -> Unit, onShare: () -> Unit) {
    val rd = r.readiness
    val active = r.codes.count { it.kind != DtcKind.PERMANENT }
    val (color, title, body) = when {
        !r.scanned -> Triple(Palette.muted, "Reading the car…", "Trouble codes, freeze frame and readiness.")
        rd?.milOn == true -> Triple(Palette.crimson, "Check-engine light on", plural(rd.dtcCount, "confirmed code"))
        active > 0 -> Triple(Palette.amber, "Light off, codes present", plural(active, "code") + " found")
        else -> Triple(Palette.emerald, "No active faults", "No stored or pending trouble codes.")
    }
    Panel(Modifier.widthIn(max = 720.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color, 12)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else {
                if (r.scanned) IconButton(onClick = onShare) { Icon(Icons.Share, "Share report", tint = Palette.muted) }
                IconButton(onClick = onScan) { Icon(Icons.Refresh, "Scan again", tint = Palette.sky) }
            }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
private fun CodeCard(dtc: Dtc) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val tint = when (dtc.kind) {
        DtcKind.STORED -> Palette.crimson
        DtcKind.PENDING -> Palette.amber
        DtcKind.PERMANENT -> Palette.muted
    }
    Panel(Modifier.widthIn(max = 720.dp), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dtc.code, style = MaterialTheme.typography.titleLarge.merge(Numbers), color = tint, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(14.dp))
            Text(dtc.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp)) {
                Text(dtc.area, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                Text("Reported by ECU ${dtc.ecu}", style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = {
                    val q = Uri.encode("${dtc.code} OBD-II code causes")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://duckduckgo.com/?q=$q".toUri())) }
                }) {
                    Icon(Icons.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Look up ${dtc.code}")
                }
            }
        }
    }
}

@Composable
private fun ReadinessCard(since: Readiness, drive: Readiness?) {
    var thisDrive by remember { mutableStateOf(false) }
    val rd = if (thisDrive && drive != null) drive else since
    Panel(Modifier.widthIn(max = 720.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (rd.incomplete == 0) "All supported monitors complete" else "${rd.incomplete} not complete yet",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (drive != null) {
                TextButton(onClick = { thisDrive = !thisDrive }) { Text(if (thisDrive) "This drive" else "Since clear") }
            }
        }
        Text(
            (if (rd.compressionIgnition) "Diesel" else "Petrol") + " monitors · " +
                if (thisDrive && drive != null) "this drive cycle" else "since codes were last cleared",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.muted,
        )
        Spacer(Modifier.size(8.dp))
        rd.monitors.sortedBy { !it.supported }.forEach { m ->
            val (icon, color, state) = when {
                !m.supported -> Triple(Icons.Dash, Palette.outline, "Not used")
                m.complete -> Triple(Icons.Check, Palette.emerald, "Complete")
                else -> Triple(Icons.Pending, Palette.amber, "Incomplete")
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Text(m.name, color = if (m.supported) Palette.text else Palette.muted, modifier = Modifier.padding(start = 12.dp).weight(1f))
                Text(state, style = MaterialTheme.typography.bodySmall, color = if (m.supported) color else Palette.muted)
            }
        }
    }
}

private fun report(r: Results): String = buildString {
    appendLine("OpenOBD diagnostic report")
    r.vehicle?.vin?.let { appendLine("VIN: $it") }
    r.readiness?.let { appendLine("Check-engine light: ${if (it.milOn) "ON" else "off"} (${it.dtcCount} confirmed)") }
    for (kind in DtcKind.entries) {
        val list = r.codes.filter { it.kind == kind }
        appendLine()
        appendLine("${kind.title} codes (service ${kind.service}): ${if (list.isEmpty()) "none" else ""}")
        list.forEach { appendLine("  ${it.code}  ${it.description}  [ECU ${it.ecu}]") }
    }
    r.freeze?.let { ff ->
        appendLine()
        appendLine("Freeze frame${ff.dtc?.let { " ($it)" } ?: ""}:")
        ff.values.forEach { (pid, v) -> appendLine("  ${pid.name}: ${Units.show(pid, v)}") }
    }
    r.readiness?.let { rd ->
        appendLine()
        appendLine("Readiness (since codes cleared):")
        rd.monitors.filter { it.supported }.forEach { appendLine("  ${it.name}: ${if (it.complete) "complete" else "INCOMPLETE"}") }
    }
}
