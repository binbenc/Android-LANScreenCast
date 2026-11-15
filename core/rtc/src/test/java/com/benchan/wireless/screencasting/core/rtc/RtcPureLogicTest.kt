package com.benchan.wireless.screencasting.core.rtc

import com.benchan.wireless.screencasting.core.rtc.receiver.DecoderLowLatencyKeys
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyBreakdown
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyEstimator
import com.benchan.wireless.screencasting.core.rtc.stats.StatEntry
import com.benchan.wireless.screencasting.core.rtc.stats.StatsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun processingDelayAndPacerUseMeasuredDeltas() {
        val p = StatsParser()
        fun inb(dec: Long, decTime: Double, proc: Double) = listOf(
            StatEntry("inbound-rtp", "i", dec * 16_000.0, mapOf("kind" to "video", "framesDecoded" to dec, "totalDecodeTime" to decTime,
                "totalProcessingDelay" to proc, "jitterBufferDelay" to 0.0, "jitterBufferEmittedCount" to dec.toDouble())),
        )
        p.parseReceiver(inb(0, 0.0, 0.0))
        // 60 帧：解码共 0.3 s（5 ms/帧），处理共 0.9 s（15 ms/帧）
        val r = p.parseReceiver(inb(60, 0.3, 0.9))!!
        assertEquals(15.0, r.processingMs!!, 0.001)
        assertEquals(5.0, r.decodeMs!!, 0.001)

        val s = StatsParser()
        fun out(sent: Double, delay: Double) = listOf(
            StatEntry("outbound-rtp", "o", sent * 1000, mapOf("kind" to "video", "packetsSent" to sent, "totalPacketSendDelay" to delay)),
        )
        s.parseSender(out(0.0, 0.0))
        assertEquals(2.0, s.parseSender(out(500.0, 1.0))!!.pacerMs!!, 0.001)
        // 旧版 / 拿不到字段：不编造
        assertNull(StatsParser().parseSender(listOf(StatEntry("outbound-rtp", "o", 0.0, mapOf("kind" to "video"))))!!.pacerMs)
    }

    @Test
    fun latencyBreakdownPrefersMeasuredSegments() {
        // 游戏档 60 帧：缓冲 = 处理 − 解码（实测），网络 = RTT/2
        val b = LatencyEstimator.breakdown(fps = 60, encodeMs = 7.0, rttMs = 6, jitterBufferMs = 40.0, decodeMs = 5.0, processingMs = 12.0, pacerMs = 1.0)!!
        assertEquals(7.0, b.bufferMs, 0.001)
        assertEquals(3.0, b.networkMs, 0.001)
        assertEquals(8.0, b.encodeMs, 0.001)
        assertEquals(5.0, b.decodeMs, 0.001)
        assertEquals(setOf(LatencyBreakdown.Segment.ENCODE, LatencyBreakdown.Segment.NETWORK, LatencyBreakdown.Segment.BUFFER, LatencyBreakdown.Segment.DECODE), b.measured)
        // 采集 / 显示是模型值：半个帧间隔 + 固定开销
        val frame = 1000.0 / 60
        assertEquals(frame / 2 + LatencyEstimator.COMPOSITION_MS, b.captureMs, 0.001)
        assertEquals(frame / 2 + LatencyEstimator.SCANOUT_MS, b.displayMs, 0.001)
        assertEquals((b.captureMs + 8.0 + 3.0 + 7.0 + 5.0 + b.displayMs).toInt(), b.totalMs)
        // 没有处理耗时时退回 jitterBufferDelay
        assertEquals(40.0, LatencyEstimator.breakdown(30, 7.0, 6, 40.0, 5.0)!!.bufferMs, 0.001)
        // 网络与缓冲都没有实测：不显示数字
        assertNull(LatencyEstimator.breakdown(30, 7.0, null, null, 5.0))
        // 缓冲为负（统计口径抖动）时按 0
        assertEquals(0.0, LatencyEstimator.breakdown(60, null, 4, null, 9.0, processingMs = 8.0)!!.bufferMs, 0.001)
    }

    @Test
    fun vendorLowLatencyKeysByCodecPrefix() {
        assertTrue(DecoderLowLatencyKeys.vendorKeys("c2.qti.avc.decoder").containsKey("vendor.qti-ext-dec-low-latency.enable"))
        assertTrue(DecoderLowLatencyKeys.vendorKeys("OMX.qcom.video.decoder.avc").containsKey("vendor.qti-ext-dec-low-latency.enable"))
        assertEquals(mapOf("vendor.low-latency.enable" to 1), DecoderLowLatencyKeys.vendorKeys("OMX.amlogic.avc.decoder.awesome2"))
        assertEquals(mapOf("vendor.rtc-ext-dec-low-latency.enable" to 1), DecoderLowLatencyKeys.vendorKeys("c2.exynos.h264.decoder"))
        assertTrue(DecoderLowLatencyKeys.vendorKeys("c2.android.avc.decoder").isEmpty())
        assertTrue(DecoderLowLatencyKeys.vendorKeys("OMX.MTK.VIDEO.DECODER.AVC").isEmpty())
        assertTrue(DecoderLowLatencyKeys.vendorKeys(null).isEmpty())
    }

    @Test
    fun audioCapturePacingNeverBusyLoops() {
        val p = com.benchan.wireless.screencasting.core.rtc.sender.PlaybackAudioCapture
        // 48kHz 立体声 10ms = 1920 字节 → 10ms；空 / 负数也至少 1ms，超大缓冲封顶 20ms
        org.junit.Assert.assertEquals(10_000_000L, p.bufferDurationNs(1920, 48_000, 2))
        org.junit.Assert.assertEquals(1_000_000L, p.bufferDurationNs(0, 48_000, 2))
        org.junit.Assert.assertEquals(1_000_000L, p.bufferDurationNs(-5, 48_000, 2))
        org.junit.Assert.assertEquals(20_000_000L, p.bufferDurationNs(1_000_000, 48_000, 2))
    }

    @Test
    fun audioCaptureRealtimeStepLimitsToRealtime() {
        val p = com.benchan.wireless.screencasting.core.rtc.sender.PlaybackAudioCapture
        val d = 10_000_000L
        // read 立即返回（回调几乎不耗时）：100 次回调必须累计睡够约 1 秒，而不是空转
        var due = 0L
        var now = 1_000_000_000L
        var slept = 0L
        repeat(100) {
            val (nd, wait) = p.realtimeStep(due, now, d)
            due = nd; slept += wait; now += wait
        }
        org.junit.Assert.assertEquals(100 * d, slept)
        // 恰好按时到达：本缓冲仍需占满自己的 10ms
        val (due2, wait2) = p.realtimeStep(due, due, d)
        org.junit.Assert.assertEquals(d, wait2)
        // read 本身已阻塞到下一交付时刻（正常路径）：不额外睡眠
        org.junit.Assert.assertEquals(0L, p.realtimeStep(due2, due2 + d, d).second)
        // 卡顿 1 秒后不追赶突发：从当前时刻重新计时
        val (due3, wait3) = p.realtimeStep(due2, due2 + 1_000_000_000L, d)
        org.junit.Assert.assertEquals(d, wait3)
        org.junit.Assert.assertEquals(due2 + 1_000_000_000L + d, due3)
    }

    @Test
    fun audioCaptureInitRetryBacksOff() {
        val p = com.benchan.wireless.screencasting.core.rtc.sender.PlaybackAudioCapture
        // 创建失败后不能每 10ms 重试（每次注册 AudioPolicy，曾导致进程因 Binder 过多被杀）
        org.junit.Assert.assertEquals(500_000_000L, p.initRetryDelayNs(1))
        org.junit.Assert.assertEquals(1_000_000_000L, p.initRetryDelayNs(2))
        org.junit.Assert.assertEquals(8_000_000_000L, p.initRetryDelayNs(5))
        org.junit.Assert.assertEquals(10_000_000_000L, p.initRetryDelayNs(6))
        org.junit.Assert.assertEquals(10_000_000_000L, p.initRetryDelayNs(100))
    }
}
