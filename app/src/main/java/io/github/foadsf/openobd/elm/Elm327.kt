package io.github.foadsf.openobd.elm

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Half-duplex command/response link to an ELM327: write a command ending in CR, then collect
 * everything up to the `>` prompt. A mutex keeps exactly one command in flight, which is what
 * the ELM327 requires — no fixed inter-command delay is needed.
 */
class Elm327(private val transport: Transport) {
    private val mutex = Mutex()

    /** Every exchange, for the terminal and for bug reports. */
    var onTraffic: ((sent: String, received: String) -> Unit)? = null

    /** Returns the raw response text (without the prompt), or throws [LinkLostException]. */
    suspend fun send(command: String, timeoutMs: Long = 4_000): String = mutex.withLock {
        // Discard anything left over from a command that previously timed out.
        while (transport.input.tryReceive().getOrNull() != null) {
            // drained
        }
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val text = StringBuilder()
        val done = withTimeoutOrNull(timeoutMs) {
            try {
                while (true) {
                    for (b in transport.input.receive()) {
                        val c = (b.toInt() and 0xFF).toChar()
                        if (c == '>') return@withTimeoutOrNull true
                        if (c != '\u0000') text.append(c)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } catch (e: ClosedReceiveChannelException) {
                throw LinkLostException(e.message ?: "Link closed")
            } catch (e: LinkLostException) {
                throw e
            }
        }
        val response = text.toString()
        onTraffic?.invoke(command, if (done == true) response else "$response\n(no prompt after ${timeoutMs} ms)")
        if (done != true && response.isBlank()) throw ElmTimeoutException(command)
        response
    }
}

class ElmTimeoutException(command: String) : Exception("Adapter did not answer \"$command\"")

/** Message text the ELM327 prints instead of data. */
val ELM_ERRORS = listOf(
    "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "BUS BUSY", "BUS ERROR", "CAN ERROR", "DATA ERROR",
    "FB ERROR", "BUFFER FULL", "RX ERROR", "STOPPED", "ERR", "?", "LV RESET", "ACT ALERT",
)

/** Splits a response into meaningful lines, dropping echoes, blanks and status chatter. */
fun responseLines(raw: String, command: String? = null): List<String> =
    raw.split('\r', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.startsWith("SEARCHING") }
        .filterNot { it.startsWith("BUS INIT") && it.endsWith("OK") }
        .filterNot { command != null && it.replace(" ", "").equals(command.replace(" ", ""), ignoreCase = true) }

fun firstError(lines: List<String>): String? =
    lines.firstOrNull { line -> ELM_ERRORS.any { line.uppercase().startsWith(it) } }

/** One complete diagnostic response from one ECU; [data] starts with the response service id. */
data class EcuMessage(val ecu: String, val data: List<Int>)

enum class Family { CAN11, CAN29, LEGACY }

/** ELM327 protocol number (from `AT DPN`) to framing family. */
fun familyOf(protocol: Int): Family = when (protocol) {
    6, 8, 11 -> Family.CAN11
    7, 9, 10, 12 -> Family.CAN29
    else -> Family.LEGACY
}

private val HEX = Regex("^[0-9A-Fa-f]+$")

/**
 * Parses header-on (`AT H1`, `AT S1`) output into per-ECU messages, reassembling ISO-TP
 * multi-frame CAN responses. Legacy protocols (J1850, ISO 9141, KWP2000) keep one message per
 * frame; their 3 header bytes and trailing checksum are removed.
 */
fun parseFrames(lines: List<String>, family: Family): List<EcuMessage> {
    val frames = lines.mapNotNull { line ->
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { !HEX.matches(it) }) return@mapNotNull null
        when (family) {
            Family.CAN11 -> {
                if (tokens[0].length != 3 || tokens.size < 2) return@mapNotNull null
                tokens[0].uppercase() to tokens.drop(1).map { it.toInt(16) }
            }
            Family.CAN29 -> {
                if (tokens.size < 5) return@mapNotNull null
                tokens.take(4).joinToString("").uppercase() to tokens.drop(4).map { it.toInt(16) }
            }
            Family.LEGACY -> {
                if (tokens.size < 5) return@mapNotNull null
                tokens[2].uppercase() to tokens.subList(3, tokens.size - 1).map { it.toInt(16) }
            }
        }
    }
    if (family == Family.LEGACY) return frames.map { (ecu, data) -> EcuMessage(ecu, data) }

    val out = mutableListOf<EcuMessage>()
    val pending = mutableMapOf<String, Pair<Int, MutableList<Int>>>()
    for ((ecu, data) in frames) {
        if (data.isEmpty()) continue
        val pci = data[0]
        when (pci shr 4) {
            0 -> {
                val len = pci and 0x0F
                if (len in 1..data.size - 1) out += EcuMessage(ecu, data.subList(1, 1 + len))
            }
            1 -> {
                if (data.size < 2) continue
                val total = ((pci and 0x0F) shl 8) or data[1]
                pending[ecu] = total to data.drop(2).toMutableList()
            }
            2 -> pending[ecu]?.second?.addAll(data.drop(1))
        }
    }
    for ((ecu, p) in pending) {
        val (total, bytes) = p
        out += EcuMessage(ecu, bytes.take(total))
    }
    return out
}

/**
 * Fallback for adapters that ignore `AT H1`: header-less output, including the numbered
 * multi-frame form (`014`, `0: 49 02 01 ...`, `1: ...`), joined into one message.
 */
fun parseHeaderless(lines: List<String>): List<EcuMessage> {
    val bytes = mutableListOf<Int>()
    for (line in lines) {
        var body = line
        val colon = body.indexOf(':')
        if (colon in 1..2) body = body.substring(colon + 1)
        val tokens = body.trim().split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.any { !HEX.matches(it) || it.length != 2 }) continue
        bytes += tokens.map { it.toInt(16) }
    }
    return if (bytes.isEmpty()) emptyList() else listOf(EcuMessage("ECU", bytes))
}

/** Sorts ECUs so the engine controller (7E8 / xx10 / 10) answers first. */
fun primaryFirst(messages: List<EcuMessage>): List<EcuMessage> =
    messages.sortedBy { m ->
        when {
            m.ecu == "7E8" || m.ecu.endsWith("F110") || m.ecu == "10" -> 0
            else -> 1
        }
    }
