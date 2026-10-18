package dev.matinzd.airplaytv.airplay

/** "Now playing" metadata arrives as DMAP: 4-byte tag, 4-byte big-endian length, value. */
object Dmap {
    data class Track(val title: String?, val artist: String?, val album: String?)

    fun parse(data: ByteArray): Track {
        val values = mutableMapOf<String, String>()
        walk(data, 0, data.size, values)
        return Track(values["minm"], values["asar"], values["asal"])
    }

    private fun walk(data: ByteArray, from: Int, to: Int, out: MutableMap<String, String>) {
        var i = from
        while (i + 8 <= to) {
            val tag = String(data, i, 4, Charsets.US_ASCII)
            val len = ((data[i + 4].toInt() and 0xff) shl 24) or ((data[i + 5].toInt() and 0xff) shl 16) or
                ((data[i + 6].toInt() and 0xff) shl 8) or (data[i + 7].toInt() and 0xff)
            val start = i + 8
            val end = start + len
            if (len < 0 || end > to) return
            when (tag) {
                "mlit" -> walk(data, start, end, out)
                "minm", "asar", "asal" -> out[tag] = String(data, start, len, Charsets.UTF_8)
            }
            i = end
        }
    }
}
