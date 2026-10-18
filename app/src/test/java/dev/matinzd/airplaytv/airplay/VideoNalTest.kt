package dev.matinzd.airplaytv.airplay

import dev.matinzd.airplaytv.airplay.VideoNal.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoNalTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun units(data: ByteArray, hevc: Boolean) =
        VideoNal.leadingUnits(data, hevc).map { Triple(it.kind, it.start, it.end) }

    @Test
    fun h264KeyframeWithParameterSets() {
        val data = bytes(
            0, 0, 0, 1, 0x67, 0x42, 0x1f,  // SPS (4-byte start code)
            0, 0, 1, 0x68, 0xce,           // PPS (3-byte start code)
            0, 0, 0, 1, 0x65, 0x88, 0x84,  // IDR slice
        )
        assertEquals(
            listOf(Triple(Kind.PARAM_SPS, 0, 7), Triple(Kind.PARAM_PPS, 7, 12), Triple(Kind.KEYFRAME, 12, 19)),
            units(data, hevc = false),
        )
    }

    @Test
    fun h264StopsAtFirstSlice() {
        val data = bytes(
            0, 0, 0, 1, 0x41, 0x9a, 0x00, // non-IDR slice
            0, 0, 0, 1, 0x67, 0x42,       // would be an SPS, but comes after the slice
        )
        assertEquals(listOf(Triple(Kind.FRAME, 0, data.size)), units(data, hevc = false))
    }

    @Test
    fun h264OtherUnitsBeforeSlice() {
        val data = bytes(0, 0, 0, 1, 0x06, 0x05, 0, 0, 0, 1, 0x09, 0xf0, 0, 0, 1, 0x41, 0x00)
        assertEquals(listOf(Kind.OTHER, Kind.OTHER, Kind.FRAME), units(data, hevc = false).map { it.first })
    }

    @Test
    fun hevcKeyframeWithParameterSets() {
        val data = bytes(
            0, 0, 0, 1, 0x40, 0x01, 0x0c, // VPS (type 32)
            0, 0, 0, 1, 0x42, 0x01, 0x01, // SPS (type 33)
            0, 0, 0, 1, 0x44, 0x01, 0xc1, // PPS (type 34)
            0, 0, 0, 1, 0x4e, 0x01, 0x05, // prefix SEI (type 39)
            0, 0, 0, 1, 0x26, 0x01, 0xaf, // IDR_W_RADL (type 19)
        )
        assertEquals(
            listOf(Kind.PARAM_VPS, Kind.PARAM_SPS, Kind.PARAM_PPS, Kind.OTHER, Kind.KEYFRAME),
            units(data, hevc = true).map { it.first },
        )
    }

    @Test
    fun hevcSliceTypes() {
        fun kindOf(header: Int) = units(bytes(0, 0, 1, header, 0x01, 0x00), hevc = true).single().first
        assertEquals(Kind.FRAME, kindOf(0x02))    // TRAIL_R (type 1)
        assertEquals(Kind.KEYFRAME, kindOf(0x20)) // BLA_W_LP (type 16)
        assertEquals(Kind.KEYFRAME, kindOf(0x2a)) // CRA (type 21)
        assertEquals(Kind.OTHER, kindOf(0x46))    // AUD (type 35)
    }

    @Test
    fun dropsStartCodeWithoutPayload() {
        assertEquals(listOf(Triple(Kind.PARAM_SPS, 0, 5)), units(bytes(0, 0, 1, 0x67, 0, 0, 0, 0, 1), hevc = false))
    }

    @Test
    fun noStartCodeGivesNoUnits() {
        assertEquals(emptyList<Triple<Kind, Int, Int>>(), units(bytes(0x65, 0x88, 0, 0, 2), hevc = false))
    }
}
