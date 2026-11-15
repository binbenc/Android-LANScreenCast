package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.protocol.upnp.MediaMetadata
import com.benchan.wireless.screencasting.protocol.upnp.Soap
import com.benchan.wireless.screencasting.protocol.upnp.SoapException
import com.benchan.wireless.screencasting.protocol.upnp.SoapRequest
import com.benchan.wireless.screencasting.protocol.upnp.UpnpError
import com.benchan.wireless.screencasting.protocol.upnp.UpnpTime

import com.benchan.wireless.screencasting.core.net.LanHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class FakePlayer : MediaRendererPlayer {
    val s = MutableStateFlow(PlayerSnapshot())
    override val snapshot: StateFlow<PlayerSnapshot> = s
    var pos = 0L
    override suspend fun load(uri: String, metadataXml: String, metadata: MediaMetadata?) = s.update { it.copy(uri = uri, metadataXml = metadataXml, metadata = metadata, state = TransportState.PLAYING) }
    override suspend fun play() = s.update { it.copy(state = TransportState.PLAYING) }
    override suspend fun pause() = s.update { it.copy(state = TransportState.PAUSED_PLAYBACK) }
    override suspend fun stop() = s.update { it.copy(state = TransportState.STOPPED) }
    override suspend fun seek(positionMs: Long) { pos = positionMs }
    override suspend fun positionMs(): Long = pos
    override suspend fun setVolume(volume: Int) = s.update { it.copy(volume = volume) }
    override suspend fun setMute(muted: Boolean) = s.update { it.copy(muted = muted) }
}

class DlnaTest {
    private val avt = DlnaService.AV_TRANSPORT.type

    private fun soap(action: String, args: String, type: String = avt) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$type">$args</u:$action></s:Body></s:Envelope>"""

    @Test
    fun soapParseAndRespond() {
        val r = Soap.parse(soap("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:30</Target>"), "\"$avt#Seek\"")
        assertEquals("Seek", r.action)
        assertEquals(avt, r.serviceType)
        assertEquals("0:01:30", r.args["Target"])
        val resp = Soap.response(avt, "GetVolume", mapOf("CurrentVolume" to "30"))
        assertTrue(resp.contains("<u:GetVolumeResponse xmlns:u=\"$avt\"><CurrentVolume>30</CurrentVolume>"))
        assertTrue(Soap.fault(UpnpError.INVALID_ACTION).contains("<errorCode>401</errorCode>"))
    }

    @Test
    fun soapRejectsXxeAndGarbage() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Play xmlns:u="x">&e;</u:Play></s:Body></s:Envelope>"""
        for (body in listOf(evil, "not xml", "<a/>", "x".repeat(Soap.MAX_BODY + 1))) {
            try {
                Soap.parse(body, null)
                fail("should reject")
            } catch (_: SoapException) {
            }
        }
    }

    @Test
    fun didlLiteAndTime() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>第 1 集</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*" duration="0:45:10.000">http://192.168.1.5/v.mp4</res></item></DIDL-Lite>"""
        val m = MediaMetadata.parse(didl)!!
        assertEquals("第 1 集", m.title)
        assertEquals("video/mp4", m.mimeType)
        assertEquals(2_710_000L, m.durationMs)
        assertEquals("http://192.168.1.5/v.mp4", m.resUrl)
        assertNull(MediaMetadata.parse("<<bad"))
        assertEquals("01:02:03", UpnpTime.format(3_723_000))
        assertEquals(3_723_500L, UpnpTime.parse("1:02:03.5"))
        assertNull(UpnpTime.parse("1:99:00"))
    }

    @Test
    fun actionsDriveThePlayerAndAskConsent() = runBlocking {
        val p = FakePlayer()
        val asked = mutableListOf<String>()
        var allow = false
        val actions = DmrActions(p) { DlnaConsent { remote, _, _ -> asked += remote; allow } }
        val set = SoapRequest(avt, "SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to "http://192.168.1.5/v.mp4", "CurrentURIMetaData" to ""))
        try {
            actions.handle(DlnaService.AV_TRANSPORT, set, "192.168.1.9", "Bili")
            fail("should be denied")
        } catch (e: SoapException) {
            assertEquals(UpnpError.ACCESS_DENIED, e.error)
        }
        allow = true
        actions.handle(DlnaService.AV_TRANSPORT, set, "192.168.1.9", "Bili")
        assertEquals(listOf("192.168.1.9", "192.168.1.9"), asked)
        assertEquals(TransportState.PLAYING, p.s.value.state)
        actions.handle(DlnaService.AV_TRANSPORT, SoapRequest(avt, "Pause", emptyMap()), "x", null)
        assertEquals("PAUSED_PLAYBACK", actions.handle(DlnaService.AV_TRANSPORT, SoapRequest(avt, "GetTransportInfo", emptyMap()), "x", null)["CurrentTransportState"])
        actions.handle(DlnaService.AV_TRANSPORT, SoapRequest(avt, "Seek", mapOf("Unit" to "REL_TIME", "Target" to "00:10:00")), "x", null)
        assertEquals(600_000L, p.pos)
        assertEquals("00:10:00", actions.handle(DlnaService.AV_TRANSPORT, SoapRequest(avt, "GetPositionInfo", emptyMap()), "x", null)["RelTime"])
        actions.handle(DlnaService.RENDERING_CONTROL, SoapRequest("", "SetVolume", mapOf("Channel" to "Master", "DesiredVolume" to "35")), "x", null)
        assertEquals("35", actions.handle(DlnaService.RENDERING_CONTROL, SoapRequest("", "GetVolume", emptyMap()), "x", null)["CurrentVolume"])
        actions.handle(DlnaService.RENDERING_CONTROL, SoapRequest("", "SetMute", mapOf("DesiredMute" to "true")), "x", null)
        assertTrue(p.s.value.muted)
        assertTrue(actions.handle(DlnaService.CONNECTION_MANAGER, SoapRequest("", "GetProtocolInfo", emptyMap()), "x", null)["Sink"]!!.contains("video/mp4"))
        // 只接受 http/https
        try {
            actions.handle(DlnaService.AV_TRANSPORT, set.copy(args = mapOf("CurrentURI" to "file:///sdcard/x.mp4")), "x", null)
            fail()
        } catch (e: SoapException) {
            assertEquals(UpnpError.RESOURCE_NOT_FOUND, e.error)
        }
    }

    @Test
    fun descriptionsAreWellFormed() {
        val d = DlnaDescriptions.device("客厅<电视>", "u-1", 8443, "dev", "AAAA")
        assertTrue(d.contains("<friendlyName>客厅&lt;电视&gt;</friendlyName>"))
        assertTrue(d.contains("<wsc:port>8443</wsc:port>"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(d.byteInputStream())
        DlnaService.entries.forEach { s ->
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(DlnaDescriptions.scpd(s).byteInputStream())
        }
    }

    @Test
    fun genaHeadersAndLastChange() {
        assertEquals(listOf("http://192.168.1.9:4004/ev"), GenaManager.parseCallbacks("<http://192.168.1.9:4004/ev>").map { it.toString() })
        assertEquals(300, GenaManager.timeoutSeconds("Second-300"))
        assertEquals(1800, GenaManager.timeoutSeconds("infinite"))
        val lc = GenaManager.lastChange(DlnaService.AV_TRANSPORT, PlayerSnapshot(state = TransportState.PLAYING))
        assertTrue(lc.contains("&lt;TransportState val=&quot;PLAYING&quot;/&gt;"))
        val g = GenaManager(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        assertNull("public callback must be rejected", g.subscribe(DlnaService.AV_TRANSPORT, "<http://8.8.8.8/ev>", null))
        assertNotNull(g.subscribe(DlnaService.AV_TRANSPORT, "<http://192.168.1.9/ev>", null))
    }

    @Test
    fun ssdpSearchMatching() {
        val adv = SsdpAdvertiser(java.net.InetAddress.getByName("192.168.1.2") as java.net.Inet4Address, "u-1", "http://192.168.1.2:1/d.xml", WscAdvert(8443, "dev", "fp", "客厅"))
        assertEquals(6, adv.matchingTargets("ssdp:all").size)
        assertEquals(listOf(DlnaDescriptions.DEVICE_TYPE), adv.matchingTargets(DlnaDescriptions.DEVICE_TYPE))
        assertTrue(adv.matchingTargets("urn:other").isEmpty())
        val r = adv.response("upnp:rootdevice")
        assertEquals("uuid:u-1::upnp:rootdevice", r["USN"])
        assertEquals("8443", r["X-WSC-PORT"])
    }

    /** 技术方案 3.7 / M0-T10 待验证项：Ktor CIO 能否处理 GENA 的非标准方法 SUBSCRIBE / UNSUBSCRIBE，并收到初始 NOTIFY。 */
    @Test
    fun ktorCioHandlesGenaAndSoap() = runBlocking {
        val p = FakePlayer()
        val gena = GenaManager(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val server = LanHttpServer("dlna-test") {
            dlnaModule({ DlnaDescriptions.device("TV", "u", null, null, null) }, DmrActions(p) { null }, gena) { svc -> GenaManager.lastChange(svc, p.s.value) }
        }
        val port = server.start()
        val notifications = CopyOnWriteArrayList<String>()
        val callback = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                callback.accept().use { s ->
                    val r = BufferedReader(InputStreamReader(s.getInputStream()))
                    notifications += r.readLine()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
        }
        try {
            val sub = raw(port, "SUBSCRIBE /dlna/avt/event HTTP/1.1\r\nHost: 127.0.0.1\r\nCALLBACK: <http://127.0.0.1:${callback.localPort}/cb>\r\nNT: upnp:event\r\nTIMEOUT: Second-300\r\nContent-Length: 0\r\n\r\n")
            assertTrue(sub, sub.startsWith("HTTP/1.1 200"))
            assertTrue(sub.contains("SID: uuid:", ignoreCase = true))
            val sid = Regex("(?i)SID: (uuid:[^\\r\\n]+)").find(sub)!!.groupValues[1]
            val body = soap("SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>http://192.168.1.5/a.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
            val ctl = raw(port, "POST /dlna/avt/control HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nSOAPACTION: \"$avt#SetAVTransportURI\"\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
            assertTrue(ctl, ctl.startsWith("HTTP/1.1 200"))
            assertEquals("http://192.168.1.5/a.mp4", p.s.value.uri)
            val unsub = raw(port, "UNSUBSCRIBE /dlna/avt/event HTTP/1.1\r\nHost: 127.0.0.1\r\nSID: $sid\r\nContent-Length: 0\r\n\r\n")
            assertTrue(unsub, unsub.startsWith("HTTP/1.1 200"))
            val desc = raw(port, "GET /dlna/description.xml HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(desc.contains("MediaRenderer"))
            var waited = 0
            while (notifications.isEmpty() && waited < 3000) { Thread.sleep(50); waited += 50 }
            assertTrue("initial NOTIFY received", notifications.firstOrNull()?.startsWith("NOTIFY /cb HTTP/1.1") == true)
            assertFalse(gena.unsubscribe(sid))
        } finally {
            server.stop()
            callback.close()
        }
    }

    private fun raw(port: Int, request: String): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        s.soTimeout = 3000
        s.getOutputStream().write(request.toByteArray())
        s.getOutputStream().flush()
        val buf = StringBuilder()
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        var contentLength = -1
        while (true) {
            val line = r.readLine() ?: break
            buf.append(line).append("\r\n")
            if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toInt()
            if (line.isEmpty()) break
        }
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = r.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            buf.append(body, 0, read)
        }
        buf.toString()
    }
}
