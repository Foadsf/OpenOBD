package io.github.foadsf.openobd

import io.github.foadsf.openobd.elm.Family
import io.github.foadsf.openobd.elm.firstError
import io.github.foadsf.openobd.elm.parseFrames
import io.github.foadsf.openobd.elm.parseHeaderless
import io.github.foadsf.openobd.elm.responseLines
import io.github.foadsf.openobd.obd.AT_CHECKS
import io.github.foadsf.openobd.obd.AdapterReport
import io.github.foadsf.openobd.obd.AtAnswer
import io.github.foadsf.openobd.obd.AtResult
import io.github.foadsf.openobd.obd.ELM_VERSIONS
import io.github.foadsf.openobd.obd.decodeReadiness
import io.github.foadsf.openobd.obd.describeDtc
import io.github.foadsf.openobd.obd.dtcCode
import io.github.foadsf.openobd.obd.parseTests
import io.github.foadsf.openobd.obd.pidInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun report(id: String, upTo: String, acceptsAnything: Boolean = false) = AdapterReport(
        id,
        AT_CHECKS.map {
            val ok = ELM_VERSIONS.indexOf(it.version) <= ELM_VERSIONS.indexOf(upTo)
            AtResult(it, if (ok) AtAnswer.YES else AtAnswer.NO, if (ok) "OK" else "?")
        },
        acceptsAnything,
    )

    @Test
    fun adapterVerdicts() {
        val genuine = report("ELM327 v2.2", "2.2")
        assertEquals("2.2", genuine.behavesLike)
        assertEquals("Handles every command up to v2.2, as it reports.", genuine.verdict)
        assertTrue(genuine.honest)

        val overclaims = report("ELM327 v2.1", "1.3a")
        assertEquals("Reports v2.1 but only handles every command up to v1.3a.", overclaims.verdict)
        assertTrue("v1.4b 0/4" in overclaims.asText())
        assertFalse(overclaims.honest)

        // A gap in an early version caps the result even if later commands work.
        val gap = AdapterReport("ELM327 v1.4b", AT_CHECKS.map { AtResult(it, if (it.command == "ATPPS") AtAnswer.NO else AtAnswer.YES, "") }, false)
        assertEquals("1.0", gap.behavesLike)

        val yesToAll = report("ELM327 v2.1", "2.2", acceptsAnything = true)
        assertTrue("cannot tell" in yesToAll.verdict)

        val broken = AdapterReport("OBDII v1.5", AT_CHECKS.map { AtResult(it, if (it.command == "ATS1") AtAnswer.SILENT else AtAnswer.YES, "") }, false)
        assertEquals(listOf("ATS1"), broken.missingForApp)
    }

    @Test
    fun responseLinesDropEchoSearchingAndBusInit() {
        val raw = "0100\rSEARCHING...\rBUS INIT: ...OK\r7E8 06 41 00 BE 3F A8 13 00\r\r"
        assertEquals(listOf("7E8 06 41 00 BE 3F A8 13 00"), responseLines(raw, "0100"))
        assertEquals("UNABLE TO CONNECT", firstError(responseLines("SEARCHING...\rUNABLE TO CONNECT\r")))
    }

    @Test
    fun can11SingleFrameStripsPciAndPadding() {
        val m = parseFrames(listOf("7E8 06 41 00 BE 3F A8 13 00"), Family.CAN11).single()
        assertEquals("7E8", m.ecu)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), m.data)
    }

    @Test
    fun can11MultiFrameIsReassembledPerEcu() {
        val lines = listOf(
            "7E8 10 14 49 02 01 31 47 31",
            "7E9 03 41 0D 32 00 00 00 00",
            "7E8 21 4A 43 35 34 34 34 52",
            "7E8 22 37 32 35 32 33 36 37",
        )
        val msgs = parseFrames(lines, Family.CAN11)
        val vin = msgs.first { it.ecu == "7E8" }.data
        assertEquals(0x14, vin.size)
        assertEquals("1G1JC5444R7252367", vin.drop(3).map { it.toChar() }.joinToString(""))
        assertEquals(listOf(0x41, 0x0D, 0x32), msgs.first { it.ecu == "7E9" }.data)
    }

    @Test
    fun multiFramePaddingIsCutToDeclaredLength() {
        val lines = listOf("7E8 10 0B 49 04 01 41 42 43", "7E8 21 44 45 46 47 48 AA AA")
        val m = parseFrames(lines, Family.CAN11).single()
        assertEquals(11, m.data.size)
        assertEquals(0x48, m.data.last())
    }

    @Test
    fun can29HeaderIsFourBytes() {
        val m = parseFrames(listOf("18 DA F1 10 03 41 0D 32 AA AA AA AA"), Family.CAN29).single()
        assertEquals("18DAF110", m.ecu)
        assertEquals(listOf(0x41, 0x0D, 0x32), m.data)
    }

    @Test
    fun legacyFramesDropHeaderAndChecksum() {
        val lines = listOf("48 6B 10 43 01 71 04 20 00 00 C4", "48 6B 10 43 03 00 00 00 00 00 7F")
        val msgs = parseFrames(lines, Family.LEGACY)
        assertEquals(2, msgs.size)
        assertEquals("10", msgs[0].ecu)
        assertEquals(listOf(0x43, 0x01, 0x71, 0x04, 0x20, 0x00, 0x00), msgs[0].data)
    }

    @Test
    fun headerlessNumberedMultiFrameIsJoined() {
        val m = parseHeaderless(listOf("014", "0: 49 02 01 31 47 31", "1: 4A 43 35 34 34 34 52", "2: 37 32 35 32 33 36 37")).single()
        assertEquals(20, m.data.size)
        assertEquals(0x49, m.data[0])
    }

    @Test
    fun standardFormulas() {
        assertEquals(1726.0, pidInfo(0x0C).decode(intArrayOf(0x1A, 0xF8)).value!!, 1e-9) // (256A+B)/4
        assertEquals(50.0, pidInfo(0x05).decode(intArrayOf(90)).value!!, 1e-9) // A-40
        assertEquals(-100.0, pidInfo(0x06).decode(intArrayOf(0)).value!!, 1e-9) // (A-128)/1.28
        assertEquals(99.21875, pidInfo(0x06).decode(intArrayOf(255)).value!!, 1e-9)
        assertEquals(50.0, pidInfo(0x0D).decode(intArrayOf(50)).value!!, 1e-9)
        assertEquals(12.6, pidInfo(0x42).decode(intArrayOf(0x31, 0x38)).value!!, 1e-9)
        assertEquals("PID FE", pidInfo(0xFE).name) // unknown PIDs still show, raw
    }

    @Test
    fun dtcBytesToCodes() {
        assertEquals("P0171", dtcCode(0x01, 0x71))
        assertEquals("C0300", dtcCode(0x43, 0x00))
        assertEquals("B1234", dtcCode(0x92, 0x34))
        assertEquals("U0100", dtcCode(0xC1, 0x00))
        assertEquals("System too lean (bank 1)", describeDtc("P0171"))
        assertEquals("Cylinder 4 misfire detected", describeDtc("P0304"))
    }

    @Test
    fun readinessBits() {
        // MIL on, 2 codes; spark ignition; catalyst, evap, O2, O2 heater supported; evap incomplete.
        val r = decodeReadiness(intArrayOf(0x82, 0x07, 0x65, 0x04))
        assertTrue(r.milOn)
        assertEquals(2, r.dtcCount)
        assertFalse(r.compressionIgnition)
        assertFalse(r.monitors.first { it.name == "Evaporative system" }.complete)
        assertTrue(r.monitors.first { it.name == "Catalyst" }.complete)
        assertEquals(1, r.incomplete)
    }

    @Test
    fun mode06LimitsDecideThePass() {
        val tests = parseTests(listOf(0x21, 0x80, 0x01, 0x00, 0xA0, 0x00, 0x00, 0x00, 0x80))
        assertFalse(tests.single().passed)
        val signed = parseTests(listOf(0x01, 0x01, 0x81, 0xFF, 0xFE, 0xFF, 0x00, 0x00, 0x10))
        assertTrue(signed.single().passed) // -2 within -256..16
    }
}
