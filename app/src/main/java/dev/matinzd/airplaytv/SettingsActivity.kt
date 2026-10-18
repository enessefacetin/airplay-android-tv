package dev.matinzd.airplaytv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Receiver settings, navigable with the TV remote. Changes restart the receiver. */
class SettingsActivity : Activity() {
    private lateinit var settings: ReceiverSettings
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ReceiverSettings(this)
        val pad = (48 * resources.displayMetrics.density).toInt()
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(16, 18, 22))
            addView(list)
        })
        build()
    }

    private fun build() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "AirPlay settings"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        })

        val currentName = settings.name ?: AirPlaySession.state.serverName.ifEmpty { "this TV's name" }
        addButton("AirPlay name: $currentName") { editName() }
        addSwitch("Require a PIN for new devices",
            "A new iPhone or Mac must enter a code shown on the TV once.", settings.requirePin) {
            settings.requirePin = it
        }
        addSwitch("4K mirroring (H.265)",
            "Sharper mirroring from devices that support it. Uses more Wi-Fi.", settings.allow4k) {
            settings.allow4k = it
        }
        addSwitch("Low-latency mirroring",
            "Shows the screen as fast as possible; sound may lag behind the picture.", settings.lowLatency) {
            settings.lowLatency = it
        }
        val devices = settings.rememberedDevices
        addButton(
            if (devices.isEmpty()) "No remembered devices"
            else "Forget remembered devices (${devices.values.sorted().joinToString()})",
        ) {
            settings.forgetAllDevices()
            applyChanges()
        }.isEnabled = devices.isNotEmpty()
    }

    private fun addButton(label: String, onClick: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 20f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }
        list.addView(button, rowParams())
        return button
    }

    private fun addSwitch(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = Switch(this).apply {
            text = "$label\n$hint"
            textSize = 20f
            setTextColor(Color.WHITE)
            isChecked = checked
            setPadding(24, 24, 24, 24)
            isFocusable = true
            setOnCheckedChangeListener { _, value ->
                onChange(value)
                applyChanges()
            }
        }
        list.addView(row, rowParams())
    }

    private fun rowParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * resources.displayMetrics.density).toInt() }

    private fun editName() {
        val input = EditText(this).apply {
            setText(settings.name ?: AirPlaySession.state.serverName)
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("AirPlay name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                settings.name = input.text.toString()
                applyChanges()
            }
            .setNeutralButton("Use TV name") { _, _ ->
                settings.name = null
                applyChanges()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyChanges() {
        AirPlayService.restart(this)
        list.postDelayed({ build() }, 300)
    }
}
