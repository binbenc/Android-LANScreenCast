package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.core.protocol.message.Offer
import com.benchan.wireless.screencasting.core.protocol.message.WscCodec
import com.benchan.wireless.screencasting.core.protocol.message.toMessage
import com.benchan.wireless.screencasting.core.protocol.quality.AutoQualityDecision
import com.benchan.wireless.screencasting.core.protocol.quality.AutoQualityPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.QualityChange
import com.benchan.wireless.screencasting.core.protocol.quality.QualitySwitchPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.ReceiverTuning
import com.benchan.wireless.screencasting.core.protocol.quality.SwitchTimeoutAction
import com.benchan.wireless.screencasting.core.protocol.quality.CaptureSize
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGradeTracker
import com.benchan.wireless.screencasting.core.protocol.quality.NetworkSample
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals("1080p · 30 fps · up to 8 Mbps", QualityProfile.BALANCED.summary(java.util.Locale.US))
        assertEquals("High", QualityProfile.HD.label(java.util.Locale.UK))
        // 中文环境选另一套文案（不断言中文字面量）
        assertNotEquals(QualityProfile.HD.label(java.util.Locale.US), QualityProfile.HD.label(java.util.Locale.CHINA))
        assertEquals("Poor", LatencyGrade.POOR.label(java.util.Locale.US))
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
        assertEquals(AutoQualityDecision.Downgrade(QualityProfile.SMOOTH), p.onSample(QualityProfile.BALANCED, true, bad))
        assertNull(p.onSample(QualityProfile.SMOOTH, true, bad))
        repeat(10) { assertNull(p.onSample(QualityProfile.HD, false, bad)) }
    }

    @Test
    fun autoQualityNeverDowngradesGameMode() {
        // F18：游戏档永不降成“流畅”，持续 10 秒差网络只给出“提示”决定
        val p = AutoQualityPolicy()
        val bad = NetworkSample(availableOutgoingBitrateBps = 500_000, packetLossPercent = 8.0, rttMs = 200)
        repeat(9) { assertNull(p.onSample(QualityProfile.GAME, true, bad)) }
        assertEquals(AutoQualityDecision.PoorNetworkInGame, p.onSample(QualityProfile.GAME, true, bad))
        repeat(60) {
            val d = p.onSample(QualityProfile.GAME, true, bad)
            assertTrue(d == null || d == AutoQualityDecision.PoorNetworkInGame)
        }
        // 游戏档的“网络较差”提示与“自动调节”开关无关（只提示、不改档，PM 走查 P2-3）
        val off = AutoQualityPolicy()
        repeat(9) { assertNull(off.onSample(QualityProfile.GAME, false, bad)) }
        assertEquals(AutoQualityDecision.PoorNetworkInGame, off.onSample(QualityProfile.GAME, false, bad))
        // 游戏档带宽条件按档位下限：高于 1 Mbps 不算差
        val bw = AutoQualityPolicy()
        val ok = NetworkSample(availableOutgoingBitrateBps = 2_000_000, packetLossPercent = 0.0, rttMs = 10)
        repeat(30) { assertNull(bw.onSample(QualityProfile.GAME, true, ok)) }
    }

    @Test
    fun receiverTuningAndRebuildRules() {
        assertEquals(ReceiverTuning.GAME, ReceiverTuning.of(QualityProfile.GAME))
        for (p in listOf(QualityProfile.SMOOTH, QualityProfile.BALANCED, QualityProfile.HD, null)) {
            assertEquals(ReceiverTuning.NORMAL, ReceiverTuning.of(p))
        }
        // 游戏档的缓冲必须低于普通档，且启用低延迟解码器（PRD 8.1.3 E）
        assertTrue(ReceiverTuning.GAME.playoutDelayMaxMs < ReceiverTuning.NORMAL.playoutDelayMaxMs)
        assertTrue(ReceiverTuning.GAME.lowLatencyDecoder)
        assertEquals(false, ReceiverTuning.NORMAL.lowLatencyDecoder)
        // 只有切进 / 切出游戏档需要重建媒体连接
        for (a in QualityProfile.entries) for (b in QualityProfile.entries) {
            assertEquals("$a -> $b", a.lowLatency != b.lowLatency, QualityProfile.needsMediaRebuild(a, b))
        }
        assertEquals(QualityProfile.GAME, QualityProfile.fromWire("GAME"))
        assertNull(QualityProfile.fromWire("ULTRA"))
        assertNull(QualityProfile.fromWire(null))
    }

    @Test
    fun qualitySwitchPlan() {
        val g = QualityProfile.GAME
        val b = QualityProfile.BALANCED
        // 普通档之间：SET_QUALITY 即时生效（无论对方版本）
        assertEquals(QualityChange.SET_QUALITY, QualitySwitchPolicy.plan(b, QualityProfile.HD, receiverSupportsRebuild = false, streaming = true))
        // 切进 / 切出游戏档：新接收端 + 正在投屏 → 重建
        assertEquals(QualityChange.REBUILD, QualitySwitchPolicy.plan(b, g, receiverSupportsRebuild = true, streaming = true))
        assertEquals(QualityChange.REBUILD, QualitySwitchPolicy.plan(g, QualityProfile.SMOOTH, receiverSupportsRebuild = true, streaming = true))
        // 旧接收端：只改分辨率帧率
        assertEquals(QualityChange.SET_QUALITY_LEGACY, QualitySwitchPolicy.plan(b, g, receiverSupportsRebuild = false, streaming = true))
        // 重连中：先改编码参数，恢复后补做重建
        assertEquals(QualityChange.DEFER_REBUILD, QualitySwitchPolicy.plan(b, g, receiverSupportsRebuild = true, streaming = false))
        // 超时：第一次回退，回退再超时进入重连
        assertEquals(SwitchTimeoutAction.ROLLBACK, QualitySwitchPolicy.onTimeout(wasRollback = false))
        assertEquals(SwitchTimeoutAction.RECONNECT, QualitySwitchPolicy.onTimeout(wasRollback = true))
        assertTrue(QualitySwitchPolicy.TIMEOUT_MS <= 5_000L)
    }

    @Test
    fun offerCarriesProfileAndRebuild() {
        val m = Offer("v=0", profile = QualityProfile.GAME.name, rebuild = true).toMessage(3, "s")
        val d = WscCodec.decode(WscCodec.encode(m)).payload as Offer
        assertEquals(QualityProfile.GAME, d.qualityProfile)
        assertTrue(d.rebuild)
        // 旧版发送端：没有新字段 → 普通档、不重建
        val legacy = WscCodec.decode("{\"t\":\"OFFER\",\"id\":1,\"v\":1,\"p\":{\"sdp\":\"v=0\"}}").payload as Offer
        assertNull(legacy.qualityProfile)
        assertEquals(false, legacy.rebuild)
        // 未知档位不让整条 OFFER 失败
        val future = WscCodec.decode("{\"t\":\"OFFER\",\"id\":1,\"v\":1,\"p\":{\"sdp\":\"v=0\",\"profile\":\"ULTRA\"}}").payload as Offer
        assertNull(future.qualityProfile)
        assertEquals(ReceiverTuning.NORMAL, ReceiverTuning.of(future.qualityProfile))
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
