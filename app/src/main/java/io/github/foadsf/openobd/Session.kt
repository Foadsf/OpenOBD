package io.github.foadsf.openobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.foadsf.openobd.elm.BleTransport
import io.github.foadsf.openobd.elm.ClassicTransport
import io.github.foadsf.openobd.elm.DemoTransport
import io.github.foadsf.openobd.elm.Elm327
import io.github.foadsf.openobd.elm.ElmTimeoutException
import io.github.foadsf.openobd.elm.LinkLostException
import io.github.foadsf.openobd.elm.Transport
import io.github.foadsf.openobd.obd.Obd
import io.github.foadsf.openobd.obd.PROTOCOLS
import io.github.foadsf.openobd.obd.ProtocolTry
import io.github.foadsf.openobd.obd.Reading
import io.github.foadsf.openobd.obd.isLivePid
import io.github.foadsf.openobd.obd.pidInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LinkKind(val label: String) { BLE("Bluetooth LE"), CLASSIC("Bluetooth Classic"), DEMO("Demo") }

data class Target(val kind: LinkKind, val address: String, val name: String)

data class AdapterInfo(
    val target: Target,
    val version: String,
    val description: String?,
    val protocol: String,
    val carOnline: Boolean,
    val carError: String?,
    val layout: String?,
)

sealed interface Conn {
    data object Idle : Conn
    data class Busy(val target: Target, val step: String) : Conn
    data class Ready(val info: AdapterInfo) : Conn
    data class Failed(val target: Target?, val message: String) : Conn
}

data class TermLine(val sent: Boolean, val text: String)

/**
 * Everything that outlives a screen: the adapter link, polling, the terminal log and logging
 * to CSV. One instance per process ([OpenObdApp]); all adapter I/O goes through [Obd], whose
 * ELM327 link serialises commands, so polling and one-off requests can interleave safely.
 */
class Session(private val context: Context) {
    val prefs = Prefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    /** Live PIDs the car reports as supported (service $01), in PID order. */
    private val _supported = MutableStateFlow<List<Int>>(emptyList())
    val supported: StateFlow<List<Int>> = _supported.asStateFlow()

    private val _readings = MutableStateFlow<Map<Int, Reading>>(emptyMap())
    val readings: StateFlow<Map<Int, Reading>> = _readings.asStateFlow()

    private val _voltage = MutableStateFlow<String?>(null)
    val voltage: StateFlow<String?> = _voltage.asStateFlow()

    /** What each protocol answered in the last one-by-one search, when the car stayed silent. */
    private val _protocolScan = MutableStateFlow<List<ProtocolTry>?>(null)
    val protocolScan: StateFlow<List<ProtocolTry>?> = _protocolScan.asStateFlow()

    private val _terminal = MutableStateFlow<List<TermLine>>(emptyList())
    val terminal: StateFlow<List<TermLine>> = _terminal.asStateFlow()

    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    private var transport: Transport? = null
    private var obd: Obd? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null

    @Volatile
    private var watched: List<Int> = emptyList()

    /** Which PIDs the visible screen wants refreshed; empty pauses polling. */
    fun watch(pids: List<Int>) {
        watched = pids
    }

    @SuppressLint("MissingPermission")
    fun connect(target: Target) {
        disconnect()
        prefs.lastTarget = target
        connectJob = scope.launch {
            try {
                _conn.value = Conn.Busy(target, "Opening ${target.kind.label} link")
                val t = when (target.kind) {
                    LinkKind.DEMO -> DemoTransport()
                    else -> {
                        val manager = context.getSystemService(BluetoothManager::class.java)
                        val adapter = manager?.adapter ?: throw LinkLostException("This device has no Bluetooth")
                        val device = adapter.getRemoteDevice(target.address)
                        if (target.kind == LinkKind.BLE) BleTransport(context, device) else ClassicTransport(device)
                    }
                }
                transport = t
                t.open()
                val elm = Elm327(t)
                elm.onTraffic = { sent, received -> if (logTraffic) appendTerminal(sent, received) }
                val o = Obd(elm).also { it.protocolChoice = prefs.protocol }
                obd = o
                _conn.value = Conn.Busy(target, "Setting up the adapter")
                val version = o.resetAdapter()
                val description = o.adapterDescription()
                _voltage.value = o.voltage()
                _conn.value = Conn.Busy(target, "Talking to the car")
                val probe = o.probe()
                if (probe.online) _supported.value = o.supported(1).filter(::isLivePid)
                _conn.value = Conn.Ready(
                    AdapterInfo(
                        target, version, description,
                        probe.protocol.ifEmpty { PROTOCOLS.first { it.first == prefs.protocol }.second },
                        probe.online, probe.error, (t as? BleTransport)?.layout,
                    ),
                )
                startPolling()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                closeLink()
                _conn.value = Conn.Failed(target, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Re-checks the car after the user switched the ignition on. */
    fun retryCar() {
        val ready = _conn.value as? Conn.Ready ?: return
        scope.launch {
            _conn.value = Conn.Busy(ready.info.target, "Talking to the car")
            val result = request { o ->
                val probe = o.probe()
                if (probe.online) _supported.value = o.supported(1).filter(::isLivePid)
                probe
            }
            result.onSuccess { probe ->
                _conn.value = Conn.Ready(ready.info.copy(protocol = probe.protocol, carOnline = probe.online, carError = probe.error))
            }
        }
    }

    /**
     * Tries every protocol on its own, for cars the automatic search misses. A protocol that
     * answers is kept as the user's choice, so the next connection uses it straight away.
     */
    fun scanProtocols() {
        val ready = _conn.value as? Conn.Ready ?: return
        scope.launch {
            val target = ready.info.target
            val result = request { o ->
                val tried = o.scanProtocols { n -> _conn.value = Conn.Busy(target, "Trying protocol $n of ${PROTOCOLS.size - 1}") }
                val found = tried.lastOrNull()?.takeIf { it.error == null }
                if (found != null) {
                    prefs.protocol = found.protocol
                    _supported.value = o.supported(1).filter(::isLivePid)
                }
                tried
            }
            result.onSuccess { tried ->
                val found = tried.lastOrNull()?.takeIf { it.error == null }
                _protocolScan.value = tried
                _conn.value = Conn.Ready(
                    ready.info.copy(
                        protocol = found?.name ?: ready.info.protocol,
                        carOnline = found != null,
                        carError = if (found != null) null else tried.lastOrNull()?.error ?: ready.info.carError,
                    ),
                )
            }.onFailure { if (_conn.value is Conn.Busy && obd != null) _conn.value = ready }
        }
    }

    fun disconnect() {
        _protocolScan.value = null
        connectJob?.cancel()
        pollJob?.cancel()
        stopRecording()
        closeLink()
        _supported.value = emptyList()
        _readings.value = emptyMap()
        _voltage.value = null
        _conn.value = Conn.Idle
    }

    private fun closeLink() {
        transport?.close()
        transport = null
        obd = null
    }

    /**
     * Runs one request against the car. A dropped link moves the app to the failed state;
     * a single timeout is reported to the caller without dropping the connection.
     */
    suspend fun <T> request(block: suspend (Obd) -> T): Result<T> {
        val o = obd ?: return Result.failure(LinkLostException("Not connected"))
        return try {
            Result.success(block(o))
        } catch (e: LinkLostException) {
            linkLost(e.message)
            Result.failure(e)
        } catch (e: ElmTimeoutException) {
            Result.failure(e)
        }
    }

    private fun linkLost(reason: String?) {
        val target = (_conn.value as? Conn.Ready)?.info?.target ?: prefs.lastTarget
        pollJob?.cancel()
        stopRecording()
        closeLink()
        _conn.value = Conn.Failed(target, "Connection lost${reason?.let { ": $it" } ?: ""}")
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastVoltage = 0L
            var timeouts = 0
            while (isActive) {
                val pids = watched
                val online = (_conn.value as? Conn.Ready)?.info?.carOnline == true
                if (pids.isEmpty() || !online) {
                    delay(300)
                } else {
                    val row = HashMap<Int, Reading>()
                    for (pid in pids) {
                        if (!isActive || watched !== pids) break
                        val result = request { it.readPid(pid) }
                        if (result.isFailure) {
                            if (obd == null) return@launch
                            if (++timeouts >= 3) {
                                linkLost("the adapter stopped answering")
                                return@launch
                            }
                            continue
                        }
                        timeouts = 0
                        val bytes = result.getOrNull() ?: continue
                        val reading = pidInfo(pid).decode(bytes)
                        row[pid] = reading
                        _readings.update { it + (pid to reading) }
                    }
                    _recording.value?.append(pids, row)
                }
                val now = System.currentTimeMillis()
                if (now - lastVoltage > 5_000) {
                    lastVoltage = now
                    request { it.voltage() }.onSuccess { v -> if (v != null) _voltage.value = v }
                }
            }
        }
    }

    // Terminal ---------------------------------------------------------------------------------

    /** When true, every exchange (including polling) is mirrored into the terminal log. */
    @Volatile
    var logTraffic = false

    private fun appendTerminal(sent: String, received: String) {
        _terminal.update { (it + TermLine(true, sent) + TermLine(false, tidy(received))).takeLast(400) }
    }

    fun sendRaw(command: String) {
        if (command.isBlank()) return
        scope.launch {
            if (!logTraffic) _terminal.update { (it + TermLine(true, command.trim())).takeLast(400) }
            val r = request { it.raw(command) }
            if (!logTraffic) {
                val text = r.fold(::tidy) { it.message ?: "Error" }
                _terminal.update { (it + TermLine(false, text)).takeLast(400) }
            }
        }
    }

    /** The ELM327 ends lines with CR only; turn them into real lines and drop blanks. */
    private fun tidy(raw: String) = raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    fun clearTerminal() {
        _terminal.value = emptyList()
    }

    // Recording --------------------------------------------------------------------------------

    fun startRecording() {
        val pids = watched.ifEmpty { return }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "openobd-$stamp.csv")
        _recording.value = Recording(file, pids)
    }

    /** A finished log waiting for the user to choose where to save it. */
    private val _finishedLog = MutableStateFlow<File?>(null)
    val finishedLog: StateFlow<File?> = _finishedLog.asStateFlow()

    /** Stops logging; a log with data is offered for saving even if the link dropped. */
    fun stopRecording() {
        val r = _recording.value ?: return
        _recording.value = null
        if (r.rows > 0) _finishedLog.value = r.file
    }

    fun logHandled() {
        _finishedLog.value?.delete()
        _finishedLog.value = null
    }
}

/** Small persistent settings. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("openobd", Context.MODE_PRIVATE)

    var lastTarget: Target?
        get() {
            val kind = p.getString("kind", null)?.let { k -> LinkKind.entries.firstOrNull { it.name == k } } ?: return null
            return Target(kind, p.getString("address", "").orEmpty(), p.getString("name", "").orEmpty())
        }
        set(t) {
            p.edit().putString("kind", t?.kind?.name).putString("address", t?.address).putString("name", t?.name).apply()
        }

    /** ELM327 protocol number; 0 = automatic. */
    var protocol: Int
        get() = p.getInt("protocol", 0)
        set(v) = p.edit().putInt("protocol", v).apply()

    var dashboard: List<Int>
        get() = p.getString("dashboard", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: listOf(0x0C, 0x0D, 0x05, 0x04)
        set(v) = p.edit().putString("dashboard", v.joinToString(",")).apply()

    var imperial: Boolean
        get() = p.getBoolean("imperial", false)
        set(v) = p.edit().putBoolean("imperial", v).apply()

    var maintenance: String?
        get() = p.getString("maintenance", null)
        set(v) = p.edit().putString("maintenance", v).apply()
}

/** A CSV log: one column per PID, one row per polling sweep. */
class Recording(val file: File, private val pids: List<Int>) {
    private val start = System.currentTimeMillis()
    var rows = 0
        private set

    init {
        val header = listOf("time_s") + pids.map { p -> pidInfo(p).let { if (it.unit.isEmpty()) it.name else "${it.name} (${it.unit})" } }
        file.writeText(header.joinToString(",") { "\"$it\"" } + "\n")
    }

    fun append(polled: List<Int>, row: Map<Int, Reading>) {
        if (polled != pids || row.isEmpty()) return
        val t = (System.currentTimeMillis() - start) / 1000.0
        val cells = listOf(String.format(Locale.US, "%.2f", t)) + pids.map { p -> row[p]?.value?.let { String.format(Locale.US, "%.3f", it) }.orEmpty() }
        file.appendText(cells.joinToString(",") + "\n")
        rows++
    }
}
