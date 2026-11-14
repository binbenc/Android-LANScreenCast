package com.benchan.wireless.screencasting.core.net

import com.benchan.wireless.screencasting.core.net.ssdp.SsdpMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpMessageTest {
    @Test
    fun parseResponseCaseInsensitive() {
        val text = "HTTP/1.1 200 OK\r\nCache-Control: max-age = 1800\r\nlocation: http://192.168.1.5:49152/desc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "X-WSC-PORT: 8443\r\n\r\n"
        val m = SsdpMessage.parse(text)!!
        assertTrue(m.isResponse)
        assertEquals("http://192.168.1.5:49152/desc.xml", m["LOCATION"])
        assertEquals("8443", m[SsdpMessage.X_WSC_PORT])
        assertEquals(1800, SsdpMessage.maxAge(m))
    }

    @Test
    fun searchRoundTrip() {
        val s = SsdpMessage.search(SsdpMessage.MEDIA_RENDERER, mx = 1)
        val parsed = SsdpMessage.parse(s.encode())!!
        assertTrue(parsed.isSearch)
        assertEquals("\"ssdp:discover\"", parsed["MAN"])
        assertEquals("1", parsed["MX"])
    }

    @Test
    fun rejectsGarbage() {
        assertNull(SsdpMessage.parse("GET / HTTP/1.1\r\n\r\n"))
        assertNull(SsdpMessage.parse(""))
        assertNull(SsdpMessage.parse("x".repeat(10_000)))
    }
}
