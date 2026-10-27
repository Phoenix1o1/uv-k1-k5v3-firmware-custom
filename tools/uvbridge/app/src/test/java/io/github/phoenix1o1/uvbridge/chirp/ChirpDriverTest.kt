package io.github.phoenix1o1.uvbridge.chirp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Reading CHIRP driver files: MEM_FORMAT, sizes, lists, setting labels. */
class ChirpDriverTest {

    private val format = """
        #seekto 0x0010;
        struct {
          ul32 freq;
          u8 a:4,
             b:4;
          u8 __UNUSED:1, c:7;   // comment
          char name[3];
        } ch[4];
        /* block
           comment */
        #seekto 0x0100;
        u8 x;
        #seek 2;
        ul16 y[3];
        struct {
          u8 p:1, q:7;
          ul16 r;
        } s;
    """

    @Test fun memFormatOffsetsAndBitFields() {
        val f = MemFormat.parse(format)
        assertEquals(ChirpDriver.Field(0x10, 9, 4, 9, type = "struct"), f["ch"])
        assertEquals(ChirpDriver.Field(0x10, 4, type = "ul32"), f["ch.freq"])
        assertEquals(ChirpDriver.Field(0x14, 1, bits = 4, shift = 4), f["ch.a"])
        assertEquals(ChirpDriver.Field(0x14, 1, bits = 4, shift = 0), f["ch.b"])
        assertEquals(ChirpDriver.Field(0x15, 1, bits = 7, shift = 0), f["ch.c"])
        assertEquals(ChirpDriver.Field(0x16, 3, type = "char"), f["ch.name"])
        assertEquals(0x100, f["x"]!!.offset)
        assertEquals(ChirpDriver.Field(0x103, 2, 3, 2, type = "ul16"), f["y"])
        assertEquals(ChirpDriver.Field(0x109, 1, bits = 1, shift = 7), f["s.p"])
        assertEquals(0x10A, f["s.r"]!!.offset)
    }

    private fun driverSource(extra: String = "", progSize: String = "0x00A178") =
        "DRIVER_VERSION = \"test v9.1.0\"\nMEM_SIZE = 0x00B190\nPROG_SIZE = $progSize\nCAL_START = 0x00B000\n" +
        "SET_FNT_LIST = [\"SMALL\",\n  \"BIG\"] + [\"HUGE\"]\nCOMPUTED_LIST = [x for x in y]\n" +
        "class R:\n    VENDOR = \"Quansheng\"\n    MODEL = \"UV-K1 & UV-K5 V3 (F4HWN)\"\n" +
        "        val = RadioSettingValueList(SET_FNT_LIST, None, 0)\n        rs = RadioSetting(\"set_fnt\", \"Set Font\", val)\n" +
        "MEM_FORMAT = \"\"\"\n" + BUILTIN_LIKE + extra + "\n\"\"\"\n"

    @Test fun driverConstantsListsAndLabels() {
        val d = ChirpDriver.parse("f4hwn.chirp.v9.1.0.py", driverSource("#seekto 0xA15B;\nu8 set_fnt:2, __UNUSED_X:6;"))
        assertEquals("9.1.0", d.firmwareVersion)
        assertEquals(0xA178, d.progSize)
        assertEquals(listOf("SMALL", "BIG", "HUGE"), d.lists["SET_FNT_LIST"])
        assertTrue("COMPUTED_LIST" !in d.lists)
        assertEquals("Set Font", d.labels["set_fnt"])
        assertEquals("SET_FNT_LIST", d.settingLists["set_fnt"])
        assertEquals(ChirpDriver.Field(0xA15B, 1, bits = 2, shift = 6), d.fields["set_fnt"])
        // a field the app does not know shows up as an extra setting
        val extra = Settings.forDriver(d).single { it.label.startsWith("Set Font") }
        val mem = ByteArray(0xB200)
        extra.set(mem, 2)
        assertEquals(0x80, mem[0xA15B].toInt() and 0xff)
        assertEquals("HUGE", extra.display(mem))
    }

    @Test fun driverThatWouldWriteCalibrationIsRefused() {
        try { ChirpDriver.parse("bad.py", driverSource(progSize = "0x00B100")); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("calibration")) }
    }

    /** The real driver, when given: -Pchirp.driver=/path/f4hwn.chirp.v6.3.0.py (not committed). */
    @Test fun realDriverMatchesTheBuiltinLayout() {
        val path = System.getProperty("chirp.driver") ?: return
        val d = ChirpDriver.parse(File(path).name, File(path).readText())
        println("${d.name}: ${d.version}, ${d.fields.size} fields, ${d.lists.size} lists, ${d.labels.size} labels")
        for ((name, f) in ChirpDriver.BUILTIN.fields) {
            val theirs = d.fields[name] ?: throw AssertionError("missing $name")
            assertEquals(name, f.offset, theirs.offset)
            assertEquals(name, f.bits, theirs.bits)
            assertEquals(name, f.shift, theirs.shift)
            if (f.type != "struct") assertEquals(name, f.size, theirs.size)
        }
        assertEquals(ChirpDriver.BUILTIN.progSize, d.progSize)
        assertEquals(ChirpDriver.BUILTIN.memSize, d.memSize)
        // the same settings, reading and writing the same bytes
        val mine = Settings.ALL
        val theirs = Settings.forDriver(d).filter { it.group != Settings.EXTRA }
        assertEquals(mine.map { it.label }, theirs.map { it.label })
        val mem = ByteArray(0xB200) { (it * 37 + 11).toByte() }
        for ((a, b) in mine.zip(theirs)) {
            assertEquals(a.label, a.display(mem), b.display(mem))
            val m1 = mem.copyOf(); val m2 = mem.copyOf()
            if (a.options.isNotEmpty()) { a.set(m1, a.options.size - 1); b.set(m2, b.options.size - 1) }
            else { a.setText!!(m1, "AB1"); b.setText!!(m2, "AB1") }
            assertArrayEquals(a.label, m1, m2)
        }
        val extras = Settings.forDriver(d).filter { it.group == Settings.EXTRA }
        println("extra settings: " + extras.map { it.label })
    }

    companion object {
        /** The channel part of the F4HWN MEM_FORMAT, which a driver must keep. */
        const val BUILTIN_LIKE = """
#seekto 0x000000;
struct {
  ul32 freq; ul32 offset; u8 rxcode; u8 txcode;
  u8 txcodeflag:4, rxcodeflag:4;
  u8 modulation:4, offsetDir:4;
  u8 __UNUSED01:1, txLock:1, busyChLockout:1, txpower:3, bandwidth:1, freq_reverse:1;
  u8 __UNUSED02:4, dtmf_pttid:3, dtmf_decode:1;
  u8 step; u8 __UNUSED03;
} channel[1024];
#seekto 0x004000;
struct { char name[16]; } channelname[1024];
#seekto 0x008000;
struct { u8 __UNUSED04:3, compander:2, band:3; u8 scanlist; } ch_attr[1031];
#seekto 0x00880E;
struct { char name[4]; } listname[24];
"""
    }
}
