package io.github.phoenix1o1.uvbridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What the app is, who made what it builds on, and the licences: the
 * credits as cards, the full texts (assets/NOTICE.txt, assets/licenses/)
 * in a dialog.
 */
class AboutActivity : Activity() {

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val version = packageManager.getPackageInfo(packageName, 0).versionName

        column.addView(Ui.text(this, getString(R.string.about_title), 24f, bold = true))
        column.addView(Ui.text(this, "${getString(R.string.app_name)} v$version", 13f, R.color.muted)
            .apply { setPadding(0, dp(2), 0, 0) })

        credit(column, getString(R.string.app_name), getString(R.string.about_app),
            "Phoenix1o1/uv-k1-k5v3-firmware-custom" to "https://github.com/Phoenix1o1/uv-k1-k5v3-firmware-custom/tree/uvbridge/tools/uvbridge")
        credit(column, "UV Studio", getString(R.string.about_studio),
            "armel.github.io/uvstudio" to "https://armel.github.io/uvstudio/")
        credit(column, getString(R.string.about_firmware_title), getString(R.string.about_firmware),
            "armel/uv-k1-k5v3-firmware-custom" to "https://github.com/armel/uv-k1-k5v3-firmware-custom")
        credit(column, getString(R.string.about_chirp_title), getString(R.string.about_chirp),
            "chirpmyradio.com" to "https://chirpmyradio.com/")
        credit(column, getString(R.string.about_libraries_title), getString(R.string.about_libraries),
            "usb-serial-for-android" to "https://github.com/mik3y/usb-serial-for-android")

        // the full texts
        val texts = Ui.card(this)
        texts.addView(Ui.text(this, getString(R.string.about_texts), 17f, bold = true))
        texts.addView(Ui.buttonRow(this, Ui.button(this, "NOTICE", filled = true) { showText("NOTICE", "NOTICE.txt") }))
        texts.addView(Ui.buttonRow(this,
            Ui.button(this, "Apache 2.0") { showText("Apache License 2.0", "licenses/Apache-2.0.txt") },
            Ui.button(this, "MIT") { showText("usb-serial-for-android (MIT)", "licenses/MIT-usb-serial-for-android.txt") }))
        column.addView(texts, Ui.lp(dp(12)))

        val scroll = ScrollView(this).apply { addView(column) }
        Ui.keepClearOfBars(scroll, dp(16))
        setContentView(scroll)
    }

    /** A card: title, text, and a link opened in the browser. */
    private fun credit(column: LinearLayout, title: String, text: String, link: Pair<String, String>) {
        val card = Ui.card(this)
        card.addView(Ui.text(this, title, 17f, bold = true))
        card.addView(Ui.text(this, text, 14f, R.color.muted).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.15f) })
        card.addView(Ui.text(this, link.first + "  ↗", 14f, R.color.accent, bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.second))) }
        })
        column.addView(card, Ui.lp(dp(12)))
    }

    private fun showText(title: String, asset: String) {
        val text = assets.open(asset).bufferedReader().use { it.readText() }
        val view = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Ui.color(this@AboutActivity, R.color.on_surface))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null).show()
    }
}
