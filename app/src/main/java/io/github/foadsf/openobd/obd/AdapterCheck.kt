package io.github.foadsf.openobd.obd

/** One AT command, the ELM327 firmware version that introduced it, and what it does. */
data class AtCommand(val version: String, val command: String, val purpose: String)

/** ELM327 firmware versions in release order. Elm Electronics never made v1.4a or v1.5. */
val ELM_VERSIONS = listOf("1.0", "1.1", "1.2", "1.3", "1.3a", "1.4", "1.4b", "2.0", "2.1", "2.2")

/**
 * Commands per firmware version, from the "Version History" of Elm Electronics' ELM327 data
 * sheet (ELM327DSK, firmware v2.2). Only commands that read something, or change a setting
 * that `AT D` puts back, are sent: nothing that writes the EEPROM (PP SV, SD, CV, @3, SP),
 * changes the baud rate, sleeps, resets, monitors the bus or transmits on it.
 */
val AT_CHECKS = listOf(
    AtCommand("1.0", "ATI", "version ID"),
    AtCommand("1.0", "AT@1", "device description"),
    AtCommand("1.0", "ATDP", "describe the protocol"),
    AtCommand("1.0", "ATDPN", "protocol number"),
    AtCommand("1.0", "ATRV", "read the voltage"),
    AtCommand("1.0", "ATE0", "echo off"),
    AtCommand("1.0", "ATL0", "linefeeds off"),
    AtCommand("1.0", "ATH1", "headers on"),
    AtCommand("1.0", "ATCAF1", "CAN auto formatting"),
    AtCommand("1.0", "ATCFC1", "CAN flow control"),
    AtCommand("1.0", "ATR1", "responses on"),
    AtCommand("1.0", "ATNL", "normal message length"),
    AtCommand("1.1", "ATPPS", "programmable parameter summary"),
    AtCommand("1.1", "ATFCSM0", "flow control mode"),
    AtCommand("1.2", "ATAT1", "adaptive timing"),
    AtCommand("1.2", "ATAR", "automatic receive address"),
    AtCommand("1.2", "ATKW1", "ISO key word checking"),
    AtCommand("1.2", "ATIIA33", "ISO init address"),
    AtCommand("1.2", "ATIFR1", "J1850 in-frame response"),
    AtCommand("1.3", "ATS1", "spaces on"),
    AtCommand("1.3", "ATD0", "CAN DLC display off"),
    AtCommand("1.3", "ATV0", "variable CAN DLC off"),
    AtCommand("1.3", "ATJE", "J1939 ELM format"),
    AtCommand("1.3", "ATCRA7E8", "CAN receive address"),
    AtCommand("1.3a", "ATFE", "forget events"),
    AtCommand("1.4", "ATIGN", "ignition input level"),
    AtCommand("1.4", "ATRD", "read the stored data byte"),
    AtCommand("1.4", "ATCEA", "CAN extended addressing off"),
    AtCommand("1.4", "ATTAF1", "tester address"),
    AtCommand("1.4b", "ATCSM1", "CAN silent monitoring"),
    AtCommand("1.4b", "ATJHF1", "J1939 header formatting"),
    AtCommand("1.4b", "ATJTM1", "J1939 timer multiplier"),
    AtCommand("1.4b", "ATCRA", "reset the receive filters"),
    AtCommand("2.0", "ATAMC", "activity monitor count"),
    AtCommand("2.0", "ATCRA7EX", "receive address with wildcards"),
    AtCommand("2.1", "ATCTM1", "CAN timer multiplier"),
    AtCommand("2.2", "ATIFR5", "in-frame response, also while monitoring"),
    AtCommand("2.2", "ATIB15", "ISO baud rate 15625"),
)

/** Commands OpenOBD itself relies on. */
val APP_NEEDS = setOf("ATE0", "ATL0", "ATH1", "ATS1", "ATAT1", "ATDP", "ATDPN", "ATRV")

/** No ELM327 has a Q command, so a genuine chip must answer this with `?`. */
const val FAKE_COMMAND = "ATQQ"

enum class AtAnswer { YES, NO, SILENT }

data class AtResult(val check: AtCommand, val answer: AtAnswer, val reply: String)

data class AdapterReport(val id: String, val results: List<AtResult>, val acceptsAnything: Boolean) {
    /** The version the adapter says it is, e.g. "1.5" from "ELM327 v1.5". */
    val claimed: String? = Regex("""v\s?(\d+\.\d+[a-z]?)""", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.lowercase()

    val byVersion: List<Pair<String, List<AtResult>>> = ELM_VERSIONS.map { v -> v to results.filter { it.check.version == v } }

    /** Highest version whose commands, and every earlier version's, all work. */
    val behavesLike: String? = byVersion.takeWhile { (_, rs) -> rs.all { it.answer == AtAnswer.YES } }.lastOrNull()?.first

    /** True when the adapter is what it says it is: no fake version, no OK to everything. */
    val honest: Boolean = !acceptsAnything && behavesLike != null && claimed != "1.5" && claimed != "1.4a" &&
        !(claimed in ELM_VERSIONS && ELM_VERSIONS.indexOf(claimed) > ELM_VERSIONS.indexOf(behavesLike))

    val missingForApp: List<String> = results.filter { it.check.command in APP_NEEDS && it.answer != AtAnswer.YES }.map { it.check.command }

    val verdict: String = when {
        acceptsAnything -> "It answers OK even to a command that does not exist, so this check cannot tell what it really supports. Clone firmware often does this."
        behavesLike == null -> "Some basic v1.0 commands fail. Expect problems."
        claimed == "1.5" || claimed == "1.4a" -> "Reports v$claimed, a version Elm Electronics never made, so this is clone firmware. It handles every command up to v$behavesLike."
        claimed == behavesLike -> "Handles every command up to v$claimed, as it reports."
        claimed in ELM_VERSIONS && ELM_VERSIONS.indexOf(claimed) > ELM_VERSIONS.indexOf(behavesLike) ->
            "Reports v$claimed but only handles every command up to v$behavesLike."
        claimed != null -> "Handles every command up to v$behavesLike. Reports v$claimed."
        else -> "Handles every command up to v$behavesLike."
    }

    /** Plain text for a bug report or a forum post. */
    fun asText(): String = buildString {
        appendLine("Adapter: $id")
        appendLine(verdict)
        appendLine(if (missingForApp.isEmpty()) "OpenOBD's commands: all work" else "OpenOBD's commands not working: ${missingForApp.joinToString()}")
        appendLine("Fake command $FAKE_COMMAND: ${if (acceptsAnything) "accepted" else "rejected"}")
        byVersion.forEach { (v, rs) ->
            val bad = rs.filter { it.answer != AtAnswer.YES }
            append("v$v ${rs.size - bad.size}/${rs.size}")
            if (bad.isNotEmpty()) append("  missing: ${bad.joinToString { it.check.command.removePrefix("AT") }}")
            appendLine()
        }
    }.trimEnd()
}
