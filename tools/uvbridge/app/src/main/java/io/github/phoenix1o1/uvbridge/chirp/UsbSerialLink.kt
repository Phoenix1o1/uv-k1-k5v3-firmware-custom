package io.github.phoenix1o1.uvbridge.chirp

import com.hoho.android.usbserial.driver.UsbSerialPort

/** [SerialLink] over a USB serial adapter, opened at the radio's 38400 8N1. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink, AutoCloseable {

    init {
        port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // CHIRP (pyserial) raises DTR and RTS on open; some cables need it.
        try { port.dtr = true; port.rts = true } catch (_: Exception) {}
    }

    override fun write(data: ByteArray) = port.write(data, 2000)

    override fun read(buf: ByteArray, timeoutMs: Int): Int = port.read(buf, timeoutMs.coerceIn(1, 1000))

    override fun close() = try { port.close() } catch (_: Exception) {}
}
