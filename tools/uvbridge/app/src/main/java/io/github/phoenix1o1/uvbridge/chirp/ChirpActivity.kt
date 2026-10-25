package io.github.phoenix1o1.uvbridge.chirp

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
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.github.phoenix1o1.uvbridge.FileSaver
import io.github.phoenix1o1.uvbridge.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Reads and writes CHIRP .img files as CHIRP's F4HWN driver does, without
 * editing them: "Download" saves the radio to an image, "Upload" writes an
 * image to the radio after saving a backup of what the radio holds.
 *
 * The transfer runs on a worker thread, which waits on the UI for the
 * confirmation and the file saves, so the sequence reads top to bottom.
 */
class ChirpActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager
    private lateinit var files: FileSaver
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<Button>
    private var busy = false

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
        buildLayout()
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
    }

    private fun buildLayout() {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            setText(R.string.chirp_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        })
        root.addView(TextView(this).apply {
            setText(R.string.chirp_intro)
            setPadding(0, dp(12), 0, dp(24))
        })
        val download = Button(this).apply { setText(R.string.chirp_download); setOnClickListener { download() } }
        val upload = Button(this).apply { setText(R.string.chirp_upload); setOnClickListener { pickImage() } }
        buttons = listOf(download, upload)
        buttons.forEach { root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(24) })
        status = TextView(this).apply { setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }
        root.addView(status)

        // Edge to edge (Android 15): keep clear of the bars and the cutout.
        val side = dp(24)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left + side, bars.top + side, bars.right + side, bars.bottom + side)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    // ---- the two operations ----

    private fun download() = withRadio { clone ->
        val image = clone.download(::showProgress)
        val name = "uvk1-${stamp()}.img"
        save(name, image)
        "Saved to Downloads/$FOLDER: $name\n${describe(image)}"
    }

    private fun upload(image: ChirpImage, fileName: String) = withRadio { clone ->
        // 1. A backup of the radio as it is now.
        val backup = clone.download(::showProgress)
        val backupName = "uvk1-backup-${stamp()}.img"
        save(backupName, backup)

        // 2. The user's go-ahead, knowing which radio and which image.
        val message = getString(R.string.chirp_confirm, clone.firmware, backupName, fileName, describe(image))
        if (!ask(getString(R.string.chirp_upload), message)) return@withRadio "Cancelled. The radio was not changed."

        // 3. Write, verify, restart.
        val mismatches = clone.upload(image, ::showProgress)
        if (mismatches.isEmpty()) "Done: written and verified. The radio restarts."
        else "Written, but ${mismatches.size} block(s) read back differently (first at 0x%04X). ".format(mismatches[0]) +
            "The radio restarts; the backup is $backupName."
    }

    private fun pickImage() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(pick, PICK_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || uri == null) return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            upload(ChirpImage.parse(bytes), displayName(uri))
        } catch (e: Exception) {
            status.text = e.message ?: e.toString()
        }
    }

    // ---- radio session: device, permission, port, hello ----

    /** Picks the cable, opens it, says hello, runs [task] on the worker; its result is shown. */
    private fun withRadio(task: (RadioClone) -> String) {
        if (busy) return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        when (drivers.size) {
            0 -> status.text = "No USB serial cable found. Check the cable and the OTG adapter."
            1 -> withPermission(drivers[0], task)
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.pick_device)
                .setItems(drivers.map { label(it) }.toTypedArray()) { _, i -> withPermission(drivers[i], task) }
                .show()
        }
    }

    private fun withPermission(driver: UsbSerialDriver, task: (RadioClone) -> String) {
        if (usb.hasPermission(driver.device)) return start(driver, task)
        onPermission = { granted -> if (granted) start(driver, task) else status.text = "USB permission denied." }
        val intent = Intent(ACTION_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(driver.device, PendingIntent.getBroadcast(this, 0, intent, flags))
    }

    private fun start(driver: UsbSerialDriver, task: (RadioClone) -> String) {
        val connection = usb.openDevice(driver.device)
            ?: run { status.text = "Cannot open the cable."; return }
        setBusy(true)
        status.text = "Connecting…"
        worker.execute {
            val result = try {
                val port = driver.ports[0].apply { open(connection) }
                UsbSerialLink(port).use { link ->
                    val clone = RadioClone(K5Protocol(link))
                    val firmware = clone.connect()
                    runOnUiThread { status.text = "Radio: firmware $firmware" }
                    task(clone)
                }
            } catch (e: Exception) {
                try { connection.close() } catch (_: Exception) {}
                "Error: ${e.message ?: e}"
            }
            runOnUiThread { status.text = result; setBusy(false) }
        }
    }

    // ---- worker → UI helpers ----

    private fun showProgress(done: Int, total: Int, stage: String) = runOnUiThread {
        progress.progress = (done * 1000L / total).toInt()
        status.text = "$stage… ${done * 100 / total}%"
    }

    /** Saves on the UI thread (FileSaver shows a toast); throws if it failed. */
    private fun save(name: String, image: ChirpImage) {
        val result = CompletableFuture<String?>()
        runOnUiThread { files.save(name, image.toBytes(), { result.complete(null) }, { result.complete(it) }, FOLDER) }
        result.get()?.let { throw RadioException("Could not save $name: $it") }
    }

    /** Asks on the UI thread and waits for the answer. */
    private fun ask(title: String, message: String): Boolean {
        val answer = CompletableFuture<Boolean>()
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(R.string.chirp_write) { _, _ -> answer.complete(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> answer.complete(false) }
                .setOnDismissListener { answer.complete(false) }
                .show()
        }
        return answer.get()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        buttons.forEach { it.isEnabled = !b }
        if (!b) progress.progress = 0
    }

    // ---- small things ----

    private fun describe(image: ChirpImage): String {
        val lists = image.listNames()
        return "${image.channelCount()} channels" + if (lists.isEmpty()) "" else ", scan lists: ${lists.joinToString(", ")}"
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "image"

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
        private const val ACTION_PERMISSION = "io.github.phoenix1o1.uvbridge.CHIRP_USB_PERMISSION"
        private const val PICK_IMAGE = 1
        /** Images and backups go to Downloads/CHIRP. */
        private const val FOLDER = "CHIRP"
    }
}
