package dev.matinzd.airplaytv.airplay

/** Minimal Annex-B helpers. */
object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8

    /** [start] is where the start code begins, [end] is exclusive. */
    class Nal(val type: Int, val start: Int, val end: Int)

    /** NAL units up to and including the first slice (parameter sets come first). */
    fun leadingNals(data: ByteArray): List<Nal> {
        val starts = mutableListOf<Pair<Int, Int>>() // start-code offset to payload offset
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += i to i + 3
                    if (isSlice(data, i + 3)) break
                    i += 3
                    continue
                }
                if (i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i to i + 4
                    if (isSlice(data, i + 4)) break
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexedNotNull { index, (start, payload) ->
            if (payload >= data.size) return@mapIndexedNotNull null
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            Nal(data[payload].toInt() and 0x1f, start, end)
        }
    }

    private fun isSlice(data: ByteArray, payload: Int): Boolean {
        if (payload >= data.size) return false
        val type = data[payload].toInt() and 0x1f
        return type == NAL_SLICE || type == NAL_IDR
    }
}
