package io.github.foadsf.openobd.obd

/** Two raw bytes to the SAE J2012 form, e.g. 0x01 0x71 → "P0171". */
fun dtcCode(a: Int, b: Int): String {
    val system = "PCBU"[a shr 6]
    val d1 = (a shr 4) and 0x03
    val d2 = a and 0x0F
    return "$system$d1${d2.toString(16).uppercase()}${hex2(b)}"
}

enum class DtcKind(val title: String, val service: String, val note: String) {
    STORED("Stored", "03", "Confirmed faults. These usually light the check-engine lamp."),
    PENDING("Pending", "07", "Seen during the current or last drive cycle, not yet confirmed."),
    PERMANENT("Permanent", "0A", "Clear only after the car re-tests the system and it passes. A scan tool cannot erase them."),
}

data class Dtc(val code: String, val kind: DtcKind, val ecu: String) {
    val description: String get() = describeDtc(code)
    val area: String get() = dtcArea(code)
}

/** What part of the car the code points at, from its structure alone. */
fun dtcArea(code: String): String {
    if (code.length != 5) return ""
    val generic = code[1] == '0' || (code[0] == 'P' && code[1] == '2') || (code[0] == 'P' && code[1] == '3' && code[2] >= '4')
    val owner = if (generic) "Generic" else "Manufacturer-specific"
    val system = when (code[0]) {
        'P' -> when (code[2]) {
            '0' -> "fuel, air & emission controls"
            '1', '2' -> "fuel & air metering"
            '3' -> "ignition & misfire"
            '4' -> "auxiliary emission controls"
            '5' -> "speed, idle & auxiliary inputs"
            '6' -> "computer & output circuits"
            '7', '8', '9' -> "transmission"
            'A' -> "hybrid propulsion"
            else -> "powertrain"
        }
        'C' -> "chassis"
        'B' -> "body"
        'U' -> "network & communication"
        else -> ""
    }
    return "$owner · $system"
}

fun describeDtc(code: String): String {
    DTC_TEXT[code]?.let { return it }
    // P0301–P0312 and P0201–P0212 follow a per-cylinder pattern.
    val cylinder = code.takeLast(2).toIntOrNull()
    if (code.length == 5 && cylinder != null && cylinder in 1..12) {
        if (code.startsWith("P03")) return "Cylinder $cylinder misfire detected"
        if (code.startsWith("P02")) return "Cylinder $cylinder injector circuit malfunction"
    }
    return "No description in the built-in list. Look it up online or in the workshop manual."
}

/** Plain-language meanings of common generic codes (SAE J2012 wording, abbreviated). */
private val DTC_TEXT = mapOf(
    "P0010" to "Camshaft position actuator circuit (bank 1)",
    "P0011" to "Camshaft timing over-advanced or system performance (bank 1)",
    "P0012" to "Camshaft timing over-retarded (bank 1)",
    "P0013" to "Exhaust camshaft position actuator circuit (bank 1)",
    "P0014" to "Exhaust camshaft timing over-advanced (bank 1)",
    "P0016" to "Crankshaft/camshaft position correlation (bank 1 sensor A)",
    "P0017" to "Crankshaft/camshaft position correlation (bank 1 sensor B)",
    "P0018" to "Crankshaft/camshaft position correlation (bank 2 sensor A)",
    "P0020" to "Camshaft position actuator circuit (bank 2)",
    "P0021" to "Camshaft timing over-advanced or system performance (bank 2)",
    "P0030" to "O₂ sensor heater control circuit (bank 1 sensor 1)",
    "P0036" to "O₂ sensor heater control circuit (bank 1 sensor 2)",
    "P0068" to "MAP/MAF – throttle position correlation",
    "P0087" to "Fuel rail/system pressure too low",
    "P0088" to "Fuel rail/system pressure too high",
    "P0100" to "Mass air flow circuit malfunction",
    "P0101" to "Mass air flow circuit range/performance",
    "P0102" to "Mass air flow circuit low input",
    "P0103" to "Mass air flow circuit high input",
    "P0105" to "Manifold absolute pressure circuit malfunction",
    "P0106" to "Manifold absolute pressure circuit range/performance",
    "P0107" to "Manifold absolute pressure circuit low input",
    "P0108" to "Manifold absolute pressure circuit high input",
    "P0110" to "Intake air temperature circuit malfunction",
    "P0112" to "Intake air temperature circuit low input",
    "P0113" to "Intake air temperature circuit high input",
    "P0115" to "Engine coolant temperature circuit malfunction",
    "P0116" to "Engine coolant temperature circuit range/performance",
    "P0117" to "Engine coolant temperature circuit low input",
    "P0118" to "Engine coolant temperature circuit high input",
    "P0120" to "Throttle/pedal position sensor A circuit malfunction",
    "P0121" to "Throttle/pedal position sensor A range/performance",
    "P0122" to "Throttle/pedal position sensor A low input",
    "P0123" to "Throttle/pedal position sensor A high input",
    "P0125" to "Insufficient coolant temperature for closed-loop fuel control",
    "P0128" to "Coolant thermostat (coolant temperature below regulating temperature)",
    "P0130" to "O₂ sensor circuit malfunction (bank 1 sensor 1)",
    "P0131" to "O₂ sensor circuit low voltage (bank 1 sensor 1)",
    "P0132" to "O₂ sensor circuit high voltage (bank 1 sensor 1)",
    "P0133" to "O₂ sensor circuit slow response (bank 1 sensor 1)",
    "P0134" to "O₂ sensor circuit no activity detected (bank 1 sensor 1)",
    "P0135" to "O₂ sensor heater circuit malfunction (bank 1 sensor 1)",
    "P0136" to "O₂ sensor circuit malfunction (bank 1 sensor 2)",
    "P0137" to "O₂ sensor circuit low voltage (bank 1 sensor 2)",
    "P0138" to "O₂ sensor circuit high voltage (bank 1 sensor 2)",
    "P0139" to "O₂ sensor circuit slow response (bank 1 sensor 2)",
    "P0140" to "O₂ sensor circuit no activity detected (bank 1 sensor 2)",
    "P0141" to "O₂ sensor heater circuit malfunction (bank 1 sensor 2)",
    "P0150" to "O₂ sensor circuit malfunction (bank 2 sensor 1)",
    "P0151" to "O₂ sensor circuit low voltage (bank 2 sensor 1)",
    "P0152" to "O₂ sensor circuit high voltage (bank 2 sensor 1)",
    "P0153" to "O₂ sensor circuit slow response (bank 2 sensor 1)",
    "P0154" to "O₂ sensor circuit no activity detected (bank 2 sensor 1)",
    "P0155" to "O₂ sensor heater circuit malfunction (bank 2 sensor 1)",
    "P0156" to "O₂ sensor circuit malfunction (bank 2 sensor 2)",
    "P0157" to "O₂ sensor circuit low voltage (bank 2 sensor 2)",
    "P0158" to "O₂ sensor circuit high voltage (bank 2 sensor 2)",
    "P0161" to "O₂ sensor heater circuit malfunction (bank 2 sensor 2)",
    "P0170" to "Fuel trim malfunction (bank 1)",
    "P0171" to "System too lean (bank 1)",
    "P0172" to "System too rich (bank 1)",
    "P0173" to "Fuel trim malfunction (bank 2)",
    "P0174" to "System too lean (bank 2)",
    "P0175" to "System too rich (bank 2)",
    "P0190" to "Fuel rail pressure sensor circuit malfunction",
    "P0191" to "Fuel rail pressure sensor range/performance",
    "P0192" to "Fuel rail pressure sensor low input",
    "P0193" to "Fuel rail pressure sensor high input",
    "P0200" to "Injector circuit malfunction",
    "P0217" to "Engine overheat condition",
    "P0219" to "Engine overspeed condition",
    "P0220" to "Throttle/pedal position sensor B circuit malfunction",
    "P0221" to "Throttle/pedal position sensor B range/performance",
    "P0222" to "Throttle/pedal position sensor B low input",
    "P0223" to "Throttle/pedal position sensor B high input",
    "P0230" to "Fuel pump primary circuit malfunction",
    "P0234" to "Turbo/supercharger overboost condition",
    "P0235" to "Turbo/supercharger boost sensor A circuit",
    "P0236" to "Turbo/supercharger boost sensor A range/performance",
    "P0237" to "Turbo/supercharger boost sensor A low input",
    "P0238" to "Turbo/supercharger boost sensor A high input",
    "P0261" to "Cylinder 1 injector circuit low",
    "P0262" to "Cylinder 1 injector circuit high",
    "P0263" to "Cylinder 1 contribution/balance",
    "P0299" to "Turbo/supercharger underboost condition",
    "P0300" to "Random/multiple cylinder misfire detected",
    "P0313" to "Misfire detected with low fuel",
    "P0315" to "Crankshaft position system variation not learned",
    "P0325" to "Knock sensor 1 circuit malfunction (bank 1)",
    "P0326" to "Knock sensor 1 circuit range/performance (bank 1)",
    "P0327" to "Knock sensor 1 circuit low input (bank 1)",
    "P0328" to "Knock sensor 1 circuit high input (bank 1)",
    "P0330" to "Knock sensor 2 circuit malfunction (bank 2)",
    "P0335" to "Crankshaft position sensor A circuit malfunction",
    "P0336" to "Crankshaft position sensor A range/performance",
    "P0337" to "Crankshaft position sensor A low input",
    "P0338" to "Crankshaft position sensor A high input",
    "P0339" to "Crankshaft position sensor A circuit intermittent",
    "P0340" to "Camshaft position sensor A circuit malfunction (bank 1)",
    "P0341" to "Camshaft position sensor A range/performance (bank 1)",
    "P0342" to "Camshaft position sensor A low input (bank 1)",
    "P0343" to "Camshaft position sensor A high input (bank 1)",
    "P0345" to "Camshaft position sensor A circuit malfunction (bank 2)",
    "P0351" to "Ignition coil A primary/secondary circuit malfunction",
    "P0352" to "Ignition coil B primary/secondary circuit malfunction",
    "P0353" to "Ignition coil C primary/secondary circuit malfunction",
    "P0354" to "Ignition coil D primary/secondary circuit malfunction",
    "P0355" to "Ignition coil E primary/secondary circuit malfunction",
    "P0356" to "Ignition coil F primary/secondary circuit malfunction",
    "P0366" to "Camshaft position sensor B range/performance (bank 1)",
    "P0380" to "Glow plug/heater circuit A malfunction",
    "P0400" to "Exhaust gas recirculation flow malfunction",
    "P0401" to "Exhaust gas recirculation flow insufficient",
    "P0402" to "Exhaust gas recirculation flow excessive",
    "P0403" to "Exhaust gas recirculation control circuit malfunction",
    "P0404" to "Exhaust gas recirculation control circuit range/performance",
    "P0405" to "EGR sensor A circuit low",
    "P0406" to "EGR sensor A circuit high",
    "P0410" to "Secondary air injection system malfunction",
    "P0411" to "Secondary air injection system incorrect flow",
    "P0420" to "Catalyst system efficiency below threshold (bank 1)",
    "P0421" to "Warm-up catalyst efficiency below threshold (bank 1)",
    "P0430" to "Catalyst system efficiency below threshold (bank 2)",
    "P0440" to "Evaporative emission system malfunction",
    "P0441" to "Evaporative emission system incorrect purge flow",
    "P0442" to "Evaporative emission system small leak detected",
    "P0443" to "Evaporative emission purge control valve circuit",
    "P0446" to "Evaporative emission vent control circuit",
    "P0449" to "Evaporative emission vent valve/solenoid circuit",
    "P0451" to "Evaporative emission pressure sensor range/performance",
    "P0452" to "Evaporative emission pressure sensor low input",
    "P0453" to "Evaporative emission pressure sensor high input",
    "P0455" to "Evaporative emission system large leak detected",
    "P0456" to "Evaporative emission system very small leak detected",
    "P0457" to "Evaporative emission leak detected (fuel cap loose/off)",
    "P0460" to "Fuel level sensor circuit malfunction",
    "P0461" to "Fuel level sensor circuit range/performance",
    "P0462" to "Fuel level sensor circuit low input",
    "P0463" to "Fuel level sensor circuit high input",
    "P0480" to "Cooling fan 1 control circuit malfunction",
    "P0481" to "Cooling fan 2 control circuit malfunction",
    "P0491" to "Secondary air injection system (bank 1)",
    "P0496" to "Evaporative emission high purge flow",
    "P0500" to "Vehicle speed sensor malfunction",
    "P0501" to "Vehicle speed sensor range/performance",
    "P0505" to "Idle control system malfunction",
    "P0506" to "Idle control system RPM lower than expected",
    "P0507" to "Idle control system RPM higher than expected",
    "P0520" to "Engine oil pressure sensor/switch circuit malfunction",
    "P0521" to "Engine oil pressure sensor/switch range/performance",
    "P0524" to "Engine oil pressure too low",
    "P0530" to "A/C refrigerant pressure sensor circuit malfunction",
    "P0560" to "System voltage malfunction",
    "P0562" to "System voltage low",
    "P0563" to "System voltage high",
    "P0571" to "Brake switch A circuit malfunction",
    "P0600" to "Serial communication link malfunction",
    "P0601" to "Control module memory checksum error",
    "P0602" to "Control module programming error",
    "P0603" to "Control module keep-alive memory (KAM) error",
    "P0604" to "Control module RAM error",
    "P0605" to "Control module ROM error",
    "P0606" to "Control module processor fault",
    "P0607" to "Control module performance",
    "P0620" to "Generator control circuit malfunction",
    "P0627" to "Fuel pump control circuit open",
    "P0641" to "Sensor reference voltage A circuit open",
    "P0650" to "Malfunction indicator lamp (MIL) control circuit",
    "P0700" to "Transmission control system malfunction (see transmission codes)",
    "P0705" to "Transmission range sensor circuit malfunction",
    "P0710" to "Transmission fluid temperature sensor circuit",
    "P0715" to "Input/turbine speed sensor circuit malfunction",
    "P0720" to "Output speed sensor circuit malfunction",
    "P0725" to "Engine speed input circuit malfunction",
    "P0730" to "Incorrect gear ratio",
    "P0740" to "Torque converter clutch circuit malfunction",
    "P0741" to "Torque converter clutch performance or stuck off",
    "P0750" to "Shift solenoid A malfunction",
    "P0755" to "Shift solenoid B malfunction",
    "P0760" to "Shift solenoid C malfunction",
    "P0765" to "Shift solenoid D malfunction",
    "P0770" to "Shift solenoid E malfunction",
    "P0841" to "Transmission fluid pressure sensor/switch A range/performance",
    "P2002" to "Diesel particulate filter efficiency below threshold (bank 1)",
    "P2004" to "Intake manifold runner control stuck open (bank 1)",
    "P2096" to "Post-catalyst fuel trim system too lean (bank 1)",
    "P2097" to "Post-catalyst fuel trim system too rich (bank 1)",
    "P2101" to "Throttle actuator control motor circuit range/performance",
    "P2135" to "Throttle/pedal position sensor A/B voltage correlation",
    "P2138" to "Throttle/pedal position sensor D/E voltage correlation",
    "P2177" to "System too lean off idle (bank 1)",
    "P2187" to "System too lean at idle (bank 1)",
    "P2188" to "System too rich at idle (bank 1)",
    "P2195" to "O₂ sensor signal biased/stuck lean (bank 1 sensor 1)",
    "P2196" to "O₂ sensor signal biased/stuck rich (bank 1 sensor 1)",
    "P2270" to "O₂ sensor signal biased/stuck lean (bank 1 sensor 2)",
    "P2271" to "O₂ sensor signal biased/stuck rich (bank 1 sensor 2)",
    "P2279" to "Intake air system leak",
    "P242F" to "Diesel particulate filter restriction – ash accumulation",
    "P2452" to "Diesel particulate filter pressure sensor A circuit",
    "P2463" to "Diesel particulate filter restriction – soot accumulation",
    "U0001" to "High-speed CAN communication bus",
    "U0073" to "Control module communication bus A off",
    "U0100" to "Lost communication with engine control module (ECM/PCM)",
    "U0101" to "Lost communication with transmission control module (TCM)",
    "U0121" to "Lost communication with anti-lock brake system (ABS) module",
    "U0140" to "Lost communication with body control module (BCM)",
    "U0155" to "Lost communication with instrument panel cluster",
)
