package dev.matinzd.airplaytv.airplay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lets the sender's volume slider control the TV or AV receiver instead of the app's
 * own audio level (ADR 0017).
 *
 * HDMI-CEC can only step the volume up or down, so slider moves become steps: 16
 * steps over AirPlay's -30..0 dB, like the iPhone's volume buttons. The first value
 * in a session is only the starting point. Mute silences the app's audio; it sends no
 * CEC mute, because that key toggles and could get out of step.
 */
class TvVolume(private val step: (up: Boolean) -> Unit) {
    private var level: Int? = null

    /** Applies a new AirPlay volume; returns the gain for the app's own audio. */
    @Synchronized
    fun set(db: Float): Float {
        if (db < -30f) return 0f // -144 = mute
        val newLevel = ((db.coerceIn(-30f, 0f) + 30f) / 30f * STEPS).roundToInt()
        val old = level
        level = newLevel
        if (old != null) repeat(abs(newLevel - old)) { step(newLevel > old) }
        return 1f
    }

    /** A new session starts from the receiver's current volume. */
    @Synchronized
    fun reset() {
        level = null
    }

    private companion object {
        const val STEPS = 16
    }
}
