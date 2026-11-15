package com.benchan.wireless.screencasting.protocol.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {
    private val avt = UpnpServices.AV_TRANSPORT

    @Test
    fun soapRequestEscapesAndOrdersArgs() {
        val didl = DidlLite.build(DidlItem("A&B", MediaKind.VIDEO, "http-get:*:video/mp4:*", "http://192.168.1.2:1/t/1.mp4"), MetadataLevel.FULL)
        val body = Soap.request(avt, "SetAVTransportURI", listOf("InstanceID" to "0", "CurrentURI" to "http://h/a?x=1&y=2", "CurrentURIMetaData" to didl))
        assertTrue(body.indexOf("<InstanceID>") < body.indexOf("<CurrentURI>"))
        assertTrue(body.contains("<CurrentURI>http://h/a?x=1&amp;y=2</CurrentURI>"))
        // DIDL 里已转义的 & 在 SOAP 里再转义一次（双重转义）
        assertTrue(body.contains("A&amp;amp;B"))
        assertFalse(body.contains("CDATA"))
        assertEquals("\"$avt#Play\"", Soap.actionHeader(avt, "Play"))
        // 往返：DMR 的解析器能读回原值
        val parsed = Soap.parse(body, Soap.actionHeader(avt, "SetAVTransportURI"))
        assertEquals("http://h/a?x=1&y=2", parsed.args["CurrentURI"])
        assertEquals("A&B", MediaMetadata.parse(parsed.args["CurrentURIMetaData"])!!.title)
    }

    @Test
    fun soapResponseVariants() {
        val ok = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetVolumeResponse xmlns:u="urn:x"><CurrentVolume>17</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"""
        assertEquals("17", (Soap.parseResponse(ok, 200) as SoapOutcome.Ok).values["CurrentVolume"])
        // BOM、不同前缀、无 encodingStyle
        val bom = "﻿  <SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"><SOAP-ENV:Body><m:GetTransportInfoResponse xmlns:m=\"urn:x\"><CurrentTransportState>PLAYING</CurrentTransportState></m:GetTransportInfoResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>"
        assertEquals("PLAYING", (Soap.parseResponse(bom, 200) as SoapOutcome.Ok).values["CurrentTransportState"])
        // 200 + 空响应体按成功
        assertTrue(Soap.parseResponse("", 200) is SoapOutcome.Ok)
        // Fault
        val fault = Soap.fault(UpnpError.ILLEGAL_MIME)
        val f = Soap.parseResponse(fault, 500) as SoapOutcome.Fault
        assertEquals(714, f.code)
        assertEquals(500, f.httpStatus)
        // 不含 errorDescription 的 Fault
        val bare = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError><errorCode>701</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
        assertEquals(701, (Soap.parseResponse(bare, 500) as SoapOutcome.Fault).code)
        // 非 XML
        assertTrue(Soap.parseResponse("<html>oops", 200) is SoapOutcome.Failure)
        assertTrue(Soap.parseResponse("<html>oops", 500) is SoapOutcome.Fault)
        // XXE
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:R xmlns:u="x"><A>&e;</A></u:R></s:Body></s:Envelope>"""
        assertTrue(Soap.parseResponse(evil, 200) is SoapOutcome.Failure)
        assertTrue(Soap.parseResponse("x".repeat(Soap.MAX_BODY + 1), 200) is SoapOutcome.Failure)
    }

    @Test
    fun didlLevelsAndTitles() {
        val item = DidlItem("第 1 集 <预告>", MediaKind.VIDEO, "http-get:*:video/mp4:DLNA.ORG_OP=01", "http://192.168.1.2:9/t/1.mp4", 1234, 61_500, "1920x1080")
        val full = DidlLite.build(item, MetadataLevel.FULL)
        assertTrue(full.contains("size=\"1234\""))
        assertTrue(full.contains("duration=\"0:01:01.500\""))
        assertTrue(full.contains("resolution=\"1920x1080\""))
        assertTrue(full.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        val m = MediaMetadata.parse(full)!!
        assertEquals("第 1 集 <预告>", m.title)
        assertEquals("video/mp4", m.mimeType)
        assertEquals(61_500L, m.durationMs)
        assertEquals("http://192.168.1.2:9/t/1.mp4", m.resUrl)
        val min = DidlLite.build(item, MetadataLevel.MINIMAL)
        assertFalse(min.contains("size="))
        assertFalse(min.contains("xmlns:dlna"))
        assertNotNull(MediaMetadata.parse(min))
        assertEquals("", DidlLite.build(item, MetadataLevel.NONE))
        assertEquals(DidlLite.MAX_TITLE, DidlLite.sanitizeTitle("长".repeat(300)).length)
        assertEquals("ab", DidlLite.sanitizeTitle("a\u0000b"))
        // 不在 emoji 代理对中间截断
        val emoji = "a".repeat(99) + "😀"
        assertFalse(Character.isHighSurrogate(DidlLite.sanitizeTitle(emoji).last()))
        assertEquals("object.item.imageItem.photo", MediaKind.IMAGE.upnpClass)
        assertEquals("object.item.audioItem.musicTrack", MediaKind.AUDIO.upnpClass)
    }

    @Test
    fun timeFormats() {
        assertEquals("01:02:03", UpnpTime.format(3_723_000))
        assertEquals("0:00:05.000", UpnpTime.formatPrecise(5_000))
        assertEquals(5_000L, UpnpTime.parse("0:00:05"))
        assertEquals(5_000L, UpnpTime.parse("00:00:05.000"))
        assertEquals(3_723_500L, UpnpTime.parse("1:02:03.5"))
        assertEquals(10_000L, UpnpTime.parse("0:00:10.1/3"))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse(""))
        assertNull(UpnpTime.parse("1:99:00"))
    }

    @Test
    fun protocolInfoParsingAndMatching() {
        val sink = ProtocolInfo.parseList(" http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC , http-get:*:video/x-mkv:*,rtsp-rtp-udp:*:video/mp2t:*,,bad,http-get:*:audio/mpeg:*")
        assertEquals(4, sink.size)
        assertEquals("AVC_MP4_HP_HD_AAC", sink[0].dlnaProfile)
        assertEquals(SinkSupport.SUPPORTED, SinkMatcher.match(sink, "video/mp4"))
        assertEquals(SinkSupport.SUPPORTED, SinkMatcher.match(sink, "video/x-matroska")) // 别名
        assertEquals(SinkSupport.UNSUPPORTED, SinkMatcher.match(sink, "video/mp2t")) // 只有 rtsp
        assertEquals(SinkSupport.UNSUPPORTED, SinkMatcher.match(sink, "image/jpeg"))
        assertEquals(SinkSupport.UNKNOWN, SinkMatcher.match(emptyList(), "video/mp4"))
        assertEquals(SinkSupport.UNKNOWN, SinkMatcher.match(ProtocolInfo.parseList("http-get:*:*:*"), "video/webm"))
        assertEquals(SinkSupport.SUPPORTED, SinkMatcher.match(ProtocolInfo.parseList("http-get:*:video/*:*"), "video/webm"))
        assertEquals("video/mp4", SinkMatcher.preferredMime(sink, "video/quicktime"))
        assertEquals("video/quicktime", SinkMatcher.preferredMime(ProtocolInfo.parseList("http-get:*:video/quicktime:*"), "video/quicktime"))
        assertEquals(listOf("AVC_MP4_HP_HD_AAC"), SinkMatcher.profilesFor(sink, "video/mp4"))
    }

    @Test
    fun formatCheckDecidesTranscodeOffer() {
        val avcOnly = ProtocolInfo.parseList("http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_SD_AAC_MULT5")
        val hevcMp4 = VideoFormatInfo("video/mp4", "video/hevc", "audio/mp4a-latm")
        val v = FormatCheck.evaluate(avcOnly, hevcMp4)
        assertTrue(v.suggestTranscode)
        assertTrue(v.codecUnsupported)
        assertFalse(FormatCheck.evaluate(avcOnly, hevcMp4.copy(videoMime = "video/avc")).suggestTranscode)
        // 容器不在声明里
        val mkv = VideoFormatInfo("video/x-matroska", "video/hevc", "audio/ac3")
        assertTrue(FormatCheck.evaluate(ProtocolInfo.parseList("http-get:*:video/mp4:*"), mkv).containerUnsupported)
        // 没声明或声明为 * 时不提议（PRD F2 ③）
        assertFalse(FormatCheck.evaluate(emptyList(), mkv).suggestTranscode)
        assertFalse(FormatCheck.evaluate(ProtocolInfo.parseList("http-get:*:*:*"), mkv).suggestTranscode)
        // 条目不带配置名称：不能据此判断编码
        assertFalse(FormatCheck.evaluate(ProtocolInfo.parseList("http-get:*:video/mp4:*"), hevcMp4).suggestTranscode)
        assertEquals("video/x-matroska|video/hevc|audio/ac3", mkv.key)
    }

    @Test
    fun dlnaParams() {
        assertEquals("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", DlnaParams.forLocal(MediaKind.VIDEO, true))
        assertTrue(DlnaParams.forLocal(MediaKind.VIDEO, false).startsWith("DLNA.ORG_OP=00"))
        assertTrue(DlnaParams.forLocal(MediaKind.VIDEO, true, converted = true).contains("DLNA.ORG_CI=1"))
        assertEquals("DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=00D00000000000000000000000000000",
            DlnaParams.forLocal(MediaKind.IMAGE, true, true, "JPEG_LRG"))
        assertEquals("JPEG_SM", DlnaParams.jpegProfile(640, 480))
        assertEquals("JPEG_MED", DlnaParams.jpegProfile(768, 1024))
        assertEquals("JPEG_LRG", DlnaParams.jpegProfile(3840, 2160))
        assertEquals("http-get:*:*:*", DlnaParams.forRemote(null))
        assertEquals("Interactive", DlnaParams.transferMode(MediaKind.IMAGE))
        assertEquals("Streaming", DlnaParams.transferMode(MediaKind.AUDIO))
    }

    @Test
    fun byteRanges() {
        val size = 1000L
        assertEquals(RangeRequest.Full, RangeRequest.parse(null, size))
        assertEquals(RangeRequest.Partial(ByteRange(0, 999)), RangeRequest.parse("bytes=0-", size))
        assertEquals(RangeRequest.Partial(ByteRange(10, 19)), RangeRequest.parse("bytes=10-19", size))
        assertEquals(RangeRequest.Partial(ByteRange(500, 999)), RangeRequest.parse("bytes=500-5000", size))
        assertEquals(RangeRequest.Partial(ByteRange(900, 999)), RangeRequest.parse("bytes=-100", size))
        assertEquals(RangeRequest.Partial(ByteRange(0, 999)), RangeRequest.parse("bytes=-5000", size))
        assertEquals(RangeRequest.Partial(ByteRange(0, 9)), RangeRequest.parse("bytes=0-9, 20-29", size))
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=1000-", size))
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=-0", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=abc", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("items=0-1", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=20-10", size))
        assertEquals("bytes 10-19/1000", ByteRange(10, 19).contentRange(1000))
        assertEquals(10L, ByteRange(10, 19).length)
    }

    @Test
    fun mimeMapping() {
        assertEquals("mp4", MediaMime.extensionOfUrl("http://x.com/a/b/movie.MP4?token=1#t"))
        assertNull(MediaMime.extensionOfUrl("http://x.com/watch?v=abc"))
        assertNull(MediaMime.extensionOfUrl("http://x.com/"))
        assertEquals("application/vnd.apple.mpegurl" to MediaKind.VIDEO, MediaMime.guessForUrl("https://cdn/x/index.m3u8"))
        assertEquals(null to MediaKind.VIDEO, MediaMime.guessForUrl("https://www.example.com/video/123"))
        assertEquals("audio/mpeg" to MediaKind.AUDIO, MediaMime.guessForUrl("http://x/song.mp3"))
        assertEquals("image/jpeg" to MediaKind.IMAGE, MediaMime.guessForUrl("http://x/p.jpeg"))
        assertEquals("video/x-matroska", MediaMime.normalize("video/x-mkv; codecs=x"))
        assertEquals("mkv", MediaMime.extensionFor("video/x-matroska"))
        assertEquals("bin", MediaMime.extensionFor("application/x-unknown"))
        assertEquals(MediaKind.AUDIO, MediaMime.kindOf("audio/x-flac"))
        assertNull(MediaMime.kindOf("text/plain"))
    }

    @Test
    fun lastChangeEvents() {
        val inner = """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0"><TransportState val="PAUSED_PLAYBACK"/><CurrentTrackURI val="http://192.168.1.2:5/t/1.mp4"/><Volume channel="LF" val="3"/><Volume channel="Master" val="40"/><Mute channel="Master" val="1"/></InstanceID><InstanceID val="1"><TransportState val="PLAYING"/></InstanceID></Event>"""
        val body = """<?xml version="1.0"?><e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0"><e:property><LastChange>${Xml.escape(inner)}</LastChange></e:property></e:propertyset>"""
        val e = LastChangeParser.parse(body)!!
        assertEquals("PAUSED_PLAYBACK", e.transportState)
        assertEquals(40, e.volume)
        assertEquals(true, e.mute)
        assertEquals("http://192.168.1.2:5/t/1.mp4", e.trackUri)
        assertNull(LastChangeParser.parse("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><Other>1</Other></e:property></e:propertyset>"))
        assertNull(LastChangeParser.parse("garbage"))
    }
}
