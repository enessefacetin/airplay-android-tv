package dev.matinzd.airplaytv

import android.app.Activity
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.matinzd.airplaytv.TvUi.dp

/**
 * Full-screen AirPlay output: the mirrored screen or AirPlay video, "now playing"
 * for AirPlay music, or the pairing PIN. Closes itself when the session ends.
 */
class PlaybackActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var videoFrame: AspectRatioFrameLayout

    private lateinit var connectingPanel: View
    private lateinit var connectingTitle: TextView

    private lateinit var pinPanel: View
    private lateinit var pinDigits: List<TextView>
    private lateinit var pinHint: TextView

    private lateinit var musicPanel: View
    private lateinit var backdrop: ImageView
    private lateinit var cover: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var trackAlbum: TextView
    private lateinit var trackSource: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }
    private var sawSession = false

    private val observer: (AirPlaySession.State) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Ask the TV for its low-latency game mode (HDMI ALLM): skips picture processing.
        if (Build.VERSION.SDK_INT >= 30) window.setPreferMinimalPostProcessing(true)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val surfaceView = SurfaceView(this).apply { holder.addCallback(this@PlaybackActivity) }
        videoFrame = AspectRatioFrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoFrame, AspectRatioFrameLayout.centered())
            addView(buildMusicPanel(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(buildConnectingPanel(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(buildPinPanel(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
    }

    private fun buildConnectingPanel(): View {
        connectingTitle = TvUi.text(this, 30f, bold = true).apply { gravity = Gravity.CENTER }
        connectingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(TvUi.BG)
            addView(ImageView(this@PlaybackActivity).apply {
                setImageResource(R.drawable.ic_airplay)
                alpha = 0.85f
            }, LinearLayout.LayoutParams(dp(this@PlaybackActivity, 120), dp(this@PlaybackActivity, 120)))
            addView(connectingTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(this@PlaybackActivity, 32)
            })
            addView(ProgressBar(this@PlaybackActivity).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(dp(this@PlaybackActivity, 40), dp(this@PlaybackActivity, 40)).apply {
                    topMargin = dp(this@PlaybackActivity, 28)
                })
        }
        return connectingPanel
    }

    private fun buildPinPanel(): View {
        val digitsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pinDigits = List(4) {
            TvUi.text(this, 88f, bold = true).apply {
                gravity = Gravity.CENTER
                background = TvUi.rounded(this@PlaybackActivity, TvUi.SURFACE, radiusDp = 20)
                digitsRow.addView(this, LinearLayout.LayoutParams(dp(this@PlaybackActivity, 140), dp(this@PlaybackActivity, 180)).apply {
                    marginStart = dp(this@PlaybackActivity, 12)
                    marginEnd = dp(this@PlaybackActivity, 12)
                })
            }
        }
        pinHint = TvUi.text(this, 22f, TvUi.TEXT_DIM).apply { gravity = Gravity.CENTER }
        pinPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(TvUi.BG)
            addView(TvUi.text(this@PlaybackActivity, 34f, bold = true).apply {
                text = "Enter this code on your device"
                gravity = Gravity.CENTER
            })
            addView(digitsRow, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(this@PlaybackActivity, 48)
            })
            addView(pinHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(this@PlaybackActivity, 48)
            })
        }
        return pinPanel
    }

    private fun buildMusicPanel(): View {
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.CLAMP))
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = TvUi.rounded(this@PlaybackActivity, TvUi.SURFACE, radiusDp = 20)
        }
        trackSource = TvUi.text(this, 18f, TvUi.ACCENT)
        trackTitle = TvUi.text(this, 44f, bold = true).apply { maxLines = 2 }
        trackArtist = TvUi.text(this, 28f).apply { maxLines = 1 }
        trackAlbum = TvUi.text(this, 22f, TvUi.TEXT_DIM).apply { maxLines = 1 }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(trackSource)
            addView(trackTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(this@PlaybackActivity, 16) })
            addView(trackArtist, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(this@PlaybackActivity, 12) })
            addView(trackAlbum, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(this@PlaybackActivity, 6) })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(this@PlaybackActivity, 96), dp(this@PlaybackActivity, 48), dp(this@PlaybackActivity, 96), dp(this@PlaybackActivity, 48))
            addView(cover, LinearLayout.LayoutParams(dp(this@PlaybackActivity, 340), dp(this@PlaybackActivity, 340)))
            addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(this@PlaybackActivity, 56) })
        }
        musicPanel = FrameLayout(this).apply {
            setBackgroundColor(TvUi.BG)
            addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(View(this@PlaybackActivity).apply { setBackgroundColor(0xB30E1015.toInt()) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return musicPanel
    }

    override fun onStart() {
        super.onStart()
        AirPlaySession.observe(observer)
    }

    override fun onStop() {
        AirPlaySession.removeObserver(observer)
        handler.removeCallbacks(finishRunnable)
        super.onStop()
    }

    private fun render(state: AirPlaySession.State) {
        val showVideo = state.showsVideo && state.videoWidth > 0
        val sessionOver = sawSession && !state.active
        videoFrame.visibility = if (state.showsVideo) View.VISIBLE else View.INVISIBLE
        if (state.videoWidth > 0 && state.videoHeight > 0) {
            videoFrame.aspectRatio = state.videoWidth.toFloat() / state.videoHeight
        }

        val pin = state.pin
        val showPin = pin != null
        val showMusic = !showPin && state.audioOnly && !state.showsVideo
        // After the session ends, stay black while closing instead of "Connecting…".
        val showConnecting = !showPin && !showMusic && !showVideo && !sessionOver
        pinPanel.visibility = if (showPin) View.VISIBLE else View.GONE
        musicPanel.visibility = if (showMusic) View.VISIBLE else View.GONE
        connectingPanel.visibility = if (showConnecting) View.VISIBLE else View.GONE

        val client = state.clientName ?: "your device"
        if (pin != null) {
            pinDigits.forEachIndexed { i, digit -> digit.text = pin.getOrNull(i)?.toString() ?: "" }
            pinHint.text = "You only need to do this once for each device"
        }
        if (showMusic) {
            if (state.coverArt != null) {
                cover.setImageBitmap(state.coverArt)
                backdrop.setImageBitmap(state.coverArt)
                cover.setPadding(0, 0, 0, 0)
            } else {
                cover.setImageResource(R.drawable.ic_airplay)
                backdrop.setImageDrawable(null)
                val pad = dp(this, 72)
                cover.setPadding(pad, pad, pad, pad)
            }
            trackSource.text = "AirPlay from $client"
            trackTitle.text = state.title ?: "Playing audio"
            trackArtist.text = state.artist ?: ""
            trackAlbum.text = state.album ?: ""
        }
        connectingTitle.text = "Connecting to $client…"

        // Close when the session is over. The short grace period covers handovers,
        // e.g. mirroring -> YouTube video, without flashing the launcher.
        if (state.active) sawSession = true
        handler.removeCallbacks(finishRunnable)
        if (!state.active) handler.postDelayed(finishRunnable, if (sawSession) 1_500L else 15_000L)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AirPlaySession.surfaceSink?.invoke(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlaySession.surfaceSink?.invoke(null)
    }
}
