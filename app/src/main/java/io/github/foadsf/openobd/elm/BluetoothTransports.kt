package io.github.foadsf.openobd.elm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

private val CCCD = uuid16("2902")
private val SPP = uuid16("1101")

/**
 * Known (service, notify, write) layouts used by ELM327-style BLE adapters. Anything else falls
 * back to the first non-standard service that offers both a notify and a write characteristic,
 * so the adapter's real UUIDs are discovered at runtime rather than assumed.
 */
private val KNOWN_LAYOUTS = listOf(
    Triple(uuid16("fff0"), uuid16("fff1"), uuid16("fff2")),
    Triple(uuid16("ffe0"), uuid16("ffe1"), uuid16("ffe1")),
    Triple(uuid16("18f0"), uuid16("2af0"), uuid16("2af1")),
    Triple(
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
    ),
)
private val GENERIC_SERVICES = setOf(uuid16("1800"), uuid16("1801"), uuid16("180a"), uuid16("180f"))

private fun BluetoothGattCharacteristic.canNotify() =
    properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

private fun BluetoothGattCharacteristic.canWrite() =
    properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

/** Bluetooth LE (GATT). Needs no system pairing — the way Veepeak recommends connecting. */
@SuppressLint("MissingPermission")
class BleTransport(private val context: Context, private val device: BluetoothDevice) : BaseTransport() {
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var ready = CompletableDeferred<Unit>()
    private val writeAck = Channel<Boolean>(Channel.CONFLATED)

    /** Which service/characteristics were chosen; shown on the adapter card. */
    var layout: String = ""
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val reason = "Bluetooth LE link closed (status $status)"
                if (!ready.isCompleted) ready.completeExceptionally(LinkLostException(reason))
                lost(reason)
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pick = pickCharacteristics(g)
            if (pick == null) {
                ready.completeExceptionally(LinkLostException("No serial service found on this device"))
                return
            }
            val (notify, write) = pick
            writer = write
            layout = "${notify.service.uuid.short()} · rx ${notify.uuid.short()} · tx ${write.uuid.short()}"
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready.complete(Unit) // some adapters notify without a CCCD
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Called below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { deliver(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value)
        }
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((s, n, w) in KNOWN_LAYOUTS) {
            val service = g.getService(s) ?: continue
            val notify = service.getCharacteristic(n)
            val write = service.getCharacteristic(w)
            if (notify != null && write != null && notify.canNotify() && write.canWrite()) return notify to write
        }
        for (service in g.services) {
            if (service.uuid in GENERIC_SERVICES) continue
            val notify = service.characteristics.firstOrNull { it.canNotify() } ?: continue
            val write = service.characteristics.firstOrNull { it.canWrite() } ?: continue
            return notify to write
        }
        return null
    }

    override suspend fun open() {
        // Android's GATT stack often fails the first attempt with status 133; one retry fixes most.
        repeat(2) { attempt ->
            ready = CompletableDeferred()
            @Suppress("DEPRECATION") // the replacement needs API 37; this form works on every version
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            val ok = withTimeoutOrNull(15_000) { runCatching { ready.await() }.isSuccess } ?: false
            if (ok) return
            gatt?.close()
            gatt = null
            if (attempt == 0) delay(600)
        }
        throw LinkLostException("Could not open Bluetooth LE link to ${device.address}")
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw LinkLostException("Not connected")
        val c = writer ?: throw LinkLostException("Not connected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        // 20 bytes fits the default ATT MTU of 23, so no MTU negotiation is needed.
        for (chunk in data.toList().chunked(20)) {
            val bytes = chunk.toByteArray()
            var queued = false
            for (attempt in 0 until 5) {
                queued = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.value = bytes
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (queued) break
                delay(20) // the stack is busy with a previous operation
            }
            if (!queued) throw LinkLostException("Bluetooth LE write rejected")
            withTimeoutOrNull(2_000) { writeAck.receive() } ?: throw LinkLostException("Bluetooth LE write timed out")
        }
    }

    override fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        lost("Closed")
    }
}

/** Classic Bluetooth serial (SPP/RFCOMM). The adapter must be paired first (PIN 1234). */
@SuppressLint("MissingPermission")
class ClassicTransport(private val device: BluetoothDevice) : BaseTransport() {
    private var socket: BluetoothSocket? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            // Many ELM327 clones only answer on channel 1 without an SDP record.
            { device.javaClass.getMethod("createRfcommSocket", Int::class.java).invoke(device, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (make in attempts) {
            val s = try {
                make()
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                s.connect()
                socket = s
                startReader(s)
                return@withContext
            } catch (e: IOException) {
                last = e
                runCatching { s.close() }
            }
        }
        throw LinkLostException("Could not open Bluetooth serial link: ${last?.message ?: "unknown error"}")
    }

    private fun startReader(s: BluetoothSocket) = thread(name = "obd-rfcomm-reader", isDaemon = true) {
        val buffer = ByteArray(512)
        try {
            val input = s.inputStream
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) deliver(buffer.copyOf(n))
            }
        } catch (_: IOException) {
        }
        lost("Bluetooth serial link closed")
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw LinkLostException("Not connected")
        try {
            s.outputStream.write(data)
            s.outputStream.flush()
        } catch (e: IOException) {
            throw LinkLostException("Bluetooth serial write failed: ${e.message}")
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        lost("Closed")
    }
}

private fun UUID.short(): String {
    val s = toString()
    return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).uppercase() else s.substring(0, 8).uppercase()
}
