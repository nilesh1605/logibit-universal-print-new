package com.logibit.universalprint

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("last_print_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        root.addView(TextView(this).apply {
            text = "Logibit Universal Print"
            textSize = 25f
        })
        root.addView(TextView(this).apply {
            text = "Choose your default page layout. These settings are applied when Logibit renders a PDF for a supported USB printer."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })

        fun section(label: String): Spinner {
            root.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setPadding(0, pad / 2, 0, 4)
            })
            return Spinner(this).also { root.addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        }
        fun spinner(label: String, values: List<String>, key: String, default: Int): Spinner {
            val view = section(label)
            view.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
            view.setSelection(prefs.getInt(key, default).coerceIn(values.indices))
            view.onItemSelectedListener = SimpleSelectionListener { position ->
                prefs.edit().putInt(key, position).apply()
            }
            return view
        }

        spinner("Page scaling", listOf("Fit to Page", "Actual Size (100%)", "Fill Page", "Custom Scale"), "layout_mode", 0)
        val scaleLabel = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 2) }
        root.addView(scaleLabel)
        val scale = SeekBar(this).apply {
            max = 175
            progress = (prefs.getInt("layout_scale", 100) - 25).coerceIn(0, 175)
        }
        root.addView(scale)
        fun updateScale() {
            val value = scale.progress + 25
            scaleLabel.text = "Custom scale: $value%"
            prefs.edit().putInt("layout_scale", value).apply()
        }
        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateScale() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        updateScale()

        spinner("Paper size", listOf("A4", "A5", "Letter", "Original document size"), "layout_paper", 0)
        spinner("Orientation", listOf("Auto", "Portrait", "Landscape"), "layout_orientation", 0)
        spinner("Pages per sheet", listOf("1 page per sheet", "2 pages per sheet", "4 pages per sheet"), "layout_pages_per_sheet", 0)
        spinner("Margins", listOf("Printer default", "Narrow", "Normal", "Wide"), "layout_margins", 0)

        root.addView(Button(this).apply {
            text = "Open Android Print Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "Connect a USB OTG printer, enable Logibit in Android Print Services, then print from any app. Layout settings are remembered for your next job."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })
        setContentView(scroll)
    }

    private class SimpleSelectionListener(private val changed: (Int) -> Unit) :
        android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = changed(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
}
