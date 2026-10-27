package io.github.phoenix1o1.uvbridge

import android.content.Context
import io.github.phoenix1o1.uvbridge.chirp.AssetPack
import io.github.phoenix1o1.uvbridge.chirp.ChirpDriver
import io.github.phoenix1o1.uvbridge.chirp.ChirpImage
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The CHIRP drivers and firmware asset packs the app keeps (in its own
 * storage, so it can read them back), and which one was used last.
 * Copies of downloaded files also go to Downloads, for CHIRP on a computer
 * and for UV Studio.
 */
class Library(context: Context) {

    private val drivers = File(context.filesDir, "drivers").apply { mkdirs() }
    private val packs = File(context.filesDir, "assets").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    // ---- CHIRP drivers ----

    /** The built-in layout, then the driver files, newest version first. */
    fun drivers(): List<ChirpDriver> = listOf(ChirpDriver.BUILTIN) +
        (drivers.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            try { ChirpDriver.parse(f.name, f.readText()) } catch (_: Exception) { null }
        }.sortedWith(compareByDescending<ChirpDriver> { versionKey(it.firmwareVersion) })

    /** Checks and keeps a driver file; returns it. Throws IllegalArgumentException when unusable. */
    fun addDriver(name: String, bytes: ByteArray): ChirpDriver {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".py")) it else "$it.py" }
        val driver = ChirpDriver.parse(safe, String(bytes))
        File(drivers, safe).writeBytes(bytes)
        return driver
    }

    fun removeDriver(d: ChirpDriver) { if (!d.isBuiltin) File(drivers, d.name).delete() }

    /** A pick, and why it was picked. */
    data class Pick<T>(val item: T, val why: String)

    /**
     * The driver for a radio or image of firmware [version] ("6.3.0"): the
     * one made for that version, else the one used last, else the built-in.
     */
    fun pickDriver(version: String): Pick<ChirpDriver> {
        val all = drivers()
        if (version.isNotEmpty()) all.firstOrNull { !it.isBuiltin && it.firmwareVersion == version }
            ?.let { return Pick(it, "made for v$version") }
        all.firstOrNull { it.name == prefs.getString(LAST_DRIVER, null) }?.let { return Pick(it, "last used") }
        return Pick(ChirpDriver.BUILTIN, if (all.size > 1) "no driver for this version" else "no driver loaded")
    }

    /**
     * The driver for editing an image: the one made for the firmware version
     * stored in it, else the last used, as long as it takes the image's
     * model; else one that does. Throws IllegalArgumentException (other
     * model, not an image) with a readable reason.
     */
    fun driverForImage(file: ByteArray): ChirpDriver {
        val all = drivers()
        val takes = { d: ChirpDriver -> runCatching { ChirpImage.parse(file, d) }.getOrNull() }
        val image = all.firstNotNullOfOrNull(takes) ?: ChirpImage.parse(file)
        val pick = pickDriver(image.firmwareVersion()).item
        return if (takes(pick) != null) pick else all.first { takes(it) != null }
    }

    fun usedDriver(d: ChirpDriver) = prefs.edit().putString(LAST_DRIVER, d.name).apply()

    // ---- firmware asset packs ----

    class StoredPack(val name: String, val pack: AssetPack)

    fun packs(): List<StoredPack> = (packs.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
        try { StoredPack(f.name.removeSuffix(".pack"), AssetPack(f.readBytes())) } catch (_: Exception) { null }
    }

    /** Keeps the asset pack of a firmware package or pack file; null when the file has none. */
    fun addPack(name: String, file: ByteArray): StoredPack? {
        val pack = AssetPack.from(file) ?: return null
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_")
        // one file per build: a newer copy of the same build replaces it
        packs().filter { it.pack.buildId == pack.buildId }.forEach { File(packs, it.name + ".pack").delete() }
        File(packs, "$base.pack").writeBytes(pack.bytes)
        return StoredPack(base, pack)
    }

    fun removePack(p: StoredPack) { File(packs, p.name + ".pack").delete() }

    /** The pack of the firmware build the radio runs, if the app has it; the last used one goes first. */
    fun pickPack(buildId: String): Pick<StoredPack>? {
        val all = packs().filter { it.pack.buildId == buildId }
        val last = all.firstOrNull { it.name == prefs.getString(LAST_PACK, null) }
        return (last ?: all.firstOrNull())?.let { Pick(it, if (last != null) "last used" else "matches the radio") }
    }

    fun usedPack(p: StoredPack) = prefs.edit().putString(LAST_PACK, p.name).apply()

    companion object {
        private const val LAST_DRIVER = "lastDriver"
        private const val LAST_PACK = "lastPack"

        /** "6.10.1" sorts after "6.9". */
        fun versionKey(v: String): Long =
            v.split('.').take(3).map { it.toLongOrNull() ?: 0 }.let { p -> (0..2).fold(0L) { acc, i -> acc * 10000 + p.getOrElse(i) { 0 } } }
    }
}

/**
 * The firmware repository (armel/uv-k1-k5v3-firmware-custom), read as UV
 * Studio reads it: CHIRP drivers are release files named
 * f4hwn.chirp.v<version>.py; firmware packages are the .bin files of archive/ on main,
 * plus the development build on the v7 branch. Blocking calls: run them on
 * a worker thread.
 */
object Repo {
    const val REPO = "armel/uv-k1-k5v3-firmware-custom"
    private const val API = "https://api.github.com/repos/$REPO"
    const val DEV_BRANCH = "feature_update_v7"
    const val DEV_FILE = "f4hwn.fusion.development.bin"

    class RemoteFile(val name: String, val label: String, val url: String, val version: String)

    /** The CHIRP drivers of the releases, newest first. */
    fun drivers(): List<RemoteFile> {
        val releases = JSONArray(String(get("$API/releases?per_page=40", json = true)))
        val out = mutableListOf<RemoteFile>()
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val name = a.getString("name")
                if (!name.endsWith(".py", true) || !name.contains("chirp", true)) continue
                out += RemoteFile(name, r.optString("tag_name"), a.getString("browser_download_url"),
                    ChirpDriver.VERSION.find(name)?.groupValues?.get(1) ?: "")
            }
        }
        return out
    }

    /** F4HWN firmware packages: the archive on main, newest first, then the development build. */
    fun firmwares(): List<RemoteFile> {
        val files = JSONArray(String(get("$API/contents/archive?ref=main", json = true)))
        val out = mutableListOf<RemoteFile>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.getString("name")
            if (!name.startsWith("f4hwn.") || !name.endsWith(".bin") || name.contains("sa818")) continue
            val version = ChirpDriver.VERSION.find(name)?.groupValues?.get(1) ?: continue
            out += RemoteFile(name, "v$version", f.getString("download_url"), version)
        }
        out.sortByDescending { Library.versionKey(it.version) }
        out += RemoteFile(DEV_FILE, "development",
            "https://raw.githubusercontent.com/$REPO/$DEV_BRANCH/archive/$DEV_FILE", "")
        return out
    }

    fun get(url: String, json: Boolean = false): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "UVBridge")
        if (json) c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw java.io.IOException("$url: HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
