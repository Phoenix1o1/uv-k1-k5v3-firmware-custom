package io.github.phoenix1o1.uvbridge

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.github.phoenix1o1.uvbridge.chirp.AssetWriter
import io.github.phoenix1o1.uvbridge.chirp.ChirpDriver
import io.github.phoenix1o1.uvbridge.chirp.ChirpImage
import io.github.phoenix1o1.uvbridge.chirp.ImageEditorActivity
import io.github.phoenix1o1.uvbridge.chirp.K5Protocol
import io.github.phoenix1o1.uvbridge.chirp.RadioClone
import io.github.phoenix1o1.uvbridge.chirp.RadioException
import io.github.phoenix1o1.uvbridge.chirp.UsbSerialLink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * The app's one screen: UV Studio, and what the app does with the radio
 * itself. CHIRP images ("Download" saves the radio to an image, "Upload"
 * writes one after saving a backup, "View / edit" opens [ImageEditorActivity]),
 * firmware assets (fonts and menus of the v7 firmwares) and the CHIRP
 * drivers whose layout the image code follows.
 *
 * Drivers and asset packs are picked for the radio: the driver made for its
 * firmware version (else the last used, else the built-in layout), the
 * asset pack of the exact firmware build it runs (loaded from the firmware
 * file that was flashed, then kept).
 *
 * Radio and network work runs on a worker thread, which waits on the UI
 * for confirmations, choices and file saves, so each sequence reads top to
 * bottom.
 */
class HomeActivity : Activity() {

    // A roomy stack: driver files are parsed on it.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(null, r, "radio", 8L shl 20) }
    private lateinit var usb: UsbManager
    private lateinit var files: FileSaver
    private lateinit var library: Library
    private lateinit var scroll: ScrollView
    private lateinit var chirpCard: LinearLayout
    /** "Install again": shown once the app keeps an asset pack. */
    private lateinit var againRow: View
    private lateinit var radioLine: TextView
    private lateinit var driverLine: TextView
    private lateinit var driverWhy: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val buttons = mutableListOf<View>()
    private var busy = false
    /** Firmware version string of the radio last connected, "" before. */
    private var lastFirmware = ""

    /** What to run once the user picked a device and granted the permission. */
    private var onPermission: ((Boolean) -> Unit)? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            onPermission?.also { onPermission = null }?.invoke(granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        files = FileSaver(this)
        library = Library(this)
        buildLayout()
        showPicks()
        showRequested(intent)
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showRequested(intent)
    }

    /** The launcher's long-press shortcut "CHIRP image" opens the home screen at that card. */
    private fun showRequested(intent: Intent?) {
        if (intent?.action == ACTION_CHIRP) scroll.post { scroll.smoothScrollTo(0, chirpCard.top) }
    }

    // ---- layout ----

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildLayout() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // header: icon, name, what the app is for, version
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), 0, dp(4)) }
        header.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        names.addView(Ui.text(this, getString(R.string.app_name), 24f, bold = true))
        names.addView(Ui.text(this, getString(R.string.home_tagline), 13f, R.color.muted))
        names.addView(Ui.text(this, "v" + packageManager.getPackageInfo(packageName, 0).versionName, 12f, R.color.muted))
        header.addView(names)
        column.addView(header)

        // status: radio, progress and messages
        val statusCard = Ui.card(this)
        statusCard.addView(Ui.cardHead(this, R.drawable.ic_radio, getString(R.string.home_radio), null))
        radioLine = Ui.text(this, getString(R.string.home_not_connected), 14f).apply { setPadding(0, dp(10), 0, 0) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        status = Ui.text(this, getString(R.string.home_hint), 13f, R.color.muted).apply {
            setPadding(0, dp(8), 0, 0); setTextIsSelectable(true)
        }
        statusCard.addView(radioLine)
        statusCard.addView(progress, Ui.lp(dp(10))); statusCard.addView(status)
        column.addView(statusCard, Ui.lp(dp(16)))

        // UV Studio, with its author
        val studio = section(R.drawable.ic_studio, R.string.studio_title, R.string.studio_text,
            action(R.string.studio_open, filled = true) { startActivity(Intent(this, MainActivity::class.java)) })
        studio.addView(Ui.text(this, getString(R.string.studio_credit), 12f, R.color.muted).apply { setPadding(0, dp(10), 0, 0) })
        column.addView(studio)

        // CHIRP image, and the driver its layout follows
        chirpCard = section(R.drawable.ic_chirp, R.string.chirp_title, R.string.chirp_intro,
            action(R.string.chirp_download, filled = true) { download() },
            action(R.string.chirp_upload) { pickFile(PICK_IMAGE) },
            action(R.string.chirp_edit) { pickFile(PICK_EDIT) })
        chirpCard.addView(Ui.divider(this), Ui.lp(dp(16)))
        val driverRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        driverRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_driver)
            imageTintList = android.content.res.ColorStateList.valueOf(Ui.color(this@HomeActivity, R.color.muted))
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val driverTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        driverLine = Ui.text(this, "", 14f, bold = true)
        driverWhy = Ui.text(this, "", 12f, R.color.muted)
        driverTexts.addView(driverLine); driverTexts.addView(driverWhy)
        driverRow.addView(driverTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chirpCard.addView(driverRow)
        chirpCard.addView(Ui.buttonRow(this, action(R.string.drivers_manage) { manageDrivers() },
            action(R.string.from_repo) { driverFromRepo() }, action(R.string.from_file) { pickFile(PICK_DRIVER) }))
        column.addView(chirpCard)

        // Advanced, folded: firmware assets, only for firmwares flashed from a
        // file (UV Studio writes the assets itself when it flashes from its list)
        val assets = section(R.drawable.ic_assets, R.string.assets_title, R.string.assets_text,
            action(R.string.assets_install, filled = true) { pickFile(PICK_FIRMWARE) })
        againRow = Ui.buttonRow(this, action(R.string.assets_again) { installAssets(null) })
        assets.addView(againRow)
        assets.visibility = View.GONE
        val advanced = Ui.text(this, "", 14f, R.color.muted, bold = true).apply {
            setPadding(dp(8), dp(14), dp(8), dp(6))
            fun label() { text = getString(R.string.advanced) + if (assets.visibility == View.VISIBLE) "  ▴" else "  ▾" }
            label()
            setOnClickListener { assets.visibility = if (assets.visibility == View.VISIBLE) View.GONE else View.VISIBLE; label() }
        }
        column.addView(advanced, Ui.lp(dp(12)))
        column.addView(assets)

        // about: credits and licences
        column.addView(Ui.text(this, getString(R.string.about_link), 14f, R.color.accent, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(20), dp(8), dp(8))
            setOnClickListener { startActivity(Intent(this@HomeActivity, AboutActivity::class.java)) }
        }, Ui.lp(dp(8)))

        scroll = ScrollView(this).apply { addView(column); isFillViewport = true }
        Ui.keepClearOfBars(scroll, dp(16))
        setContentView(scroll)
    }

    private fun action(label: Int, filled: Boolean = false, onClick: () -> Unit) =
        Ui.button(this, getString(label), filled) { if (!busy) onClick() }.also { buttons += it }

    /** A card: head, then its buttons, the first one full width. */
    private fun section(icon: Int, title: Int, text: Int, vararg actions: View): LinearLayout {
        val card = Ui.card(this)
        card.addView(Ui.cardHead(this, icon, getString(title), getString(text)))
        card.addView(Ui.buttonRow(this, actions[0]))
        if (actions.size > 1) card.addView(Ui.buttonRow(this, *actions.drop(1).toTypedArray()))
        return card.also { it.layoutParams = Ui.lp(dp(12)) }
    }

    /** The driver the next radio or image session will use, and whether a pack is kept. */
    private fun showPicks() {
        againRow.visibility = if (library.packs().isEmpty()) View.GONE else View.VISIBLE
        val version = ChirpDriver.VERSION.find(lastFirmware)?.groupValues?.get(1) ?: ""
        val pick = library.pickDriver(version)
        driverLine.text = getString(R.string.home_driver, driverName(pick.item))
        driverWhy.text = (if (pick.item.isBuiltin) "" else pick.item.name + " · ") + pick.why
    }

    /** "F4HWN 6.3.0", "F4HWN 6.3.0 (built-in)". */
    private fun driverName(d: ChirpDriver) =
        "F4HWN ${d.firmwareVersion.ifEmpty { "?" }}" + if (d.isBuiltin) " " + getString(R.string.drivers_builtin) else ""

    // ---- CHIRP image ----

    private fun download() = withRadio { s ->
        val image = s.clone.download(::showProgress)
        val name = "uvk1-${stamp()}.img"
        save(name, image.toBytes(), CHIRP_FOLDER)
        "Saved to Downloads/$CHIRP_FOLDER: $name\n${describe(image)}"
    }

    private fun upload(bytes: ByteArray, fileName: String) = withRadio { s ->
        val image = ChirpImage.parse(bytes, s.driver)             // refuses other models
        // 1. A backup of the radio as it is now.
        val backup = s.clone.download(::showProgress)
        val backupName = "uvk1-backup-${stamp()}.img"
        save(backupName, backup.toBytes(), CHIRP_FOLDER)

        // 2. The user's go-ahead, knowing which radio and which image.
        val message = getString(R.string.chirp_confirm, s.firmware, backupName, fileName, describe(image))
        if (!ask(getString(R.string.chirp_upload), message, getString(R.string.chirp_write)))
            return@withRadio "Cancelled. The radio was not changed."

        // 3. Write, verify, restart.
        val mismatches = s.clone.upload(image, ::showProgress)
        if (mismatches.isEmpty()) "Done: written and verified. The radio restarts."
        else "Written, but ${mismatches.size} block(s) read back differently (first at 0x%04X). ".format(mismatches[0]) +
            "The radio restarts; the backup is $backupName."
    }

    private fun describe(image: ChirpImage): String {
        val lists = image.listNames()
        return "${image.channelCount()} channels" + if (lists.isEmpty()) "" else ", scan lists: ${lists.joinToString(", ")}"
    }

    // ---- firmware assets ----

    /**
     * Writes an asset pack to the radio: [file]'s (the firmware .bin flashed
     * by hand, or a .pack), else the kept pack of the build the radio runs.
     * The radio only takes the pack of its own build.
     */
    private fun installAssets(file: Library.StoredPack?) = withRadio { s ->
        val writer = AssetWriter(s.k5)
        val info = writer.info()
        val stored = file ?: library.pickPack(info.runningBuildId)?.item
            ?: return@withRadio "No kept asset pack for the firmware build of this radio (${info.runningBuildId.take(8)}…). " +
                "Use \"${getString(R.string.assets_install)}\" with the .bin you flashed."
        if (info.loaded && info.status == 0 &&
            !ask(getString(R.string.assets_title), getString(R.string.assets_already), getString(R.string.assets_write_again)))
            return@withRadio "The radio's assets are installed and valid. Nothing written."
        writer.write(stored.pack, ::showProgress)
        library.usedPack(stored)
        "Firmware assets written and checked (${stored.name}). The radio redraws with them."
    }

    /** A firmware file was picked: keep its asset pack and write it. */
    private fun firmwareFile(name: String, bytes: ByteArray) {
        val stored = library.addPack(name, bytes)
            ?: return run { show("$name has no asset pack (only v7 firmware packages carry one); nothing to install.") }
        showPicks()
        installAssets(stored)
    }

    // ---- CHIRP drivers ----

    private fun driverFromRepo() = background {
        val list = Repo.drivers()
        if (list.isEmpty()) return@background "No CHIRP driver found in the releases."
        val kept = library.drivers().map { it.name }.toSet()
        val i = choose(getString(R.string.drivers_pick), list.map { f ->
            Ui.Row("F4HWN ${f.version.ifEmpty { f.label }}", f.name, if (f.name in kept) "Kept" else null)
        }) ?: return@background "Nothing downloaded."
        val f = list[i]
        status("Downloading ${f.name}…")
        val bytes = Repo.get(f.url)
        val driver = library.addDriver(f.name, bytes)
        save(f.name, bytes, DRIVER_FOLDER)
        runOnUiThread { showPicks() }
        "Driver kept: ${driver.name}\n${driver.version}\nAlso saved to Downloads/$DRIVER_FOLDER for CHIRP on a computer."
    }

    private fun manageDrivers() {
        val all = library.drivers()
        val current = library.pickDriver(ChirpDriver.VERSION.find(lastFirmware)?.groupValues?.get(1) ?: "").item
        val rows = all.map { d ->
            val tag = if (d.name == current.name) "In use" else null
            Ui.Row(driverName(d), if (d.isBuiltin) getString(R.string.drivers_builtin_text) else d.name, tag)
        }
        AlertDialog.Builder(this).setTitle(R.string.drivers_title)
            .setAdapter(Ui.rows(this, rows)) { _, i -> driverOptions(all[i]) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun driverOptions(d: ChirpDriver) {
        val options = mutableListOf(getString(R.string.drivers_use))
        if (!d.isBuiltin) options += getString(R.string.drivers_remove)
        AlertDialog.Builder(this).setTitle(d.name)
            .setItems(options.toTypedArray()) { _, i ->
                if (i == 0) { library.usedDriver(d); toast("${d.name} is used when no driver matches the radio.") }
                else { library.removeDriver(d); toast("Removed ${d.name}.") }
                showPicks()
            }.show()
    }

    // ---- files ----

    private fun pickFile(request: Int) {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(pick, request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) return
        try {
            if (requestCode == EDITED) {
                // "Upload" in the editor: the edited image, saved or not.
                val bytes = data.getByteArrayExtra(ImageEditorActivity.EXTRA_IMAGE) ?: return
                return upload(bytes, data.getStringExtra(ImageEditorActivity.EXTRA_NAME) ?: "image")
            }
            val uri = data.data ?: return
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val name = displayName(uri)
            when (requestCode) {
                PICK_IMAGE -> upload(bytes, name)
                PICK_EDIT -> {
                    val driver = library.driverForImage(bytes)        // refuses other models
                    @Suppress("DEPRECATION") startActivityForResult(
                        ImageEditorActivity.intent(this, bytes, name, uri, driver.name), EDITED)
                }
                PICK_DRIVER -> {
                    val d = library.addDriver(name, bytes)
                    showPicks()
                    show("Driver kept: ${d.name}\n${d.version}")
                }
                PICK_FIRMWARE -> firmwareFile(name, bytes)
            }
        } catch (e: Exception) {
            show(e.message ?: e.toString())
        }
    }

    // ---- radio session: device, permission, port, hello ----

    /** A connected radio: its protocol, firmware and the driver picked for it. */
    private inner class Session(val k5: K5Protocol, val firmware: String) {
        val version = ChirpDriver.VERSION.find(firmware)?.groupValues?.get(1) ?: ""
        val pick = library.pickDriver(version)
        val driver = pick.item
        val clone by lazy { RadioClone(k5, driver).also { it.firmware = firmware } }
    }

    /** Picks the cable, opens it, says hello, runs [task] on the worker; its result is shown. */
    private fun withRadio(task: (Session) -> String) {
        if (busy) return toast(getString(R.string.chirp_wait))
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        when (drivers.size) {
            0 -> show("No USB serial cable found. Check the cable and the OTG adapter.")
            1 -> withPermission(drivers[0], task)
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.pick_device)
                .setItems(drivers.map { label(it) }.toTypedArray()) { _, i -> withPermission(drivers[i], task) }
                .show()
        }
    }

    private fun withPermission(driver: UsbSerialDriver, task: (Session) -> String) {
        if (usb.hasPermission(driver.device)) return start(driver, task)
        onPermission = { granted -> if (granted) start(driver, task) else show("USB permission denied.") }
        show("Waiting for the USB permission…")
        val intent = Intent(ACTION_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(driver.device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun start(driver: UsbSerialDriver, task: (Session) -> String) {
        val connection = usb.openDevice(driver.device)
            ?: run { show("Cannot open the cable. Close UV Studio if it is connected, then try again."); return }
        background("Connecting…") {
            try {
                val port = driver.ports[0].apply { open(connection) }
                UsbSerialLink(port).use { link ->
                    val k5 = K5Protocol(link)
                    val s = Session(k5, k5.hello())
                    runOnUiThread {
                        lastFirmware = s.firmware
                        radioLine.text = getString(R.string.home_connected, s.firmware)
                        showPicks()
                    }
                    val result = task(s)
                    if (!s.driver.isBuiltin) library.usedDriver(s.driver)    // the built-in is the fallback anyway
                    result
                }
            } catch (e: Exception) {
                try { connection.close() } catch (_: Exception) {}
                throw e
            }
        }
    }

    /** Runs [task] on the worker with the buttons off; shows its result or error. */
    private fun background(first: String = "Working…", task: () -> String) {
        if (busy) return toast(getString(R.string.chirp_wait))
        setBusy(true)
        show(first)
        worker.execute {
            // Throwable: whatever goes wrong, the buttons come back.
            val result = try { task() } catch (e: Throwable) {
                when (e) {
                    is RadioException, is IllegalArgumentException -> e.message ?: e.toString()
                    else -> "Error: ${e.message ?: e}"
                }
            }
            runOnUiThread { status.text = result; setBusy(false) }
        }
    }

    // ---- worker → UI helpers ----

    private fun showProgress(done: Int, total: Int, stage: String) = runOnUiThread {
        progress.visibility = View.VISIBLE
        progress.progress = (done * 1000L / total).toInt()
        status.text = "$stage… ${done * 100 / total}%"
    }

    private fun status(text: String) = runOnUiThread { show(text) }

    /** A message in the status card, scrolled into view. */
    private fun show(text: String) {
        status.text = text
        scroll.smoothScrollTo(0, 0)
    }

    /** Saves on the UI thread (FileSaver shows a toast); throws if it failed. */
    private fun save(name: String, bytes: ByteArray, folder: String) {
        val result = CompletableFuture<String?>()
        runOnUiThread { files.save(name, bytes, { result.complete(null) }, { result.complete(it) }, folder) }
        result.get()?.let { throw RadioException("Could not save $name: $it") }
    }

    /** Asks on the UI thread and waits for the answer. */
    private fun ask(title: String, message: String, yes: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(yes) { _, _ -> answer.complete(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer.complete(false) }
                .setOnDismissListener { answer.complete(false) }
                .show()
        }
        return answer.get()
    }

    /** Lets the user pick one of [rows] on the UI thread; null when cancelled. */
    private fun choose(title: String, rows: List<Ui.Row>): Int? {
        val answer = CompletableFuture<Int?>()
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title)
                .setAdapter(Ui.rows(this, rows)) { _, i -> answer.complete(i) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer.complete(null) }
                .setOnDismissListener { answer.complete(null) }
                .show()
        }
        return answer.get()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        buttons.forEach { Ui.enable(it, !b) }
        if (!b) { progress.progress = 0; progress.visibility = View.GONE }
    }

    // ---- small things ----

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    private fun label(d: UsbSerialDriver) =
        "%s  (%04x:%04x)".format(d.device.productName ?: "USB serial", d.device.vendorId, d.device.productId)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Leaving would not stop the transfer, but would hide its outcome.
        if (busy) Toast.makeText(this, R.string.chirp_wait, Toast.LENGTH_SHORT).show()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CHIRP = "io.github.phoenix1o1.uvbridge.CHIRP"
        private const val ACTION_PERMISSION = "io.github.phoenix1o1.uvbridge.CHIRP_USB_PERMISSION"
        private const val PICK_IMAGE = 1
        private const val PICK_EDIT = 2
        private const val EDITED = 3
        private const val PICK_DRIVER = 4
        private const val PICK_FIRMWARE = 5
        /** Images and backups go to Downloads/CHIRP, drivers to its drivers folder. */
        const val CHIRP_FOLDER = "CHIRP"
        const val DRIVER_FOLDER = "CHIRP/drivers"
    }
}
