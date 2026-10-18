package dev.matinzd.airplaytv.airplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays AirPlay video (HLS, e.g. YouTube's AirPlay button). UxPlay serves the
 * rewritten playlists on http://localhost:<port>/; this plays that URL and
 * follows the iPhone's play/pause/seek/stop commands.
 *
 * ExoPlayer lives on the main thread. The iPhone polls playback info from a
 * network thread, so a snapshot is refreshed a few times a second.
 */
class HlsPlayer(
    private val context: Context,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val onEnded: () -> Unit,
    /** Playback failed; [protected] = DRM (FairPlay) video, which Android can't decrypt. */
    private val onError: (protected: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var volume = 1f

    private class Snapshot(
        val active: Boolean = false,
        val durationSec: Double = 0.0,
        val positionSec: Double = 0.0,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val ended: Boolean = false,
    )

    @Volatile
    private var snapshot = Snapshot()

    private val ticker = object : Runnable {
        override fun run() {
            refreshSnapshot()
            if (player != null) main.postDelayed(this, 250)
        }
    }

    fun play(url: String, startSec: Float) = onMain {
        releasePlayer()
        Log.i(TAG, "playing $url from ${startSec}s")
        player = ExoPlayer.Builder(context).build().apply {
            setVideoSurface(surface)
            volume = this@HlsPlayer.volume
            addListener(listener)
            setMediaItem(MediaItem.fromUri(url), (startSec * 1000).toLong())
            prepare()
            playWhenReady = true
        }
        snapshot = Snapshot(active = true)
        main.post(ticker)
    }

    fun seek(positionSec: Float) = onMain { player?.seekTo((positionSec * 1000).toLong()) }

    /** AirPlay rate: 1 = play, 0 = pause. */
    fun setRate(rate: Float) = onMain { player?.playWhenReady = rate > 0f }

    /** Pauses and returns the current position (the iPhone uses it to resume later). */
    fun pauseAndGetPosition(): Float {
        val position = snapshot.positionSec.toFloat()
        main.post { player?.playWhenReady = false }
        return position
    }

    fun stop() = onMain { releasePlayer() }

    /** Stops and waits (up to 1 s) until the player has let go of the Surface. */
    fun stopAndWait() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releasePlayer()
            return
        }
        val done = CountDownLatch(1)
        main.post {
            releasePlayer()
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
    }

    fun setSurface(newSurface: Surface?) = onMain {
        surface = newSurface
        player?.setVideoSurface(newSurface)
    }

    fun setVolume(gain: Float) = onMain {
        volume = gain
        player?.volume = gain
    }

    /** [duration, position, seekStart, seekDuration, rate, stillPlaying, bufferEmpty, bufferFull] */
    fun playbackInfo(): DoubleArray {
        val s = snapshot
        // The iPhone polls right after /play, before the playlists are fetched and
        // playback starts. Only report "finished" once a video has actually ended,
        // otherwise the iPhone tears the session down.
        val stillPlaying = !s.ended
        return doubleArrayOf(
            s.durationSec, s.positionSec, 0.0, s.durationSec,
            if (s.playing) 1.0 else 0.0,
            if (stillPlaying) 1.0 else 0.0,
            if (s.buffering) 1.0 else 0.0,
            if (s.buffering) 0.0 else 1.0,
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            refreshSnapshot()
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width > 0 && size.height > 0) {
                onVideoSize((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "playback error", error)
            // Apple TV+ plays its clear intro, then fails at the first FairPlay segment.
            // Let go of the frozen frame and report "finished", so the iPhone stops too.
            releasePlayer()
            snapshot = Snapshot(ended = true)
            onError(error.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED)
        }
    }

    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private fun refreshSnapshot() {
        val p = player
        snapshot = if (p == null) {
            Snapshot()
        } else {
            Snapshot(
                active = true,
                durationSec = if (p.duration == C.TIME_UNSET) 0.0 else p.duration / 1000.0,
                positionSec = p.currentPosition / 1000.0,
                playing = p.isPlaying,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
            )
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(ticker)
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        snapshot = Snapshot()
    }

    private companion object {
        const val TAG = "HlsPlayer"
    }
}
