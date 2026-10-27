package io.github.phoenix1o1.uvbridge.chirp

import io.github.phoenix1o1.uvbridge.chirp.ChirpImage.Companion.BLOCK

/**
 * CHIRP's download and upload, as the F4HWN driver does them:
 * download reads 0x0000..0xB1FF, upload writes 0x0000..0xA177 in 128-byte
 * blocks (the last one 120 bytes) and restarts the radio. Calibration
 * (0xB000..) is never written. The sizes are those of [driver].
 */
class RadioClone(private val radio: K5Protocol, private val driver: ChirpDriver = ChirpDriver.BUILTIN) {

    private val downloadSize = driver.downloadSize
    private val progSize = driver.progSize
    private val verifyEnd = driver.verifyEnd

    /** progress(done, total, stage) */
    fun interface Progress { fun update(done: Int, total: Int, stage: String) }

    /** The radio's firmware version string, from [connect] (or a hello said before). */
    var firmware: String = ""

    fun connect(): String {
        firmware = radio.hello()
        return firmware
    }

    fun download(progress: Progress): ChirpImage {
        val mem = ByteArray(downloadSize)
        var addr = 0
        while (addr < downloadSize) {
            progress.update(addr, downloadSize, "Reading the radio")
            radio.readMem(addr, BLOCK).copyInto(mem, addr)
            addr += BLOCK
        }
        progress.update(downloadSize, downloadSize, "Reading the radio")
        return ChirpImage.fromDownload(mem, driver.model)
    }

    /**
     * Writes the image, reads the channel data back, then restarts the radio.
     * Returns the addresses whose read-back differed (empty when verified).
     */
    fun upload(image: ChirpImage, progress: Progress): List<Int> {
        check(progSize <= ChirpImage.CAL_START)          // calibration is never written
        var addr = 0
        while (addr < progSize) {
            val n = minOf(BLOCK, progSize - addr)
            progress.update(addr, progSize, "Writing the radio")
            radio.writeMem(addr, image.memory.copyOfRange(addr, addr + n))
            addr += n
        }
        progress.update(progSize, progSize, "Writing the radio")

        // Channels, names, attributes and list names are stored as written;
        // the settings after them may be normalised by the firmware, so they
        // are not compared.
        val mismatches = mutableListOf<Int>()
        addr = 0
        while (addr < verifyEnd) {
            val n = minOf(BLOCK, verifyEnd - addr)
            progress.update(addr, verifyEnd, "Verifying")
            val back = radio.readMem(addr, BLOCK)
            for (i in 0 until n) if (back[i] != image.memory[addr + i]) { mismatches += addr + i; break }
            addr += BLOCK
        }
        progress.update(verifyEnd, verifyEnd, "Verifying")

        radio.reset()
        return mismatches
    }
}
