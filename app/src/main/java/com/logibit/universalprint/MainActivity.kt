package com.logibit.universalprint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(TextView(this).apply {
            text = "Logibit Universal Print"
            textSize = 25f
        })
        root.addView(TextView(this).apply {
            text = "Print PDF documents to compatible Wi-Fi / LAN and USB OTG printers. Open Android print settings to enable this service.\n\nUSB support in this build detects USB Printer Class devices and prints to models that advertise PCL 5, plus Samsung's own SPL2/QPDL language (common on budget ML/SCX/CLX mono laser printers, e.g. the ML-1670, which don't speak PCL at all). Other printers using GDI-only, ESC/POS or other proprietary languages still need a matching driver. Bluetooth printing is not implemented yet."
            textSize = 16f
            setPadding(0, pad, 0, pad)
        })
        root.addView(Button(this).apply {
            text = "Open Print Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "USB tip: connect a powered printer using a data-capable OTG adapter, unlock the phone, approve the USB permission prompt, then print. Network tip: connect both devices to the same Wi-Fi network."
            textSize = 14f
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)
    }
}
