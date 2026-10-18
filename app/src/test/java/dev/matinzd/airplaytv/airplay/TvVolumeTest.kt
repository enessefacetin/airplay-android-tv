package dev.matinzd.airplaytv.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class TvVolumeTest {
    private val steps = mutableListOf<Boolean>()
    private val volume = TvVolume { steps += it }

    private fun ups() = steps.count { it }
    private fun downs() = steps.count { !it }

    @Test
    fun firstValueOnlySetsStartingPoint() {
        assertEquals(1f, volume.set(-15f))
        assertEquals(emptyList<Boolean>(), steps)
    }

    @Test
    fun sliderMovesBecomeSteps() {
        volume.set(-30f)
        volume.set(0f)
        assertEquals(16, ups())
        volume.set(-15f)
        assertEquals(8, downs())
    }

    @Test
    fun oneIphoneButtonPressIsOneStep() {
        volume.set(-15f)
        volume.set(-15f + 30f / 16)
        assertEquals(listOf(true), steps)
    }

    @Test
    fun smallChangesBelowOneStepSendNothing() {
        volume.set(-15f)
        volume.set(-14.5f)
        assertEquals(emptyList<Boolean>(), steps)
    }

    @Test
    fun muteSilencesAppAudioWithoutSteps() {
        volume.set(-15f)
        assertEquals(0f, volume.set(-144f))
        assertEquals(emptyList<Boolean>(), steps)
        // Unmuting at the same level sends nothing; the receiver never moved.
        assertEquals(1f, volume.set(-15f))
        assertEquals(emptyList<Boolean>(), steps)
    }

    @Test
    fun resetStartsANewSession() {
        volume.set(-30f)
        volume.reset()
        volume.set(0f)
        assertEquals(emptyList<Boolean>(), steps)
    }

    @Test
    fun valuesOutsideRangeAreClamped() {
        volume.set(0f)
        volume.set(6f)
        assertEquals(emptyList<Boolean>(), steps)
    }
}
