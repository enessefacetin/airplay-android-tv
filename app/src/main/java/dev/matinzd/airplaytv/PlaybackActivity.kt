package dev.matinzd.airplaytv

import android.app.Activity
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
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
import dev.matinzd.airplaytv.airplay.RemoteControl
import kotlin.random.Random

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
    private lateinit var statusIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var pausedOverlay: View
    private lateinit var progressBar: View
    private lateinit var progressFill: View
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var controls: View
    private lateinit var playPauseButton: ImageView
    private lateinit var previousButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var musicContent: View
    private var shownTitle: String? = null

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
        val ctx = this
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(120f, 120f, Shader.TileMode.CLAMP))
        }
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = TvUi.rounded(ctx, TvUi.SURFACE, radiusDp = 20)
        }
        // Paused: the cover darkens and shows a pause sign.
        pausedOverlay = FrameLayout(this).apply {
            clipToOutline = true
            background = TvUi.rounded(ctx, 0x99000000.toInt(), radiusDp = 20)
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_pause) },
                FrameLayout.LayoutParams(dp(ctx, 112), dp(ctx, 112), Gravity.CENTER))
        }
        val coverFrame = FrameLayout(this).apply {
            addView(cover, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(pausedOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        statusIcon = ImageView(this)
        statusText = TvUi.text(this, 20f, bold = true)
        trackSource = TvUi.text(this, 20f, TvUi.TEXT_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusIcon, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))
            addView(statusText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(ctx, 8) })
            addView(trackSource, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(ctx, 12) })
        }
        // One line each, scrolling when too long: the layout keeps the same height for
        // every track, so nothing jumps when skipping.
        trackTitle = marquee(TvUi.text(this, 44f, bold = true))
        trackArtist = marquee(TvUi.text(this, 28f))
        trackAlbum = TvUi.text(this, 22f, TvUi.TEXT_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

        progressFill = View(this).apply { background = TvUi.rounded(ctx, TvUi.TEXT, radiusDp = 3) }
        progressBar = FrameLayout(this).apply {
            background = TvUi.rounded(ctx, 0x40FFFFFF, radiusDp = 3)
            addView(progressFill, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        elapsedText = TvUi.text(this, 18f, TvUi.TEXT_DIM)
        durationText = TvUi.text(this, 18f, TvUi.TEXT_DIM).apply { gravity = Gravity.END }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(elapsedText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(durationText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusRow)
            addView(trackTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 16) })
            addView(trackArtist, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 12) })
            addView(trackAlbum, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 6) })
            addView(progressBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(ctx, 6)).apply { topMargin = dp(ctx, 40) })
            addView(times, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 10) })
        }
        val content = LinearLayout(this).apply {
            musicContent = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 96), dp(ctx, 48), dp(ctx, 96), dp(ctx, 48))
            addView(coverFrame, LinearLayout.LayoutParams(dp(ctx, 340), dp(ctx, 340)))
            addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 56) })
        }

        // On-screen controls: shown for a few seconds after a remote button press,
        // and while paused. The pressed button lights up.
        fun round(icon: Int, sizeDp: Int) = ImageView(ctx).apply {
            setImageResource(icon)
            val pad = dp(ctx, sizeDp / 4)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }
        previousButton = round(R.drawable.ic_previous, 56)
        playPauseButton = round(R.drawable.ic_pause, 72)
        nextButton = round(R.drawable.ic_next, 56)
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            // Room for a pressed button to grow without being clipped by the layout around it.
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            addView(previousButton, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56)))
            addView(playPauseButton, LinearLayout.LayoutParams(dp(ctx, 72), dp(ctx, 72)).apply {
                marginStart = dp(ctx, 20); marginEnd = dp(ctx, 20)
            })
            addView(nextButton, LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56)))
            addView(TvUi.text(ctx, 16f, TvUi.TEXT_DIM).apply { text = "Back to pause and close" },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(ctx, 32) })
        }
        info.addView(controls, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(ctx, 18)
            marginStart = -dp(ctx, 10) // keep the buttons aligned with the text despite the padding
        })

        musicPanel = FrameLayout(this).apply {
            setBackgroundColor(TvUi.BG)
            addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(View(ctx).apply { setBackgroundColor(0xB30E1015.toInt()) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        return musicPanel
    }

    /** Playing/paused, position and the on-screen controls; also refreshed every half second. */
    private fun renderPlayback(state: AirPlaySession.State) {
        val playing = state.playing
        statusIcon.setIconIfChanged(if (playing) R.drawable.ic_play else R.drawable.ic_pause)
        statusText.setIfChanged(if (playing) "Playing" else "Paused")
        statusText.setTextColor(if (playing) TvUi.ACCENT else TvUi.TEXT_DIM)
        statusIcon.setColorFilter(if (playing) TvUi.ACCENT else TvUi.TEXT_DIM)
        pausedOverlay.visibility = if (playing) View.GONE else View.VISIBLE
        playPauseButton.setIconIfChanged(if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        val duration = state.durationSec
        val known = duration > 1
        progressBar.visibility = if (known) View.VISIBLE else View.INVISIBLE
        elapsedText.visibility = progressBar.visibility
        durationText.visibility = progressBar.visibility
        if (known) {
            val position = state.positionNow().coerceIn(0.0, duration)
            // Scale instead of resizing: no layout pass (which would restart the marquee).
            progressFill.pivotX = 0f
            progressFill.scaleX = (position / duration).toFloat()
            elapsedText.setIfChanged(formatTime(position))
            durationText.setIfChanged(formatTime(duration))
        }
        // Controls stay visible while paused, so it's clear how to resume; they fade
        // again a few seconds after playback resumes.
        if (!playing) showControls(hideAfter = false)
        else if (!wasPlaying) showControls()
        wasPlaying = playing
    }

    private fun formatTime(sec: Double): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private var wasPlaying = true

    /** Setting an image also lays the screen out again; skip it when nothing changed. */
    private fun ImageView.setIconIfChanged(res: Int) {
        if (tag != res) {
            tag = res
            setImageResource(res)
        }
    }

    private fun TextView.setIfChanged(value: String) {
        if (text.toString() != value) text = value
    }

    private fun marquee(view: TextView) = view.apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1 // forever
        isSelected = true // marquee only runs on a selected view
        setHorizontalFadingEdgeEnabled(true)
    }

    private val hideControls = Runnable {
        if (AirPlaySession.state.playing) controls.animate().alpha(0f).setDuration(400).start()
    }

    private fun showControls(hideAfter: Boolean = true) {
        handler.removeCallbacks(hideControls)
        controls.animate().alpha(1f).setDuration(150).start()
        if (hideAfter) handler.postDelayed(hideControls, CONTROLS_VISIBLE_MS)
    }

    /** Lights up the button that was pressed. */
    private fun flash(button: View) {
        showControls()
        button.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction {
            button.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (musicPanel.visibility == View.VISIBLE) renderPlayback(AirPlaySession.state)
            handler.postDelayed(this, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        AirPlaySession.observe(observer)
        handler.postDelayed(drift, DRIFT_MS)
        handler.post(progressTicker)
        onActivity()
    }

    override fun onStop() {
        AirPlaySession.removeObserver(observer)
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(drift)
        handler.removeCallbacks(dim)
        handler.removeCallbacks(progressTicker)
        handler.removeCallbacks(hideControls)
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
            if (state.title != shownTitle) {
                shownTitle = state.title
                onActivity()
            }
            trackSource.setIfChanged("·  from $client")
            // Setting text restarts a marquee, so only set it when it changes.
            trackTitle.setIfChanged(state.title ?: "Playing audio")
            trackArtist.setIfChanged(state.artist ?: "")
            trackAlbum.setIfChanged(state.album ?: "")
            renderPlayback(state)
        }
        connectingTitle.text = "Connecting to $client…"

        // Close when the session is over. The short grace period covers handovers,
        // e.g. mirroring -> YouTube video, without flashing the launcher.
        if (state.active) sawSession = true
        handler.removeCallbacks(finishRunnable)
        if (!state.active) handler.postDelayed(finishRunnable, if (sawSession) 1_500L else 15_000L)
    }

    // ---- TV remote ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        onActivity()
        val music = musicPanel.visibility == View.VISIBLE
        val command = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> RemoteControl.Command.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteControl.Command.PLAY
            KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteControl.Command.PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT -> RemoteControl.Command.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> RemoteControl.Command.PREVIOUS
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (music) RemoteControl.Command.PLAY_PAUSE else null
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (music) RemoteControl.Command.NEXT else null
            KeyEvent.KEYCODE_DPAD_LEFT -> if (music) RemoteControl.Command.PREVIOUS else null
            else -> null
        }
        if (command != null) {
            AirPlaySession.remoteCommand?.invoke(command)
            if (music) flash(when (command) {
                RemoteControl.Command.NEXT -> nextButton
                RemoteControl.Command.PREVIOUS -> previousButton
                else -> playPauseButton
            })
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Back, as on an Apple TV: music is paused and stays connected (the iPhone doesn't
     * notice a dropped audio connection, so disconnecting left it showing "connected");
     * mirroring and video are disconnected. Home leaves everything running.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val state = AirPlaySession.state
        if (state.audioOnly && !state.showsVideo) {
            AirPlaySession.remoteCommand?.invoke(RemoteControl.Command.PAUSE)
        } else if (state.active) {
            AirPlaySession.stopSession?.invoke()
        }
        finish()
    }

    // ---- Burn-in protection (OLED): the music screen is static for a long time ----

    private val drift = object : Runnable {
        override fun run() {
            val x = Random.nextInt(-dp(this@PlaybackActivity, 48), dp(this@PlaybackActivity, 48))
            val y = Random.nextInt(-dp(this@PlaybackActivity, 32), dp(this@PlaybackActivity, 32))
            musicContent.animate().translationX(x.toFloat()).translationY(y.toFloat()).setDuration(4_000).start()
            handler.postDelayed(this, DRIFT_MS)
        }
    }

    private val dim = Runnable { musicPanel.animate().alpha(DIM_ALPHA).setDuration(3_000).start() }

    /** A key press or a new track: full brightness, dim again after a while. */
    private fun onActivity() {
        handler.removeCallbacks(dim)
        musicPanel.animate().alpha(1f).setDuration(300).start()
        handler.postDelayed(dim, DIM_AFTER_MS)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        AirPlaySession.surfaceSink?.invoke(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AirPlaySession.surfaceSink?.invoke(null)
    }

    private companion object {
        const val DRIFT_MS = 60_000L
        const val DIM_AFTER_MS = 5 * 60_000L
        const val DIM_ALPHA = 0.35f
        const val CONTROLS_VISIBLE_MS = 4_000L
    }
}
