package io.github.phoenix1o1.uvbridge.chirp

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [SerialLink] over a USB serial adapter, opened at the radio's 38400 8N1.
 *
 * Received data comes from a background reader that never stops reading,
 * as in SerialBridge: a USB read that times out can lose bytes arriving at
 * that moment, and a write reply can take seconds while the radio erases
 * flash, so polling with short reads would drop replies.
 */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink, AutoCloseable {

    private val incoming = LinkedBlockingQueue<ByteArray>()
    private var chunk = ByteArray(0)
    private var chunkPos = 0
    @Volatile private var error: Exception? = null

    private val reader = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) { incoming.add(data) }
        override fun onRunError(e: Exception) { error = e; incoming.add(ByteArray(0)) }
    })

    init {
        port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // CHIRP (pyserial) raises DTR and RTS on open; some cables need it.
        try { port.dtr = true; port.rts = true } catch (_: Exception) {}
        reader.start()
    }

    override fun write(data: ByteArray) = port.write(data, 2000)

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (chunkPos >= chunk.size) {
            chunk = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            chunkPos = 0
            error?.let { throw IOException("Connection lost: ${it.message}") }
        }
        val n = minOf(buf.size, chunk.size - chunkPos)
        chunk.copyInto(buf, 0, chunkPos, chunkPos + n)
        chunkPos += n
        return n
    }

    override fun close() {
        reader.listener = null
        reader.stop()
        try { port.close() } catch (_: Exception) {}
    }
}
