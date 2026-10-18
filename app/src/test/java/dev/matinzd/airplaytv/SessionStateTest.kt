package dev.matinzd.airplaytv

import dev.matinzd.airplaytv.AirPlaySession.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test
    fun idleReceiverIsNotActive() {
        assertFalse(State(serverRunning = true).active)
    }

    @Test
    fun pinKeepsSessionActiveWithoutConnection() {
        // The iPhone drops the connection that asked for the PIN while the user types it.
        assertTrue(State(pin = "1234").active)
    }

    @Test
    fun connectionAloneIsNotActive() {
        assertFalse(State(connections = 1).active)
    }

    @Test
    fun connectionWithStreamOrClientIsActive() {
        assertTrue(State(connections = 1, mirroring = true).active)
        assertTrue(State(connections = 1, hlsVideo = true).active)
        assertTrue(State(connections = 1, audioOnly = true).active)
        assertTrue(State(connections = 1, clientName = "iPhone").active)
    }

    @Test
    fun streamWithoutConnectionIsNotActive() {
        assertFalse(State(mirroring = true, clientName = "iPhone").active)
    }

    @Test
    fun endSessionKeepsPinAndServer() {
        val state = State(
            serverRunning = true, serverName = "TV", port = 7000, connections = 1, pin = "1234",
            clientName = "iPhone", mirroring = true, title = "Song", playing = true, positionSec = 12.0,
        ).endSession()
        assertEquals("1234", state.pin)
        assertTrue(state.active)
        assertEquals(State(serverRunning = true, serverName = "TV", port = 7000, connections = 1, pin = "1234"), state)
    }

    @Test
    fun videoErrorStaysUpAfterSessionEnds() {
        // The iPhone disconnects once told the video can't play; the message stays.
        val state = State(connections = 1, hlsVideo = true, videoError = State.VideoError.PROTECTED).endSession()
        assertEquals(State.VideoError.PROTECTED, state.videoError)
        assertTrue(state.copy(connections = 0).active)
    }

    @Test
    fun endSessionClearsTrack() {
        val state = State(
            audioOnly = true, title = "Song", artist = "Artist", album = "Album", playing = true,
            positionSec = 30.0, positionAtNs = 1, durationSec = 200.0,
        ).endSession()
        assertNull(state.title)
        assertFalse(state.playing)
        assertEquals(0.0, state.positionNow(), 0.0)
        assertEquals(0.0, state.durationSec, 0.0)
    }

    @Test
    fun pausedPositionDoesNotMove() {
        val state = State(playing = false, positionSec = 30.0, positionAtNs = System.nanoTime() - 5_000_000_000L)
        assertEquals(30.0, state.positionNow(), 0.0)
    }

    @Test
    fun playingPositionMovesOn() {
        val state = State(playing = true, positionSec = 30.0, positionAtNs = System.nanoTime() - 5_000_000_000L)
        assertEquals(35.0, state.positionNow(), 0.5)
    }

    @Test
    fun playingPositionStopsAtDuration() {
        val state = State(
            playing = true, positionSec = 198.0, positionAtNs = System.nanoTime() - 5_000_000_000L, durationSec = 200.0,
        )
        assertEquals(200.0, state.positionNow(), 0.0)
    }

    @Test
    fun positionWithoutTimestampDoesNotMove() {
        assertEquals(30.0, State(playing = true, positionSec = 30.0).positionNow(), 0.0)
    }

    @Test
    fun showsVideoForMirroringAndHls() {
        assertTrue(State(mirroring = true).showsVideo)
        assertTrue(State(hlsVideo = true).showsVideo)
        assertFalse(State(audioOnly = true).showsVideo)
    }
}
