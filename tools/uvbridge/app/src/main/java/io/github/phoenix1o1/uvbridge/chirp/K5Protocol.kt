package io.github.phoenix1o1.uvbridge.chirp

import java.io.IOException

/** A serial connection: the USB port in the app, a simulated radio in tests. */
interface SerialLink {
    fun write(data: ByteArray)
    /** Reads up to buf.size bytes, waiting at most timeoutMs; returns 0 on timeout. */
    fun read(buf: ByteArray, timeoutMs: Int): Int
}

class RadioException(message: String) : IOException(message)

/**
 * The Quansheng UV-K5 / UV-K1 programming protocol, as the F4HWN CHIRP driver
 * (f4hwn.chirp.v6.3.0.py) speaks it and App/app/uart.c answers it.
 *
 * Packet: AB CD | len | 00 | obfuscated(payload + CRC16-XMODEM LE) | DC BA.
 * Payload: message id (LE u16), body length (LE u16), body.
 * Replies use the same frame; their CRC is not meaningful and is ignored.
 */
class K5Protocol(private val link: SerialLink, private val timeoutMs: Int = 6000) {

    /** Says hello; returns the firmware version string. Writes need this first. */
    fun hello(): String {
        var reply: ByteArray? = null
        for (attempt in 1..5) {
            send(HELLO)
            reply = try { receive() } catch (e: RadioException) { if (attempt == 5) throw e; null }
            if (reply != null) break
        }
        val r = reply!!
        if (r.size >= 2 && r[0] == 0x18.toByte() && r[1] == 0x05.toByte())
            throw RadioException("The radio is in flash (DFU) mode. Restart it normally.")
        if (r.size < 4 || r[0] != 0x15.toByte() || r[1] != 0x05.toByte())
            throw RadioException("Unexpected answer to hello (id ${hex16(r)})")
        return printable(r, 4, 16)
    }

    /** Reads [length] (≤ 128) bytes at [offset]. */
    fun readMem(offset: Int, length: Int): ByteArray {
        val cmd = byteArrayOf(0x1b, 0x05, 0x08, 0x00,
            lo(offset), hi(offset), length.toByte(), 0) + TIMESTAMP
        send(cmd)
        val r = receive()
        if (r.size < 8 + length || r[0] != 0x1c.toByte() || r[1] != 0x05.toByte() ||
            r[4] != lo(offset) || r[5] != hi(offset))
            throw RadioException("Bad answer to read at 0x%04X".format(offset))
        return r.copyOfRange(8, 8 + length)
    }

    /** Writes [data] (≤ 128 bytes, a multiple of 8) at [offset]. */
    fun writeMem(offset: Int, data: ByteArray) {
        val n = data.size
        val cmd = byteArrayOf(0x1d, 0x05, (n + 8).toByte(), 0,
            lo(offset), hi(offset), n.toByte(), 1 /* allow password area */) + TIMESTAMP + data
        send(cmd)
        val r = receive()
        if (r.size < 6 || r[0] != 0x1e.toByte() || r[4] != lo(offset) || r[5] != hi(offset))
            throw RadioException("Bad answer to write at 0x%04X".format(offset))
    }

    /** Restarts the radio, as CHIRP does after an upload. No answer. */
    fun reset() = send(byteArrayOf(0xdd.toByte(), 0x05, 0x00, 0x00))

    // ---- framing ----

    fun send(payload: ByteArray) = link.write(frame(payload))

    /** Next reply payload, deobfuscated. Skips bytes until a frame header. */
    fun receive(): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        var b = readByte(deadline)
        while (true) {
            if (b != 0xAB) { b = readByte(deadline); continue }
            b = readByte(deadline)
            if (b != 0xCD) continue          // may itself be the AB of the real header
            val len = readByte(deadline)
            b = readByte(deadline)
            if (b != 0x00) continue
            val body = readExact(len, deadline)
            val footer = readExact(4, deadline)
            if (footer[2] != 0xDC.toByte() || footer[3] != 0xBA.toByte())
                throw RadioException("Bad reply footer")
            return xor(body)
        }
    }

    // Received bytes are read in large chunks (USB serial reads need a buffer
    // of at least one USB packet) and handed out from here.
    private val rx = ByteArray(4096)
    private var rxPos = 0
    private var rxLen = 0

    private fun readByte(deadline: Long): Int {
        while (rxPos >= rxLen) {
            val left = (deadline - System.currentTimeMillis()).toInt()
            if (left <= 0) throw RadioException("No answer from the radio (timeout)")
            rxLen = link.read(rx, left)
            rxPos = 0
        }
        return rx[rxPos++].toInt() and 0xff
    }

    private fun readExact(n: Int, deadline: Long) = ByteArray(n) { readByte(deadline).toByte() }

    companion object {
        /** The driver's fixed session timestamp, 0x6457396A. */
        val TIMESTAMP = byteArrayOf(0x6a, 0x39, 0x57, 0x64)
        val HELLO = byteArrayOf(0x14, 0x05, 0x04, 0x00) + TIMESTAMP

        private val XOR_KEY = intArrayOf(22, 108, 20, 230, 46, 145, 13, 64, 33, 53, 213, 64, 19, 3, 233, 128)

        fun xor(data: ByteArray) = ByteArray(data.size) { i ->
            (data[i].toInt() xor XOR_KEY[i % XOR_KEY.size]).toByte()
        }

        fun crc16xmodem(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xff) shl 8)
                repeat(8) {
                    crc = crc shl 1
                    if (crc and 0x10000 != 0) crc = (crc xor 0x1021) and 0xffff
                }
            }
            return crc and 0xffff
        }

        fun frame(payload: ByteArray): ByteArray {
            val crc = crc16xmodem(payload)
            val body = xor(payload + byteArrayOf(lo(crc), hi(crc)))
            return byteArrayOf(0xab.toByte(), 0xcd.toByte(), payload.size.toByte(), 0) + body +
                byteArrayOf(0xdc.toByte(), 0xba.toByte())
        }

        private fun lo(v: Int) = (v and 0xff).toByte()
        private fun hi(v: Int) = ((v shr 8) and 0xff).toByte()
        private fun hex16(r: ByteArray) =
            if (r.size >= 2) "%02x%02x".format(r[1], r[0]) else "?"

        private fun printable(b: ByteArray, from: Int, max: Int): String {
            val sb = StringBuilder()
            for (i in from until minOf(b.size, from + max)) {
                val c = b[i].toInt() and 0xff
                if (c < 0x20 || c > 0x7e) break
                sb.append(c.toChar())
            }
            return sb.toString().trim()
        }
    }
}
