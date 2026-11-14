package com.benchan.wireless.screencasting.core.discovery

import com.benchan.wireless.screencasting.core.discovery.mdns.WscTxt
import com.benchan.wireless.screencasting.core.discovery.ssdp.SsdpSearcher
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpMessage
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DiscoveryParsingTest {
    @Test
    fun txtRoundTripAndUtf8Truncation() {
        val txt = WscTxt(1, "id-1", "客厅电视", listOf("h264", "opus"), "AAECAwQFBgc")
        val attrs = txt.toAttributes().mapValues { it.value.toByteArray() }
        assertEquals(txt, WscTxt.parse(attrs))
        assertNull(WscTxt.parse(mapOf("n" to "x".toByteArray())))
        val long = "电".repeat(100) // 300 字节
        val cut = WscTxt.truncateUtf8(long, 200)
        assertTrue(cut.toByteArray().size <= 200)
        assertEquals(66, cut.length)
    }

    @Test
    fun ssdpResponseWithWscHeadersIsWscDevice() {
        val msg = SsdpMessage.parse(
            "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:u1::urn:x\r\n" +
                "LOCATION: http://192.168.1.9:49152/d.xml\r\nX-WSC-PORT: 8443\r\nX-WSC-ID: dev-1\r\nX-WSC-NAME: %E5%AE%A2%E5%8E%85\r\n\r\n",
        )!!
        val d = SsdpSearcher.parse(SsdpPacket(msg, InetAddress.getByName("192.168.1.9"), 1900), now = 5)!!
        assertEquals("dev-1", d.deviceId)
        assertEquals(8443, d.port)
        assertEquals("客厅", d.name)
        assertEquals(DeviceProtocol.WSC, d.protocol)
    }

    @Test
    fun plainRendererIsDlnaOnly() {
        val msg = SsdpMessage.parse(
            "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:tv::urn:x\r\n" +
                "SERVER: Linux UPnP/1.0 卧室电视\r\nLOCATION: http://192.168.1.10:1234/d.xml\r\n\r\n",
        )!!
        val d = SsdpSearcher.parse(SsdpPacket(msg, InetAddress.getByName("192.168.1.10"), 1900))!!
        assertEquals(DeviceProtocol.DLNA_ONLY, d.protocol)
        assertEquals("dlna:tv", d.deviceId)
    }

    @Test
    fun mergeKeepsFullFingerprintAndUnionsSources() {
        val a = DiscoveredDevice("x", "TV", "192.168.1.2", 8443, fingerprintHex = "ab", sources = setOf(DiscoverySource.RECENT), lastSeenAt = 1)
        val b = DiscoveredDevice("x", "客厅电视", "192.168.1.3", 8443, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 2)
        val m = a.mergeWith(b)
        assertEquals("192.168.1.3", m.host)
        assertEquals("ab", m.fingerprintHex)
        assertEquals(setOf(DiscoverySource.RECENT, DiscoverySource.MDNS), m.sources)
    }
}
