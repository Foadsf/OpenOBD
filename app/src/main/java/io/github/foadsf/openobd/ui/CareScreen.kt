package io.github.foadsf.openobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.foadsf.openobd.CareItem
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.Garage
import io.github.foadsf.openobd.Session
import io.github.foadsf.openobd.garageFromJson
import io.github.foadsf.openobd.grouped
import io.github.foadsf.openobd.obd.pidInfo
import io.github.foadsf.openobd.status
import io.github.foadsf.openobd.toJson
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private fun statusColor(fraction: Double?) = when {
    fraction == null -> Palette.muted
    fraction <= 0 -> Palette.crimson
    fraction <= 0.2 -> Palette.amber
    else -> Palette.emerald
}

@Composable
fun CareScreen(session: Session, notify: (String) -> Unit) {
    var garage by remember { mutableStateOf(garageFromJson(session.prefs.maintenance)) }
    var editing by remember { mutableStateOf<CareItem?>(null) }
    var editingOdo by remember { mutableStateOf(false) }
    val conn by session.conn.collectAsState()
    val supported by session.supported.collectAsState()
    val scope = rememberCoroutineScope()
    val canReadOdo = (conn as? Conn.Ready)?.info?.carOnline == true && 0xA6 in supported
    val now = System.currentTimeMillis()

    fun save(g: Garage) {
        garage = g
        session.prefs.maintenance = g.toJson()
    }

    val sorted = garage.items.sortedBy { it.status(garage.odometerKm, now).fraction ?: Double.MAX_VALUE }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Panel(Modifier.widthIn(max = 720.dp)) {
                Text("Odometer", style = MaterialTheme.typography.labelLarge, color = Palette.muted)
                Text(
                    garage.odometerKm?.let { "${grouped(it)} km" } ?: "Not set",
                    style = MaterialTheme.typography.headlineMedium.merge(Numbers),
                )
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { editingOdo = true }) { Text("Update") }
                    if (canReadOdo) OutlinedButton(onClick = {
                        scope.launch {
                            session.request { it.readPid(0xA6) }.getOrNull()?.let { bytes ->
                                val km = pidInfo(0xA6).decode(bytes).value?.roundToInt()
                                if (km != null) {
                                    save(garage.copy(odometerKm = km))
                                    notify("Odometer read from the car: ${grouped(km)} km")
                                }
                            } ?: notify("The car did not report its odometer")
                        }
                    }) { Text("Read from car") }
                }
            }
        }
        item {
            SectionHeader("Service schedule", Modifier.widthIn(max = 720.dp)) {
                TextButton(onClick = { editing = CareItem(-1, "", null, 12) }) {
                    Icon(Icons.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Add")
                }
            }
        }
        items(sorted, key = { it.id }) { item ->
            val st = item.status(garage.odometerKm, now)
            val color = statusColor(st.fraction)
            Panel(Modifier.widthIn(max = 720.dp), onClick = { editing = item }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(intervalText(item), style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                Spacer(Modifier.size(10.dp))
                LinearProgressIndicator(
                    progress = { (st.fraction ?: 0.0).toFloat().coerceIn(0f, 1f) },
                    color = color,
                    trackColor = Palette.outline,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.size(8.dp))
                Text(st.text, style = MaterialTheme.typography.bodySmall.merge(Numbers), color = color)
            }
        }
        item {
            Text(
                "Intervals are generic starting points. Your owner's manual is the authority for your car.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.muted,
                modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 4.dp),
            )
        }
    }

    if (editingOdo) {
        NumberDialog("Odometer (km)", garage.odometerKm, onDismiss = { editingOdo = false }) { km ->
            save(garage.copy(odometerKm = km))
            editingOdo = false
        }
    }
    editing?.let { item ->
        ItemDialog(
            item = item,
            odometerKm = garage.odometerKm,
            onDismiss = { editing = null },
            onDelete = {
                save(garage.copy(items = garage.items.filterNot { it.id == item.id }))
                editing = null
            },
            onSave = { updated ->
                val items = if (item.id < 0) garage.items + updated.copy(id = (garage.items.maxOfOrNull { it.id } ?: 0) + 1)
                else garage.items.map { if (it.id == item.id) updated else it }
                save(garage.copy(items = items))
                editing = null
            },
        )
    }
}

private fun intervalText(i: CareItem) = listOfNotNull(
    i.everyKm?.let { "${grouped(it)} km" },
    i.everyMonths?.let { if (it % 12 == 0) "${it / 12} yr" else "$it mo" },
).joinToString(" / ").ifEmpty { "no interval" }

@Composable
private fun NumberDialog(title: String, initial: Int?, onDismiss: () -> Unit, onSave: (Int?) -> Unit) {
    var text by remember { mutableStateOf(initial?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(7) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { Button(onClick = { onSave(text.toIntOrNull()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDialog(
    item: CareItem,
    odometerKm: Int?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (CareItem) -> Unit,
) {
    var name by remember { mutableStateOf(item.name) }
    var everyKm by remember { mutableStateOf(item.everyKm?.toString().orEmpty()) }
    var everyMonths by remember { mutableStateOf(item.everyMonths?.toString().orEmpty()) }
    var lastKm by remember { mutableStateOf(item.lastKm?.toString().orEmpty()) }
    var lastMillis by remember { mutableStateOf(item.lastMillis) }
    var pickDate by remember { mutableStateOf(false) }
    val digits: (String) -> String = { it.filter(Char::isDigit).take(7) }

    fun current() = item.copy(
        name = name.trim().ifEmpty { "Service item" },
        everyKm = everyKm.toIntOrNull(),
        everyMonths = everyMonths.toIntOrNull(),
        lastKm = lastKm.toIntOrNull(),
        lastMillis = lastMillis,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text(if (item.id < 0) "New service item" else item.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        everyKm, { everyKm = digits(it) }, label = { Text("Every km") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        everyMonths, { everyMonths = digits(it).take(3) }, label = { Text("Every months") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
                Text("Last done", style = MaterialTheme.typography.labelLarge, color = Palette.muted, modifier = Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        lastKm, { lastKm = digits(it) }, label = { Text("At km") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                        Text(lastMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "Pick date", maxLines = 1)
                    }
                }
                FilledTonalButton(
                    onClick = { onSave(current().copy(lastKm = odometerKm ?: lastKm.toIntOrNull(), lastMillis = System.currentTimeMillis())) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done today" + (odometerKm?.let { " at ${grouped(it)} km" } ?: "")) }
                if (item.id >= 0) TextButton(onClick = onDelete) { Text("Delete item", color = Palette.crimson) }
            }
        },
        confirmButton = { Button(onClick = { onSave(current()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = lastMillis ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    lastMillis = state.selectedDateMillis
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
