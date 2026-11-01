package io.github.foadsf.openobd.obd

import io.github.foadsf.openobd.elm.EcuMessage
import io.github.foadsf.openobd.elm.Elm327
import io.github.foadsf.openobd.elm.ElmTimeoutException
import io.github.foadsf.openobd.elm.Family
import io.github.foadsf.openobd.elm.familyOf
import io.github.foadsf.openobd.elm.firstError
import io.github.foadsf.openobd.elm.parseFrames
import io.github.foadsf.openobd.elm.parseHeaderless
import io.github.foadsf.openobd.elm.primaryFirst
import io.github.foadsf.openobd.elm.responseLines

/** ELM327 protocol numbers selectable with `AT SP` / `AT TP`. */
val PROTOCOLS = listOf(
    0 to "Automatic",
    1 to "SAE J1850 PWM (41.6 kbaud)",
    2 to "SAE J1850 VPW (10.4 kbaud)",
    3 to "ISO 9141-2 (5 baud init)",
    4 to "ISO 14230-4 KWP (5 baud init)",
    5 to "ISO 14230-4 KWP (fast init)",
    6 to "ISO 15765-4 CAN (11 bit, 500 kbaud)",
    7 to "ISO 15765-4 CAN (29 bit, 500 kbaud)",
    8 to "ISO 15765-4 CAN (11 bit, 250 kbaud)",
    9 to "ISO 15765-4 CAN (29 bit, 250 kbaud)",
)

data class Query(val lines: List<String>, val messages: List<EcuMessage>, val error: String?)

data class FreezeFrame(val dtc: String?, val values: List<Pair<Pid, Reading>>)

data class EcuInfo(val ecu: String, val name: String?, val calibrations: List<String>, val cvns: List<String>)

data class VehicleInfo(val vin: String?, val ecus: List<EcuInfo>)

/** Result of the first contact with the car after the adapter is up. */
data class Probe(val online: Boolean, val protocol: String, val error: String?)

/** One protocol tried on its own; [error] is null when the car answered. */
data class ProtocolTry(val protocol: Int, val name: String, val error: String?)

/** SAE J1979 diagnostic services on top of an [Elm327] link. Headers stay on (`AT H1`). */
class Obd(private val elm: Elm327) {
    var family = Family.CAN11
        private set
    var protocolNumber = 0
        private set

    /** 0 = automatic search, otherwise an ELM327 protocol number tried without saving. */
    var protocolChoice = 0

    /** Set when the terminal sent an AT command, so adapter settings are restored before use. */
    private var dirty = false

    private suspend fun at(cmd: String, timeout: Long = 2_000) = responseLines(elm.send(cmd, timeout), cmd)

    private suspend fun baseline() {
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1")) at(cmd)
        at(if (protocolChoice == 0) "ATSP0" else "ATTP${protocolChoice.toString(16).uppercase()}")
    }

    /** Resets the adapter and applies the settings this app relies on. Returns `AT I`. */
    suspend fun resetAdapter(): String {
        try {
            elm.send("ATZ", 5_000)
        } catch (_: ElmTimeoutException) {
            // Some clones stay silent after a reset; the next command still works.
        }
        baseline()
        return at("ATI").lastOrNull() ?: "Unknown"
    }

    suspend fun adapterDescription(): String? =
        at("AT@1").lastOrNull()?.takeUnless { it == "?" || it.isBlank() }

    suspend fun voltage(): String? = at("ATRV").lastOrNull()?.takeIf { it.any(Char::isDigit) }?.removeSuffix("V")?.let { "$it V" }

    /** Wakes the car's bus and learns which protocol it speaks. */
    suspend fun probe(): Probe {
        val raw = elm.send("0100", 15_000)
        val lines = responseLines(raw, "0100")
        val dpn = at("ATDPN").lastOrNull().orEmpty()
        protocolNumber = dpn.lastOrNull()?.digitToIntOrNull(16) ?: 0
        family = familyOf(protocolNumber)
        val description = at("ATDP").lastOrNull().orEmpty().removePrefix("AUTO, ")
        val messages = parse(lines)
        val online = messages.any { it.data.size >= 2 && it.data[0] == 0x41 && it.data[1] == 0x00 }
        return Probe(online, description, if (online) null else firstError(lines) ?: lines.firstOrNull() ?: "No answer within 15 s")
    }

    /**
     * Tries each protocol on its own (`AT TP h`: no automatic search, nothing saved), for cars
     * the automatic search misses. Stops at the first that answers and stays on it; otherwise
     * puts the adapter back on [protocolChoice]. Returns what each protocol answered.
     */
    suspend fun scanProtocols(progress: (protocol: Int) -> Unit = {}): List<ProtocolTry> {
        restoreIfDirty()
        val tried = mutableListOf<ProtocolTry>()
        for ((n, name) in PROTOCOLS.drop(1)) {
            progress(n)
            at("ATTP$n")
            val lines = try {
                responseLines(elm.send("0100", 12_000), "0100")
            } catch (_: ElmTimeoutException) {
                emptyList()
            }
            family = familyOf(n)
            val online = parse(lines).any { it.data.size >= 2 && it.data[0] == 0x41 && it.data[1] == 0x00 }
            tried += ProtocolTry(n, name, if (online) null else firstError(lines) ?: lines.firstOrNull() ?: "No answer")
            if (online) {
                protocolChoice = n
                protocolNumber = n
                return tried
            }
        }
        baseline()
        protocolNumber = 0
        return tried
    }

    private fun parse(lines: List<String>): List<EcuMessage> {
        val framed = parseFrames(lines, family)
        return primaryFirst(framed.ifEmpty { parseHeaderless(lines) })
    }

    suspend fun query(cmd: String, timeout: Long = 4_000): Query {
        restoreIfDirty()
        val lines = responseLines(elm.send(cmd, timeout), cmd)
        return Query(lines, parse(lines), firstError(lines))
    }

    private suspend fun restoreIfDirty() {
        if (!dirty) return
        dirty = false
        at("ATD")
        baseline()
        probe()
    }

    /**
     * Sends harmless commands from every ELM327 firmware version and records which ones the
     * adapter understands, plus [FAKE_COMMAND] to catch firmware that says OK to anything.
     */
    suspend fun checkAdapter(progress: (done: Int, total: Int) -> Unit = { _, _ -> }): AdapterReport {
        val id = at("ATI").lastOrNull().orEmpty()
        // The checks change filters and timers; restore everything before the next car query.
        dirty = true
        val results = AT_CHECKS.mapIndexed { i, c ->
            progress(i, AT_CHECKS.size)
            val lines = try {
                at(c.command)
            } catch (_: ElmTimeoutException) {
                emptyList()
            }
            val answer = when {
                lines.isEmpty() -> AtAnswer.SILENT
                lines.any { it == "?" } -> AtAnswer.NO
                else -> AtAnswer.YES
            }
            AtResult(c, answer, lines.joinToString(" / "))
        }
        val control = try {
            at(FAKE_COMMAND)
        } catch (_: ElmTimeoutException) {
            emptyList()
        }
        at("ATD")
        baseline()
        progress(AT_CHECKS.size, AT_CHECKS.size)
        return AdapterReport(id, results, acceptsAnything = control.any { it.equals("OK", ignoreCase = true) })
    }

    /** Sends anything typed in the terminal and returns the adapter's raw answer. */
    suspend fun raw(cmd: String): String {
        val c = cmd.trim().uppercase()
        if (c.startsWith("AT") || c.startsWith("ST")) dirty = true
        return elm.send(cmd.trim(), 10_000)
    }

    /** Support bitmaps for service $01 (PIDs), $02 (freeze frame) or $06 (monitor IDs). */
    suspend fun supported(service: Int): Set<Int> {
        val found = sortedSetOf<Int>()
        var block = 0
        while (block <= 0xE0) {
            val cmd = if (service == 2) "02${hex2(block)}00" else "${hex2(service)}${hex2(block)}"
            val q = query(cmd)
            val skip = if (service == 2) 3 else 2
            val maps = q.messages.filter { it.data.size >= skip + 4 && it.data[0] == 0x40 + service && it.data[1] == block }
            if (maps.isEmpty()) break
            for (m in maps) {
                for (i in 0 until 32) {
                    val byte = m.data[skip + i / 8]
                    if (byte and (0x80 shr (i % 8)) != 0) found += block + i + 1
                }
            }
            if (block + 0x20 !in found) break
            block += 0x20
        }
        return found
    }

    /** One service $01 value from the first ECU that answers, or null. */
    suspend fun readPid(pid: Int): IntArray? {
        val q = query("01${hex2(pid)}")
        val m = q.messages.firstOrNull { it.data.size >= 3 && it.data[0] == 0x41 && it.data[1] == pid } ?: return null
        return m.data.drop(2).toIntArray()
    }

    suspend fun readiness(thisDrive: Boolean = false): Readiness? =
        readPid(if (thisDrive) 0x41 else 0x01)?.let(::decodeReadiness)

    suspend fun readDtcs(kind: DtcKind): Pair<List<Dtc>, String?> {
        val q = query(kind.service, 8_000)
        val service = kind.service.toInt(16) + 0x40
        val found = linkedSetOf<Dtc>()
        for (m in q.messages.filter { it.data.isNotEmpty() && it.data[0] == service }) {
            // CAN responses carry a DTC count byte after the service id; legacy frames do not.
            val body = m.data.drop(if (family == Family.LEGACY) 1 else 2)
            for (pair in body.chunked(2)) {
                if (pair.size == 2 && (pair[0] != 0 || pair[1] != 0)) found += Dtc(dtcCode(pair[0], pair[1]), kind, m.ecu)
            }
        }
        val error = if (q.messages.isEmpty() && q.error != "NO DATA") q.error else null
        return found.toList() to error
    }

    /** Service $04. Returns null on success, otherwise the reason. */
    suspend fun clearDtcs(): String? {
        val q = query("04", 10_000)
        if (q.messages.any { it.data.firstOrNull() == 0x44 }) return null
        val negative = q.messages.firstOrNull { it.data.size >= 3 && it.data[0] == 0x7F }
        return when (negative?.data?.get(2)) {
            0x22 -> "The car refused: conditions not correct. Switch the engine off, leave the ignition on, and try again."
            0x31 -> "The car refused the request (out of range)."
            null -> q.error ?: "No confirmation from the car."
            else -> "The car refused the request (code ${hex2(negative.data[2])})."
        }
    }

    suspend fun freezeFrame(): FreezeFrame? {
        val dtcQ = query("020200")
        val dtcMsg = dtcQ.messages.firstOrNull { it.data.size >= 5 && it.data[0] == 0x42 && it.data[1] == 0x02 }
        val dtc = dtcMsg?.let { dtcCode(it.data[3], it.data[4]) }?.takeUnless { it == "P0000" }
        val pids = supported(2).filter(::isLivePid)
        if (dtc == null && pids.isEmpty()) return null
        val values = pids.mapNotNull { pid ->
            val q = query("02${hex2(pid)}00")
            val m = q.messages.firstOrNull { it.data.size >= 4 && it.data[0] == 0x42 && it.data[1] == pid }
                ?: return@mapNotNull null
            val info = pidInfo(pid)
            info to info.decode(m.data.drop(3).toIntArray())
        }
        return FreezeFrame(dtc, values)
    }

    /** Service $09 payloads per ECU, joining legacy multi-frame answers in sequence order. */
    private suspend fun info(pid: Int): Map<String, List<Int>> {
        val q = query("09${hex2(pid)}", 6_000)
        return q.messages
            .filter { it.data.size >= 3 && it.data[0] == 0x49 && it.data[1] == pid }
            .groupBy { it.ecu }
            .mapValues { (_, msgs) -> msgs.sortedBy { it.data[2] }.flatMap { it.data.drop(3) } }
    }

    suspend fun vehicleInfo(): VehicleInfo {
        val vin = info(0x02).values.firstOrNull()?.let { bytes ->
            ascii(bytes).filter { it.isLetterOrDigit() }.takeLast(17).ifEmpty { null }
        }
        val cal = info(0x04)
        val cvn = info(0x06)
        val names = info(0x0A)
        val ecus = (cal.keys + cvn.keys + names.keys).distinct().sorted().map { ecu ->
            EcuInfo(
                ecu = ecu,
                name = names[ecu]?.let { ascii(it).replace('\u0000', ' ').replace(Regex(" *- *"), " – ").trim() }?.ifEmpty { null },
                calibrations = cal[ecu]?.chunked(16)?.map { ascii(it).trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                cvns = cvn[ecu]?.chunked(4)?.filter { it.size == 4 }?.map { c -> c.joinToString("") { hex2(it) } }.orEmpty(),
            )
        }
        return VehicleInfo(vin, ecus)
    }

    /** Service $06 on-board monitor results. CAN only; older buses use a different layout. */
    suspend fun tests(): List<TestResult>? {
        if (family == Family.LEGACY) return null
        val mids = supported(6).filter { it % 0x20 != 0 }
        return mids.flatMap { mid ->
            val q = query("06${hex2(mid)}")
            q.messages.filter { it.data.size > 1 && it.data[0] == 0x46 }.flatMap { parseTests(it.data.drop(1)) }
        }
    }

    private fun ascii(bytes: List<Int>) = bytes.map { if (it in 0x20..0x7E) it.toChar() else '\u0000' }
        .joinToString("").trim('\u0000')
}
