package io.github.foadsf.openobd.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.Session

/** Common commands, one tap away. Everything else can be typed. */
private val QUICK = listOf("ATI", "ATRV", "ATDP", "AT@1", "ATCS", "0100", "0101", "03", "07", "0A", "0902", "0600")

@Composable
fun TerminalScreen(session: Session, openConnect: () -> Unit) {
    val conn by session.conn.collectAsState()
    if (conn !is Conn.Ready) {
        EmptyState(
            Icons.Terminal, "Adapter terminal",
            "Talk to the ELM327 directly with AT commands and raw OBD requests, including manufacturer-specific services. Connect first.",
            "Connect", openConnect,
        )
        return
    }
    val lines by session.terminal.collectAsState()
    var input by remember { mutableStateOf("") }
    var traffic by remember { mutableStateOf(session.logTraffic) }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

    fun send(text: String) {
        session.sendRaw(text)
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Show polling traffic",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.muted,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = traffic,
                onCheckedChange = {
                    traffic = it
                    session.logTraffic = it
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.sky),
            )
            IconButton(onClick = session::clearTerminal) { Icon(Icons.Delete, "Clear", tint = Palette.muted) }
        }
        Panel(Modifier.weight(1f).padding(horizontal = 16.dp), padding = 0) {
            if (lines.isEmpty()) {
                Text(
                    "Answers appear here. Settings you change with AT commands are restored automatically before the app's next own request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.muted,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(state = list, contentPadding = PaddingValues(14.dp), modifier = Modifier.fillMaxSize()) {
                items(lines) { l ->
                    Text(
                        if (l.sent) "> ${l.text}" else l.text,
                        style = Mono,
                        color = if (l.sent) Palette.sky else Palette.text,
                        modifier = Modifier.padding(top = if (l.sent) 8.dp else 0.dp),
                    )
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QUICK.forEach { cmd ->
                AssistChip(
                    onClick = { send(cmd) },
                    label = { Text(cmd, style = Mono) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Palette.surface),
                )
            }
        }
        Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.uppercase() },
                placeholder = { Text("e.g. ATSH7E0 or 22F190", style = Mono) },
                textStyle = Mono,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { send(input) }) { Icon(Icons.Send, "Send", tint = Palette.sky) }
        }
    }
}
