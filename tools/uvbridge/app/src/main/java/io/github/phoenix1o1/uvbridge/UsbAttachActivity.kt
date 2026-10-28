package io.github.phoenix1o1.uvbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * What Android opens when a USB serial adapter is plugged in: the home
 * screen, unless UV Studio is open, which keeps the radio's connection
 * (a radio restarting during a flash plugs in again). No screen of its own.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MainActivity.open) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
