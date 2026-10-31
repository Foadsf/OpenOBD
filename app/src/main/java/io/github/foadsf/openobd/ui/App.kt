package io.github.foadsf.openobd.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.foadsf.openobd.Conn
import io.github.foadsf.openobd.Session
import io.github.foadsf.openobd.obd.Dtc
import io.github.foadsf.openobd.obd.FreezeFrame
import io.github.foadsf.openobd.obd.Readiness
import io.github.foadsf.openobd.obd.TestResult
import io.github.foadsf.openobd.obd.VehicleInfo
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Speed),
    CODES("Codes", Icons.Warning),
    VEHICLE("Vehicle", Icons.Car),
    CARE("Care", Icons.Wrench),
    TERMINAL("Terminal", Icons.Terminal),
}

/** Results that should survive switching tabs, cleared when the connection changes. */
class Results {
    var scanned by mutableStateOf(false)
    var codes by mutableStateOf<List<Dtc>>(emptyList())
    var codesError by mutableStateOf<String?>(null)
    var readiness by mutableStateOf<Readiness?>(null)
    var readinessDrive by mutableStateOf<Readiness?>(null)
    var freeze by mutableStateOf<FreezeFrame?>(null)
    var vehicle by mutableStateOf<VehicleInfo?>(null)
    var status by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var tests by mutableStateOf<List<TestResult>?>(null)
    var testsRan by mutableStateOf(false)
}

@Composable
fun AppRoot(session: Session) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val conn by session.conn.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val results = remember { Results() }
    val readyTarget = (conn as? Conn.Ready)?.info?.target
    LaunchedEffect(readyTarget) {
        results.scanned = false
        results.codes = emptyList()
        results.readiness = null
        results.readinessDrive = null
        results.freeze = null
        results.vehicle = null
        results.status = emptyList()
        results.tests = null
        results.testsRan = false
    }

    LogSaver(session) { message -> scope.launch { snackbar.showSnackbar(message) } }

    val notify: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val body: @Composable () -> Unit = {
        when (tab) {
            Tab.LIVE -> LiveScreen(session)
            Tab.CODES -> CodesScreen(session, results, notify) { tab = Tab.LIVE }
            Tab.VEHICLE -> VehicleScreen(session, results, notify) { tab = Tab.LIVE }
            Tab.CARE -> CareScreen(session, notify)
            Tab.TERMINAL -> TerminalScreen(session) { tab = Tab.LIVE }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background)) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = Palette.surface,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                ) {
                    Spacer(Modifier.size(12.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = Palette.surfaceHigh, selectedIconColor = Palette.sky, selectedTextColor = Palette.sky),
                        )
                    }
                }
                Scaffold(
                    containerColor = Palette.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom),
                    modifier = Modifier.weight(1f),
                ) { inner ->
                    Column(Modifier.padding(inner).consumeWindowInsets(inner)) {
                        ConnectionBar(session, conn, onLive = tab == Tab.LIVE) { tab = Tab.LIVE }
                        Box(Modifier.weight(1f)) { body() }
                    }
                }
            }
        } else {
            Scaffold(
                containerColor = Palette.background,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = Palette.surface) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.surfaceHigh, selectedIconColor = Palette.sky, selectedTextColor = Palette.sky),
                            )
                        }
                    }
                },
            ) { inner ->
                Column(Modifier.padding(inner).consumeWindowInsets(inner)) {
                    ConnectionBar(session, conn, onLive = tab == Tab.LIVE) { tab = Tab.LIVE }
                    Box(Modifier.weight(1f)) { body() }
                }
            }
        }
    }
}

/** One quiet line that always says what the app is connected to. */
@Composable
private fun ConnectionBar(session: Session, conn: Conn, onLive: Boolean, openConnect: () -> Unit) {
    val voltage by session.voltage.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (conn) {
            Conn.Idle -> {
                Dot(Palette.muted)
                Text("Not connected", color = Palette.muted, modifier = Modifier.padding(start = 12.dp).weight(1f))
                if (!onLive) TextButton(onClick = openConnect) { Text("Connect") }
            }
            is Conn.Busy -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(conn.target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(conn.step, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
                }
                TextButton(onClick = session::disconnect) { Text("Cancel") }
            }
            is Conn.Ready -> {
                Dot(if (conn.info.carOnline) Palette.emerald else Palette.amber)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(conn.info.target.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (conn.info.carOnline) conn.info.protocol else "Adapter ready · car not answering",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                voltage?.let { Pill(it, Palette.sky) }
                IconButton(onClick = session::disconnect) { Icon(Icons.Close, "Disconnect", tint = Palette.muted) }
            }
            is Conn.Failed -> {
                Dot(Palette.crimson)
                Text(
                    conn.message,
                    color = Palette.crimson,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f).clickable(onClick = openConnect),
                )
                conn.target?.let { t -> TextButton(onClick = { session.connect(t) }) { Text("Retry") } }
            }
        }
    }
}

/** Asks where to save a finished CSV log, whichever screen is showing. */
@Composable
private fun LogSaver(session: Session, notify: (String) -> Unit) {
    val context = LocalContext.current
    val finished by session.finishedLog.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val file = session.finishedLog.value
        if (uri != null && file != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onSuccess { notify("Log saved") }.onFailure { notify("Could not save the log: ${it.message}") }
        }
        session.logHandled()
    }
    LaunchedEffect(finished) {
        finished?.let { launcher.launch(it.name) }
    }
}
