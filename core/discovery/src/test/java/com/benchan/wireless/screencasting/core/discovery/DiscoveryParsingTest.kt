package com.benchan.wireless.screencasting.core.discovery

import com.benchan.wireless.screencasting.core.discovery.mdns.WscTxt
import com.benchan.wireless.screencasting.core.discovery.ssdp.SsdpHit
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
    fun txtDlnaPortIsOptionalAndValidated() {
        val base = WscTxt(1, "id-1", "TV", listOf("h264"), "fp")
        // 旧版接收端：没有 dlna 键
        assertNull(WscTxt.parse(base.toAttributes().mapValues { it.value.toByteArray() })!!.dlnaPort)
        assertTrue("dlna" !in base.toAttributes())
        val withPort = base.copy(dlnaPort = 49153)
        assertEquals("49153", withPort.toAttributes()["dlna"])
        assertEquals(withPort, WscTxt.parse(withPort.toAttributes().mapValues { it.value.toByteArray() }))
        listOf("0", "65536", "-1", "abc", "").forEach { bad ->
            val attrs = base.toAttributes().mapValues { it.value.toByteArray() } + ("dlna" to bad.toByteArray())
            assertNull(bad, WscTxt.parse(attrs)!!.dlnaPort)
        }
    }

    @Test
    fun withDlnaPortAddsReplacesAndRemovesKey() {
        val attrs = WscTxt(1, "id-1", "TV", listOf("h264"), "fp").toAttributes()
        val on = WscTxt.withDlnaPort(attrs, 8200)
        assertEquals("8200", on["dlna"])
        assertEquals(attrs, on - "dlna")
        assertEquals("8300", WscTxt.withDlnaPort(on, 8300)["dlna"])
        assertEquals(attrs, WscTxt.withDlnaPort(on, null))
        assertEquals(attrs, WscTxt.withDlnaPort(attrs, 70000))
    }

    @Test
    fun ssdpResponseWithWscHeadersIsWscDevice() {
        val msg = SsdpMessage.parse(
            "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:u1::urn:x\r\n" +
                "LOCATION: http://192.168.1.9:49152/d.xml\r\nX-WSC-PORT: 8443\r\nX-WSC-ID: dev-1\r\nX-WSC-NAME: %E5%AE%A2%E5%8E%85\r\n\r\n",
        )!!
        val hit = SsdpSearcher.parse(SsdpPacket(msg, InetAddress.getByName("192.168.1.9"), 1900), now = 5) as SsdpHit.Wsc
        val d = hit.device
        assertEquals("http://192.168.1.9:49152/d.xml", hit.location)
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
        val d = SsdpSearcher.parse(SsdpPacket(msg, InetAddress.getByName("192.168.1.10"), 1900)) as SsdpHit.Renderer
        assertEquals("tv", d.uuid)
        assertEquals("http://192.168.1.10:1234/d.xml", d.location)
        // 名字要等设备描述，不再用 SERVER 头（SERVER 末尾常是 DLNADOC/1.50 之类）
        assertEquals("Linux UPnP/1.0 卧室电视", d.server)
        // AVTransport 搜索的响应也算；无关设备忽略
        val avt = SsdpMessage.parse("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\nUSN: uuid:tv2::x\r\nLOCATION: http://192.168.1.11/d.xml\r\n\r\n")!!
        assertTrue(SsdpSearcher.parse(SsdpPacket(avt, InetAddress.getByName("192.168.1.11"), 1900)) is SsdpHit.Renderer)
        val router = SsdpMessage.parse("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nUSN: uuid:gw::x\r\nLOCATION: http://192.168.1.1/d.xml\r\n\r\n")!!
        assertNull(SsdpSearcher.parse(SsdpPacket(router, InetAddress.getByName("192.168.1.1"), 1900)))
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
