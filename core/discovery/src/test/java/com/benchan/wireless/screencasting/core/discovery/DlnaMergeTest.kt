package com.benchan.wireless.screencasting.core.discovery

import com.benchan.wireless.screencasting.core.discovery.dlna.DlnaMerge
import com.benchan.wireless.screencasting.core.discovery.dlna.DlnaRendererResolver
import com.benchan.wireless.screencasting.protocol.upnp.ProtocolInfo
import com.benchan.wireless.screencasting.protocol.upnp.RendererDescription
import com.benchan.wireless.screencasting.protocol.upnp.ServiceEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaMergeTest {
    private fun ref(udn: String, host: String, wscId: String? = null, name: String = "电视", sink: String? = null) = DlnaRendererRef(
        RendererDescription(
            udn = udn, friendlyName = name, manufacturer = "B", modelName = "M", modelNumber = null,
            location = "http://$host:1234/d.xml", host = host,
            avTransport = ServiceEndpoint("urn:schemas-upnp-org:service:AVTransport:1", "http://$host:1234/c", null, null),
            renderingControl = null, connectionManager = null, wscId = wscId, isOwnApp = wscId != null,
        ),
        server = "Linux UPnP/1.0",
        sink = sink?.let { ProtocolInfo.parseList(it) },
    )

    @Test
    fun dlnaRowFromDescription() {
        val d = DlnaRendererResolver.toDevice(ref("u1", "192.168.1.30", name = "卧室电视"), DiscoverySource.SSDP, 5)
        assertEquals("dlna:u1", d.deviceId)
        assertEquals("卧室电视", d.name)
        assertEquals(1234, d.port)
        assertEquals(DeviceProtocol.DLNA_ONLY, d.protocol)
        assertTrue(d.canPush)
        assertFalse(d.canMirror)
    }

    @Test
    fun selfIsExcluded() {
        assertTrue(DlnaMerge.isSelf(null, "192.168.1.5", "192.168.1.5", "me"))
        assertTrue(DlnaMerge.isSelf(ref("me", "192.168.1.9"), "192.168.1.9", "192.168.1.5", "me"))
        assertFalse(DlnaMerge.isSelf(ref("tv", "192.168.1.9"), "192.168.1.9", "192.168.1.5", "me"))
    }

    @Test
    fun ownReceiverDmrFoldsIntoWscRow() {
        val wsc = DiscoveredDevice("dev-1", "客厅", "192.168.1.9", 8443, sources = setOf(DiscoverySource.MDNS), lastSeenAt = 1)
        val ownDmr = DlnaRendererResolver.toDevice(ref("u-own", "192.168.1.9", wscId = "dev-1"), DiscoverySource.SSDP, 2)
        val tv = DlnaRendererResolver.toDevice(ref("u-tv", "192.168.1.30"), DiscoverySource.SSDP, 2)
        val merged = DlnaMerge.fold(listOf(ownDmr, wsc, tv))
        assertEquals(2, merged.size)
        val row = merged.first { it.deviceId == "dev-1" }
        assertTrue(row.canMirror && row.canPush)
        assertEquals(8443, row.port)
        // WSC 行还没出现时，本应用接收端的 DMR 先作为普通 DLNA 行
        assertEquals(2, DlnaMerge.fold(listOf(ownDmr, tv)).size)
    }

    @Test
    fun mergeKeepsWscPortAndDlnaRef() {
        val wsc = DiscoveredDevice("dev-1", "客厅", "192.168.1.9", 8443, sources = setOf(DiscoverySource.SSDP), lastSeenAt = 1)
        val withDlna = wsc.copy(dlna = ref("u", "192.168.1.9", "dev-1"), lastSeenAt = 3, sources = setOf(DiscoverySource.MDNS))
        val m = wsc.mergeWith(withDlna)
        assertTrue(m.canPush)
        assertEquals(setOf(DiscoverySource.SSDP, DiscoverySource.MDNS), m.sources)
    }

    @Test
    fun audioOnlyRenderer() {
        assertTrue(ref("s", "192.168.1.40", sink = "http-get:*:audio/mpeg:*,http-get:*:audio/flac:*").audioOnly)
        assertFalse(ref("t", "192.168.1.41", sink = "http-get:*:audio/mpeg:*,http-get:*:video/mp4:*").audioOnly)
        assertFalse(ref("u", "192.168.1.42", sink = "http-get:*:*:*").audioOnly)
        assertFalse(ref("v", "192.168.1.43").audioOnly)
    }
}
