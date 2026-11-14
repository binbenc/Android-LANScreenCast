package com.benchan.wireless.screencasting.core.rtc

import com.benchan.wireless.screencasting.core.rtc.stats.LatencyEstimator
import com.benchan.wireless.screencasting.core.rtc.stats.StatEntry
import com.benchan.wireless.screencasting.core.rtc.stats.StatsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtcPureLogicTest {
    private val sdp = listOf(
        "v=0", "m=audio 9 UDP/TLS/RTP/SAVPF 111 63", "a=rtpmap:111 opus/48000/2",
        "a=fmtp:111 minptime=10;useinbandfec=1", "a=rtpmap:63 red/48000/2", "m=video 9 UDP/TLS/RTP/SAVPF 96",
        "a=rtpmap:96 H264/90000", "a=candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host",
    ).joinToString("\r\n")

    @Test
    fun opusTuning() {
        val out = SdpMunger.tuneOpus(sdp)
        assertTrue(out.contains("a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;maxaveragebitrate=128000;usedtx=0"))
        assertTrue(out.contains("a=rtpmap:96 H264/90000"))
        // 幂等
        assertEquals(out, SdpMunger.tuneOpus(out))
        // 没有 fmtp 时补一行
        val noFmtp = SdpMunger.tuneOpus("m=audio 9 x 111\r\na=rtpmap:111 opus/48000/2\r\nm=video")
        assertTrue(noFmtp.contains("a=rtpmap:111 opus/48000/2\r\na=fmtp:111 stereo=1"))
    }

    @Test
    fun hostCandidateFilter() {
        assertTrue(SdpMunger.isHostCandidate("candidate:1 1 udp 2122260223 192.168.1.23 50000 typ host generation 0"))
        assertFalse(SdpMunger.isHostCandidate("candidate:2 1 udp 1686052607 1.2.3.4 50000 typ srflx raddr 192.168.1.23"))
        assertEquals(setOf("host"), SdpMunger.candidateTypes(sdp))
    }

    @Test
    fun senderStatsAreDiffed() {
        val p = StatsParser()
        fun entries(bytes: Long, ts: Double, frames: Long, encodeTime: Double) = listOf(
            StatEntry("outbound-rtp", "o", ts, mapOf("kind" to "video", "bytesSent" to bytes, "framesEncoded" to frames, "totalEncodeTime" to encodeTime,
                "framesPerSecond" to 30.0, "frameWidth" to 1920L, "frameHeight" to 864L, "encoderImplementation" to "MediaCodec")),
            StatEntry("remote-inbound-rtp", "r", ts, mapOf("kind" to "video", "roundTripTime" to 0.008, "fractionLost" to 0.01)),
            StatEntry("transport", "t", ts, mapOf("selectedCandidatePairId" to "cp")),
            StatEntry("candidate-pair", "cp", ts, mapOf("availableOutgoingBitrate" to 9_000_000.0, "localCandidateId" to "l", "remoteCandidateId" to "rc")),
            StatEntry("local-candidate", "l", ts, mapOf("candidateType" to "host")),
            StatEntry("remote-candidate", "rc", ts, mapOf("candidateType" to "host")),
        )
        p.parseSender(entries(0, 1_000_000.0, 0, 0.0))
        val s = p.parseSender(entries(950_000, 2_000_000.0, 30, 0.3))!!
        assertEquals(7600, s.bitrateKbps)
        assertEquals(10.0, s.encodeMs!!, 0.001)
        assertEquals(8, s.rttMs)
        assertEquals(1.0, s.lossPercent, 0.001)
        assertEquals(9_000_000L, s.availableOutgoingBitrateBps)
        assertTrue(s.hostOnly)
    }

    @Test
    fun receiverStatsAndLatency() {
        val p = StatsParser()
        fun entries(dec: Long, decTime: Double, jb: Double, jbn: Double) = listOf(
            StatEntry("inbound-rtp", "i", dec * 33_000.0, mapOf("kind" to "video", "framesDecoded" to dec, "totalDecodeTime" to decTime,
                "jitterBufferDelay" to jb, "jitterBufferEmittedCount" to jbn, "framesDropped" to 1L, "framesReceived" to dec,
                "codecId" to "c", "powerEfficientDecoder" to true)),
            StatEntry("codec", "c", 0.0, mapOf("mimeType" to "video/H264")),
        )
        p.parseReceiver(entries(0, 0.0, 0.0, 0.0))
        val r = p.parseReceiver(entries(30, 0.18, 1.2, 30.0))!!
        assertEquals(6.0, r.decodeMs!!, 0.001)
        assertEquals(40.0, r.jitterBufferMs!!, 0.001)
        assertEquals("H264", r.codec)
        assertEquals(true, r.hardwareDecoder)
        val lat = LatencyEstimator.estimate(30, 10.0, 8, r.jitterBufferMs, r.decodeMs)
        assertNotNull(lat)
        assertTrue(lat!! in 80..110)
    }

    @Test
    fun audioCapturePacingNeverBusyLoops() {
        val p = com.benchan.wireless.screencasting.core.rtc.sender.PlaybackAudioCapture
        // 48kHz 立体声 10ms = 1920 字节 → 10ms
        org.junit.Assert.assertEquals(10L, p.paceMillis(1920, 48_000, 2))
        // 读失败（缺整个缓冲）或缺 0 字节时也至少睡 1ms，绝不空转
        org.junit.Assert.assertEquals(1L, p.paceMillis(0, 48_000, 2))
        org.junit.Assert.assertEquals(1L, p.paceMillis(-5, 48_000, 2))
        org.junit.Assert.assertEquals(20L, p.paceMillis(1_000_000, 48_000, 2))
    }
}
