package io.github.foadsf.openobd.obd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

/** A decoded value: [value] drives gauges and logs, [text] is what a person reads. */
data class Reading(val value: Double?, val text: String)

/**
 * One SAE J1979 service $01/$02 parameter. [decode] receives the data bytes after the PID.
 * [min]/[max] are the gauge range in SI units.
 */
class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val decode: (IntArray) -> Reading,
) {
    val gaugeable get() = max > min
}

private fun IntArray.u(i: Int) = getOrElse(i) { 0 }
private fun IntArray.w(i: Int) = u(i) * 256 + u(i + 1)
private fun IntArray.sw(i: Int) = w(i).let { if (it >= 0x8000) it - 0x10000 else it }

fun fmt(value: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

private fun num(unit: String, decimals: Int, f: (IntArray) -> Double): (IntArray) -> Reading = { b ->
    val v = f(b)
    Reading(v, if (unit.isEmpty()) fmt(v, decimals) else "${fmt(v, decimals)} $unit")
}

private fun pct(i: Int = 0) = num("%", 1) { it.u(i) * 100.0 / 255 }
private fun temp(i: Int = 0) = num("°C", 0) { it.u(i) - 40.0 }
private fun trim(i: Int = 0) = num("%", 1) { (it.u(i) - 128) * 100.0 / 128 }

private fun text(f: (IntArray) -> String): (IntArray) -> Reading = { Reading(null, f(it)) }

private val FUEL_SYSTEM = mapOf(
    0 to "—", 1 to "Open loop (warming up)", 2 to "Closed loop", 4 to "Open loop (load/decel)",
    8 to "Open loop (fault)", 16 to "Closed loop (sensor fault)",
)

val OBD_STANDARDS = mapOf(
    1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD and OBD-II", 4 to "OBD-I", 5 to "Not OBD compliant",
    6 to "EOBD (Europe)", 7 to "EOBD and OBD-II", 8 to "EOBD and OBD", 9 to "EOBD, OBD and OBD-II",
    10 to "JOBD (Japan)", 11 to "JOBD and OBD-II", 12 to "JOBD and EOBD", 13 to "JOBD, EOBD and OBD-II",
    17 to "EMD", 18 to "EMD+", 19 to "HD OBD-C", 20 to "HD OBD", 21 to "WWH-OBD", 23 to "HD EOBD-I",
    24 to "HD EOBD-I N", 25 to "HD EOBD-II", 26 to "HD EOBD-II N", 28 to "OBDBr-1", 29 to "OBDBr-2",
    30 to "KOBD (Korea)", 31 to "IOBD I (India)", 32 to "IOBD II (India)", 33 to "HD EOBD-VI",
)

val FUEL_TYPES = mapOf(
    0 to "Not available", 1 to "Gasoline", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel", 5 to "LPG",
    6 to "CNG", 7 to "Propane", 8 to "Electric", 9 to "Bi-fuel gasoline", 10 to "Bi-fuel methanol",
    11 to "Bi-fuel ethanol", 12 to "Bi-fuel LPG", 13 to "Bi-fuel CNG", 14 to "Bi-fuel propane",
    15 to "Bi-fuel electricity", 16 to "Bi-fuel electric/combustion", 17 to "Hybrid gasoline",
    18 to "Hybrid ethanol", 19 to "Hybrid diesel", 20 to "Hybrid electric", 21 to "Hybrid electric/combustion",
    22 to "Hybrid regenerative", 23 to "Bi-fuel diesel",
)

private fun o2Narrow(n: Int) = Pid(0x13 + n, "O₂ sensor $n voltage", "V", 0.0, 1.275) { b ->
    val v = b.u(0) / 200.0
    val trim = if (b.u(1) == 0xFF) "" else " · trim ${fmt((b.u(1) - 128) * 100.0 / 128, 1)} %"
    Reading(v, "${fmt(v, 3)} V$trim")
}

private fun o2WideVoltage(n: Int) = Pid(0x23 + n, "O₂ sensor $n λ (voltage)", "λ", 0.0, 2.0) { b ->
    val lambda = b.w(0) * 2.0 / 65536
    Reading(lambda, "λ ${fmt(lambda, 3)} · ${fmt(b.w(2) * 8.0 / 65536, 3)} V")
}

private fun o2WideCurrent(n: Int) = Pid(0x33 + n, "O₂ sensor $n λ (current)", "λ", 0.0, 2.0) { b ->
    val lambda = b.w(0) * 2.0 / 65536
    Reading(lambda, "λ ${fmt(lambda, 3)} · ${fmt(b.w(2) / 256.0 - 128, 2)} mA")
}

private fun bits(b: Int, names: List<String>) =
    names.filterIndexed { i, _ -> b and (1 shl i) != 0 }.joinToString(", ").ifEmpty { "none" }

private val PID_LIST: List<Pid> = buildList {
    add(Pid(0x03, "Fuel system status", "", 0.0, 0.0, text { b ->
        val one = FUEL_SYSTEM[b.u(0)] ?: "Code ${b.u(0)}"
        if (b.u(1) == 0) one else "$one / ${FUEL_SYSTEM[b.u(1)] ?: "Code ${b.u(1)}"}"
    }))
    add(Pid(0x04, "Engine load", "%", 0.0, 100.0, pct()))
    add(Pid(0x05, "Coolant temperature", "°C", -40.0, 130.0, temp()))
    add(Pid(0x06, "Short-term fuel trim B1", "%", -25.0, 25.0, trim()))
    add(Pid(0x07, "Long-term fuel trim B1", "%", -25.0, 25.0, trim()))
    add(Pid(0x08, "Short-term fuel trim B2", "%", -25.0, 25.0, trim()))
    add(Pid(0x09, "Long-term fuel trim B2", "%", -25.0, 25.0, trim()))
    add(Pid(0x0A, "Fuel pressure", "kPa", 0.0, 765.0, num("kPa", 0) { it.u(0) * 3.0 }))
    add(Pid(0x0B, "Intake manifold pressure", "kPa", 0.0, 255.0, num("kPa", 0) { it.u(0).toDouble() }))
    add(Pid(0x0C, "Engine speed", "rpm", 0.0, 8000.0, num("rpm", 0) { it.w(0) / 4.0 }))
    add(Pid(0x0D, "Vehicle speed", "km/h", 0.0, 220.0, num("km/h", 0) { it.u(0).toDouble() }))
    add(Pid(0x0E, "Timing advance", "°", -64.0, 64.0, num("°", 1) { it.u(0) / 2.0 - 64 }))
    add(Pid(0x0F, "Intake air temperature", "°C", -40.0, 100.0, temp()))
    add(Pid(0x10, "Mass air flow", "g/s", 0.0, 300.0, num("g/s", 2) { it.w(0) / 100.0 }))
    add(Pid(0x11, "Throttle position", "%", 0.0, 100.0, pct()))
    add(Pid(0x12, "Secondary air status", "", 0.0, 0.0, text { b ->
        when (b.u(0)) {
            1 -> "Upstream"; 2 -> "Downstream of catalyst"; 4 -> "From outside / off"; 8 -> "Pump on for diagnostics"
            else -> "Code ${b.u(0)}"
        }
    }))
    add(Pid(0x13, "O₂ sensors present", "", 0.0, 0.0, text { b ->
        bits(b.u(0), listOf("B1S1", "B1S2", "B1S3", "B1S4", "B2S1", "B2S2", "B2S3", "B2S4"))
    }))
    for (n in 1..8) add(o2Narrow(n))
    add(Pid(0x1C, "OBD standard", "", 0.0, 0.0, text { OBD_STANDARDS[it.u(0)] ?: "Code ${it.u(0)}" }))
    add(Pid(0x1D, "O₂ sensors present (4 banks)", "", 0.0, 0.0, text { b ->
        bits(b.u(0), listOf("B1S1", "B1S2", "B2S1", "B2S2", "B3S1", "B3S2", "B4S1", "B4S2"))
    }))
    add(Pid(0x1E, "Power take-off", "", 0.0, 0.0, text { if (it.u(0) and 1 != 0) "Active" else "Inactive" }))
    add(Pid(0x1F, "Run time since start", "s", 0.0, 0.0, num("s", 0) { it.w(0).toDouble() }))
    add(Pid(0x21, "Distance with MIL on", "km", 0.0, 0.0, num("km", 0) { it.w(0).toDouble() }))
    add(Pid(0x22, "Fuel rail pressure (rel.)", "kPa", 0.0, 5177.0, num("kPa", 1) { it.w(0) * 0.079 }))
    add(Pid(0x23, "Fuel rail gauge pressure", "kPa", 0.0, 655350.0, num("kPa", 0) { it.w(0) * 10.0 }))
    for (n in 1..8) add(o2WideVoltage(n))
    add(Pid(0x2C, "Commanded EGR", "%", 0.0, 100.0, pct()))
    add(Pid(0x2D, "EGR error", "%", -100.0, 100.0, trim()))
    add(Pid(0x2E, "Commanded evap purge", "%", 0.0, 100.0, pct()))
    add(Pid(0x2F, "Fuel tank level", "%", 0.0, 100.0, pct()))
    add(Pid(0x30, "Warm-ups since codes cleared", "", 0.0, 0.0, num("", 0) { it.u(0).toDouble() }))
    add(Pid(0x31, "Distance since codes cleared", "km", 0.0, 0.0, num("km", 0) { it.w(0).toDouble() }))
    add(Pid(0x32, "Evap vapour pressure", "Pa", -8192.0, 8192.0, num("Pa", 1) { it.sw(0) / 4.0 }))
    add(Pid(0x33, "Barometric pressure", "kPa", 0.0, 255.0, num("kPa", 0) { it.u(0).toDouble() }))
    for (n in 1..8) add(o2WideCurrent(n))
    add(Pid(0x3C, "Catalyst temp B1S1", "°C", -40.0, 1000.0, num("°C", 0) { it.w(0) / 10.0 - 40 }))
    add(Pid(0x3D, "Catalyst temp B2S1", "°C", -40.0, 1000.0, num("°C", 0) { it.w(0) / 10.0 - 40 }))
    add(Pid(0x3E, "Catalyst temp B1S2", "°C", -40.0, 1000.0, num("°C", 0) { it.w(0) / 10.0 - 40 }))
    add(Pid(0x3F, "Catalyst temp B2S2", "°C", -40.0, 1000.0, num("°C", 0) { it.w(0) / 10.0 - 40 }))
    add(Pid(0x42, "Control module voltage", "V", 0.0, 18.0, num("V", 2) { it.w(0) / 1000.0 }))
    add(Pid(0x43, "Absolute load", "%", 0.0, 100.0, num("%", 1) { it.w(0) * 100.0 / 255 }))
    add(Pid(0x44, "Commanded λ", "λ", 0.0, 2.0, num("", 3) { it.w(0) * 2.0 / 65536 }))
    add(Pid(0x45, "Relative throttle position", "%", 0.0, 100.0, pct()))
    add(Pid(0x46, "Ambient air temperature", "°C", -40.0, 60.0, temp()))
    add(Pid(0x47, "Throttle position B", "%", 0.0, 100.0, pct()))
    add(Pid(0x48, "Throttle position C", "%", 0.0, 100.0, pct()))
    add(Pid(0x49, "Accelerator pedal D", "%", 0.0, 100.0, pct()))
    add(Pid(0x4A, "Accelerator pedal E", "%", 0.0, 100.0, pct()))
    add(Pid(0x4B, "Accelerator pedal F", "%", 0.0, 100.0, pct()))
    add(Pid(0x4C, "Commanded throttle actuator", "%", 0.0, 100.0, pct()))
    add(Pid(0x4D, "Time run with MIL on", "min", 0.0, 0.0, num("min", 0) { it.w(0).toDouble() }))
    add(Pid(0x4E, "Time since codes cleared", "min", 0.0, 0.0, num("min", 0) { it.w(0).toDouble() }))
    add(Pid(0x4F, "Maximum ratings", "", 0.0, 0.0, text { b ->
        "λ ${b.u(0)} · ${b.u(1)} V · ${b.u(2)} mA · ${b.u(3) * 10} kPa"
    }))
    add(Pid(0x50, "Maximum air flow", "g/s", 0.0, 0.0, num("g/s", 0) { it.u(0) * 10.0 }))
    add(Pid(0x51, "Fuel type", "", 0.0, 0.0, text { FUEL_TYPES[it.u(0)] ?: "Code ${it.u(0)}" }))
    add(Pid(0x52, "Ethanol fuel", "%", 0.0, 100.0, pct()))
    add(Pid(0x53, "Evap vapour pressure (abs.)", "kPa", 0.0, 330.0, num("kPa", 2) { it.w(0) / 200.0 }))
    add(Pid(0x54, "Evap vapour pressure (wide)", "Pa", -32768.0, 32767.0, num("Pa", 0) { it.sw(0).toDouble() }))
    add(Pid(0x55, "Short-term O₂ trim B1/B3", "%", -100.0, 100.0, trim()))
    add(Pid(0x56, "Long-term O₂ trim B1/B3", "%", -100.0, 100.0, trim()))
    add(Pid(0x57, "Short-term O₂ trim B2/B4", "%", -100.0, 100.0, trim()))
    add(Pid(0x58, "Long-term O₂ trim B2/B4", "%", -100.0, 100.0, trim()))
    add(Pid(0x59, "Fuel rail absolute pressure", "kPa", 0.0, 655350.0, num("kPa", 0) { it.w(0) * 10.0 }))
    add(Pid(0x5A, "Relative pedal position", "%", 0.0, 100.0, pct()))
    add(Pid(0x5B, "Hybrid battery remaining", "%", 0.0, 100.0, pct()))
    add(Pid(0x5C, "Engine oil temperature", "°C", -40.0, 160.0, temp()))
    add(Pid(0x5D, "Fuel injection timing", "°", -210.0, 302.0, num("°", 2) { it.w(0) / 128.0 - 210 }))
    add(Pid(0x5E, "Engine fuel rate", "L/h", 0.0, 50.0, num("L/h", 2) { it.w(0) / 20.0 }))
    add(Pid(0x5F, "Emission requirements", "", 0.0, 0.0, text { "Code ${it.u(0)}" }))
    add(Pid(0x61, "Driver demand torque", "%", -125.0, 130.0, num("%", 0) { it.u(0) - 125.0 }))
    add(Pid(0x62, "Actual engine torque", "%", -125.0, 130.0, num("%", 0) { it.u(0) - 125.0 }))
    add(Pid(0x63, "Engine reference torque", "N·m", 0.0, 0.0, num("N·m", 0) { it.w(0).toDouble() }))
    add(Pid(0x64, "Engine torque points", "", 0.0, 0.0, text { b ->
        (0 until 5).joinToString(" · ") { "${b.u(it) - 125} %" }
    }))
    add(Pid(0x66, "Mass air flow sensor", "g/s", 0.0, 300.0, num("g/s", 2) { it.w(1) / 32.0 }))
    add(Pid(0x67, "Coolant temperature sensors", "°C", -40.0, 130.0, num("°C", 0) { it.u(1) - 40.0 }))
    add(Pid(0x68, "Intake air temperature sensors", "°C", -40.0, 100.0, num("°C", 0) { it.u(1) - 40.0 }))
    add(Pid(0x8E, "Engine friction torque", "%", -125.0, 130.0, num("%", 0) { it.u(0) - 125.0 }))
    add(Pid(0xA2, "Cylinder fuel rate", "mg/stroke", 0.0, 2048.0, num("mg/stroke", 1) { it.w(0) / 32.0 }))
    add(Pid(0xA4, "Transmission gear ratio", "", 0.0, 0.0, text { b ->
        val gear = b.u(1) shr 4
        val ratio = b.w(2) / 1000.0
        if (gear == 0) "Ratio ${fmt(ratio, 3)}" else "Gear $gear · ratio ${fmt(ratio, 3)}"
    }))
    add(Pid(0xA6, "Odometer", "km", 0.0, 0.0, num("km", 0) { b ->
        ((b.u(0).toLong() shl 24) or (b.u(1).toLong() shl 16) or (b.u(2).toLong() shl 8) or b.u(3).toLong()) / 10.0
    }))
}

private val BY_ID = PID_LIST.associateBy { it.id }

/** PIDs that are support bitmaps or are shown elsewhere (readiness, freeze-frame DTC). */
private val META = setOf(0x00, 0x01, 0x02, 0x20, 0x40, 0x41, 0x60, 0x80, 0xA0, 0xC0, 0xE0)

fun isLivePid(id: Int) = id !in META

/** The definition for [id], or a raw-bytes fallback so every supported PID is still visible. */
fun pidInfo(id: Int): Pid = BY_ID[id] ?: Pid(id, "PID ${hex2(id)}", "", 0.0, 0.0) { b ->
    Reading(null, b.joinToString(" ") { hex2(it) })
}

fun hex2(v: Int) = String.format(Locale.US, "%02X", v)

/** Unit preference: metric is native; imperial converts for display only. */
object Units {
    /** Snapshot state, so every value on screen re-renders when the user flips it. */
    var imperial by mutableStateOf(false)

    fun convert(value: Double, unit: String): Pair<Double, String> = if (!imperial) value to unit else when (unit) {
        "km/h" -> value * 0.621371 to "mph"
        "km" -> value * 0.621371 to "mi"
        "°C" -> value * 9 / 5 + 32 to "°F"
        "kPa" -> value * 0.145038 to "psi"
        "L/h" -> value * 0.264172 to "gal/h"
        else -> value to unit
    }

    /** Display text for a reading, converting the primary value when imperial is on. */
    fun show(pid: Pid, r: Reading): String {
        val v = r.value ?: return r.text
        if (!imperial || pid.unit !in setOf("km/h", "km", "°C", "kPa", "L/h")) return r.text
        val (cv, cu) = convert(v, pid.unit)
        val decimals = if (cu == "psi" || cu == "gal/h") 1 else 0
        return "${fmt(cv, decimals)} $cu"
    }

    fun rounded(v: Double): String = if (kotlin.math.abs(v) >= 100) v.roundToInt().toString() else fmt(v, 1)
}
