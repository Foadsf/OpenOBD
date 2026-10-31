package io.github.foadsf.openobd

import io.github.foadsf.openobd.elm.DemoTransport
import io.github.foadsf.openobd.elm.Elm327
import io.github.foadsf.openobd.obd.DtcKind
import io.github.foadsf.openobd.obd.Obd
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole stack — framing, ELM327 link, services — against the simulated car. */
class DemoCarTest {
    private fun withCar(block: suspend (Obd) -> Unit) = runBlocking {
        val t = DemoTransport(latencyMs = 1)
        t.open()
        val obd = Obd(Elm327(t))
        assertEquals("ELM327 v1.5", obd.resetAdapter())
        block(obd)
        t.close()
    }

    @Test
    fun connectsAndReadsLiveData() = withCar { obd ->
        val probe = obd.probe()
        assertTrue(probe.online)
        assertEquals("ISO 15765-4 (CAN 11/500)", probe.protocol)
        val supported = obd.supported(1)
        assertTrue(0x0C in supported && 0x42 in supported && 0xA6 in supported)
        val rpm = obd.readPid(0x0C)!!
        assertEquals(2, rpm.size)
        assertTrue(obd.voltage()!!.endsWith("V"))
    }

    @Test
    fun readsAndClearsCodes() = withCar { obd ->
        obd.probe()
        assertEquals(listOf("P0171", "P0420"), obd.readDtcs(DtcKind.STORED).first.map { it.code })
        assertEquals(listOf("P0300"), obd.readDtcs(DtcKind.PENDING).first.map { it.code })
        assertEquals(listOf("P0420"), obd.readDtcs(DtcKind.PERMANENT).first.map { it.code })
        assertTrue(obd.readiness()!!.milOn)
        assertEquals("P0171", obd.freezeFrame()!!.dtc)

        assertNull(obd.clearDtcs())
        assertTrue(obd.readDtcs(DtcKind.STORED).first.isEmpty())
        assertEquals(listOf("P0420"), obd.readDtcs(DtcKind.PERMANENT).first.map { it.code })
        assertNull(obd.freezeFrame())
    }

    @Test
    fun readsVehicleInfoAcrossMultiFrameAndEcus() = withCar { obd ->
        obd.probe()
        val info = obd.vehicleInfo()
        assertEquals("DEMO0PENOBD000001", info.vin)
        assertEquals(listOf("7E8", "7E9"), info.ecus.map { it.ecu })
        assertEquals("ECM – EngineControl", info.ecus[0].name)
        assertEquals(listOf("OPENOBD-DEMO-CAL"), info.ecus[0].calibrations)
        assertEquals(listOf("1A2B3C4D"), info.ecus[0].cvns)
    }

    @Test
    fun monitorTestsFlagTheFailingCatalyst() = withCar { obd ->
        obd.probe()
        val tests = obd.tests()!!
        assertEquals(3, tests.size)
        assertEquals(listOf("Catalyst B1"), tests.filterNot { it.passed }.map { it.midName })
    }

    @Test
    fun terminalChangesAreUndoneBeforeTheNextRequest() = withCar { obd ->
        obd.probe()
        obd.raw("ATH0")
        obd.raw("ATE1")
        // Headers back on and echo off again, so framing still parses.
        assertEquals(2, obd.readPid(0x0C)!!.size)
        assertEquals("DEMO0PENOBD000001", obd.vehicleInfo().vin)
    }
}
