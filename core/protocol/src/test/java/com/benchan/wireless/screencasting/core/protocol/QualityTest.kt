package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.core.protocol.quality.AutoQualityPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.CaptureSize
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGradeTracker
import com.benchan.wireless.screencasting.core.protocol.quality.NetworkSample
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {

    @Test
    fun captureSizeFitsBoxAndAlignsTo16() {
        // 20:9 竖屏手机在 1080p 档 → 864×1920 以内（技术方案 3.2）
        val s = CaptureSize.fit(1080, 2400, QualityProfile.BALANCED)
        assertTrue(s.width <= 864 && s.height <= 1920)
        assertEquals(0, s.width % 16)
        assertEquals(0, s.height % 16)
        assertEquals(CaptureSize(864, 1920), s)
        // 横屏
        assertEquals(CaptureSize(1920, 864), CaptureSize.fit(2400, 1080, QualityProfile.BALANCED))
        // 720p 档
        assertEquals(CaptureSize(576, 1280), CaptureSize.fit(1080, 2400, QualityProfile.SMOOTH))
        // 比框小时不放大
        assertEquals(CaptureSize(720, 1280), CaptureSize.fit(720, 1280, QualityProfile.HD))
        // 各种宽高比都对齐
        for ((w, h) in listOf(1440 to 3200, 1080 to 2340, 1200 to 1920, 1768 to 2208, 2560 to 1600, 1079 to 2399)) {
            for (p in QualityProfile.entries) {
                val c = CaptureSize.fit(w, h, p)
                assertEquals(0, c.width % 16)
                assertEquals(0, c.height % 16)
                assertTrue(maxOf(c.width, c.height) <= p.longEdge && minOf(c.width, c.height) <= p.shortEdge)
            }
        }
    }

    @Test
    fun profilesMatchPrd() {
        assertEquals(4_000_000, QualityProfile.SMOOTH.maxBitrateBps)
        assertEquals(8_000_000, QualityProfile.BALANCED.maxBitrateBps)
        assertEquals(15_000_000, QualityProfile.HD.maxBitrateBps)
        assertEquals(60, QualityProfile.GAME.fps)
        assertEquals(false, QualityProfile.GAME.avSync)
        assertEquals("1080p · 30 帧 · 最高 8 Mbps", QualityProfile.BALANCED.summary)
    }

    @Test
    fun latencyGrades() {
        assertEquals(LatencyGrade.EXCELLENT, LatencyGrade.of(68))
        assertEquals(LatencyGrade.GOOD, LatencyGrade.of(100))
        assertEquals(LatencyGrade.GOOD, LatencyGrade.of(150))
        assertEquals(LatencyGrade.FAIR, LatencyGrade.of(196))
        assertEquals(LatencyGrade.POOR, LatencyGrade.of(312))
    }

    @Test
    fun latencyGradeHysteresis() {
        val t = LatencyGradeTracker()
        assertEquals(LatencyGrade.EXCELLENT, t.update(99, 0))
        assertEquals(LatencyGrade.EXCELLENT, t.update(101, 100))
        assertEquals(LatencyGrade.EXCELLENT, t.update(99, 200))
        assertEquals(LatencyGrade.EXCELLENT, t.update(101, 300))
        assertEquals(LatencyGrade.EXCELLENT, t.update(101, 1200))
        assertEquals(LatencyGrade.GOOD, t.update(101, 1300))
    }

    @Test
    fun autoQualityDowngradesAfterSustainedBadNetwork() {
        val p = AutoQualityPolicy()
        val bad = NetworkSample(availableOutgoingBitrateBps = 1_000_000, packetLossPercent = 0.0, rttMs = 10)
        val good = NetworkSample(availableOutgoingBitrateBps = 20_000_000, packetLossPercent = 0.0, rttMs = 10)
        repeat(4) { assertNull(p.onSample(QualityProfile.BALANCED, true, bad)) }
        assertNull(p.onSample(QualityProfile.BALANCED, true, good))
        repeat(4) { assertNull(p.onSample(QualityProfile.BALANCED, true, bad)) }
        assertEquals(QualityProfile.SMOOTH, p.onSample(QualityProfile.BALANCED, true, bad))
        assertNull(p.onSample(QualityProfile.SMOOTH, true, bad))
        repeat(10) { assertNull(p.onSample(QualityProfile.HD, false, bad)) }
    }

    @Test
    fun dataChannelMessagesRoundTrip() {
        val msgs = listOf(
            DcMessage.SenderStats(7600, 30, 1920, 1080, rttMs = 8, latencyMs = 68, profile = QualityProfile.BALANCED),
            DcMessage.ReceiverStats(jitterBufferMs = 40.0, decodeMs = 6.5, framesDecoded = 100, hardwareDecoder = true),
            DcMessage.SetQuality(QualityProfile.SMOOTH),
            DcMessage.AppSwitched(false),
            DcMessage.ProtectedContent(true),
            DcMessage.Audio(AudioState.NO_PERMISSION),
        )
        for (m in msgs) assertEquals(m, DcMessage.decodeOrNull(DcMessage.encode(m)))
        assertTrue(DcMessage.encode(DcMessage.SetQuality(QualityProfile.HD)).contains("\"t\":\"SET_QUALITY\""))
        assertNull(DcMessage.decodeOrNull("{\"t\":\"???\"}"))
    }
}
