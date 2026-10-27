package io.github.phoenix1o1.uvbridge.chirp

import java.util.zip.CRC32

/**
 * Firmware assets (fonts, menu texts) of the F4HWN v7 firmwares: an asset
 * pack lives in a 16 KiB zone of the firmware slot, in the external flash,
 * and the firmware draws with it (App/fw_assets.h). A release .bin carries
 * the pack after the firmware image (cmake/pack_fw_assets.py):
 *
 *   [image][pack = FWA1 header (32 B) + payload][trailer (16 B)]
 *   FWA1 header: "FWA1", u16 version 1, u16 header size 32, u32 payload size,
 *                u32 payload CRC-32, u8[16] build id of the firmware
 *   trailer:     "FWAT", u32 image size, u32 pack size, u32 CRC-32 of image + pack
 *
 * UV Studio flashes the image through the bootloader, then writes the pack
 * over the serial link with the 0x075x commands, as [AssetWriter] does.
 */
class AssetPack(val bytes: ByteArray) {

    val payloadSize: Int
    /** GNU build id of the firmware the pack belongs to, in hex. */
    val buildId: String

    init {
        require(bytes.size >= HEADER_SIZE) { "Not an asset pack (too short)." }
        require(u32(bytes, 0) == PACK_MAGIC) { "Not an asset pack (no FWA1 header)." }
        require(u16(bytes, 4) == 1 && u16(bytes, 6) == HEADER_SIZE) { "Unsupported asset pack version." }
        payloadSize = u32(bytes, 8).toInt()
        require(payloadSize > 0 && HEADER_SIZE + payloadSize == bytes.size) { "The asset pack size is wrong." }
        require(bytes.size <= ZONE_SIZE) { "The asset pack is larger than its 16 KiB zone." }
        require(crc32(bytes, HEADER_SIZE, bytes.size) == u32(bytes, 12)) { "The asset pack is damaged (CRC)." }
        buildId = hex(bytes, 16, BUILD_ID_SIZE)
    }

    companion object {
        const val PACK_MAGIC = 0x31415746L      // "FWA1"
        const val TRAILER_MAGIC = 0x54415746L   // "FWAT"
        const val HEADER_SIZE = 32
        const val TRAILER_SIZE = 16
        const val BUILD_ID_SIZE = 16
        const val ZONE_SIZE = 0x4000
        const val IMAGE_MAX = 0x1D800

        /**
         * The pack of a firmware package (.bin with its trailer), or of a bare
         * pack file. Null for a plain firmware .bin, which has no assets (v6
         * and older, stock firmwares). Throws on a damaged file.
         */
        fun from(file: ByteArray): AssetPack? {
            if (file.size >= HEADER_SIZE && u32(file, 0) == PACK_MAGIC) return AssetPack(file)
            if (file.size < TRAILER_SIZE) return null
            val tail = file.size - TRAILER_SIZE
            if (u32(file, tail) != TRAILER_MAGIC) return null
            val imageSize = u32(file, tail + 4).toInt()
            val packSize = u32(file, tail + 8).toInt()
            require(imageSize in 1..IMAGE_MAX && imageSize + packSize == tail) { "The firmware package is damaged (layout)." }
            require(crc32(file, 0, tail) == u32(file, tail + 12)) { "The firmware package is damaged (CRC)." }
            return AssetPack(file.copyOfRange(imageSize, tail))
        }

        fun crc32(b: ByteArray, from: Int, to: Int): Long = CRC32().apply { update(b, from, to - from) }.value

        fun hex(b: ByteArray, from: Int, n: Int) = (from until from + n).joinToString("") { "%02x".format(b[it]) }

        internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        internal fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    }
}

/**
 * The asset commands of App/app/uart.c (0x0750..0x0757), after a hello.
 * Slot 0xFF is the running firmware's slot; the firmware only takes the
 * pack of its own build there.
 */
class AssetWriter(private val radio: K5Protocol) {

    /** What 0x0750 tells: the zone's [status] (0 valid) and the running firmware's build id. */
    data class Info(val slot: Int, val status: Int, val runningSlot: Int, val loaded: Boolean, val runningBuildId: String)

    /** Throws [NotSupported] when the firmware does not answer (no asset zone: v6 and older). */
    fun info(): Info {
        val d = try {
            radio.command(0x0750, byteArrayOf(RUNNING.toByte()), 0x0751, timeout = 3000, tries = 2)
        } catch (e: Timeout) {
            throw NotSupported()
        }
        if (d.size < 52) throw RadioException("Short answer to the asset info request.")
        return Info(d[0].toInt() and 0xff, d[1].toInt() and 0xff, d[2].toInt() and 0xff, d[3].toInt() == 1,
            AssetPack.hex(d, 36, AssetPack.BUILD_ID_SIZE))
    }

    /**
     * Writes [pack] into the running slot's zone: erase, payload, then the
     * header last, so an interrupted write never validates; then the
     * firmware checks it and redraws with it.
     */
    fun write(pack: AssetPack, progress: RadioClone.Progress) {
        val info = info()
        if (info.runningBuildId != pack.buildId)
            throw RadioException("This asset pack belongs to another firmware build (${pack.buildId.take(8)}…, " +
                "the radio runs ${info.runningBuildId.take(8)}…). Flash the matching firmware first.")
        val slot = info.slot
        val total = pack.bytes.size
        progress.update(0, total, "Erasing the asset zone")
        status("erase", radio.command(0x0752, byteArrayOf(slot.toByte(), 0) + K5Protocol.TIMESTAMP, 0x0753, timeout = 30000))
        var off = AssetPack.HEADER_SIZE
        while (off < total) {
            val n = minOf(CHUNK, total - off)
            progress.update(off, total, "Writing the assets")
            chunk(slot, off, pack.bytes.copyOfRange(off, off + n))
            off += n
        }
        chunk(slot, 0, pack.bytes.copyOfRange(0, AssetPack.HEADER_SIZE))
        progress.update(total, total, "Checking the assets")
        status("check", radio.command(0x0756, byteArrayOf(slot.toByte()), 0x0757, timeout = 5000))
    }

    // Programming the same bytes again is harmless: a lost reply is retried.
    private fun chunk(slot: Int, offset: Int, data: ByteArray) {
        val cmd = byteArrayOf(slot.toByte(), 0, lo(offset), lo(offset shr 8), lo(offset shr 16), lo(offset shr 24),
            lo(data.size), lo(data.size shr 8)) + K5Protocol.TIMESTAMP + data
        status("write at $offset", radio.command(0x0754, cmd, 0x0755, timeout = 1500, tries = 5))
    }

    private fun status(what: String, reply: ByteArray) {
        val st = reply.getOrNull(1)?.toInt()?.and(0xff) ?: 9
        if (st != 0) throw RadioException("Asset $what failed: ${STATUS.getOrElse(st) { "error $st" }}")
    }

    class NotSupported : RadioException("This firmware has no firmware assets (they come with F4HWN v7).")

    companion object {
        const val RUNNING = 0xFF
        const val CHUNK = 128
        /** MB_ERR_* of the firmware. */
        private val STATUS = listOf("valid", "empty", "new header", "incomplete", "bad size", "CRC error",
            "flash error", "bad slot", "not authorised", "error", "error", "another build")
        private fun lo(v: Int) = (v and 0xff).toByte()
    }
}
