package dev.matinzd.airplaytv.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DmapTest {
    private fun item(tag: String, value: ByteArray): ByteArray {
        val n = value.size
        return tag.toByteArray(Charsets.US_ASCII) +
            byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + value
    }

    private fun item(tag: String, value: String) = item(tag, value.toByteArray(Charsets.UTF_8))

    @Test
    fun readsTopLevelFields() {
        val track = Dmap.parse(item("minm", "Song") + item("asar", "Artist") + item("asal", "Album"))
        assertEquals(Dmap.Track("Song", "Artist", "Album"), track)
    }

    @Test
    fun readsFieldsInsideListing() {
        val track = Dmap.parse(item("mlit", item("asar", "Artist") + item("minm", "Song")))
        assertEquals(Dmap.Track("Song", "Artist", null), track)
    }

    @Test
    fun skipsUnknownTags() {
        val track = Dmap.parse(item("astm", byteArrayOf(0, 3, 0x0d, 0x40)) + item("minm", "Song"))
        assertEquals("Song", track.title)
    }

    @Test
    fun decodesUtf8() {
        assertEquals("Café ☕ 日本", Dmap.parse(item("minm", "Café ☕ 日本")).title)
    }

    @Test
    fun keepsEarlierFieldsWhenDataIsTruncated() {
        val data = item("minm", "Song") + item("asar", "Artist").copyOf(10)
        assertEquals(Dmap.Track("Song", null, null), Dmap.parse(data))
    }

    @Test
    fun ignoresImpossibleLength() {
        val data = "minm".toByteArray() + byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        assertNull(Dmap.parse(data).title)
    }

    @Test
    fun emptyInputGivesEmptyTrack() {
        assertEquals(Dmap.Track(null, null, null), Dmap.parse(ByteArray(0)))
    }
}
