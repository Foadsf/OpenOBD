package io.github.foadsf.openobd.elm

import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A simulated ELM327 v1.5 on ISO 15765-4 CAN (11 bit, 500 kbaud) with an engine ECU (7E8)
 * and a transmission ECU (7E9). It honours echo/header/space/linefeed settings and ISO-TP
 * framing, so the demo mode and the tests exercise the same code paths as a real adapter.
 * [car] can also make it a car the automatic search misses, or one with no OBD-II at all.
 */
enum class DemoCar {
    STANDARD,

    /** Answers only once protocol 6 is chosen explicitly. */
    SEARCH_MISSES,

    /** Answers on no protocol, like a diesel built before EOBD. */
    NO_OBD,
}

class DemoTransport(private val latencyMs: Long = 35, private val car: DemoCar = DemoCar.STANDARD) : BaseTransport() {
    /** 0 = automatic search, otherwise the protocol set with AT SP / AT TP. */
    private var protocol = 0
    private var echo = true
    private var headers = false
    private var spaces = true
    private var linefeeds = false
    private var cleared = false
    private val start = System.currentTimeMillis()

    override suspend fun open() = delay(latencyMs * 4)

    override fun close() = lost("Closed")

    override suspend fun write(data: ByteArray) {
        val typed = String(data, Charsets.US_ASCII).trim()
        val cmd = typed.uppercase().replace(" ", "")
        delay(if (cmd.startsWith("AT")) latencyMs / 4 else latencyMs)
        val eol = if (linefeeds) "\r\n" else "\r"
        val out = StringBuilder()
        if (echo) out.append(typed).append(eol)
        for (line in respond(cmd)) out.append(line).append(eol)
        out.append(eol).append('>')
        // Arrive in BLE-sized pieces, as from a real adapter.
        out.toString().toByteArray(Charsets.US_ASCII).toList().chunked(20).forEach { deliver(it.toByteArray()) }
    }

    private fun respond(cmd: String): List<String> {
        if (cmd.startsWith("AT")) return at(cmd.substring(2))
        if (cmd.isEmpty() || cmd.length % 2 != 0 || cmd.any { it !in "0123456789ABCDEF" }) return listOf("?")
        when {
            car == DemoCar.NO_OBD || (protocol == 0 && car == DemoCar.SEARCH_MISSES) ->
                return listOf("SEARCHING...", "UNABLE TO CONNECT")
            protocol in 1..5 -> return listOf(if (protocol <= 2) "NO DATA" else "BUS INIT: ...ERROR")
            protocol > 6 -> return listOf("CAN ERROR")
        }
        val bytes = cmd.chunked(2).map { it.toInt(16) }
        val answers = listOf("7E8" to engine(bytes), "7E9" to transmission(bytes)).filter { it.second != null }
        if (answers.isEmpty()) return listOf("NO DATA")
        return answers.flatMap { (header, payload) -> frame(header, payload!!) }
    }

    private fun at(c: String): List<String> = when {
        c == "Z" -> {
            echo = true; headers = false; spaces = true; linefeeds = false
            listOf("", "ELM327 v1.5")
        }
        c == "WS" -> listOf("", "ELM327 v1.5")
        c == "I" -> listOf("ELM327 v1.5")
        c == "@1" -> listOf("OpenOBD demo adapter")
        c == "RV" -> listOf("${fmt1(13.9 + 0.2 * wave(7.0))}V")
        c == "DPN" -> listOf(if (protocol == 0) "A6" else "$protocol")
        c == "DP" -> listOf((if (protocol == 0) "AUTO, " else "") + "ISO 15765-4 (CAN 11/500)")
        (c.startsWith("SP") || c.startsWith("TP")) && c.length == 3 && c[2].isDigit() -> {
            protocol = c[2].digitToInt()
            listOf("OK")
        }
        c == "D" -> {
            echo = true; headers = false; spaces = true; linefeeds = false
            listOf("OK")
        }
        c.length == 2 && c[0] in "EHSL" && c[1] in "01" -> {
            val on = c[1] == '1'
            when (c[0]) {
                'E' -> echo = on
                'H' -> headers = on
                'S' -> spaces = on
                'L' -> linefeeds = on
            }
            listOf("OK")
        }
        c in CLONE_LACKS || (c.startsWith("CRA") && 'X' in c) || c.startsWith("AMT") || c.startsWith("CER") -> listOf("?")
        else -> listOf("OK")
    }

    private companion object {
        /** Like most "v1.5" clones: nothing from v1.4b on, and no made-up commands. */
        val CLONE_LACKS = setOf(
            "CSM0", "CSM1", "JHF0", "JHF1", "JTM1", "JTM5", "CRA", "AMC", "CTM1", "CTM5",
            "IB12", "IB15", "IFR4", "IFR5", "IFR6", "QQ",
        )
    }

    private val t get() = (System.currentTimeMillis() - start) / 1000.0
    private fun wave(period: Double, phase: Double = 0.0) = sin(2 * PI * t / period + phase)
    private fun fmt1(v: Double) = ((v * 10).roundToInt() / 10.0).toString()

    private val livePids = sortedSetOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15,
        0x1C, 0x1F, 0x21, 0x2F, 0x30, 0x31, 0x33, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C,
        0x4D, 0x4E, 0x51, 0x5C, 0xA6,
    )

    private fun bitmap(set: Set<Int>, block: Int): List<Int> {
        val bits = (1..32).fold(0L) { acc, i ->
            val pid = block + i
            val on = pid in set || (i == 32 && set.any { it > block + 32 })
            if (on) acc or (1L shl (32 - i)) else acc
        }
        return listOf((bits shr 24).toInt() and 0xFF, (bits shr 16).toInt() and 0xFF, (bits shr 8).toInt() and 0xFF, bits.toInt() and 0xFF)
    }

    private fun rpm() = 820 + 1900 * (0.5 + 0.5 * wave(14.0)) + 300 * wave(3.0)
    private fun speed() = (rpm() - 800) / 28.0

    private fun live(pid: Int): List<Int>? {
        val rpm = rpm()
        val coolant = (20 + t * 0.6).coerceAtMost(91.0) + wave(30.0)
        return when (pid) {
            0x01 -> if (cleared) listOf(0x00, 0x07, 0x65, 0x65) else listOf(0x82, 0x07, 0x65, 0x04)
            0x03 -> listOf(if (coolant < 40) 1 else 2, 0)
            0x04 -> listOf((25 + 20 * (0.5 + 0.5 * wave(14.0))).times(2.55).roundToInt())
            0x05 -> listOf((coolant + 40).roundToInt())
            0x06 -> listOf((128 + 6 * wave(2.0)).roundToInt())
            0x07 -> listOf(140)
            0x0B -> listOf((35 + 40 * (0.5 + 0.5 * wave(14.0))).roundToInt())
            0x0C -> (rpm * 4).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x0D -> listOf(speed().roundToInt().coerceIn(0, 255))
            0x0E -> listOf(((12 + 4 * wave(5.0) + 64) * 2).roundToInt())
            0x0F -> listOf(62)
            0x10 -> ((3 + rpm / 220) * 100).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x11 -> listOf((18 + 22 * (0.5 + 0.5 * wave(14.0))).times(2.55).roundToInt())
            0x13 -> listOf(0x03)
            0x14 -> listOf((90 + 80 * (0.5 + 0.5 * wave(1.3))).roundToInt(), 0x80)
            0x15 -> listOf(140, 0xFF)
            0x1C -> listOf(6)
            0x1F -> t.roundToInt().let { listOf(it shr 8 and 0xFF, it and 0xFF) }
            0x21 -> if (cleared) listOf(0, 0) else listOf(0, 37)
            0x2F -> listOf((0.62 * 255).roundToInt())
            0x30 -> listOf(if (cleared) 0 else 14)
            0x31 -> if (cleared) listOf(0, 0) else listOf(0x01, 0x2C)
            0x33 -> listOf(101)
            0x42 -> (14.1 * 1000 + 100 * wave(7.0)).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x43 -> listOf(0, 80)
            0x44 -> listOf(0x80, 0x00)
            0x45 -> listOf(30)
            0x46 -> listOf(54)
            0x47 -> listOf(52)
            0x49 -> listOf(40)
            0x4A -> listOf(20)
            0x4C -> listOf(28)
            0x4D -> if (cleared) listOf(0, 0) else listOf(0, 55)
            0x4E -> if (cleared) listOf(0, 0) else listOf(0x01, 0x10)
            0x51 -> listOf(1)
            0x5C -> listOf((coolant - 4 + 40).roundToInt())
            0xA6 -> listOf(0x00, 0x1D, 0x4C, 0x9A) // 192 015.4 km
            else -> null
        }
    }

    private fun ascii(s: String, size: Int) = s.map { it.code }.let { it + List(size - it.size) { 0 } }

    private fun engine(b: List<Int>): List<Int>? {
        val service = b[0]
        val pid = b.getOrNull(1)
        return when (service) {
            0x01 -> when {
                pid == null -> null
                pid % 0x20 == 0 -> if (livePids.any { it > pid }) listOf(0x41, pid) + bitmap(livePids, pid) else null
                else -> live(pid)?.let { listOf(0x41, pid) + it }
            }
            0x02 -> {
                val ff = setOf(0x02, 0x04, 0x05, 0x0C, 0x0D, 0x11)
                when {
                    cleared -> if (pid == 0x02) listOf(0x42, 0x02, 0x00, 0x00, 0x00) else null
                    pid == 0x00 -> listOf(0x42, 0x00, 0x00) + bitmap(ff, 0)
                    pid == 0x02 -> listOf(0x42, 0x02, 0x00, 0x01, 0x71)
                    pid == 0x04 -> listOf(0x42, 0x04, 0x00, 92)
                    pid == 0x05 -> listOf(0x42, 0x05, 0x00, 128)
                    pid == 0x0C -> listOf(0x42, 0x0C, 0x00, 0x2E, 0xE0)
                    pid == 0x0D -> listOf(0x42, 0x0D, 0x00, 63)
                    pid == 0x11 -> listOf(0x42, 0x11, 0x00, 48)
                    else -> null
                }
            }
            0x03 -> if (cleared) listOf(0x43, 0x00) else listOf(0x43, 0x02, 0x01, 0x71, 0x04, 0x20)
            0x04 -> {
                cleared = true
                listOf(0x44)
            }
            0x06 -> when (pid) {
                0x00 -> listOf(0x46, 0x00) + bitmap(setOf(0x01, 0x21), 0x00)
                0x20 -> listOf(0x46, 0x20) + bitmap(setOf(0x21), 0x20)
                0x01 -> listOf(
                    0x46,
                    0x01, 0x01, 0x0A, 0x0E, 0x69, 0x0B, 0x00, 0x12, 0x00,
                    0x01, 0x05, 0x10, 0x00, 0x3C, 0x00, 0x00, 0x00, 0x64,
                )
                0x21 -> listOf(0x46, 0x21, 0x80, 0x01, 0x00, if (cleared) 0x40 else 0xA0, 0x00, 0x00, 0x00, 0x80)
                else -> null
            }
            0x07 -> if (cleared) listOf(0x47, 0x00) else listOf(0x47, 0x01, 0x03, 0x00)
            0x09 -> when (pid) {
                0x02 -> listOf(0x49, 0x02, 0x01) + ascii("DEMO0PENOBD000001", 17)
                0x04 -> listOf(0x49, 0x04, 0x01) + ascii("OPENOBD-DEMO-CAL", 16)
                0x06 -> listOf(0x49, 0x06, 0x01, 0x1A, 0x2B, 0x3C, 0x4D)
                0x0A -> listOf(0x49, 0x0A, 0x01) + ascii("ECM\u0000-EngineControl", 20)
                else -> null
            }
            0x0A -> listOf(0x4A, 0x01, 0x04, 0x20)
            else -> null
        }
    }

    private fun transmission(b: List<Int>): List<Int>? = when {
        b == listOf(0x01, 0x00) -> listOf(0x41, 0x00, 0x80, 0x00, 0x00, 0x00)
        b == listOf(0x03) -> listOf(0x43, 0x00)
        b == listOf(0x09, 0x0A) -> listOf(0x49, 0x0A, 0x01) + ascii("TCM\u0000-TransmissionCtl", 20)
        else -> null
    }

    /** ISO-TP framing, printed the way an ELM327 prints it for the current settings. */
    private fun frame(header: String, payload: List<Int>): List<String> {
        val sep = if (spaces) " " else ""
        fun hex(v: Int) = v.toString(16).uppercase().padStart(2, '0')
        fun line(bytes: List<Int>) = bytes.joinToString(sep) { hex(it) }
        if (payload.size <= 7) {
            if (!headers) return listOf(line(payload))
            val bytes = (listOf(payload.size) + payload).let { it + List(8 - it.size) { 0x00 } }
            return listOf(header + sep + line(bytes))
        }
        val frames = mutableListOf(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))
        var seq = 1
        payload.drop(6).chunked(7).forEach { chunk ->
            frames += (listOf(0x20 or (seq and 0x0F)) + chunk).let { it + List(8 - it.size) { 0x00 } }
            seq++
        }
        if (headers) return frames.map { header + sep + line(it) }
        // Header-less multi-frame form: total length, then numbered data lines.
        val out = mutableListOf(payload.size.toString(16).uppercase().padStart(3, '0'))
        out += "0:$sep${line(payload.take(6))}"
        payload.drop(6).chunked(7).forEachIndexed { i, chunk -> out += "${(i + 1) % 16}:$sep${line(chunk)}" }
        return out
    }
}
