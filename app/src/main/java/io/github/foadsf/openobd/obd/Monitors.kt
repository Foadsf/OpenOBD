package io.github.foadsf.openobd.obd

import java.util.Locale

/** One I/M readiness monitor. [complete] is meaningless when [supported] is false. */
data class Monitor(val name: String, val supported: Boolean, val complete: Boolean)

data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<Monitor>,
) {
    val incomplete get() = monitors.count { it.supported && !it.complete }
}

private val SPARK = listOf(
    "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air system",
    "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system",
)
private val COMPRESSION = listOf(
    "NMHC catalyst", "NOx / SCR aftertreatment", "Reserved", "Boost pressure",
    "Reserved", "Exhaust gas sensor", "Particulate filter", "EGR / VVT system",
)

/** Decodes service $01 PID $01 (since codes cleared) or $41 (this drive cycle): bytes A–D. */
fun decodeReadiness(b: IntArray): Readiness {
    val a = b.getOrElse(0) { 0 }
    val bb = b.getOrElse(1) { 0 }
    val c = b.getOrElse(2) { 0 }
    val d = b.getOrElse(3) { 0 }
    val diesel = bb and 0x08 != 0
    val list = mutableListOf(
        Monitor("Misfire", bb and 0x01 != 0, bb and 0x10 == 0),
        Monitor("Fuel system", bb and 0x02 != 0, bb and 0x20 == 0),
        Monitor("Components", bb and 0x04 != 0, bb and 0x40 == 0),
    )
    val names = if (diesel) COMPRESSION else SPARK
    for (i in 0 until 8) {
        if (names[i] == "Reserved") continue
        list += Monitor(names[i], c and (1 shl i) != 0, d and (1 shl i) == 0)
    }
    return Readiness(a and 0x80 != 0, a and 0x7F, diesel, list)
}

/** One on-board monitor test (service $06, CAN format). */
data class TestResult(
    val mid: Int,
    val tid: Int,
    val uas: Int,
    val value: Int,
    val min: Int,
    val max: Int,
) {
    private val signed get() = uas >= 0x80
    private fun s(v: Int) = if (signed && v >= 0x8000) v - 0x10000 else v

    val passed get() = s(value) in s(min)..s(max)
    val midName get() = midName(mid)
    val tidName get() = tidName(mid, tid)

    fun show(v: Int): String {
        val (scale, offset, unit) = UAS[uas] ?: return "${s(v)} (UAS ${hex2(uas)})"
        val x = s(v) * scale + offset
        val decimals = when {
            scale >= 1 -> 0
            scale >= 0.1 -> 1
            scale >= 0.01 -> 2
            else -> 3
        }
        return String.format(Locale.US, "%.${decimals}f", x) + if (unit.isEmpty()) "" else " $unit"
    }
}

/** Parses a CAN service $06 response body (bytes after 0x46) into 9-byte test records. */
fun parseTests(body: List<Int>): List<TestResult> =
    body.chunked(9).filter { it.size == 9 }.map { r ->
        TestResult(r[0], r[1], r[2], r[3] * 256 + r[4], r[5] * 256 + r[6], r[7] * 256 + r[8])
    }

private fun midName(mid: Int): String = when (mid) {
    in 0x01..0x10 -> "O₂ sensor B${(mid - 1) / 4 + 1}S${(mid - 1) % 4 + 1}"
    in 0x21..0x24 -> "Catalyst B${mid - 0x20}"
    in 0x31..0x34 -> "EGR B${mid - 0x30}"
    in 0x35..0x38 -> "VVT B${mid - 0x34}"
    0x39 -> "EVAP (cap off)"
    0x3A -> "EVAP (0.090\")"
    0x3B -> "EVAP (0.040\")"
    0x3C -> "EVAP (0.020\")"
    0x3D -> "Purge flow"
    in 0x41..0x50 -> "O₂ heater B${(mid - 0x41) / 4 + 1}S${(mid - 0x41) % 4 + 1}"
    in 0x61..0x64 -> "Heated catalyst B${mid - 0x60}"
    in 0x71..0x74 -> "Secondary air ${mid - 0x70}"
    in 0x81..0x84 -> "Fuel system B${mid - 0x80}"
    0x85 -> "Boost pressure B1"
    0x86 -> "Boost pressure B2"
    0x90 -> "NOx adsorber B1"
    0x91 -> "NOx adsorber B2"
    0x98 -> "NOx catalyst B1"
    0x99 -> "NOx catalyst B2"
    0xA1 -> "Misfire (general)"
    in 0xA2..0xAD -> "Misfire cylinder ${mid - 0xA1}"
    0xB0 -> "Particulate filter B1"
    0xB1 -> "Particulate filter B2"
    else -> "Monitor ${hex2(mid)}"
}

private fun tidName(mid: Int, tid: Int): String {
    if (mid in 0x01..0x10) {
        when (tid) {
            0x01 -> return "Rich→lean threshold voltage"
            0x02 -> return "Lean→rich threshold voltage"
            0x03 -> return "Low voltage for switch time"
            0x04 -> return "High voltage for switch time"
            0x05 -> return "Rich→lean switch time"
            0x06 -> return "Lean→rich switch time"
            0x07 -> return "Minimum voltage"
            0x08 -> return "Maximum voltage"
            0x09 -> return "Time between transitions"
            0x0A -> return "Sensor period"
        }
    }
    if (mid in 0xA1..0xAD) {
        when (tid) {
            0x0B -> return "Misfires, average of last 10 cycles"
            0x0C -> return "Misfires, last/current cycle"
        }
    }
    return if (tid >= 0x80) "Manufacturer test ${hex2(tid)}" else "Test ${hex2(tid)}"
}

/** SAE J1979 unit-and-scaling IDs: scale, offset, unit. Unknown IDs are shown raw. */
private val UAS: Map<Int, Triple<Double, Double, String>> = mapOf(
    0x01 to Triple(1.0, 0.0, ""), 0x02 to Triple(0.1, 0.0, ""), 0x03 to Triple(0.01, 0.0, ""),
    0x04 to Triple(0.001, 0.0, ""), 0x05 to Triple(0.0000305, 0.0, ""), 0x06 to Triple(0.000305, 0.0, ""),
    0x07 to Triple(0.25, 0.0, "rpm"), 0x08 to Triple(0.01, 0.0, "km/h"), 0x09 to Triple(1.0, 0.0, "km/h"),
    0x0A to Triple(0.122, 0.0, "mV"), 0x0B to Triple(0.001, 0.0, "V"), 0x0C to Triple(0.01, 0.0, "V"),
    0x0D to Triple(0.00390625, 0.0, "mA"), 0x0E to Triple(0.001, 0.0, "A"), 0x0F to Triple(0.01, 0.0, "A"),
    0x10 to Triple(1.0, 0.0, "ms"), 0x11 to Triple(100.0, 0.0, "ms"), 0x12 to Triple(1.0, 0.0, "s"),
    0x13 to Triple(1.0, 0.0, "mΩ"), 0x14 to Triple(1.0, 0.0, "Ω"), 0x15 to Triple(1.0, 0.0, "kΩ"),
    0x16 to Triple(0.1, -40.0, "°C"), 0x17 to Triple(0.01, 0.0, "kPa"), 0x18 to Triple(0.0117, 0.0, "kPa"),
    0x19 to Triple(0.079, 0.0, "kPa"), 0x1A to Triple(1.0, 0.0, "kPa"), 0x1B to Triple(10.0, 0.0, "kPa"),
    0x1C to Triple(0.01, 0.0, "°"), 0x1D to Triple(0.5, 0.0, "°"), 0x1E to Triple(0.0000305, 0.0, "λ"),
    0x1F to Triple(0.05, 0.0, "A/F"), 0x20 to Triple(0.0039062, 0.0, ""), 0x21 to Triple(1.0, 0.0, "mHz"),
    0x22 to Triple(1.0, 0.0, "Hz"), 0x23 to Triple(1.0, 0.0, "kHz"), 0x24 to Triple(1.0, 0.0, "counts"),
    0x25 to Triple(1.0, 0.0, "km"), 0x27 to Triple(0.01, 0.0, "g/s"), 0x28 to Triple(1.0, 0.0, "g/s"),
    0x2F to Triple(0.01, 0.0, "%"), 0x30 to Triple(0.001526, 0.0, "%"), 0x31 to Triple(0.001, 0.0, "L"),
    0x34 to Triple(1.0, 0.0, "min"), 0x35 to Triple(10.0, 0.0, "ms"),
    0x81 to Triple(1.0, 0.0, ""), 0x82 to Triple(0.1, 0.0, ""), 0x83 to Triple(0.01, 0.0, ""),
    0x84 to Triple(0.001, 0.0, ""), 0x85 to Triple(0.0000305, 0.0, ""), 0x86 to Triple(0.000305, 0.0, ""),
    0x8A to Triple(0.122, 0.0, "mV"), 0x8B to Triple(0.001, 0.0, "V"), 0x8C to Triple(0.01, 0.0, "V"),
    0x8D to Triple(0.00390625, 0.0, "mA"), 0x8E to Triple(0.001, 0.0, "A"), 0x90 to Triple(1.0, 0.0, "ms"),
    0x96 to Triple(0.1, 0.0, "°C"), 0x9C to Triple(0.01, 0.0, "°"), 0x9D to Triple(0.5, 0.0, "°"),
    0xA8 to Triple(1.0, 0.0, "g/s"), 0xA9 to Triple(0.25, 0.0, "Pa/s"), 0xAF to Triple(0.01, 0.0, "%"),
    0xB0 to Triple(0.003052, 0.0, "%"), 0xB1 to Triple(2.0, 0.0, "mV/s"), 0xFD to Triple(0.001, 0.0, "kPa"),
    0xFE to Triple(0.25, 0.0, "Pa"),
)
