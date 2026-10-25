package io.github.phoenix1o1.uvbridge.chirp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The CHIRP download/upload against a simulated radio that answers as
 * App/app/uart.c does, and the packets against the CHIRP driver's own framing.
 */
class RadioCloneTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Packets produced by f4hwn.chirp.v6.3.0.py's xorarr / CRC / _send_command.
    @Test fun framingMatchesTheChirpDriver() {
        assertArrayEquals(hex("abcd0800026910e644a85a24b9a9dcba"), K5Protocol.frame(K5Protocol.HELLO))
        val read = byteArrayOf(0x1b, 0x05, 0x08, 0x00, 0x34, 0x12, 0x80.toByte(), 0) + K5Protocol.TIMESTAMP
        assertArrayEquals(hex("abcd0c000d691ce61a838d404b0c82247ff2dcba"), K5Protocol.frame(read))
        val data = ByteArray(16) { it.toByte() }
        val write = byteArrayOf(0x1d, 0x05, 24, 0, 0x40, 0x00, 16, 1) + K5Protocol.TIMESTAMP + data
        assertArrayEquals(hex("abcd1c000b690ce66e911d414b0c82241302eb83126912e12698074b2d38db4f5489dcba"),
            K5Protocol.frame(write))
        // and what the protocol class itself sends
        val radio = FakeRadio()
        K5Protocol(radio).apply { hello() }.writeMem(0x40, data)
        assertArrayEquals(hex("abcd1c000b690ce66e911d414b0c82241302eb83126912e12698074b2d38db4f5489dcba"),
            radio.lastPacket)
    }

    @Test fun downloadReadsTheWholeMemory() {
        val radio = FakeRadio()
        val clone = RadioClone(K5Protocol(radio))
        assertEquals("v6.3.0", clone.connect())
        val img = clone.download { _, _, _ -> }
        assertEquals(ChirpImage.DOWNLOAD_SIZE, img.memory.size)
        assertArrayEquals(radio.mem.copyOf(ChirpImage.DOWNLOAD_SIZE), img.memory)
        // a downloaded image parses back as a CHIRP image for this model
        val again = ChirpImage.parse(img.toBytes())
        assertEquals(ChirpImage.MODEL, again.model)
    }

    @Test fun uploadWritesOnlyChirpsRangeAndNeverCalibration() {
        val radio = FakeRadio()
        val calibration = radio.mem.copyOfRange(ChirpImage.CAL_START, ChirpImage.DOWNLOAD_SIZE)
        val image = syntheticImage()
        val clone = RadioClone(K5Protocol(radio))
        clone.connect()
        val mismatches = clone.upload(image) { _, _, _ -> }
        assertTrue("verified", mismatches.isEmpty())
        assertArrayEquals(image.memory.copyOf(ChirpImage.PROG_SIZE), radio.mem.copyOf(ChirpImage.PROG_SIZE))
        assertArrayEquals(calibration, radio.mem.copyOfRange(ChirpImage.CAL_START, ChirpImage.DOWNLOAD_SIZE))
        assertEquals(ChirpImage.PROG_SIZE, radio.highestWrite)
        assertTrue("restarted", radio.wasReset)
    }

    @Test fun writesWithoutHelloAreIgnoredByTheRadio() {
        val radio = FakeRadio()
        try {
            K5Protocol(radio, timeoutMs = 300, writeTimeoutMs = 300).writeMem(0, ByteArray(8))
            fail("expected a timeout")
        } catch (e: RadioException) { }
        assertEquals(0xff.toByte(), radio.mem[0])
    }

    @Test fun flashModeIsReported() {
        val radio = FakeRadio(bootloader = true)
        try {
            RadioClone(K5Protocol(radio)).connect()
            fail("expected an error")
        } catch (e: RadioException) {
            assertTrue(e.message!!.contains("flash"))
        }
    }

    @Test fun wrongModelIsRefused() {
        val other = ChirpImage(ByteArray(ChirpImage.DOWNLOAD_SIZE),
            "{\"vendor\": \"Quansheng\", \"model\": \"UV-K5\"}").toBytes()
        try { ChirpImage.parse(other); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("UV-K5"))
        }
        try { ChirpImage.parse(ByteArray(100)); fail() } catch (e: IllegalArgumentException) { }
    }

    // Writes wait while the radio erases flash: seconds per block at worst.
    @Test fun slowWritesAreWaitedFor() {
        val radio = FakeRadio().apply { writeDelayMs = 1500 }
        val k5 = K5Protocol(radio, timeoutMs = 500, writeTimeoutMs = 3000).apply { hello() }
        k5.writeMem(0x40, ByteArray(16) { 1 })
        assertEquals(1, radio.writes)
    }

    // A lost reply is retried; writing the same block again is harmless.
    @Test fun lostWriteReplyIsRetried() {
        val radio = FakeRadio().apply { dropReplyOfWrite += 3 }
        val image = syntheticImage()
        val clone = RadioClone(K5Protocol(radio, timeoutMs = 300, writeTimeoutMs = 300))
        clone.connect()
        assertTrue(clone.upload(image) { _, _, _ -> }.isEmpty())
        assertArrayEquals(image.memory.copyOf(ChirpImage.PROG_SIZE), radio.mem.copyOf(ChirpImage.PROG_SIZE))
        assertEquals((ChirpImage.PROG_SIZE + ChirpImage.BLOCK - 1) / ChirpImage.BLOCK + 1, radio.writes)
    }

    // A reply that comes after the retry was sent leaves a second, stale reply
    // behind; the next commands skip it.
    @Test fun lateReplyIsSkipped() {
        val radio = FakeRadio().apply { writeDelayMs = 500; slowWrites = 1 }
        val k5 = K5Protocol(radio, timeoutMs = 300, writeTimeoutMs = 300).apply { hello() }
        k5.writeMem(0x00, ByteArray(8) { 1 })
        k5.writeMem(0x80, ByteArray(8) { 2 })
        assertArrayEquals(ByteArray(8) { 1 }, k5.readMem(0x00, 8))
        assertArrayEquals(ByteArray(8) { 2 }, k5.readMem(0x80, 8))
    }

    /** A real image, when given: -Dchirp.img=/path/to/file.img (not committed). */
    @Test fun realImageRoundTrip() {
        val path = System.getProperty("chirp.img") ?: return
        val image = ChirpImage.parse(File(path).readBytes())
        println("image: ${image.channelCount()} channels, lists ${image.listNames()}")
        val radio = FakeRadio()
        val clone = RadioClone(K5Protocol(radio))
        clone.connect()
        assertTrue(clone.upload(image) { _, _, _ -> }.isEmpty())
        assertArrayEquals(image.memory.copyOf(ChirpImage.PROG_SIZE), radio.mem.copyOf(ChirpImage.PROG_SIZE))
        val back = RadioClone(K5Protocol(radio)).apply { connect() }.download { _, _, _ -> }
        assertEquals(image.channelCount(), back.channelCount())
        assertEquals(image.listNames(), back.listNames())
    }

    private fun syntheticImage(): ChirpImage {
        val mem = ByteArray(ChirpImage.DOWNLOAD_SIZE) { (it * 7 + 3).toByte() }
        return ChirpImage.parse(ChirpImage.fromDownload(mem).toBytes())
    }
}

/**
 * The radio side of App/app/uart.c: checks the CRC and the session timestamp,
 * answers 0x0514 / 0x051B / 0x051D, restarts on 0x05DD. Delivers its replies
 * in random small chunks, with stray bytes between them.
 */
class FakeRadio(private val bootloader: Boolean = false) : SerialLink {
    val mem = ByteArray(0x10000) { 0xff.toByte() }.also { m ->
        for (i in ChirpImage.CAL_START until ChirpImage.MEM_SIZE) m[i] = (i * 13).toByte()   // calibration
    }
    var wasReset = false
    var highestWrite = 0
    var lastPacket = ByteArray(0)
    var writes = 0
    /** Reply delay for the first [slowWrites] writes, as while erasing flash. */
    var writeDelayMs = 0L
    var slowWrites = Int.MAX_VALUE
    /** Writes (1-based) whose reply is lost. */
    val dropReplyOfWrite = mutableSetOf<Int>()
    private var readyAt = 0L
    private var timestamp: ByteArray? = null
    private val out = ArrayDeque<Byte>()
    private val rnd = Random(1)

    override fun write(data: ByteArray) {
        lastPacket = data
        check(data[0] == 0xab.toByte() && data[1] == 0xcd.toByte() && data[3] == 0.toByte())
        val len = data[2].toInt() and 0xff
        check(data[4 + len + 2] == 0xdc.toByte() && data[4 + len + 3] == 0xba.toByte())
        val plain = K5Protocol.xor(data.copyOfRange(4, 4 + len + 2))
        val payload = plain.copyOf(len)
        val crc = (plain[len].toInt() and 0xff) or ((plain[len + 1].toInt() and 0xff) shl 8)
        if (crc != K5Protocol.crc16xmodem(payload)) return        // the firmware drops bad frames
        handle(payload)
    }

    private fun handle(p: ByteArray) {
        val id = (p[0].toInt() and 0xff) or ((p[1].toInt() and 0xff) shl 8)
        when (id) {
            0x0514 -> {
                timestamp = p.copyOfRange(4, 8)
                val version = (if (bootloader) "7.00.07" else "v6.3.0").toByteArray().copyOf(16)
                reply(if (bootloader) 0x0518 else 0x0515, version + ByteArray(20))
            }
            0x051B -> {
                if (!p.copyOfRange(8, 12).contentEquals(timestamp)) return
                val off = u16(p, 4); val size = p[6].toInt() and 0xff
                reply(0x051C, byteArrayOf(p[4], p[5], p[6], 0) + mem.copyOfRange(off, off + size))
            }
            0x051D -> {
                if (!p.copyOfRange(8, 12).contentEquals(timestamp)) return
                val off = u16(p, 4); val size = p[6].toInt() and 0xff
                for (i in 0 until size / 8 * 8) mem[off + i] = p[12 + i]
                highestWrite = maxOf(highestWrite, off + size)
                writes++
                if (writes in dropReplyOfWrite) return
                if (writes <= slowWrites) readyAt = maxOf(readyAt, System.currentTimeMillis() + writeDelayMs)
                reply(0x051E, byteArrayOf(p[4], p[5]))
            }
            0x05DD -> wasReset = true
        }
    }

    private fun reply(id: Int, body: ByteArray) {
        val payload = byteArrayOf((id and 0xff).toByte(), (id shr 8).toByte(),
            (body.size and 0xff).toByte(), (body.size shr 8).toByte()) + body
        repeat(rnd.nextInt(3)) { out.addLast(rnd.nextInt(256).toByte()) }   // stray bytes
        val frame = byteArrayOf(0xab.toByte(), 0xcd.toByte(), payload.size.toByte(), 0) +
            K5Protocol.xor(payload) + byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xdc.toByte(), 0xba.toByte())
        frame.forEach { out.addLast(it) }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (out.isEmpty() || System.currentTimeMillis() < readyAt) { Thread.sleep(minOf(timeoutMs, 20).toLong()); return 0 }
        val n = minOf(buf.size, out.size, 1 + rnd.nextInt(40))
        for (i in 0 until n) buf[i] = out.removeFirst()
        return n
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
}
