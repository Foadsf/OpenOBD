package io.github.foadsf.openobd

import io.github.foadsf.openobd.elm.BaseTransport
import io.github.foadsf.openobd.elm.Elm327
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmLinkTest {
    /** An adapter that is still searching when the app gives up: it only stops on the next character. */
    private class StillSearching : BaseTransport() {
        val received = mutableListOf<String>()
        private var searching = false
        override suspend fun open() {}
        override fun close() = lost("Closed")
        override suspend fun write(data: ByteArray) {
            val text = String(data, Charsets.US_ASCII)
            received += text
            when {
                searching -> {
                    // The interrupting character is discarded, never run as a command.
                    searching = false
                    deliver("STOPPED\r\r>".toByteArray())
                }
                text == "0100\r" -> {
                    searching = true
                    deliver("SEARCHING...\r".toByteArray())
                }
                else -> deliver("${text.trim()}\rOK\r\r>".toByteArray())
            }
        }
    }

    @Test
    fun aCommandAfterATimeoutIsNotSwallowed() = runBlocking {
        val t = StillSearching()
        val elm = Elm327(t)
        assertTrue(elm.send("0100", timeoutMs = 50).startsWith("SEARCHING"))
        assertEquals(listOf("0100\r", "\r"), t.received)
        assertTrue("OK" in elm.send("ATDPN"))
        assertEquals("ATDPN\r", t.received.last())
    }
}
