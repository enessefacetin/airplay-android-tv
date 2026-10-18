package dev.matinzd.airplaytv.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class MdnsTxtTest {
    private fun txt(vararg entries: String) = entries.fold(ByteArray(0)) { out, entry ->
        val bytes = entry.toByteArray(Charsets.UTF_8)
        out + byteArrayOf(bytes.size.toByte()) + bytes
    }

    @Test
    fun readsKeyValuePairsInOrder() {
        assertEquals(
            listOf("deviceid" to "02:11:22:33:44:55", "features" to "0x5A7FFEE6,0x0", "pw" to "false"),
            MdnsAdvertiser.parseTxt(txt("deviceid=02:11:22:33:44:55", "features=0x5A7FFEE6,0x0", "pw=false")),
        )
    }

    @Test
    fun keepsEqualsSignsInValue() {
        assertEquals(listOf("pk" to "a=b="), MdnsAdvertiser.parseTxt(txt("pk=a=b=")))
    }

    @Test
    fun allowsEmptyValue() {
        assertEquals(listOf("vv" to ""), MdnsAdvertiser.parseTxt(txt("vv=")))
    }

    @Test
    fun skipsEntriesWithoutKey() {
        assertEquals(listOf("a" to "1"), MdnsAdvertiser.parseTxt(txt("flag", "=x", "", "a=1")))
    }

    @Test
    fun readsLengthAsUnsigned() {
        val long = "k=" + "v".repeat(200)
        assertEquals(listOf("k" to "v".repeat(200)), MdnsAdvertiser.parseTxt(txt(long)))
    }

    @Test
    fun stopsAtEndOfTruncatedRecord() {
        val data = txt("a=1", "b=22").copyOf(7) // second entry cut after "b="
        assertEquals(listOf("a" to "1", "b" to ""), MdnsAdvertiser.parseTxt(data))
    }

    @Test
    fun emptyRecordGivesNoEntries() {
        assertEquals(emptyList<Pair<String, String>>(), MdnsAdvertiser.parseTxt(ByteArray(0)))
    }
}
