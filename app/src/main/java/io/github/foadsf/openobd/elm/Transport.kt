package io.github.foadsf.openobd.elm

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** A byte pipe to an ELM327-compatible adapter. */
interface Transport {
    /** Bytes as they arrive from the adapter. Closed when the link drops. */
    val input: ReceiveChannel<ByteArray>

    suspend fun open()
    suspend fun write(data: ByteArray)
    fun close()
}

class LinkLostException(message: String) : Exception(message)

/** Shared plumbing: an unbounded inbox that the concrete transports feed. */
abstract class BaseTransport : Transport {
    protected val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val input: ReceiveChannel<ByteArray> get() = inbox

    protected fun deliver(bytes: ByteArray) {
        inbox.trySend(bytes)
    }

    protected fun lost(reason: String) {
        inbox.close(LinkLostException(reason))
    }
}
