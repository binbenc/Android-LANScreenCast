package com.benchan.wireless.screencasting.core.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexTest {
    @Test
    fun roundTrip() {
        val bytes = byteArrayOf(0, 1, 0x7F, -1, -128)
        assertEquals("00017fff80", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("0x00017FFF80"))
        assertArrayEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
    }

    @Test
    fun randomDigitsHaveRequestedLength() {
        repeat(100) {
            val d = Ids.randomDigits(5)
            assertEquals(5, d.length)
            assertTrue(d.all { it.isDigit() })
        }
    }

    @Test
    fun logSinkReceivesMessagesAndSurvivesFailingSink() {
        val got = mutableListOf<String>()
        val bad = LogSink { _, _, _, _ -> error("boom") }
        val good = LogSink { level, tag, msg, _ -> got += "$level/$tag/$msg" }
        WscLog.addSink(bad)
        WscLog.addSink(good)
        WscLog.i("T", "hello")
        WscLog.removeSink(bad)
        WscLog.removeSink(good)
        assertEquals(listOf("INFO/T/hello"), got)
    }
}
