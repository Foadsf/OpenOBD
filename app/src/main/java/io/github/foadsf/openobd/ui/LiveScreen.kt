package io.github.foadsf.openobd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.foadsf.openobd.AdapterInfo
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.Session
import io.github.foadsf.openobd.obd.Units
import io.github.foadsf.openobd.obd.pidInfo
import kotlinx.coroutines.delay

@Composable
fun LiveScreen(session: Session) {
    val conn by session.conn.collectAsState()
    when (val c = conn) {
        is Conn.Ready -> if (c.info.carOnline) Dashboard(session) else CarSilent(session, c.info)
        else -> ConnectScreen(session, c)
    }
}

@Composable
private fun CarSilent(session: Session, info: AdapterInfo) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text("The adapter is ready, but the car isn't answering", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            info.carError?.let {
                Text("Adapter says: $it", style = Mono, color = Palette.amber)
                Spacer(Modifier.size(8.dp))
            }
            Text(
                "• Switch the ignition on (or start the engine).\n" +
                    "• Push the adapter firmly into the port.\n" +
                    "• The car must be OBD-II/EOBD compliant (EU petrol 2001+, diesel 2004+; US 1996+).\n" +
                    "• If you chose a protocol under Vehicle › Adapter, try Automatic.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.muted,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = session::retryCar) { Text("Try again") }
        }
    }
}

@Composable
private fun Dashboard(session: Session) {
    val supported by session.supported.collectAsState()
    val readings by session.readings.collectAsState()
    val recording by session.recording.collectAsState()
    var showAll by rememberSaveable { mutableStateOf(false) }
    var dashboard by remember { mutableStateOf(session.prefs.dashboard) }
    var picking by remember { mutableStateOf<Int?>(null) }

    val gaugeable = supported.filter { pidInfo(it).gaugeable }
    // Keep the user's choices where the car supports them; fill gaps with what it does support.
    val slots = remember(dashboard, supported) {
        val chosen = dashboard.filter { it in gaugeable }.toMutableList()
        for (p in gaugeable) if (chosen.size < 4 && p !in chosen) chosen += p
        chosen.take(4)
    }
    val watched = if (showAll) supported else slots
    DisposableEffect(watched) {
        session.watch(watched)
        onDispose { session.watch(emptyList()) }
    }

    BoxWithConstraints {
        val columns = if (maxWidth >= 560.dp) 4 else 2
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf("Gauges", "All sensors (${supported.size})").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = showAll == (i == 1),
                                onClick = { showAll = i == 1 },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = Palette.surfaceHigh, activeContentColor = Palette.sky, inactiveContainerColor = Palette.background),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
            if (!showAll) {
                if (slots.isEmpty()) item {
                    Text("This car reports no numeric live values.", color = Palette.muted)
                }
                items(slots.chunked(columns)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { pid ->
                            Gauge(pidInfo(pid), readings[pid], Modifier.weight(1f)) { picking = slots.indexOf(pid) }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item {
                    Text(
                        "Tap a gauge to choose what it shows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                item {
                    Panel(padding = 0) {
                        supported.forEachIndexed { i, pid ->
                            val info = pidInfo(pid)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(info.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(
                                    readings[pid]?.let { Units.show(info, it) } ?: "…",
                                    style = MaterialTheme.typography.bodyMedium.merge(Numbers),
                                    color = Palette.sky,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(0.9f),
                                )
                            }
                            if (i < supported.lastIndex) HorizontalDivider(color = Palette.outline, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
            item { RecordButton(session, recording != null) }
        }
    }

    picking?.let { slot ->
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = { TextButton(onClick = { picking = null }) { Text("Close") } },
            title = { Text("Show on this gauge") },
            containerColor = Palette.surface,
            text = {
                LazyColumn {
                    items(gaugeable) { pid ->
                        val info = pidInfo(pid)
                        Text(
                            info.name + if (info.unit.isNotEmpty()) "  ·  ${Units.convert(0.0, info.unit).second}" else "",
                            color = if (pid in slots) Palette.sky else Palette.text,
                            modifier = Modifier.fillMaxWidth().clickable {
                                val next = slots.toMutableList()
                                val existing = next.indexOf(pid)
                                if (existing >= 0) next[existing] = next[slot] // swap rather than duplicate
                                if (slot < next.size) next[slot] = pid else next += pid
                                dashboard = next
                                session.prefs.dashboard = next
                                picking = null
                            }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun RecordButton(session: Session, active: Boolean) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        seconds = 0
        while (active) {
            delay(1_000)
            seconds++
        }
    }
    if (active) {
        Button(
            onClick = session::stopRecording,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.crimson),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Icon(Icons.Stop, null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Stop recording · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
        }
    } else {
        FilledTonalButton(
            onClick = session::startRecording,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            Icon(Icons.Record, null, Modifier.size(14.dp), tint = Palette.crimson)
            Spacer(Modifier.size(8.dp))
            Text("Record a CSV log of what's on screen")
        }
    }
}
