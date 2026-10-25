package io.github.phoenix1o1.uvbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * UV Studio in a WebView, with Web Serial provided by the app.
 *
 * The page is the live UV Studio site. assets/serial-polyfill.js is injected
 * before its scripts and talks to [SerialBridge] through a message listener
 * that only the UV Studio origin can reach.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bridge: SerialBridge
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A flash must not be cut short by the screen going off.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Toast.makeText(this, R.string.webview_too_old, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        web = WebView(this)
        setContentView(web)
        keepClearOfSystemBars()
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            // Lay the page out as mobile Chrome does: honour its viewport tag
            // and zoom out to fit when it is wider than the screen (UV Studio's
            // header is a little wider than a phone).
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // The system font size would enlarge the text past what the
            // layout allows; pinch zoom is there instead.
            textZoom = 100
        }

        bridge = SerialBridge(this, FileSaver(this), ::setBarColor)
        bridge.start()

        val origins = setOf(UV_STUDIO_ORIGIN)
        WebViewCompat.addWebMessageListener(web, "UVBridgeNative", origins) { _, message, _, _, reply ->
            message.data?.let { bridge.onMessage(it, reply) }
        }
        val polyfill = assets.open("serial-polyfill.js").bufferedReader().use { it.readText() }
        WebViewCompat.addDocumentStartJavaScript(web, polyfill, origins)

        web.webViewClient = object : WebViewClient() {
            // Stay on UV Studio; any other site opens in the browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "https" && url.host == UV_STUDIO_HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // <input type="file">: firmware .bin, calibration .dat, logos...
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                           params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                return try {
                    startActivityForResult(Intent.createChooser(pick, null), PICK_FILE)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        // Regular http(s) downloads (the CHIRP driver...); blob downloads are
        // handled by the polyfill.
        web.setDownloadListener { url, _, disposition, mime, _ ->
            if (!url.startsWith("http")) return@setDownloadListener
            val name = URLUtil.guessFileName(url, disposition, mime)
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(DownloadManager.Request(Uri.parse(url))
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED))
            Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("$UV_STUDIO_ORIGIN/uvstudio/")
    }

    /** Android 15 draws apps edge to edge: pad the page off the status bar,
     *  the navigation bar and the camera cutout. */
    private fun keepClearOfSystemBars() {
        web.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** The page's background colour, from the polyfill: paint it behind the
     *  bars, with icons that stay readable on it. */
    private fun setBarColor(color: Int) {
        web.setBackgroundColor(color)
        window.decorView.setBackgroundColor(color)
        val r = (color shr 16) and 0xff; val g = (color shr 8) and 0xff; val b = color and 0xff
        val light = r * 299 + g * 587 + b * 114 > 128_000          // perceived brightness
        val dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                   WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (light) dark else 0, dark)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILE) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            fileCallback?.onReceiveValue(uri?.let { arrayOf(it) })
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) bridge.stop()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val UV_STUDIO_HOST = "armel.github.io"
        private const val UV_STUDIO_ORIGIN = "https://$UV_STUDIO_HOST"
        private const val PICK_FILE = 1
    }
}
