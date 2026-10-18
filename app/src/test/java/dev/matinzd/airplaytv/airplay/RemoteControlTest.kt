package dev.matinzd.airplaytv.airplay

import dev.matinzd.airplaytv.airplay.RemoteControl.Companion.isSenderService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlTest {
    @Test
    fun matchesSameId() {
        assertTrue(isSenderService("iTunes_Ctrl_1BCBD66F0383F6E3", "1BCBD66F0383F6E3"))
    }

    @Test
    fun matchesIdWithoutLeadingZero() {
        // Seen on iOS: the header drops the leading zero, the service name keeps it.
        assertTrue(isSenderService("iTunes_Ctrl_0ED089007A70EE1B", "ED089007A70EE1B"))
    }

    @Test
    fun ignoresCase() {
        assertTrue(isSenderService("itunes_ctrl_0ed089007a70ee1b", "ED089007A70EE1B"))
    }

    @Test
    fun rejectsOtherIds() {
        assertFalse(isSenderService("iTunes_Ctrl_1CFCAB48E73FB3CF", "ED089007A70EE1B"))
        assertFalse(isSenderService("iTunes_Ctrl_ED089007A70EE1B0", "ED089007A70EE1B"))
    }

    @Test
    fun rejectsOtherServices() {
        assertFalse(isSenderService("ED089007A70EE1B", "ED089007A70EE1B"))
    }
}
