package dev.matinzd.airplaytv.airplay

import android.util.Log

/**
 * Lip-sync for screen mirroring and AirPlay music.
 *
 * Every audio and video packet carries the sender's timestamp, converted to
 * System.nanoTime() by the native layer. Each stream reports how much latency
 * it needs (how late it would otherwise be). Both streams are then presented at
 * `timestamp + latency`, with the latency set by the slower stream plus a margin.
 *
 * Raised at once when a stream needs more, lowered slowly when it needs less.
 * Never negative: for AirPlay music (packets arrive ~2 s early) it is 0, so audio
 * plays exactly when the sender intends.
 */
class AvSync {
    @Volatile
    var latencyNs = 0L
        private set

    private val video = Window()
    private val audio = Window()
    private var lastDecreaseNs = 0L
    private var lastLogNs = 0L

    /** [requiredNs]: how far behind its timestamp a decoded frame becomes ready. */
    fun reportVideo(requiredNs: Long) = report(video, requiredNs)

    /** [requiredNs]: arrival lateness plus the audio output pipeline's latency. */
    fun reportAudio(requiredNs: Long) = report(audio, requiredNs)

    @Synchronized
    fun reset() {
        latencyNs = 0
        video.reset()
        audio.reset()
    }

    @Synchronized
    private fun report(window: Window, requiredNs: Long) {
        // Frames replayed from the keyframe cache, or a clock not yet in sync.
        if (requiredNs > MAX_LATENCY_NS || requiredNs < -MAX_EARLY_NS) return
        val now = System.nanoTime()
        window.add(requiredNs, now)

        val needed = maxOf(video.max(now), audio.max(now))
        if (needed == Window.NONE) return
        val target = (needed + MARGIN_NS).coerceIn(0, MAX_LATENCY_NS)
        val current = latencyNs
        if (target > current) {
            latencyNs = target
        } else if (current - target > HYSTERESIS_NS && now - lastDecreaseNs > 1_000_000_000L) {
            // Shed a quarter of the excess per second (at least 10 ms).
            latencyNs = current - maxOf((current - target) / 4, minOf(current - target, STEP_DOWN_NS))
            lastDecreaseNs = now
        }
        if (now - lastLogNs > 5_000_000_000L) {
            lastLogNs = now
            Log.i(TAG, "latency %d ms (video needs %s, audio needs %s)".format(
                latencyNs / MS, video.describe(now), audio.describe(now)))
        }
    }

    /** Max over the last 1-2 s; inactive after 2 s without samples. */
    private class Window {
        private var current = NONE
        private var previous = NONE
        private var startNs = 0L
        private var lastSampleNs = 0L

        fun add(value: Long, now: Long) {
            if (now - startNs > 1_000_000_000L) {
                previous = current
                current = NONE
                startNs = now
            }
            if (value > current) current = value
            lastSampleNs = now
        }

        fun max(now: Long) = if (now - lastSampleNs > 2_000_000_000L) NONE else maxOf(current, previous)

        fun describe(now: Long) = max(now).let { if (it == NONE) "-" else "${it / MS} ms" }

        fun reset() {
            current = NONE; previous = NONE; startNs = 0; lastSampleNs = 0
        }

        companion object {
            const val NONE = Long.MIN_VALUE / 4
        }
    }

    companion object {
        private const val TAG = "AvSync"
        const val MS = 1_000_000L
        private const val MARGIN_NS = 20 * MS        // covers vsync and scheduling jitter
        private const val HYSTERESIS_NS = 30 * MS
        private const val STEP_DOWN_NS = 10 * MS
        const val MAX_LATENCY_NS = 1_000 * MS
        private const val MAX_EARLY_NS = 5_000 * MS  // music arrives ~2 s early
    }
}
