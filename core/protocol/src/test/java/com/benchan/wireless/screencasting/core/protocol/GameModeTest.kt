package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.quality.GameHint
import com.benchan.wireless.screencasting.core.protocol.quality.GameHintQueue
import com.benchan.wireless.screencasting.core.protocol.quality.GameHintType
import com.benchan.wireless.screencasting.core.protocol.quality.GameModeMonitor
import com.benchan.wireless.screencasting.core.protocol.quality.GameSample
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyCause
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameModeTest {

    @Test
    fun hintQueuePriorityOncePerSessionAndInvalidate() {
        val q = GameHintQueue()
        assertTrue(q.offer(GameHint.PoorNetwork))
        assertTrue(q.offer(GameHint.LowBattery(18)))
        // 同时成立时只显示优先级最高的一张（⑦ 高于 ⑨）
        assertEquals(GameHintType.POOR_NETWORK, q.current?.type)
        assertTrue(q.offer(GameHint.SwitchFailed(QualityProfile.BALANCED)))
        assertEquals(GameHintType.SWITCH_FAILED, q.current?.type)
        q.dismiss(GameHintType.SWITCH_FAILED)
        assertEquals(GameHintType.POOR_NETWORK, q.current?.type)
        // 网络恢复：未看的 ⑦ 直接丢弃
        q.invalidate(GameHintType.POOR_NETWORK)
        assertEquals(GameHintType.LOW_BATTERY, q.current?.type)
        // 每类每次投屏最多一次
        assertFalse(q.offer(GameHint.PoorNetwork))
        q.dismiss(GameHintType.LOW_BATTERY)
        assertNull(q.current)
        q.reset()
        assertTrue(q.offer(GameHint.PoorNetwork))
        // 只有 ② ③ 是 warningContainer
        assertTrue(GameHint.Thermal.warning)
        assertTrue(GameHint.FpsDropped(true).warning)
        assertFalse(GameHint.LegacyReceiver.warning)
    }

    @Test
    fun switchFailedAndNextTimeAreRepeatable() {
        // 产品拍板：①④ 是操作结果，每次都提示
        val q = GameHintQueue()
        assertTrue(q.offer(GameHint.SwitchFailed(QualityProfile.BALANCED)))
        q.dismiss(GameHintType.SWITCH_FAILED)
        assertTrue(q.offer(GameHint.SwitchFailed(QualityProfile.HD)))
        assertEquals(GameHint.SwitchFailed(QualityProfile.HD), q.current)
        assertTrue(q.offer(GameHint.NextTime))
        q.dismiss(GameHintType.NEXT_TIME)
        assertTrue(q.offer(GameHint.NextTime))
    }

    @Test
    fun latencyUnmetCauseNeedsEvidence() {
        val base = GameSample(latencyMs = 140, senderFps = 60, receiverFps = 59, receiverDecodeMs = 8.0, receiverLowLatency = true, captureFps = 60, rttMs = 6)
        // 解码与网络都不慢：只给数字，不给原因（不把“解码不慢”当成“网络差”）
        val m = GameModeMonitor()
        var at = 0
        var found: GameModeMonitor.Finding? = null
        for (i in 1..40) {
            val f = m.onSample(base, 60)
            if (f != null && found == null) { found = f; at = i }
        }
        assertEquals(GameModeMonitor.Finding.LatencyUnmet(140, null), found)
        assertTrue("at=$at", at in 15..20)
        // 网络确实偏差 → NETWORK
        val net = GameModeMonitor()
        val f1 = (1..40).firstNotNullOfOrNull { net.onSample(base.copy(rttMs = 80), 60) }
        assertEquals(LatencyCause.NETWORK, (f1 as GameModeMonitor.Finding.LatencyUnmet).cause)
        val loss = GameModeMonitor()
        val f2 = (1..40).firstNotNullOfOrNull { loss.onSample(base.copy(lossPercent = 5.0), 60) }
        assertEquals(LatencyCause.NETWORK, (f2 as GameModeMonitor.Finding.LatencyUnmet).cause)
        // 解码慢 / 不支持低延迟 → DECODER
        val dec = GameModeMonitor()
        val f3 = (1..40).firstNotNullOfOrNull { dec.onSample(base.copy(receiverDecodeMs = 55.0, rttMs = 80), 60) }
        assertEquals(LatencyCause.DECODER, (f3 as GameModeMonitor.Finding.LatencyUnmet).cause)
        val noLl = GameModeMonitor()
        val f4 = (1..40).firstNotNullOfOrNull { noLl.onSample(base.copy(receiverLowLatency = false), 60) }
        assertEquals(LatencyCause.DECODER, (f4 as GameModeMonitor.Finding.LatencyUnmet).cause)
        // 旧版接收端不判定
        val legacy = GameModeMonitor()
        repeat(60) { assertTrue(legacy.onSample(base, 60, checkLatency = false).let { it == null || it == GameModeMonitor.Finding.FullRate }) }
    }

    @Test
    fun latencyRecoveredAfterUnmet() {
        val m = GameModeMonitor()
        val slow = GameSample(140, 60, 59, 8.0, true, captureFps = 60)
        val unmet = (1..40).firstNotNullOfOrNull { m.onSample(slow, 60) }
        assertTrue(unmet is GameModeMonitor.Finding.LatencyUnmet)
        val ok = slow.copy(latencyMs = 80)
        val rec = (1..30).firstNotNullOfOrNull { m.onSample(ok, 60).takeIf { f -> f !is GameModeMonitor.Finding.FullRate } }
        assertEquals(GameModeMonitor.Finding.LatencyRecovered, rec)
    }

    @Test
    fun goodLatencyNeverReported() {
        val m = GameModeMonitor()
        val ok = GameSample(85, 60, 58, 6.0, true, captureFps = 60)
        repeat(120) { val f = m.onSample(ok, 60); assertTrue(f == null || f == GameModeMonitor.Finding.FullRate) }
    }

    @Test
    fun lowContentFrameRateIsNotFallingBehind() {
        // 画面静止 / 大厅 / 30 帧游戏：采集、发出、显示都低，但彼此一致 → 不判定、不记 30 帧（PM 走查 P0-2）
        val m = GameModeMonitor()
        repeat(120) {
            assertNull(m.onSample(GameSample(90, senderFps = 5, receiverFps = 5, receiverDecodeMs = 6.0, receiverLowLatency = true, captureFps = 5), 60))
        }
        val g30 = GameModeMonitor()
        repeat(120) {
            assertNull(g30.onSample(GameSample(90, senderFps = 30, receiverFps = 29, receiverDecodeMs = 6.0, receiverLowLatency = true, captureFps = 30), 60))
        }
    }

    @Test
    fun receiverAndSenderFallingBehindAttributed() {
        // 发出 60、电视只显示 38 → 电视跟不上
        val tv = GameModeMonitor()
        val f = (1..30).firstNotNullOfOrNull { tv.onSample(GameSample(90, senderFps = 60, receiverFps = 38, receiverDecodeMs = 10.0, receiverLowLatency = true, captureFps = 60), 60) }
        assertEquals(GameModeMonitor.Finding.FpsLow(receiverCause = true), f)
        // 内容 60、只编出 38 → 手机跟不上
        val phone = GameModeMonitor()
        val f2 = (1..30).firstNotNullOfOrNull { phone.onSample(GameSample(90, senderFps = 38, receiverFps = 38, receiverDecodeMs = 10.0, receiverLowLatency = true, captureFps = 60), 60) }
        assertEquals(GameModeMonitor.Finding.FpsLow(receiverCause = false), f2)
        // 已降为 30 帧时不再做 60 帧判定
        val capped = GameModeMonitor()
        repeat(60) { assertNull(capped.onSample(GameSample(90, 30, 20, 10.0, true, captureFps = 60), 30)) }
    }

    @Test
    fun fullRateDetectedForClearingMemory() {
        val m = GameModeMonitor()
        val full = GameSample(85, senderFps = 60, receiverFps = 58, receiverDecodeMs = 6.0, receiverLowLatency = true, captureFps = 60)
        val f = (1..60).firstNotNullOfOrNull { m.onSample(full, 60) }
        assertEquals(GameModeMonitor.Finding.FullRate, f)
    }
}
