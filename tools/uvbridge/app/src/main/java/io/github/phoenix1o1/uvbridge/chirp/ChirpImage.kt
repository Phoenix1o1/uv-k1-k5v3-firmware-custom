package io.github.phoenix1o1.uvbridge.chirp

import java.util.Base64

/**
 * A CHIRP .img file for the F4HWN UV-K1 / UV-K5 V3 driver: the radio memory
 * as downloaded (0xB200 bytes: 0xB190 rounded up to whole 128-byte blocks),
 * then CHIRP's metadata: the marker 00 FF "chirp" EE "img" 00 01 and a base64
 * JSON object naming the vendor and model.
 */
class ChirpImage(val memory: ByteArray, val metadata: String) {

    val vendor: String get() = field("vendor")
    val model: String get() = field("model")

    /** A string field of the metadata JSON (flat, as CHIRP writes it). */
    private fun field(name: String): String =
        Regex("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").find(metadata)?.groupValues?.get(1) ?: ""

    /** Memories with a frequency set (not 0, not erased). */
    fun channelCount(): Int = (0 until CHANNELS).count { ch ->
        val f = u32(memory, ch * 16)
        f != 0L && f != 0xFFFFFFFFL
    }

    /** Names of the scan lists 1..24 that have one; [withEmpty]: all 24, "" when unnamed. */
    fun listNames(withEmpty: Boolean = false): List<String> = (0 until LISTS).mapNotNull { i ->
        val raw = memory.copyOfRange(LIST_NAMES + i * 4, LIST_NAMES + i * 4 + 4)
        val name = raw.takeWhile { it in 0x20..0x7e }.map { it.toInt().toChar() }.joinToString("").trim()
        if (name.isEmpty() && !withEmpty) null else name
    }

    /** The firmware version the radio stored in the image ("6.3.0"), "" when there is none. */
    fun firmwareVersion(): String {
        val raw = memory.copyOfRange(VERSION_AT, minOf(memory.size, VERSION_AT + 16))
        val text = raw.takeWhile { it in 0x20..0x7e }.map { it.toInt().toChar() }.joinToString("")
        return ChirpDriver.VERSION.find(text)?.groupValues?.get(1) ?: ""
    }

    fun toBytes(): ByteArray {
        val meta = Base64.getEncoder().encode(metadata.toByteArray())
        return memory + MARKER + meta
    }

    companion object {
        const val VENDOR = "Quansheng"
        const val MODEL = "UV-K1 & UV-K5 V3 (F4HWN)"

        // Layout and sizes from f4hwn.chirp.v6.3.0.py (ChirpDriver.BUILTIN); a
        // loaded driver gives its own, but never writes calibration.
        const val MEM_SIZE = 0xB190        // driver memory size
        const val DOWNLOAD_SIZE = 0xB200   // what a download reads: whole 128-byte blocks
        const val PROG_SIZE = 0xA178       // what an upload writes: 0x0000..0xA177
        const val CAL_START = 0xB000       // calibration: never written by this app
        const val BLOCK = 0x80
        const val CHANNELS = 1024
        const val LIST_NAMES = 0x880E
        const val LISTS = 24
        /** Channels, names, attributes and list names: plain storage, verified after an upload. */
        const val VERIFY_END = 0x886E
        /** version.version of the driver's MEM_FORMAT. */
        const val VERSION_AT = 0xA160

        val MARKER = byteArrayOf(0x00, 0xff.toByte()) + "chirp".toByteArray() +
            byteArrayOf(0xee.toByte()) + "img".toByteArray() + byteArrayOf(0x00, 0x01)

        /**
         * Parses a CHIRP image for [driver]'s model (or the built-in one);
         * throws IllegalArgumentException with a readable reason.
         */
        fun parse(file: ByteArray, driver: ChirpDriver = ChirpDriver.BUILTIN): ChirpImage {
            val at = indexOf(file, MARKER)
            require(at >= 0) { "Not a CHIRP image (no CHIRP metadata found)." }
            val meta = try {
                String(Base64.getDecoder().decode(file.copyOfRange(at + MARKER.size, file.size).trimEnd()))
            } catch (e: Exception) {
                throw IllegalArgumentException("Unreadable CHIRP metadata.")
            }
            val img = ChirpImage(file.copyOfRange(0, at), meta)
            require(img.vendor == VENDOR && (img.model == MODEL || img.model == driver.model)) {
                "This image is for \"${img.vendor} ${img.model}\", not \"$VENDOR ${driver.model}\"."
            }
            require(img.memory.size >= driver.progSize) {
                "The image is too short (${img.memory.size} bytes, at least ${driver.progSize} needed)."
            }
            return img
        }

        /** An image of downloaded memory, with metadata CHIRP accepts. */
        fun fromDownload(memory: ByteArray, model: String = MODEL): ChirpImage = ChirpImage(memory,
            "{\"mem_extra\": {}, \"rclass\": \"DynamicRadioAlias\", \"vendor\": \"$VENDOR\", " +
            "\"model\": \"$model\", \"variant\": \"\", \"chirp_version\": \"uvbridge\"}")

        private fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xff) or ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or ((b[o + 3].toLong() and 0xff) shl 24)

        private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        private fun ByteArray.trimEnd(): ByteArray {
            var n = size
            while (n > 0 && (this[n - 1] == '\n'.code.toByte() || this[n - 1] == '\r'.code.toByte() ||
                        this[n - 1] == ' '.code.toByte())) n--
            return copyOf(n)
        }
    }
}
