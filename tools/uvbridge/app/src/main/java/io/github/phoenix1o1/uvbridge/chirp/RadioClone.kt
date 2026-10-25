package io.github.phoenix1o1.uvbridge.chirp

import io.github.phoenix1o1.uvbridge.chirp.ChirpImage.Companion.BLOCK
import io.github.phoenix1o1.uvbridge.chirp.ChirpImage.Companion.DOWNLOAD_SIZE
import io.github.phoenix1o1.uvbridge.chirp.ChirpImage.Companion.PROG_SIZE
import io.github.phoenix1o1.uvbridge.chirp.ChirpImage.Companion.VERIFY_END

/**
 * CHIRP's download and upload, as the F4HWN driver does them:
 * download reads 0x0000..0xB1FF, upload writes 0x0000..0xA177 in 128-byte
 * blocks (the last one 120 bytes) and restarts the radio. Calibration
 * (0xB000..) is never written.
 */
class RadioClone(private val radio: K5Protocol) {

    /** progress(done, total, stage) */
    fun interface Progress { fun update(done: Int, total: Int, stage: String) }

    var firmware: String = ""
        private set

    fun connect(): String {
        firmware = radio.hello()
        return firmware
    }

    fun download(progress: Progress): ChirpImage {
        val mem = ByteArray(DOWNLOAD_SIZE)
        var addr = 0
        while (addr < DOWNLOAD_SIZE) {
            progress.update(addr, DOWNLOAD_SIZE, "Reading the radio")
            radio.readMem(addr, BLOCK).copyInto(mem, addr)
            addr += BLOCK
        }
        progress.update(DOWNLOAD_SIZE, DOWNLOAD_SIZE, "Reading the radio")
        return ChirpImage.fromDownload(mem)
    }

    /**
     * Writes the image, reads the channel data back, then restarts the radio.
     * Returns the addresses whose read-back differed (empty when verified).
     */
    fun upload(image: ChirpImage, progress: Progress): List<Int> {
        var addr = 0
        while (addr < PROG_SIZE) {
            val n = minOf(BLOCK, PROG_SIZE - addr)
            progress.update(addr, PROG_SIZE, "Writing the radio")
            radio.writeMem(addr, image.memory.copyOfRange(addr, addr + n))
            addr += n
        }
        progress.update(PROG_SIZE, PROG_SIZE, "Writing the radio")

        // Channels, names, attributes and list names are stored as written;
        // the settings after them may be normalised by the firmware, so they
        // are not compared.
        val mismatches = mutableListOf<Int>()
        addr = 0
        while (addr < VERIFY_END) {
            val n = minOf(BLOCK, VERIFY_END - addr)
            progress.update(addr, VERIFY_END, "Verifying")
            val back = radio.readMem(addr, BLOCK)
            for (i in 0 until n) if (back[i] != image.memory[addr + i]) { mismatches += addr + i; break }
            addr += BLOCK
        }
        progress.update(VERIFY_END, VERIFY_END, "Verifying")

        radio.reset()
        return mismatches
    }
}
