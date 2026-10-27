package io.github.phoenix1o1.uvbridge.chirp

/**
 * What the app takes from a CHIRP driver file (f4hwn.chirp.v<version>.py):
 * the memory map of its MEM_FORMAT, its sizes, its model name and the option
 * lists of its settings. The encoding itself stays the app's own
 * (F4hwnMemory.kt); a newer driver moves fields, adds settings or drops
 * them, and the app follows. The Python code is never run.
 *
 * [BUILTIN] is the layout of f4hwn.chirp.v6.3.0.py, used when no driver file
 * is loaded.
 */
class ChirpDriver(
    /** "Built-in (F4HWN 6.3)" or the file name. */
    val name: String,
    /** DRIVER_VERSION of the file, "" when it has none. */
    val version: String,
    val vendor: String,
    val model: String,
    val memSize: Int,
    val progSize: Int,
    val calStart: Int,
    val fields: Map<String, Field>,
    /** NAME_LIST = ["...", ...] string lists of the file. */
    val lists: Map<String, List<String>> = emptyMap(),
    /** Setting labels: RadioSetting("field", "Label", ...). */
    val labels: Map<String, String> = emptyMap(),
    /** The list each setting shows: field name to list name. */
    val settingLists: Map<String, String> = emptyMap(),
    /** Settings the driver only writes in its advanced upload mode. */
    val advanced: Set<String> = emptySet(),
) {
    /**
     * One MEM_FORMAT element. [offset] is absolute, of the first element when
     * the element or one of its parents is an array; [count] and [stride] are
     * those of the element itself. Bit fields have [bits] > 0 and [shift]
     * counted from the least significant bit of their [size]-byte type.
     */
    data class Field(val offset: Int, val size: Int, val count: Int = 1, val stride: Int = size,
                     val bits: Int = 0, val shift: Int = 0, val type: String = "u8")

    val isBuiltin get() = this === BUILTIN

    /** What a download reads: the memory size in whole 128-byte blocks. */
    val downloadSize get() = (memSize + ChirpImage.BLOCK - 1) / ChirpImage.BLOCK * ChirpImage.BLOCK

    /** The firmware version the file is for ("6.3.0"), from its name or DRIVER_VERSION. */
    val firmwareVersion: String get() =
        VERSION.find(name)?.groupValues?.get(1) ?: VERSION.find(version)?.groupValues?.get(1) ?: ""

    fun field(name: String): Field = fields[name] ?: throw IllegalStateException("The driver has no field $name")
    fun addr(name: String): Int = field(name).offset
    fun has(name: String) = name in fields

    /** Channels, names, attributes and list names: plain storage, verified after an upload. */
    val verifyEnd: Int get() = field("listname").let { it.offset + it.count * it.stride }

    companion object {
        val VERSION = Regex("""v?(\d+\.\d+(?:\.\d+)?)""")

        /** What the app knows how to encode: these must be where it expects them. */
        private val FIXED = listOf("channel", "channelname", "ch_attr") +
            listOf("freq", "offset", "rxcode", "txcode", "txcodeflag", "rxcodeflag", "modulation",
                "offsetDir", "txLock", "busyChLockout", "txpower", "bandwidth", "freq_reverse",
                "dtmf_pttid", "step").map { "channel.$it" } +
            listOf("compander", "band", "scanlist").map { "ch_attr.$it" }

        /**
         * Reads a driver file. Throws IllegalArgumentException with a readable
         * reason when it is not an F4HWN UV-K1 / UV-K5 V3 driver this version
         * of the app can follow.
         */
        fun parse(name: String, source: String): ChirpDriver {
            val format = Regex("""MEM_FORMAT\s*=\s*""" + "\"\"\"" + """(.*?)""" + "\"\"\"", RegexOption.DOT_MATCHES_ALL)
                .find(source)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("$name is not a CHIRP driver (no MEM_FORMAT).")
            val fields = MemFormat.parse(format)
            val ints = Regex("""(?m)^([A-Z_]+)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\b""").findAll(source)
                .associate { it.groupValues[1] to Integer.decode(it.groupValues[2]) }
            fun str(key: String) = Regex("""(?m)^\s*$key\s*=\s*"([^"]*)"""").find(source)?.groupValues?.get(1) ?: ""

            val driver = ChirpDriver(name, str("DRIVER_VERSION"), str("VENDOR"), str("MODEL"),
                ints["MEM_SIZE"] ?: 0, ints["PROG_SIZE"] ?: 0, ints["CAL_START"] ?: 0, fields,
                stringLists(source), labels(source), settingLists(source), advanced(source))
            driver.check()
            return driver
        }

        /** NAME = ["a", "b", ...] (several lines allowed), and NAME = [...] + [...]; others are skipped. */
        private fun stringLists(source: String): Map<String, List<String>> {
            val out = mutableMapOf<String, List<String>>()
            val start = Regex("""(?m)^([A-Z][A-Z0-9_]*)\s*=\s*\[""")
            for (m in start.findAll(source)) {
                var i = m.range.last           // at the "["
                val items = mutableListOf<String>()
                var ok = true
                while (ok) {
                    val end = matching(source, i) ?: run { ok = false; -1 }
                    if (!ok) break
                    val body = source.substring(i + 1, end)
                    // only literal strings and separators
                    val stripped = body.replace(Regex("\"[^\"\\n]*\"|'[^'\\n]*'"), "").replace(Regex("#[^\\n]*"), "")
                    if (stripped.any { !it.isWhitespace() && it != ',' }) { ok = false; break }
                    Regex("\"([^\"\\n]*)\"|'([^'\\n]*)'").findAll(body).forEach { s ->
                        items += s.groupValues[1].ifEmpty { s.groupValues[2] }
                    }
                    val rest = Regex("""^\s*\+\s*\[""").find(source.substring(end + 1)) ?: break
                    i = end + 1 + rest.range.last
                }
                if (ok && items.isNotEmpty()) out[m.groupValues[1]] = items
            }
            return out
        }

        private fun matching(s: String, open: Int): Int? {
            var depth = 0; var quote: Char? = null
            for (j in open until s.length) {
                val c = s[j]
                if (quote != null) { if (c == quote) quote = null; continue }
                when (c) { '"', '\'' -> quote = c; '[' -> depth++; ']' -> if (--depth == 0) return j }
            }
            return null
        }

        private val SETTING = Regex("""RadioSetting\(\s*"([A-Za-z_][A-Za-z0-9_]*)"\s*,\s*"([^"]*)"""")
        private fun labels(source: String) = SETTING.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }

        /** The list of each RadioSetting whose value, built just before it, is a RadioSettingValueList. */
        private fun settingLists(source: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val value = Regex("""RadioSettingValue(\w+)\(\s*([A-Z][A-Z0-9_]*)?""")
            for (m in SETTING.findAll(source)) {
                val before = source.substring(maxOf(0, m.range.first - 400), m.range.first)
                val last = value.findAll(before).lastOrNull() ?: continue
                if (last.groupValues[1] == "List" && last.groupValues[2].isNotEmpty())
                    out[m.groupValues[1]] = last.groupValues[2]
            }
            return out
        }

        private fun advanced(source: String) =
            Regex("""elname == "([A-Za-z0-9_]+)":\s*\n\s*if self\.upload_advanced""").findAll(source)
                .map { it.groupValues[1] }.toSet()

        val BUILTIN = ChirpDriver("Built-in (F4HWN 6.3)", "", ChirpImage.VENDOR, ChirpImage.MODEL,
            0xB190, 0xA178, 0xB000, builtinFields())

        /** The elements of f4hwn.chirp.v6.3.0.py's MEM_FORMAT that the app uses. */
        private fun builtinFields(): Map<String, Field> {
            val f = mutableMapOf<String, Field>()
            fun u8(name: String, at: Int, shift: Int = 0, bits: Int = 0) { f[name] = Field(at, 1, bits = bits, shift = shift) }
            fun u16(name: String, at: Int) { f[name] = Field(at, 2, type = "ul16") }
            fun u32(name: String, at: Int) { f[name] = Field(at, 4, type = "ul32") }
            fun chars(name: String, at: Int, n: Int, count: Int = 1, stride: Int = n) {
                f[name] = Field(at, n, count, stride, type = "char")
            }

            f["channel"] = Field(0x0000, 16, 1024, 16, type = "struct")
            u32("channel.freq", 0x00); u32("channel.offset", 0x04)
            u8("channel.rxcode", 0x08); u8("channel.txcode", 0x09)
            u8("channel.txcodeflag", 0x0A, 4, 4); u8("channel.rxcodeflag", 0x0A, 0, 4)
            u8("channel.modulation", 0x0B, 4, 4); u8("channel.offsetDir", 0x0B, 0, 4)
            u8("channel.txLock", 0x0C, 6, 1); u8("channel.busyChLockout", 0x0C, 5, 1)
            u8("channel.txpower", 0x0C, 2, 3); u8("channel.bandwidth", 0x0C, 1, 1)
            u8("channel.freq_reverse", 0x0C, 0, 1)
            u8("channel.dtmf_pttid", 0x0D, 1, 3); u8("channel.step", 0x0E)
            f["channelname"] = Field(0x4000, 16, 1024, 16, type = "struct")
            chars("channelname.name", 0x4000, 16)
            f["ch_attr"] = Field(0x8000, 2, 1031, 2, type = "struct")
            u8("ch_attr.compander", 0x8000, 3, 2); u8("ch_attr.band", 0x8000, 0, 3)
            u8("ch_attr.scanlist", 0x8001)
            f["listname"] = Field(0x880E, 4, 24, 4, type = "struct")
            chars("listname.name", 0x880E, 4)

            u8("set_rxa_am", 0xA000, 4, 4); u8("set_rxa_fm", 0xA000, 0, 4)
            u8("squelch", 0xA001); u8("max_talk_time", 0xA002)
            u8("set_nav", 0xA004, 6, 1); u8("set_key", 0xA004, 2, 4)
            u8("set_menu_lock", 0xA004, 1, 1); u8("key_lock", 0xA004, 0, 1)
            u8("vox_switch", 0xA005); u8("vox_level", 0xA006); u8("mic_gain", 0xA007)
            u8("backlight_min", 0xA008, 4, 4); u8("backlight_max", 0xA008, 0, 4)
            u8("channel_display_mode", 0xA009); u8("crossband", 0xA00A); u8("battery_save", 0xA00B)
            u8("dual_watch", 0xA00C); u8("backlight_time", 0xA00D)
            u8("set_nfm", 0xA00E, 1, 2); u8("ste", 0xA00E, 0, 1)
            u8("keyM_longpress_action", 0xA0A8, 1, 7); u8("button_beep", 0xA0A8, 0, 1)
            u8("key1_shortpress_action", 0xA0A9); u8("key1_longpress_action", 0xA0AA)
            u8("key2_shortpress_action", 0xA0AB); u8("key2_longpress_action", 0xA0AC)
            u8("scan_resume_mode", 0xA0AD); u8("auto_keypad_lock", 0xA0AE); u8("power_on_dispmode", 0xA0AF)
            u8("roger_beep", 0xA0C1); u8("rp_ste", 0xA0C2); u8("TX_VFO", 0xA0C3)
            chars("logo_line1", 0xA0C8, 16); chars("logo_line2", 0xA0D8, 16)
            u8("sl.slPriorEnab", 0xA130, 7, 1); u8("sl.slDef", 0xA130, 0, 7)
            u16("sl.slPriorCh1", 0xA131); u16("sl.slPriorCh2", 0xA133); u16("sl.call_channel", 0xA135)
            u8("backlight_on_TX_RX", 0xA157, 6, 2); u8("mic_bar", 0xA157, 4, 1); u8("battery_text", 0xA157, 2, 2)
            u8("set_lck", 0xA15A, 0, 2)
            u8("set_off_tmr", 0xA15C, 1, 7); u8("set_tmr", 0xA15C, 0, 1)
            u8("set_gui", 0xA15D, 7, 1); u8("set_met", 0xA15D, 6, 1)
            u8("set_inv", 0xA15D, 4, 1); u8("set_contrast", 0xA15D, 0, 4)
            u8("set_tot", 0xA15E, 4, 4); u8("set_eot", 0xA15E, 0, 4)
            u8("set_pwr", 0xA15F, 4, 4); u8("set_sav", 0xA15F, 2, 2)
            u8("set_scn", 0xA15F, 1, 1); u8("set_ptt", 0xA15F, 0, 1)
            chars("version.version", 0xA160, 16)
            return f
        }
    }

    private fun check() {
        require(vendor == ChirpImage.VENDOR && model.contains("F4HWN")) {
            "$name is a driver for \"$vendor $model\", not the F4HWN UV-K1 / UV-K5 V3 firmware."
        }
        require(memSize in 0xA000..0x10000 && progSize in 0x9000..memSize) { "$name has unexpected memory sizes." }
        // Calibration is never written, whatever a driver says.
        require(progSize <= ChirpImage.CAL_START && calStart >= ChirpImage.CAL_START) {
            "$name would write calibration memory (PROG_SIZE 0x%X); refused.".format(progSize)
        }
        for (key in FIXED + "listname") {
            val mine = BUILTIN.fields[key]
            val theirs = fields[key]
                ?: throw IllegalArgumentException("$name has no $key: its channel layout is not one this app knows.")
            // where elements are, not how many of them (ch_attr also covers the VFOs)
            require(theirs.offset == mine!!.offset && theirs.bits == mine.bits && theirs.shift == mine.shift &&
                theirs.stride == mine.stride) {
                "$name stores $key differently: this app version cannot follow it yet."
            }
        }
    }
}

/**
 * CHIRP's bitwise MEM_FORMAT language, as far as the F4HWN driver uses it:
 * `#seekto addr;` (absolute), `#seek n;` (relative), scalar types (u8, u16,
 * ul16, u24, ul24, u32, ul32, i8, il16, ..., char, bbcd, lbcd), arrays,
 * bit fields listed most significant first, and nested structs, which may
 * be arrays. Comments are // and /* */.
 */
object MemFormat {

    private val SIZES = mapOf("u8" to 1, "i8" to 1, "char" to 1, "bbcd" to 1, "lbcd" to 1,
        "u16" to 2, "ul16" to 2, "i16" to 2, "il16" to 2, "u24" to 3, "ul24" to 3, "i24" to 3, "il24" to 3,
        "u32" to 4, "ul32" to 4, "i32" to 4, "il32" to 4)

    fun parse(format: String): Map<String, ChirpDriver.Field> {
        val text = format.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\\n]*"), " ")
        val tokens = Regex("""#seekto|#seek|[A-Za-z_][A-Za-z0-9_]*|0x[0-9A-Fa-f]+|\d+|[{}\[\];:,]""")
            .findAll(text).map { it.value }.toList()
        val out = linkedMapOf<String, ChirpDriver.Field>()
        Parser(tokens, out).block("", top = true)
        return out
    }

    private class Parser(val t: List<String>, val out: MutableMap<String, ChirpDriver.Field>) {
        var i = 0
        /** Current address. */
        var pos = 0

        fun peek() = t.getOrNull(i)
        fun next(): String = t.getOrNull(i++) ?: throw IllegalArgumentException("MEM_FORMAT ends early")
        fun expect(s: String) { val n = next(); require(n == s) { "MEM_FORMAT: expected $s, found $n" } }
        fun optional(s: String) { if (peek() == s) next() }
        fun number(s: String): Int = Integer.decode(s)

        /** Declarations up to "}" (or the end, at the top level), into [into] under [prefix]. */
        fun block(prefix: String, into: MutableMap<String, ChirpDriver.Field> = out, top: Boolean = false) {
            while (true) {
                when (peek()) {
                    null -> { require(top) { "MEM_FORMAT: missing }" }; return }
                    "}" -> { require(!top) { "MEM_FORMAT: stray }" }; return }
                    "#seekto" -> { next(); pos = number(next()); optional(";") }
                    "#seek" -> { next(); pos += number(next()); optional(";") }
                    "struct" -> struct(prefix, into)
                    ";" -> next()
                    else -> scalar(prefix, into)
                }
            }
        }

        /** struct { ... } name[count]; its body is read once, at its first element. */
        fun struct(prefix: String, into: MutableMap<String, ChirpDriver.Field>) {
            expect("struct"); expect("{")
            val start = pos
            val body = linkedMapOf<String, ChirpDriver.Field>()
            block("", body)
            expect("}")
            val size = pos - start
            val name = key(prefix, next())
            var count = 1
            if (peek() == "[") { next(); count = number(next()); expect("]") }
            optional(";")
            into[name] = ChirpDriver.Field(start, size, count, size, type = "struct")
            for ((k, v) in body) into["$name.$k"] = v
            pos = start + size * count
        }

        /** type a, b[n]; or type a:bits, b:bits; */
        fun scalar(prefix: String, into: MutableMap<String, ChirpDriver.Field>) {
            val type = next()
            val size = SIZES[type] ?: throw IllegalArgumentException("MEM_FORMAT: unknown type $type")
            var bitPos = 0                 // from the most significant bit
            while (true) {
                val name = key(prefix, next())
                if (peek() == ":") {
                    next()
                    val bits = number(next())
                    into[name] = ChirpDriver.Field(pos, size, bits = bits, shift = size * 8 - bitPos - bits, type = type)
                    bitPos += bits
                } else {
                    var count = 1
                    if (peek() == "[") { next(); count = number(next()); expect("]") }
                    // a char array is one string
                    into[name] = if (type == "char") ChirpDriver.Field(pos, count, type = type)
                                 else ChirpDriver.Field(pos, size, count, size, type = type)
                    pos += size * count
                }
                when (val sep = next()) {
                    "," -> continue
                    ";" -> break
                    else -> throw IllegalArgumentException("MEM_FORMAT: expected , or ; after $name, found $sep")
                }
            }
            if (bitPos > 0) pos += size
        }

        private fun key(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix.$name"
    }
}
