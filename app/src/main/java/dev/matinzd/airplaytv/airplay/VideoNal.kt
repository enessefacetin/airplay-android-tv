package dev.matinzd.airplaytv.airplay

/** Minimal Annex-B helpers for H.264 and H.265 (HEVC). */
object VideoNal {
    enum class Kind { PARAM_VPS, PARAM_SPS, PARAM_PPS, KEYFRAME, FRAME, OTHER }

    /** [start] is where the start code begins, [end] is exclusive. */
    class Unit(val kind: Kind, val start: Int, val end: Int)

    /** NAL units up to and including the first slice (parameter sets come first). */
    fun leadingUnits(data: ByteArray, hevc: Boolean): List<Unit> {
        val starts = mutableListOf<Pair<Int, Int>>() // start-code offset to payload offset
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                val payload = when {
                    data[i + 2].toInt() == 1 -> i + 3
                    i + 4 <= data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1 -> i + 4
                    else -> -1
                }
                if (payload > 0) {
                    starts += i to payload
                    if (payload < data.size && isSlice(kind(data[payload], hevc))) break
                    i = payload
                    continue
                }
            }
            i++
        }
        return starts.mapIndexedNotNull { index, (start, payload) ->
            if (payload >= data.size) return@mapIndexedNotNull null
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            Unit(kind(data[payload], hevc), start, end)
        }
    }

    private fun isSlice(kind: Kind) = kind == Kind.KEYFRAME || kind == Kind.FRAME

    private fun kind(header: Byte, hevc: Boolean): Kind {
        val h = header.toInt() and 0xff
        return if (hevc) {
            when (val type = (h shr 1) and 0x3f) {
                32 -> Kind.PARAM_VPS
                33 -> Kind.PARAM_SPS
                34 -> Kind.PARAM_PPS
                in 16..21 -> Kind.KEYFRAME // BLA, IDR, CRA
                else -> if (type < 32) Kind.FRAME else Kind.OTHER
            }
        } else {
            when (h and 0x1f) {
                7 -> Kind.PARAM_SPS
                8 -> Kind.PARAM_PPS
                5 -> Kind.KEYFRAME
                1 -> Kind.FRAME
                else -> Kind.OTHER
            }
        }
    }
}
