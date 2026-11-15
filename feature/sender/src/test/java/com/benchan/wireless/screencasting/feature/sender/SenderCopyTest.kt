package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.protocol.message.ErrorCode
import com.benchan.wireless.screencasting.feature.sender.session.CastTarget
import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.feature.sender.ui.EndedAction
import com.benchan.wireless.screencasting.feature.sender.ui.SenderCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderCopyTest {
    private val target = CastTarget("id", "客厅电视", "192.168.1.8", 8443, DiscoverySource.MDNS)

    @Test
    fun readyToCaptureDoesNotStopTheProjectionService() {
        // A1：服务拿到授权后首先观察到的是 ReadyToCapture，不能据此 stopSelf
        assertFalse(SenderState.ReadyToCapture(target).isTerminal)
        assertFalse(SenderState.Starting(target).isTerminal)
        assertFalse(SenderState.Connecting(target).isTerminal)
        assertTrue(SenderState.Idle.isTerminal)
        assertTrue(SenderState.Ended(target, EndReason.USER_STOPPED).isTerminal)
    }

    @Test
    fun errorCodesNeverLeakIntoUserText() {
        val codes = ErrorCode.entries.map { it.name }
        for (reason in EndReason.entries) {
            for (failure in ConnectFailure.entries + listOf(null)) {
                val text = SenderCopy.endedSnackbar(reason, "客厅电视", failure) ?: continue
                codes.forEach { assertFalse("$reason → $text", text.contains(it)) }
                assertFalse(text.contains("WSC"))
            }
        }
    }

    @Test
    fun connectFailuresAreDistinguishedAndOfferDiagnostics() {
        val texts = ConnectFailure.entries.map { SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, "客厅电视", it) }.toSet()
        assertEquals(ConnectFailure.entries.size, texts.size)
        assertEquals(EndedAction.DIAGNOSTICS, SenderCopy.endedAction(EndReason.CONNECT_FAILED))
    }

    @Test
    fun errorsCarryNextStep() {
        assertTrue(SenderCopy.endedSnackbar(EndReason.BUSY, "客厅电视")!!.contains("结束当前投屏"))
        assertTrue(SenderCopy.endedSnackbar(EndReason.CAST_CODE_WRONG, null)!!.contains("新码"))
        assertEquals(EndedAction.RETYPE_CAST_CODE, SenderCopy.endedAction(EndReason.CAST_CODE_WRONG))
        assertEquals(EndedAction.RESCAN, SenderCopy.endedAction(EndReason.QR_EXPIRED))
        assertNull(SenderCopy.endedAction(EndReason.REJECTED))
        // 整页说明的原因不出 Snackbar
        assertNull(SenderCopy.endedSnackbar(EndReason.RECONNECT_FAILED, "客厅电视"))
    }

    @Test
    fun castCodeNetworkHint() {
        val noLan = SenderCopy.castCodeNetworkHint(hasLan = false, prefixSupported = false)
        assertNotNull(noLan)
        assertTrue(noLan!!.contains("Wi‑Fi"))
        val unsupported = SenderCopy.castCodeNetworkHint(hasLan = true, prefixSupported = false)
        assertNotNull(unsupported)
        assertNotEquals(noLan, unsupported)
        assertFalse(unsupported!!.contains("/24"))
        assertNull(SenderCopy.castCodeNetworkHint(hasLan = true, prefixSupported = true))
    }

    @Test
    fun secondRoundCopy() {
        assertTrue(SenderCopy.endedSnackbar(EndReason.RECEIVER_NO_DISPLAY, "客厅电视")!!.contains("客厅电视 上没有打开“等待投屏”页面"))
        // PIN 路径超时不归因到电视
        assertFalse(SenderCopy.endedSnackbar(EndReason.PIN_TIMEOUT, "客厅电视")!!.contains("客厅电视"))
        assertTrue(SenderCopy.endedSnackbar(EndReason.AUTH_FAILED, null)!!.contains("移除对方"))
        assertNotEquals(
            SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, null, ConnectFailure.QR_PAIR_TIMEOUT),
            SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, null, ConnectFailure.CODE_PAIR_TIMEOUT),
        )
        assertTrue(SenderCopy.isLongText(SenderCopy.endedSnackbar(EndReason.BUSY, "客厅电视")!!))
        assertFalse(SenderCopy.isLongText("已恢复"))
    }

    @Test
    fun gameModeCopyNeverPromisesNumbersAndHonoursAudio() {
        val game = com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile.GAME
        assertTrue(SenderCopy.qualityDescription(game).contains("声音可能比画面稍晚"))
        // 声音关闭时不提声音晚
        assertFalse(SenderCopy.qualityDescription(game, audioOn = false).contains("声音"))
        assertTrue(SenderCopy.qualityDescription(game, fpsCapped = com.benchan.wireless.screencasting.core.protocol.quality.FpsCapReason.THERMAL).contains("30 帧"))
        assertTrue(SenderCopy.qualityDescription(game, fpsCapped = com.benchan.wireless.screencasting.core.protocol.quality.FpsCapReason.REMEMBERED).contains("再点一次"))
        // 界面不出现目标延迟数字（产品拍板）
        assertFalse(SenderCopy.qualityDescription(game).contains("100"))
        val unmet = SenderCopy.hint(com.benchan.wireless.screencasting.core.protocol.quality.GameHint.LatencyUnmet(140, null))
        assertFalse(unmet.title.contains("100"))
        assertNull(unmet.detail)
        assertTrue(SenderCopy.measuredLatency(85, inGame = false).contains("游戏模式"))
        assertEquals("正在切换到游戏模式，电视画面会停一下", SenderCopy.qualitySwitching(game))
        assertTrue(SenderCopy.measuredLatency(85).startsWith("这台电视上实测约 85"))
        val hints = listOf(
            com.benchan.wireless.screencasting.core.protocol.quality.GameHint.SwitchFailed(com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile.BALANCED),
            com.benchan.wireless.screencasting.core.protocol.quality.GameHint.PoorNetwork,
            com.benchan.wireless.screencasting.core.protocol.quality.GameHint.NextTime,
            com.benchan.wireless.screencasting.core.protocol.quality.GameHint.LegacyReceiver,
        )
        assertEquals("切换没有成功，已保持“均衡”", SenderCopy.hint(hints[0]).title)
        assertEquals("切到流畅", SenderCopy.hint(hints[1]).action)
        assertTrue(SenderCopy.hint(hints[2]).detail!!.startsWith("低延迟将在下次投屏生效"))
        assertEquals("去更新", SenderCopy.hint(hints[3]).action)
        // 界面文字不出现 AirPlay
        hints.forEach { assertFalse(SenderCopy.hint(it).title.contains("AirPlay")) }
    }
}
