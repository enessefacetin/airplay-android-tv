package dev.matinzd.airplaytv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address

/** Status screen. Opening it (or booting the device) starts the receiver. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var details: TextView
    private val observer: (AirPlaySession.State) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)

        val density = resources.displayMetrics.density
        val padding = (48 * density).toInt()
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }
        details = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 20f
            setLineSpacing(0f, 1.3f)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(16, 18, 22))
            setPadding(padding, padding, padding, padding)
            addView(status)
            addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (24 * density).toInt() })
            addView(Button(this@MainActivity).apply {
                text = "Settings"
                isAllCaps = false
                textSize = 20f
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (32 * density).toInt() })
        })
    }

    override fun onStart() {
        super.onStart()
        AirPlaySession.observe(observer)
    }

    override fun onStop() {
        AirPlaySession.removeObserver(observer)
        super.onStop()
    }

    private fun render(state: AirPlaySession.State) {
        status.text = when {
            state.error != null -> "AirPlay receiver failed"
            !state.serverRunning -> "Starting AirPlay receiver…"
            state.active -> "AirPlay from ${state.clientName ?: "iPhone"}"
            else -> "Ready for AirPlay"
        }
        val lines = mutableListOf<String>()
        state.error?.let { lines += it }
        if (state.serverRunning) {
            lines += "On your iPhone: Control Center → Screen Mirroring → “${state.serverName}”"
            lines += "Address: ${ipAddress() ?: "no network"}, port ${state.port}"
        }
        if (!Settings.canDrawOverlays(this)) {
            lines += ""
            lines += "⚠ Waking the TV from standby isn't set up yet. Run scripts/setup-device.sh (or the setupDevice Gradle task)."
        }
        details.text = lines.joinToString("\n")
    }

    private fun ipAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val props: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }
}
