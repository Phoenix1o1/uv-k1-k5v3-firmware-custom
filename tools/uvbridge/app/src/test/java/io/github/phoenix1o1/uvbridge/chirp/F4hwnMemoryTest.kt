package io.github.phoenix1o1.uvbridge.chirp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Channel and setting encoding against the F4HWN driver's memory map. */
class F4hwnMemoryTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Worked out from set_memory and the MEM_FORMAT bit fields, on an erased slot.
    @Test fun channelIsEncodedAsTheDriverDoes() {
        val mem = ByteArray(ChirpImage.DOWNLOAD_SIZE) { 0xff.toByte() }
        val c = Channel(number = 3, name = "TEST", freqHz = 145_600_000, duplex = 2, offsetHz = 600_000,
            mode = 1, power = 7, step = 5, txTone = Tone(Tone.CTCSS, 8), rxTone = Tone(Tone.DCS_R, 0),
            scanList = 3, compander = 3, txLock = true, busyLock = true, reverse = false, pttId = 2)
        Channels.write(mem, c)
        //            freq      offset    rx tx fl md flags pt st --
        assertArrayEquals(hex("002bde0060ea0000" + "0008" + "1302" + "fef405ff"), mem.copyOfRange(32, 48))
        assertArrayEquals("TEST      ".toByteArray() + ByteArray(6), mem.copyOfRange(0x4020, 0x4030))
        assertArrayEquals(hex("fa03"), mem.copyOfRange(0x8004, 0x8006))
        assertEquals(c, Channels.read(mem, 3))

        Channels.delete(mem, 3)
        assertNull(Channels.read(mem, 3))
        assertArrayEquals(ByteArray(16) { 0x20 }, mem.copyOfRange(0x4020, 0x4030))
        assertArrayEquals(hex("ffff"), mem.copyOfRange(0x8004, 0x8006))
    }

    @Test fun usbIsStoredNarrow() {
        val mem = ByteArray(ChirpImage.DOWNLOAD_SIZE) { 0xff.toByte() }
        Channels.write(mem, Channel(number = 1, freqHz = 14_200_000, mode = 4))
        assertEquals(0x20, mem[0x0B].toInt() and 0xf0)          // modulation 2
        assertEquals(0x02, mem[0x0C].toInt() and 0x02)          // bandwidth narrow
        assertEquals(4, Channels.read(mem, 1)!!.mode)
    }

    @Test fun erasedF4hwnByteReadsAsDefaultsAndIsInitialised() {
        val mem = ByteArray(ChirpImage.DOWNLOAD_SIZE) { 0xff.toByte() }
        val scan = Settings.ALL.first { it.label.startsWith("Scan speed") }
        assertEquals("NORMAL", scan.display(mem))
        scan.set(mem, 1)                                         // FAST is stored as 0
        assertEquals(0x00, mem[0xA15F].toInt() and 0xff)
        assertEquals("FAST", scan.display(mem))
    }

    /** On a real image (-Dchirp.img=...): re-encoding everything as read changes nothing. */
    @Test fun realImageReencodesUnchanged() {
        val path = System.getProperty("chirp.img") ?: return
        val image = ChirpImage.parse(File(path).readBytes())
        val mem = image.memory.copyOf()
        val channels = (1..Channels.COUNT).mapNotNull { Channels.read(mem, it) }
        channels.forEach { Channels.write(mem, it) }
        assertArrayEquals("channels", image.memory.copyOf(0x9000), mem.copyOf(0x9000))
        for (s in Settings.ALL) {
            val v = s.get(mem)
            if (v != null) s.set(mem, v)
            s.text?.let { s.setText!!(mem, it(mem)) }
        }
        val diff = (0xA000 until ChirpImage.PROG_SIZE).filter { mem[it] != image.memory[it] }
        assertTrue("settings changed at ${diff.map { "%04X".format(it) }}", diff.isEmpty())

        for (c in channels.take(12)) println("%4d %-10s %10.5f %s %-4s %-14s tx[%s] rx[%s] list %d".format(
            c.number, c.name, c.freqHz / 1e6, Channels.DUPLEXES[c.duplex], Channels.MODES[c.mode],
            Channels.POWERS[c.power], c.txTone, c.rxTone, c.scanList))
        for (s in Settings.ALL.take(20)) println("${s.label}: ${s.display(mem)}")
    }
}
