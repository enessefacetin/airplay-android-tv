package dev.matinzd.airplaytv

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.matinzd.airplaytv.TvUi.dp
import java.net.Inet4Address

/** Status screen. Opening it (or booting the device) starts the receiver. */
class MainActivity : Activity() {
    private lateinit var statusDot: FrameLayout
    private lateinit var status: TextView
    private lateinit var name: TextView
    private lateinit var steps: TextView
    private lateinit var footer: TextView
    private lateinit var warning: TextView
    private val observer: (AirPlaySession.State) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)

        statusDot = FrameLayout(this)
        status = TvUi.text(this, 18f, TvUi.TEXT_DIM)
        name = TvUi.text(this, 40f, bold = true).apply { maxLines = 1 }
        steps = TvUi.text(this, 20f, TvUi.TEXT_DIM).apply { setLineSpacing(dp(this@MainActivity, 8).toFloat(), 1f) }
        footer = TvUi.text(this, 15f, TvUi.TEXT_DIM)
        warning = TvUi.text(this, 17f, TvUi.WARNING)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(this@MainActivity, 14), dp(this@MainActivity, 14)))
            addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(this@MainActivity, 12) })
        }
        val settings = TvUi.card(TvUi.text(this, 22f).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false // let the focused button grow past the column edge
            gravity = Gravity.CENTER_VERTICAL
            addView(statusRow)
            addView(name, gap(12))
            addView(steps, gap(28))
            addView(warning, gap(20))
            addView(settings, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(this@MainActivity, 32) })
            addView(footer, gap(20))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_airplay)
            alpha = 0.9f
            val pad = dp(this@MainActivity, 56)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TvUi.SURFACE)
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(TvUi.BG)
            // Keep content inside the TV's overscan-safe area.
            setPadding(dp(this@MainActivity, 80), dp(this@MainActivity, 48), dp(this@MainActivity, 80), dp(this@MainActivity, 48))
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon, LinearLayout.LayoutParams(dp(this@MainActivity, 280), dp(this@MainActivity, 280)).apply {
                marginStart = dp(this@MainActivity, 48)
            })
        })
        settings.requestFocus()
    }

    private fun gap(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(this@MainActivity, top) }

    override fun onStart() {
        super.onStart()
        AirPlaySession.observe(observer)
    }

    override fun onStop() {
        AirPlaySession.removeObserver(observer)
        super.onStop()
    }

    private fun render(state: AirPlaySession.State) {
        val (label, color) = when {
            state.error != null -> "Not working: ${state.error}" to 0xFFE57373.toInt()
            !state.serverRunning -> "Starting…" to TvUi.TEXT_DIM
            state.active -> "Playing from ${state.clientName ?: "a device"}" to TvUi.ACCENT
            else -> "Ready for AirPlay" to 0xFF66BB6A.toInt()
        }
        status.text = label
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        name.text = state.serverName.ifEmpty { "AirPlay Receiver" }
        steps.text = "1   Open Control Center on your iPhone or Mac\n" +
            "2   Select Screen Mirroring or AirPlay\n" +
            "3   Choose “${state.serverName.ifEmpty { "this TV" }}”"
        footer.text = if (state.serverRunning) "${ipAddress() ?: "No network"} · port ${state.port}" else ""
        val canWake = Settings.canDrawOverlays(this)
        warning.text = if (canWake) "" else "Waking the TV from standby is not set up. Run scripts/setup-device.sh once."
        warning.visibility = if (canWake) TextView.GONE else TextView.VISIBLE
    }

    private fun ipAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
    }
}
