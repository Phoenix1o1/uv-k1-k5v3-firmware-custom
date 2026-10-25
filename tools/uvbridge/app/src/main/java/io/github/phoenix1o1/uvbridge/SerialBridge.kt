package io.github.phoenix1o1.uvbridge

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.webkit.JavaScriptReplyProxy
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Native half of the Web Serial polyfill (assets/serial-polyfill.js).
 *
 * Requests arrive as JSON from the page through [onMessage]; answers and
 * events go back through the page's reply proxy, always on the UI thread.
 * A device is identified by its UsbDevice name ("/dev/bus/usb/001/004"),
 * stable while it stays plugged in.
 */
class SerialBridge(
    private val activity: Activity,
    private val files: FileSaver,
    private val onPageColor: (Int) -> Unit,
) {

    private val usb = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()      // writes, opens, closes
    private var reply: JavaScriptReplyProxy? = null

    private class Open(val port: UsbSerialPort, val manager: SerialInputOutputManager)
    private val open = HashMap<String, Open>()

    private var pendingPermission: Pair<Int, UsbDevice>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceOf(intent) ?: return
            when (intent.action) {
                ACTION_PERMISSION -> onPermission(device, intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33)
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag") activity.registerReceiver(receiver, filter)
    }

    fun stop() {
        activity.unregisterReceiver(receiver)
        open.keys.toList().forEach { closePort(it) }
        io.shutdown()
    }

    /** A message from the page. The proxy answers the frame that sent it. */
    fun onMessage(json: String, proxy: JavaScriptReplyProxy) {
        reply = proxy
        val msg = JSONObject(json)
        val id = msg.getInt("id")
        try {
            when (msg.getString("op")) {
                "requestPort" -> requestPort(id)
                "getPorts" -> ok(id, JSONArray().apply {
                    drivers().filter { usb.hasPermission(it.device) }.forEach { put(describe(it.device)) }
                })
                "open" -> openPort(id, msg)
                "write" -> write(id, msg.getString("key"), Base64.decode(msg.getString("b64"), Base64.DEFAULT))
                "close" -> { closePort(msg.getString("key")); ok(id, null) }
                "pageColor" -> { onPageColor(msg.getInt("rgb") or 0xff000000.toInt()); ok(id, null) }
                "save" -> files.save(msg.getString("name"), Base64.decode(msg.getString("b64"), Base64.DEFAULT),
                    { ok(id, null) }, { e -> fail(id, e) })
                else -> fail(id, "Unknown operation")
            }
        } catch (e: Exception) {
            fail(id, e.message ?: e.toString())
        }
    }

    // ---- requestPort: our own device list, then Android's permission dialog ----

    private fun requestPort(id: Int) {
        val list = drivers()
        if (list.isEmpty()) {
            fail(id, "No USB serial device found. Check the cable and the OTG adapter.", "NotFoundError")
            return
        }
        val labels = list.map { label(it) }.toTypedArray()
        var picked = false
        AlertDialog.Builder(activity)
            .setTitle(R.string.pick_device)
            .setItems(labels) { _, which -> picked = true; withPermission(id, list[which].device) }
            .setOnDismissListener { if (!picked) fail(id, "No port selected.", "NotFoundError") }
            .show()
    }

    private fun withPermission(id: Int, device: UsbDevice) {
        if (usb.hasPermission(device)) {
            ok(id, describe(device))
            return
        }
        pendingPermission = id to device
        val intent = Intent(ACTION_PERMISSION).setPackage(activity.packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(device, PendingIntent.getBroadcast(activity, 0, intent, flags))
    }

    private fun onPermission(device: UsbDevice, granted: Boolean) {
        val (id, wanted) = pendingPermission ?: return
        if (wanted.deviceName != device.deviceName) return
        pendingPermission = null
        if (granted) ok(id, describe(device)) else fail(id, "USB permission denied.", "SecurityError")
    }

    // ---- open / write / close ----

    private fun openPort(id: Int, msg: JSONObject) {
        val key = msg.getString("key")
        val driver = drivers().firstOrNull { it.device.deviceName == key }
            ?: return fail(id, "The device is no longer connected.", "NotFoundError")
        if (open.containsKey(key)) return fail(id, "The port is already open.", "InvalidStateError")
        val connection = usb.openDevice(driver.device)
            ?: return fail(id, "Cannot open the device (no USB permission?).", "SecurityError")

        io.execute {
            try {
                val port = driver.ports[0]
                port.open(connection)
                port.setParameters(
                    msg.getInt("baudRate"), msg.optInt("dataBits", 8),
                    if (msg.optInt("stopBits", 1) == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1,
                    when (msg.optString("parity", "none")) {
                        "even" -> UsbSerialPort.PARITY_EVEN
                        "odd" -> UsbSerialPort.PARITY_ODD
                        else -> UsbSerialPort.PARITY_NONE
                    })
                // Desktop Chrome raises DTR and RTS on open; some adapters need it.
                try { port.dtr = true; port.rts = true } catch (_: Exception) {}

                val manager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
                        post(JSONObject().put("ev", "data").put("key", key).put("b64", b64))
                    }
                    override fun onRunError(e: Exception) {
                        main.post { if (open.containsKey(key)) lost(key) }
                    }
                })
                manager.start()
                main.post { open[key] = Open(port, manager); ok(id, null) }
            } catch (e: Exception) {
                try { connection.close() } catch (_: Exception) {}
                fail(id, "Open failed: ${e.message}")
            }
        }
    }

    private fun write(id: Int, key: String, bytes: ByteArray) {
        val o = open[key] ?: return fail(id, "The port is not open.", "InvalidStateError")
        io.execute {
            try {
                o.port.write(bytes, WRITE_TIMEOUT_MS)
                ok(id, null)
            } catch (e: Exception) {
                fail(id, "Write failed: ${e.message}")
            }
        }
    }

    private fun closePort(key: String) {
        val o = open.remove(key) ?: return
        o.manager.listener = null
        o.manager.stop()
        io.execute { try { o.port.close() } catch (_: Exception) {} }
    }

    // ---- plug / unplug ----

    private fun onDetached(device: UsbDevice) = lost(device.deviceName)

    private fun lost(key: String) {
        closePort(key)
        post(JSONObject().put("ev", "disconnect").put("key", key))
    }

    /** UV Studio reconnects on "connect"; only for devices we may already use. */
    private fun onAttached(device: UsbDevice) {
        if (usb.hasPermission(device) && drivers().any { it.device.deviceName == device.deviceName })
            post(describe(device).put("ev", "connect"))
    }

    // ---- helpers ----

    private fun drivers(): List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usb)

    private fun describe(device: UsbDevice) = JSONObject()
        .put("key", device.deviceName)
        .put("info", JSONObject().put("vendorId", device.vendorId).put("productId", device.productId))

    private fun label(d: UsbSerialDriver): String {
        val dev = d.device
        val name = dev.productName ?: d.javaClass.simpleName.removeSuffix("SerialDriver")
        return "%s  (%04x:%04x)".format(name, dev.vendorId, dev.productId)
    }

    private fun ok(id: Int, result: Any?) =
        post(JSONObject().put("id", id).put("ok", true).put("result", result ?: JSONObject.NULL))

    private fun fail(id: Int, error: String, name: String = "NetworkError") =
        post(JSONObject().put("id", id).put("ok", false).put("error", error).put("name", name))

    private fun post(msg: JSONObject) {
        val text = msg.toString()
        main.post { reply?.postMessage(text) }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_PERMISSION = "io.github.phoenix1o1.uvbridge.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 2000
    }
}
