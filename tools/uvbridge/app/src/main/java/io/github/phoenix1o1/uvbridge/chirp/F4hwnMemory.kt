package io.github.phoenix1o1.uvbridge.chirp

/**
 * Channels and settings of a CHIRP image, read and written as the F4HWN
 * driver (f4hwn.chirp.v6.3.0.py) does in get_memory / set_memory and
 * get_settings / set_settings. Bytes the driver does not touch are kept.
 *
 * Bit fields are listed in the driver most significant bit first.
 */

// ---- channels ----

data class Tone(val type: Int = NONE, val index: Int = 0) {
    override fun toString() = when (type) {
        CTCSS -> "%.1f".format(CTCSS_TONES[index])
        DCS -> "D%03dN".format(DCS_CODES[index])
        DCS_R -> "D%03dI".format(DCS_CODES[index])
        else -> ""
    }
    companion object {
        const val NONE = 0; const val CTCSS = 1; const val DCS = 2; const val DCS_R = 3
        val TYPES = listOf("None", "CTCSS", "DCS", "DCS inverted")
    }
}

data class Channel(
    val number: Int,                 // 1..1024
    val name: String = "",
    val freqHz: Long = 145_500_000,
    val duplex: Int = 0,             // index in DUPLEXES
    val offsetHz: Long = 0,
    val mode: Int = 0,               // index in MODES
    val power: Int = 7,              // index in POWERS = the txpower field
    val step: Int = 5,               // index in STEPS (25 kHz)
    val txTone: Tone = Tone(),
    val rxTone: Tone = Tone(),
    val scanList: Int = 0,           // 0 off, 1..24, 25 all
    val compander: Int = 0,
    val txLock: Boolean = false,     // the driver's "TX Lock": ON allows TX
    val busyLock: Boolean = false,
    val reverse: Boolean = false,
    val pttId: Int = 0,
) {
    val txHz: Long get() = when (duplex) { 1 -> freqHz + offsetHz; 2 -> freqHz - offsetHz; else -> freqHz }
}

object Channels {
    const val COUNT = 1024
    val MODES = listOf("FM", "NFM", "AM", "NAM", "USB")
    val DUPLEXES = listOf("Simplex", "+", "−")
    val POWERS = listOf("USER", "LOW 1 (<20 mW)", "LOW 2 (125 mW)", "LOW 3 (250 mW)",
        "LOW 4 (500 mW)", "LOW 5 (1 W)", "MID (2 W)", "HIGH (5 W)")
    val COMPANDER = listOf("OFF", "TX", "RX", "TX/RX")
    val PTT_ID = listOf("OFF", "UP CODE", "DOWN CODE", "UP+DOWN CODE", "APOLLO QUINDAR")
    /** In the radio's order, which the step field indexes. */
    val STEPS = listOf(2.5, 5.0, 6.25, 10.0, 12.5, 25.0, 8.33, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 1.25,
        9.0, 15.0, 20.0, 30.0, 50.0, 100.0, 125.0, 200.0, 250.0, 500.0)
    /** BANDS_WIDE in MHz; the attribute byte records the band of the frequency. */
    private val BANDS = listOf(18.0 to 108.0, 108.0 to 136.9999, 137.0 to 173.9999, 174.0 to 349.9999,
        350.0 to 399.9999, 400.0 to 469.9999, 470.0 to 1300.0)
    const val MIN_HZ = 18_000_000L
    const val MAX_HZ = 1_300_000_000L

    private fun rec(n: Int) = (n - 1) * 16
    private fun nameAt(n: Int) = 0x4000 + (n - 1) * 16
    private fun attr(n: Int) = 0x8000 + (n - 1) * 2

    fun isEmpty(mem: ByteArray, n: Int): Boolean {
        val f = u32(mem, rec(n)); return f == 0L || f == 0xFFFFFFFFL
    }

    fun read(mem: ByteArray, n: Int): Channel? {
        if (isEmpty(mem, n)) return null
        val r = rec(n)
        val codeFlags = mem[r + 0x0A].toInt() and 0xff
        val modDir = mem[r + 0x0B].toInt() and 0xff
        val flags = mem[r + 0x0C].toInt() and 0xff
        val modulation = modDir shr 4
        val bandwidth = (flags shr 1) and 1
        val m = modulation * 2 + bandwidth
        val a0 = mem[attr(n)].toInt() and 0xff
        return Channel(
            number = n,
            name = String(mem.copyOfRange(nameAt(n), nameAt(n) + 16).takeWhile { it != 0.toByte() && it != 0xff.toByte() }
                .map { (it.toInt() and 0xff).toChar() }.toCharArray()).trimEnd(),
            freqHz = u32(mem, r) * 10,
            offsetHz = u32(mem, r + 4) * 10,
            duplex = when (modDir and 0x0f) { 1 -> 1; 2 -> 2; else -> 0 },
            mode = when { m < 5 -> m; m == 5 -> 4; else -> 0 },
            power = (flags shr 2) and 7,
            step = (mem[r + 0x0E].toInt() and 0xff).let { if (it < STEPS.size) it else 0 },
            txTone = tone(codeFlags shr 4, mem[r + 9].toInt() and 0xff),
            rxTone = tone(codeFlags and 0x0f, mem[r + 8].toInt() and 0xff),
            scanList = mem[attr(n) + 1].toInt() and 0xff,
            compander = ((a0 shr 3) and 3),
            txLock = (flags shr 6) and 1 == 1,
            busyLock = (flags shr 5) and 1 == 1,
            reverse = flags and 1 == 1,
            pttId = ((mem[r + 0x0D].toInt() and 0xff) shr 1 and 7).let { if (it < PTT_ID.size) it else 0 },
        )
    }

    private fun tone(flag: Int, code: Int): Tone = when (flag) {
        Tone.CTCSS -> if (code < CTCSS_TONES.size) Tone(flag, code) else Tone()
        Tone.DCS, Tone.DCS_R -> if (code < DCS_CODES.size) Tone(flag, code) else Tone()
        else -> Tone()
    }

    /** set_memory for a channel. */
    fun write(mem: ByteArray, c: Channel) {
        val r = rec(c.number)
        put32(mem, r, c.freqHz / 10)
        put32(mem, r + 4, c.offsetHz / 10)
        mem[r + 8] = c.rxTone.code().toByte()
        mem[r + 9] = c.txTone.code().toByte()
        mem[r + 0x0A] = ((c.txTone.type shl 4) or c.rxTone.type).toByte()
        val modulation = c.mode / 2
        val bandwidth = if (c.mode == 4) 1 else c.mode % 2           // USB is stored narrow
        val dir = when (c.duplex) { 1 -> 1; 2 -> 2; else -> 0 }
        mem[r + 0x0B] = ((modulation shl 4) or dir).toByte()
        val old0C = mem[r + 0x0C].toInt() and 0x80                 // unused bit kept
        mem[r + 0x0C] = (old0C or (c.txLock.bit() shl 6) or (c.busyLock.bit() shl 5) or
            (c.power shl 2) or (bandwidth shl 1) or c.reverse.bit()).toByte()
        // pttid; DTMF decode is not offered by the driver and it writes 0.
        mem[r + 0x0D] = ((mem[r + 0x0D].toInt() and 0xf0) or (c.pttId shl 1)).toByte()
        mem[r + 0x0E] = c.step.toByte()

        val name = c.name.take(10).padEnd(10, ' ')
        for (i in 0 until 16) mem[nameAt(c.number) + i] = if (i < 10) name[i].code.toByte() else 0

        val a = attr(c.number)
        mem[a] = ((mem[a].toInt() and 0xe0) or (c.compander shl 3) or band(c.freqHz)).toByte()
        mem[a + 1] = c.scanList.toByte()
    }

    /** set_memory for an empty memory. */
    fun delete(mem: ByteArray, n: Int) {
        mem.fill(0xff.toByte(), rec(n), rec(n) + 16)
        mem.fill(0x20, nameAt(n), nameAt(n) + 16)
        mem.fill(0xff.toByte(), attr(n), attr(n) + 2)
    }

    fun band(hz: Long): Int {
        val mhz = hz / 1e6
        return BANDS.indexOfFirst { mhz >= it.first && mhz <= it.second }.coerceAtLeast(0)
    }

    private fun Tone.code() = if (type == Tone.NONE) 0 else index
    private fun Boolean.bit() = if (this) 1 else 0
}

// ---- settings ----

/**
 * One radio setting: [get] gives the index of the current value in
 * [options] (null when the stored value is not a valid one), [set] stores
 * an index. Text settings have no options and use [text]/[setText].
 */
class Setting(
    val group: String,
    val label: String,
    val options: List<String> = emptyList(),
    val get: (ByteArray) -> Int? = { null },
    val set: (ByteArray, Int) -> Unit = { _, _ -> },
    val maxLength: Int = 0,
    val text: ((ByteArray) -> String)? = null,
    val setText: ((ByteArray, String) -> Unit)? = null,
) {
    fun display(mem: ByteArray): String =
        text?.invoke(mem) ?: get(mem)?.let { options.getOrNull(it) } ?: "—"
}

object Settings {

    /** A bit field of [width] bits, [shift] bits above the least significant one. */
    private fun field(group: String, label: String, addr: Int, shift: Int, width: Int, options: List<String>) =
        Setting(group, label, options,
            get = { m -> ((m[addr].toInt() and 0xff) shr shift and ((1 shl width) - 1)).takeIf { it < options.size } },
            set = { m, v ->
                val mask = ((1 shl width) - 1) shl shift
                m[addr] = ((m[addr].toInt() and mask.inv()) or ((v shl shift) and mask)).toByte()
            })

    private fun byte(group: String, label: String, addr: Int, options: List<String>) =
        field(group, label, addr, 0, 8, options)

    private fun u16(group: String, label: String, addr: Int, options: List<String>) =
        Setting(group, label, options,
            get = { m -> ((m[addr].toInt() and 0xff) or ((m[addr + 1].toInt() and 0xff) shl 8)).takeIf { it < options.size } },
            set = { m, v -> m[addr] = v.toByte(); m[addr + 1] = (v shr 8).toByte() })

    /** The F4HWN byte at 0xA15F (set_pwr:4 set_sav:2 set_scn:1 set_ptt:1);
     *  while still erased the driver shows its defaults. */
    private const val STATE7 = 0xA15F
    private fun state7(label: String, shift: Int, width: Int, options: List<String>, inverted: Boolean = false): Setting {
        val f = field("F4HWN", label, STATE7, shift, width, if (inverted) listOf("1", "0") else options)
        val erased = { m: ByteArray -> (m[STATE7].toInt() and 0xff) == 0xff }
        return Setting("F4HWN", label, options,
            get = { m -> if (erased(m)) 0 else f.get(m)?.let { if (inverted) 1 - it else it } },
            set = { m, v ->
                // the driver writes every field of this byte: start from its defaults
                if (erased(m)) m[STATE7] = 0x02       // pwr 0, sav 0, scn stored 1 (NORMAL), ptt 0
                f.set(m, if (inverted) 1 - v else v)
            })
    }

    private fun logo(label: String, addr: Int) = Setting("Basic", label, maxLength = 12,
        text = { m -> ascii(m, addr, 16).takeWhile { it != '\u0000' && it != '\u00ff' }.trim() },
        setText = { m, s ->
            val b = (s.trimEnd(' ') + "\u0000".repeat(12)).take(12) + "\u0000\u00ff\u00ff\u00ff"
            for (i in 0 until 16) m[addr + i] = b[i].code.toByte()
        })

    private fun listName(i: Int) = Setting("Scan lists", "List ${i + 1} name", maxLength = 3,
        text = { m -> ascii(m, 0x880E + i * 4, 4).filter { it.code in 32..126 }.trim() },
        setText = { m, s ->
            val b = if (s.isEmpty()) ByteArray(4) { 0xff.toByte() }
                    else s.take(3).padEnd(4, ' ').map { it.code.toByte() }.toByteArray()
            b.copyInto(m, 0x880E + i * 4)
        })

    /** RX mode: dual_watch (0xA00C) and crossband (0xA00A), set to the main VFO + 1 (0xA0C3). */
    private val rxMode = Setting("Basic", "RX mode (RxMode)", RX_MODES,
        get = { m ->
            val dw = m[0xA00C].toInt() and 0xff
            val cb = m[0xA00A].toInt() and 0xff
            if (dw == 3) (if (cb != 0) 5 else 4) else (if (cb != 0) 2 else 0) + (if (dw != 0) 1 else 0)
        },
        set = { m, v ->
            val main = (m[0xA0C3].toInt() and 0xff) + 1
            if (v >= 4) {
                m[0xA00C] = 3; m[0xA00A] = (if (v == 5) main else 0).toByte()
            } else {
                m[0xA00A] = (main * (v shr 1 and 1)).toByte(); m[0xA00C] = (main * (v and 1)).toByte()
            }
        })

    /** VOX: vox_switch (0xA005) and vox_level (0xA006). */
    private val vox = Setting("Basic", "VOX", listOf("OFF") + range(1, 10),
        get = { m -> (((m[0xA006].toInt() and 0xff) + 1) * (if (m[0xA005].toInt() != 0) 1 else 0)).takeIf { it <= 10 } },
        set = { m, v -> m[0xA005] = (if (v > 0) 1 else 0).toByte(); m[0xA006] = (if (v > 0) v - 1 else 0).toByte() })

    /** Default scan list (slDef, 7 bits at 0xA130): stored 1..24, 26 for MIX, 25 for ALL. */
    private val defaultList = Setting("Scan lists", "Default scan list", (1..24).map { "LIST $it" } + listOf("MIX", "ALL"),
        get = { m -> SL_VALUES.indexOf(m[0xA130].toInt() and 0x7f).takeIf { it >= 0 } },
        set = { m, v -> m[0xA130] = ((m[0xA130].toInt() and 0x80) or SL_VALUES[v]).toByte() })

    private val channels = (1..Channels.COUNT).map { "M$it" }

    val ALL: List<Setting> = listOf(
        byte("Basic", "Squelch (Sql)", 0xA001, range(0, 9)),
        rxMode,
        byte("Basic", "Main VFO", 0xA0C3, listOf("A", "B")),
        byte("Basic", "Max TX time (TxTOut)", 0xA002, TALK_TIME),
        vox,
        byte("Basic", "Mic gain (Mic)", 0xA007, MIC_GAIN),
        byte("Basic", "Battery saver (BatSav)", 0xA00B, listOf("OFF", "1:1", "1:2", "1:3", "1:4", "1:5")),
        byte("Basic", "Channel display (ChDisp)", 0xA009, listOf("FREQ", "CHANNEL NUMBER", "NAME", "NAME + FREQ")),
        byte("Basic", "Scan resume (ScnRev)", 0xA0AD, SCAN_RESUME),
        byte("Basic", "Auto keypad lock (KeyLck)", 0xA0AE, listOf("OFF") + (1..40).map { "%02dm:%02ds".format(it * 15 / 60, it * 15 % 60) }),
        field("Basic", "Keypad locked", 0xA004, 0, 1, OFF_ON),
        field("Basic", "Key beep (Beep)", 0xA0A8, 0, 1, OFF_ON),
        byte("Basic", "Roger beep (Roger)", 0xA0C1, listOf("OFF", "ROGER", "MDC")),
        field("Basic", "Squelch tail elimination (STE)", 0xA00E, 0, 1, OFF_ON),
        byte("Basic", "Repeater tail elimination (RP STE)", 0xA0C2, listOf("OFF") + (1..10).map { "${it * 100}ms" }),
        byte("Basic", "Power-on message (POnMsg)", 0xA0AF, listOf("ALL", "SOUND", "MESSAGE", "VOLTAGE", "LOGO", "NONE")),
        logo("Welcome line 1", 0xA0C8),
        logo("Welcome line 2", 0xA0D8),
        u16("Basic", "One-key call channel (1 Call)", 0xA135, channels),

        byte("Display", "Backlight time (BLTime)", 0xA00D, BACKLIGHT),
        field("Display", "Backlight min (BLMin)", 0xA008, 4, 4, range(0, 10)),
        field("Display", "Backlight max (BLMax)", 0xA008, 0, 4, range(0, 10)),
        field("Display", "Backlight on TX/RX (BLTxRx)", 0xA157, 6, 2, listOf("OFF", "TX", "RX", "TX/RX")),
        field("Display", "Battery display (BatTXT)", 0xA157, 2, 2, listOf("NONE", "VOLTAGE", "PERCENT")),
        field("Display", "Mic level bar (MicBar)", 0xA157, 4, 1, OFF_ON),
        field("Display", "Contrast (SetCtr)", 0xA15D, 0, 4, range(0, 15)),
        field("Display", "Invert display (SetInv)", 0xA15D, 4, 1, OFF_ON),
        field("Display", "S-meter style (SetMet)", 0xA15D, 6, 1, listOf("TINY", "CLASSIC")),
        field("Display", "Text style (SetGui)", 0xA15D, 7, 1, listOf("TINY", "CLASSIC")),

        state7("USER power level (SetPwr)", 4, 4, listOf("< 20mW", "125mW", "250mW", "500mW", "1W", "2W", "5W")),
        state7("PTT mode (SetPtt)", 0, 1, listOf("CLASSIC", "ONEPUSH")),
        state7("Scan speed (SetScn)", 1, 1, listOf("NORMAL", "FAST"), inverted = true),
        state7("Screen saver (SetSav)", 2, 2, listOf("OFF", "LOGO", "LOGO+", "MATRIX")),
        field("F4HWN", "TX timeout alert (SetTot)", 0xA15E, 4, 4, ALERT),
        field("F4HWN", "End of TX alert (SetEot)", 0xA15E, 0, 4, ALERT),
        field("F4HWN", "Keypad lock scope (SetLck)", 0xA15A, 0, 2, listOf("KEYS", "KEYS + ACTIONS", "KEYS + PTT", "KEYS + ACTIONS + PTT")),
        field("F4HWN", "Timer (SetTmr)", 0xA15C, 0, 1, OFF_ON),
        field("F4HWN", "Off timer (SetOff)", 0xA15C, 1, 7, listOf("OFF") + (1..120).map { "%dh:%02dm".format(it / 60, it % 60) }),
        field("F4HWN", "Narrow FM (SetNFM)", 0xA00E, 1, 2, listOf("NARROW", "NARROWER")),
        field("F4HWN", "RX audio FM (SetRxA)", 0xA000, 0, 4, listOf("FLAT", "CLEAN", "MID", "BOOST", "MAX")),
        field("F4HWN", "RX audio AM (SetRxA)", 0xA000, 4, 4, listOf("SHARP", "STOCK", "OPEN")),
        field("F4HWN", "Menu key (SetKEY)", 0xA004, 2, 4, listOf("MENU", "KEY_UP", "KEY_DOWN", "KEY_EXIT", "KEY_STAR")),
        field("F4HWN", "RescueOps (menu lock)", 0xA004, 1, 1, OFF_ON),

        byte("Keys", "Side key 1 short", 0xA0A9, KEY_ACTIONS),
        byte("Keys", "Side key 1 long", 0xA0AA, KEY_ACTIONS),
        byte("Keys", "Side key 2 short", 0xA0AB, KEY_ACTIONS),
        byte("Keys", "Side key 2 long", 0xA0AC, KEY_ACTIONS),
        field("Keys", "M key long", 0xA0A8, 1, 7, KEY_ACTIONS),

        defaultList,
        field("Scan lists", "Priority scan", 0xA130, 7, 1, OFF_ON),
        u16("Scan lists", "Priority channel 1", 0xA131, channels + "None"),
        u16("Scan lists", "Priority channel 2", 0xA133, channels + "None"),
    ) + (0 until 24).map { listName(it) }

    private fun range(from: Int, to: Int) = (from..to).map { it.toString() }
}

// ---- helpers and tables ----

private fun ascii(m: ByteArray, addr: Int, n: Int) = String(CharArray(n) { (m[addr + it].toInt() and 0xff).toChar() })

internal fun u32(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
    ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }

val CTCSS_TONES = listOf(67.0, 69.3, 71.9, 74.4, 77.0, 79.7, 82.5, 85.4, 88.5, 91.5, 94.8, 97.4, 100.0, 103.5,
    107.2, 110.9, 114.8, 118.8, 123.0, 127.3, 131.8, 136.5, 141.3, 146.2, 151.4, 156.7, 159.8, 162.2, 165.5,
    167.9, 171.3, 173.8, 177.3, 179.9, 183.5, 186.2, 189.9, 192.8, 196.6, 199.5, 203.5, 206.5, 210.7, 218.1,
    225.7, 229.1, 233.6, 241.8, 250.3, 254.1)

val DCS_CODES = listOf(23, 25, 26, 31, 32, 36, 43, 47, 51, 53, 54, 65, 71, 72, 73, 74, 114, 115, 116, 122, 125,
    131, 132, 134, 143, 145, 152, 155, 156, 162, 165, 172, 174, 205, 212, 223, 225, 226, 243, 244, 245, 246, 251,
    252, 255, 261, 263, 265, 266, 271, 274, 306, 311, 315, 325, 331, 332, 343, 346, 351, 356, 364, 365, 371, 411,
    412, 413, 423, 431, 432, 445, 446, 452, 454, 455, 462, 464, 465, 466, 503, 506, 516, 523, 526, 532, 546, 565,
    606, 612, 624, 627, 631, 632, 654, 662, 664, 703, 712, 723, 731, 732, 734, 743, 754)

private val OFF_ON = listOf("OFF", "ON")
private val RX_MODES = listOf("MAIN ONLY", "DUAL RX RESPOND", "CROSS BAND", "MAIN TX DUAL RX", "FULL RX RESPOND", "MAIN TX FULL RX")
private val MIC_GAIN = listOf("+1.5dB", "+4.0dB", "+8.0dB", "+12.0dB", "+16.0dB", "+20.0dB", "+24.0dB", "+28.0dB", "+31.5dB")
private val SL_VALUES = (1..24).toList() + listOf(26, 25)
private fun duration(s: Int) = if (s < 60) "$s sec" else if (s % 60 == 0) "${s / 60} min" else "${s / 60} min : ${s % 60} sec"
private val TALK_TIME = List(5) { "N/U" } + (30..900 step 5).map(::duration)
private val BACKLIGHT = listOf("OFF") + (5..300 step 5).map(::duration) + "Always on"
private val ALERT = listOf("OFF", "SOUND", "VISUAL", "ALL")
private val SCAN_RESUME = listOf("STOP") +
    (1..80).map { "CARRIER %02ds:%03dms".format(it / 4, it % 4 * 250) } +
    (5..120 step 5).map { "TIMEOUT %02dm:%02ds".format(it / 60, it % 60) }
/** Persisted action ids, in the firmware's ACTION_OPT_t order. */
private val KEY_ACTIONS = listOf("NONE", "FLASHLIGHT", "POWER", "MONITOR", "SCAN", "VOX", "FM RADIO", "1750Hz",
    "LOCK KEYPAD", "VFO A / VFO B", "VFO / MEM", "MODE", "RX MODE", "MAIN ONLY", "PTT", "WIDE / NARROW", "MUTE",
    "RxA", "RF LOG", "BEAM", "POWER HIGH", "REMOVE OFFSET", "FOX HUNT", "BEACON", "TONE CALL")
