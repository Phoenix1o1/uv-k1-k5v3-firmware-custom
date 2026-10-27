package io.github.phoenix1o1.uvbridge.chirp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Firmware asset packs: the package format of cmake/pack_fw_assets.py, and writing one to the radio. */
class FirmwareAssetsTest {

    private val build = ByteArray(16) { (0xA0 + it).toByte() }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value.toInt()
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** A pack as pack_fw_assets.py builds it. */
    private fun pack(payload: ByteArray, id: ByteArray = build): ByteArray {
        val h = le(32).putInt(0x31415746).putShort(1).putShort(32).putInt(payload.size).putInt(crc(payload)).put(id)
        return h.array() + payload
    }

    /** image + pack + trailer. */
    private fun firmwarePackage(image: ByteArray, pack: ByteArray): ByteArray {
        val t = le(16).putInt(0x54415746).putInt(image.size).putInt(pack.size).putInt(crc(image + pack))
        return image + pack + t.array()
    }

    private val payload = ByteArray(1000) { (it * 31 + 7).toByte() }

    @Test fun packageAndPackFilesAreRead() {
        val p = pack(payload)
        val fromPackage = AssetPack.from(firmwarePackage(ByteArray(5000) { it.toByte() }, p))!!
        assertArrayEquals(p, fromPackage.bytes)
        assertEquals(AssetPack.hex(build, 0, 16), fromPackage.buildId)
        assertEquals(1000, fromPackage.payloadSize)
        assertArrayEquals(p, AssetPack.from(p)!!.bytes)
        // a plain firmware has none
        assertNull(AssetPack.from(ByteArray(5000) { it.toByte() }))
        // damage is reported
        val bad = firmwarePackage(ByteArray(5000), p).also { it[100]++ }
        try { AssetPack.from(bad); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("CRC")) }
    }

    @Test fun packIsWrittenPayloadFirstHeaderLast() {
        val radio = FakeRadio(buildId = build)
        val k5 = K5Protocol(radio).apply { hello() }
        val p = AssetPack(pack(payload))
        AssetWriter(k5).write(p) { _, _, _ -> }
        assertArrayEquals(p.bytes, radio.zone.copyOf(p.bytes.size))
        assertTrue(radio.assetsLoaded)
        assertEquals(0, radio.assetWrites.last())                  // the header goes last
        assertEquals(listOf(32, 160, 288, 416, 544, 672, 800, 928, 0), radio.assetWrites)
        val info = AssetWriter(k5).info()
        assertTrue(info.loaded)
        assertEquals(0, info.status)
    }

    @Test fun lostChunkReplyIsRetried() {
        val radio = FakeRadio(buildId = build).apply { dropReplyOfAssetWrite += 2 }
        val k5 = K5Protocol(radio, timeoutMs = 300).apply { hello() }
        val p = AssetPack(pack(payload))
        AssetWriter(k5).write(p) { _, _, _ -> }
        assertArrayEquals(p.bytes, radio.zone.copyOf(p.bytes.size))
        assertTrue(radio.assetsLoaded)
    }

    @Test fun packOfAnotherBuildIsRefused() {
        val radio = FakeRadio(buildId = build)
        val k5 = K5Protocol(radio).apply { hello() }
        try {
            AssetWriter(k5).write(AssetPack(pack(payload, ByteArray(16))), { _, _, _ -> }); fail()
        } catch (e: RadioException) { assertTrue(e.message!!.contains("another firmware build")) }
        assertTrue(radio.assetWrites.isEmpty())
    }

    @Test fun firmwareWithoutAssetsIsReported() {
        val radio = FakeRadio()                                    // v6: no asset commands
        val k5 = K5Protocol(radio).apply { hello() }
        try { AssetWriter(k5).info(); fail() } catch (e: AssetWriter.NotSupported) { }
    }
}
