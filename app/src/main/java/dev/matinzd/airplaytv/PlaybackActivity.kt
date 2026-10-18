package dev.matinzd.airplaytv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.TextView

/**
 * Full-screen AirPlay output: the mirrored screen, or cover art and track info
 * for audio-only AirPlay. Closes itself when the session ends.
 */
class PlaybackActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }
    private var sawSession = false

    private val observer: (AirPlaySession.State) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val surfaceView = SurfaceView(this).apply { holder.addCallback(this@PlaybackActivity) }
        videoFrame = AspectRatioFrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val density = resources.displayMetrics.density
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_airplay)
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        subtitle = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val size = (280 * density).toInt()
            addView(cover, LinearLayout.LayoutParams(size, size).apply { bottomMargin = (32 * density).toInt() })
            addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(subtitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = (8 * density).toInt()
            })
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoFrame, AspectRatioFrameLayout.centered())
            addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
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
        val showVideo = state.mirroring && state.videoWidth > 0
        videoFrame.visibility = if (state.mirroring) View.VISIBLE else View.INVISIBLE
        if (state.videoWidth > 0 && state.videoHeight > 0) {
            videoFrame.aspectRatio = state.videoWidth.toFloat() / state.videoHeight
        }
        overlay.visibility = if (showVideo) View.GONE else View.VISIBLE

        val client = state.clientName ?: "iPhone"
        when {
            state.audioOnly -> {
                if (state.coverArt != null) cover.setImageBitmap(state.coverArt) else cover.setImageResource(R.drawable.ic_airplay)
                title.text = state.title ?: "AirPlay"
                subtitle.text = listOfNotNull(state.artist, state.album).joinToString(" — ").ifEmpty { "Playing from $client" }
            }
            else -> {
                cover.setImageResource(R.drawable.ic_airplay)
                title.text = "Connecting to $client…"
                subtitle.text = state.serverName
            }
        }

        // Close when the session is over (with a short grace period for reconnects).
        if (state.active) sawSession = true
        handler.removeCallbacks(finishRunnable)
        if (!state.active) handler.postDelayed(finishRunnable, if (sawSession) 1_500L else 15_000L)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AirPlaySession.videoDecoder?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlaySession.videoDecoder?.setSurface(null)
    }
}
