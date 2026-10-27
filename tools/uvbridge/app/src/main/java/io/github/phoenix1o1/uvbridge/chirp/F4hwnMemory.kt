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

    /** The group of the settings that only the loaded driver knows. */
    const val EXTRA = "From the driver"

    /** The settings of the built-in layout (f4hwn.chirp.v6.3.0.py). */
    val ALL: List<Setting> by lazy { forDriver(ChirpDriver.BUILTIN) }

    /**
     * The settings, at the addresses [d] gives. A setting whose fields the
     * driver no longer has is left out; fields of the driver this app has
     * no setting for are added at the end, in [EXTRA], with the driver's
     * label and list.
     */
    fun forDriver(d: ChirpDriver): List<Setting> = Builder(d).run {
        listOfNotNull(
            byte("Basic", "Squelch (Sql)", "squelch", range(0, 9)),
            rxMode(),
            byte("Basic", "Main VFO", "TX_VFO", listOf("A", "B")),
            byte("Basic", "Max TX time (TxTOut)", "max_talk_time", TALK_TIME),
            vox(),
            byte("Basic", "Mic gain (Mic)", "mic_gain", MIC_GAIN),
            byte("Basic", "Battery saver (BatSav)", "battery_save", listOf("OFF", "1:1", "1:2", "1:3", "1:4", "1:5")),
            byte("Basic", "Channel display (ChDisp)", "channel_display_mode", listOf("FREQ", "CHANNEL NUMBER", "NAME", "NAME + FREQ")),
            byte("Basic", "Scan resume (ScnRev)", "scan_resume_mode", SCAN_RESUME),
            byte("Basic", "Auto keypad lock (KeyLck)", "auto_keypad_lock", listOf("OFF") + (1..40).map { "%02dm:%02ds".format(it * 15 / 60, it * 15 % 60) }),
            byte("Basic", "Keypad locked", "key_lock", OFF_ON),
            byte("Basic", "Key beep (Beep)", "button_beep", OFF_ON),
            byte("Basic", "Roger beep (Roger)", "roger_beep", listOf("OFF", "ROGER", "MDC")),
            byte("Basic", "Squelch tail elimination (STE)", "ste", OFF_ON),
            byte("Basic", "Repeater tail elimination (RP STE)", "rp_ste", listOf("OFF") + (1..10).map { "${it * 100}ms" }),
            byte("Basic", "Power-on message (POnMsg)", "power_on_dispmode", listOf("ALL", "SOUND", "MESSAGE", "VOLTAGE", "LOGO", "NONE")),
            logo("Welcome line 1", "logo_line1"),
            logo("Welcome line 2", "logo_line2"),
            u16("Basic", "One-key call channel (1 Call)", "sl.call_channel", channels),

            byte("Display", "Backlight time (BLTime)", "backlight_time", BACKLIGHT),
            byte("Display", "Backlight min (BLMin)", "backlight_min", range(0, 10)),
            byte("Display", "Backlight max (BLMax)", "backlight_max", range(0, 10)),
            byte("Display", "Backlight on TX/RX (BLTxRx)", "backlight_on_TX_RX", listOf("OFF", "TX", "RX", "TX/RX")),
            byte("Display", "Battery display (BatTXT)", "battery_text", listOf("NONE", "VOLTAGE", "PERCENT")),
            byte("Display", "Mic level bar (MicBar)", "mic_bar", OFF_ON),
            byte("Display", "Contrast (SetCtr)", "set_contrast", range(0, 15)),
            byte("Display", "Invert display (SetInv)", "set_inv", OFF_ON),
            byte("Display", "S-meter style (SetMet)", "set_met", listOf("TINY", "CLASSIC")),
            byte("Display", "Text style (SetGui)", "set_gui", listOf("TINY", "CLASSIC")),

            state7("USER power level (SetPwr)", "set_pwr", listOf("< 20mW", "125mW", "250mW", "500mW", "1W", "2W", "5W")),
            state7("PTT mode (SetPtt)", "set_ptt", listOf("CLASSIC", "ONEPUSH")),
            state7("Scan speed (SetScn)", "set_scn", listOf("NORMAL", "FAST"), inverted = true),
            state7("Screen saver (SetSav)", "set_sav", listOf("OFF", "LOGO", "LOGO+", "MATRIX")),
            byte("F4HWN", "TX timeout alert (SetTot)", "set_tot", ALERT),
            byte("F4HWN", "End of TX alert (SetEot)", "set_eot", ALERT),
            byte("F4HWN", "Keypad lock scope (SetLck)", "set_lck", listOf("KEYS", "KEYS + ACTIONS", "KEYS + PTT", "KEYS + ACTIONS + PTT")),
            byte("F4HWN", "Timer (SetTmr)", "set_tmr", OFF_ON),
            byte("F4HWN", "Off timer (SetOff)", "set_off_tmr", listOf("OFF") + (1..120).map { "%dh:%02dm".format(it / 60, it % 60) }),
            byte("F4HWN", "Narrow FM (SetNFM)", "set_nfm", listOf("NARROW", "NARROWER")),
            byte("F4HWN", "RX audio FM (SetRxA)", "set_rxa_fm", listOf("FLAT", "CLEAN", "MID", "BOOST", "MAX")),
            byte("F4HWN", "RX audio AM (SetRxA)", "set_rxa_am", listOf("SHARP", "STOCK", "OPEN")),
            byte("F4HWN", "Menu key (SetKEY)", "set_key", listOf("MENU", "KEY_UP", "KEY_DOWN", "KEY_EXIT", "KEY_STAR")),
            byte("F4HWN", "RescueOps (menu lock)", "set_menu_lock", OFF_ON),

            byte("Keys", "Side key 1 short", "key1_shortpress_action", KEY_ACTIONS),
            byte("Keys", "Side key 1 long", "key1_longpress_action", KEY_ACTIONS),
            byte("Keys", "Side key 2 short", "key2_shortpress_action", KEY_ACTIONS),
            byte("Keys", "Side key 2 long", "key2_longpress_action", KEY_ACTIONS),
            byte("Keys", "M key long", "keyM_longpress_action", KEY_ACTIONS),

            defaultList(),
            byte("Scan lists", "Priority scan", "sl.slPriorEnab", OFF_ON),
            u16("Scan lists", "Priority channel 1", "sl.slPriorCh1", channels + "None"),
            u16("Scan lists", "Priority channel 2", "sl.slPriorCh2", channels + "None"),
        ) + listNames() + extras()
    }

    /** Fields the settings above use: the extras skip them. */
    private val KNOWN = setOf("squelch", "dual_watch", "crossband", "TX_VFO", "max_talk_time", "vox_switch",
        "vox_level", "mic_gain", "battery_save", "channel_display_mode", "scan_resume_mode", "auto_keypad_lock",
        "key_lock", "button_beep", "roger_beep", "ste", "rp_ste", "power_on_dispmode", "logo_line1", "logo_line2",
        "backlight_time", "backlight_min", "backlight_max", "backlight_on_TX_RX", "battery_text", "mic_bar",
        "set_contrast", "set_inv", "set_met", "set_gui", "set_pwr", "set_ptt", "set_scn", "set_sav", "set_tot",
        "set_eot", "set_lck", "set_tmr", "set_off_tmr", "set_nfm", "set_rxa_fm", "set_rxa_am", "set_key",
        "set_menu_lock", "key1_shortpress_action", "key1_longpress_action", "key2_shortpress_action",
        "key2_longpress_action", "keyM_longpress_action")

    private val channels = (1..Channels.COUNT).map { "M$it" }

    private class Builder(val d: ChirpDriver) {

        /** A bit field or a whole byte, as the driver places it; null when it has no such field. */
        fun byte(group: String, label: String, name: String, options: List<String>): Setting? {
            val f = d.fields[name]?.takeIf { it.size == 1 && it.count == 1 } ?: return null
            val shift = f.shift
            val width = if (f.bits > 0) f.bits else 8
            val addr = f.offset
            return Setting(group, label, options,
                get = { m -> ((m[addr].toInt() and 0xff) shr shift and ((1 shl width) - 1)).takeIf { it < options.size } },
                set = { m, v ->
                    val mask = ((1 shl width) - 1) shl shift
                    m[addr] = ((m[addr].toInt() and mask.inv()) or ((v shl shift) and mask)).toByte()
                })
        }

        fun u16(group: String, label: String, name: String, options: List<String>): Setting? {
            val addr = d.fields[name]?.takeIf { it.size == 2 }?.offset ?: return null
            return Setting(group, label, options,
                get = { m -> ((m[addr].toInt() and 0xff) or ((m[addr + 1].toInt() and 0xff) shl 8)).takeIf { it < options.size } },
                set = { m, v -> m[addr] = v.toByte(); m[addr + 1] = (v shr 8).toByte() })
        }

        /**
         * The F4HWN byte of set_pwr / set_sav / set_scn / set_ptt (0xA15F);
         * while still erased the driver shows its defaults, and writing one
         * field writes them all: SetScn stored 1 (NORMAL), the others 0.
         */
        fun state7(label: String, name: String, options: List<String>, inverted: Boolean = false): Setting? {
            val f = byte("F4HWN", label, name, if (inverted) listOf("1", "0") else options) ?: return null
            val addr = d.addr(name)
            val defaults = d.fields["set_scn"]?.let { 1 shl it.shift } ?: 0
            val erased = { m: ByteArray -> (m[addr].toInt() and 0xff) == 0xff }
            return Setting("F4HWN", label, options,
                get = { m -> if (erased(m)) 0 else f.get(m)?.let { if (inverted) 1 - it else it } },
                set = { m, v ->
                    if (erased(m)) m[addr] = defaults.toByte()
                    f.set(m, if (inverted) 1 - v else v)
                })
        }

        fun logo(label: String, name: String): Setting? {
            val addr = d.fields[name]?.takeIf { it.type == "char" && it.size == 16 }?.offset ?: return null
            return Setting("Basic", label, maxLength = 12,
                text = { m -> ascii(m, addr, 16).takeWhile { it != '\u0000' && it != '\u00ff' }.trim() },
                setText = { m, s ->
                    val b = (s.trimEnd(' ') + "\u0000".repeat(12)).take(12) + "\u0000\u00ff\u00ff\u00ff"
                    for (i in 0 until 16) m[addr + i] = b[i].code.toByte()
                })
        }

        fun listNames(): List<Setting> {
            val base = d.addr("listname")
            return (0 until ChirpImage.LISTS).map { i ->
                Setting("Scan lists", "List ${i + 1} name", maxLength = 3,
                    text = { m -> ascii(m, base + i * 4, 4).filter { it.code in 32..126 }.trim() },
                    setText = { m, s ->
                        val b = if (s.isEmpty()) ByteArray(4) { 0xff.toByte() }
                                else s.take(3).padEnd(4, ' ').map { it.code.toByte() }.toByteArray()
                        b.copyInto(m, base + i * 4)
                    })
            }
        }

        /** RX mode: dual_watch and crossband, set to the main VFO + 1. */
        fun rxMode(): Setting? {
            if (!d.has("dual_watch") || !d.has("crossband") || !d.has("TX_VFO")) return null
            val dwA = d.addr("dual_watch"); val cbA = d.addr("crossband"); val vfoA = d.addr("TX_VFO")
            return Setting("Basic", "RX mode (RxMode)", RX_MODES,
                get = { m ->
                    val dw = m[dwA].toInt() and 0xff
                    val cb = m[cbA].toInt() and 0xff
                    if (dw == 3) (if (cb != 0) 5 else 4) else (if (cb != 0) 2 else 0) + (if (dw != 0) 1 else 0)
                },
                set = { m, v ->
                    val main = (m[vfoA].toInt() and 0xff) + 1
                    if (v >= 4) {
                        m[dwA] = 3; m[cbA] = (if (v == 5) main else 0).toByte()
                    } else {
                        m[cbA] = (main * (v shr 1 and 1)).toByte(); m[dwA] = (main * (v and 1)).toByte()
                    }
                })
        }

        /** VOX: vox_switch and vox_level. */
        fun vox(): Setting? {
            if (!d.has("vox_switch") || !d.has("vox_level")) return null
            val sw = d.addr("vox_switch"); val lvl = d.addr("vox_level")
            return Setting("Basic", "VOX", listOf("OFF") + range(1, 10),
                get = { m -> (((m[lvl].toInt() and 0xff) + 1) * (if (m[sw].toInt() != 0) 1 else 0)).takeIf { it <= 10 } },
                set = { m, v -> m[sw] = (if (v > 0) 1 else 0).toByte(); m[lvl] = (if (v > 0) v - 1 else 0).toByte() })
        }

        /** Default scan list (slDef, 7 bits): stored 1..24, 26 for MIX, 25 for ALL. */
        fun defaultList(): Setting? {
            val f = d.fields["sl.slDef"]?.takeIf { it.size == 1 && it.bits == 7 } ?: return null
            val addr = f.offset; val shift = f.shift
            val mask = 0x7f shl shift
            return Setting("Scan lists", "Default scan list", (1..24).map { "LIST $it" } + listOf("MIX", "ALL"),
                get = { m -> SL_VALUES.indexOf((m[addr].toInt() and mask) shr shift).takeIf { it >= 0 } },
                set = { m, v -> m[addr] = ((m[addr].toInt() and mask.inv()) or (SL_VALUES[v] shl shift)).toByte() })
        }

        /**
         * Settings of the driver the app does not know: single-byte fields in
         * the settings area that the driver shows as a list (the stored value
         * is the index in it). Left out: fields the driver only writes in its
         * advanced mode, and its int_* unlock settings (TX band plan...).
         */
        fun extras(): List<Setting> = d.fields.mapNotNull { (name, f) ->
            if ('.' in name || name.startsWith("__") || name.startsWith("int_") || name in KNOWN || name in d.advanced)
                return@mapNotNull null
            if (f.size != 1 || f.count != 1 || f.type == "char" || f.offset !in 0xA000 until d.progSize) return@mapNotNull null
            val width = if (f.bits > 0) f.bits else 8
            val options = (d.settingLists[name] ?: (name.uppercase() + "_LIST")).let { d.lists[it] }
                ?.takeIf { it.size in 2..(1 shl width) } ?: return@mapNotNull null
            val label = d.labels[name]?.let { "$it ($name)" } ?: name
            byte(EXTRA, label, name, options)
        }
    }

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
