package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.protocol.quality.FpsCapReason
import com.benchan.wireless.screencasting.core.protocol.quality.GameHint
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyCause
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.feature.sender.session.CastTarget
import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import com.benchan.wireless.screencasting.feature.sender.session.isTerminal
import com.benchan.wireless.screencasting.feature.sender.ui.EndedAction
import com.benchan.wireless.screencasting.feature.sender.ui.SenderCopy
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.labelRes
import com.benchan.wireless.screencasting.feature.sender.ui.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文案选择逻辑（资源 ID + 参数）。不断言具体语言的字面量：英文 / 中文由资源文件决定。 */
class SenderCopyTest {
    private val target = CastTarget("id", "Living room TV", "192.168.1.8", 8443, DiscoverySource.MDNS)

    private fun UiText?.res(): UiText.Res = this as UiText.Res

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
    fun deviceNameIsPassedRawAndFallsBackToTvResource() {
        val t = SenderCopy.endedSnackbar(EndReason.BUSY, "Living room TV").res()
        assertEquals(R.string.sender_ended_busy, t.id)
        assertEquals(listOf(UiText.Raw("Living room TV")), t.args)
        val fallback = SenderCopy.endedSnackbar(EndReason.REMOTE_CLOSED, null).res()
        assertEquals(listOf(UiText.Res(R.string.sender_tv_fallback)), fallback.args)
    }

    @Test
    fun connectFailuresAreDistinguishedAndOfferDiagnostics() {
        val ids = ConnectFailure.entries.map { SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, "TV", it).res().id }.toSet()
        assertEquals(ConnectFailure.entries.size, ids.size)
        // 未知失败按“连不上”处理
        assertEquals(R.string.sender_fail_unreachable, SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, "TV", null).res().id)
        assertEquals(EndedAction.DIAGNOSTICS, SenderCopy.endedAction(EndReason.CONNECT_FAILED))
    }

    @Test
    fun errorsCarryNextStep() {
        assertEquals(R.string.sender_ended_cast_code_wrong, SenderCopy.endedSnackbar(EndReason.CAST_CODE_WRONG, null).res().id)
        assertEquals(EndedAction.RETYPE_CAST_CODE, SenderCopy.endedAction(EndReason.CAST_CODE_WRONG))
        assertEquals(EndedAction.RESCAN, SenderCopy.endedAction(EndReason.QR_EXPIRED))
        assertNull(SenderCopy.endedAction(EndReason.REJECTED))
        // 整页说明的原因不出 Snackbar
        assertNull(SenderCopy.endedSnackbar(EndReason.RECONNECT_FAILED, "TV"))
        assertNull(SenderCopy.endedSnackbar(EndReason.SCREEN_LOCKED, "TV"))
        assertNull(SenderCopy.endedSnackbar(EndReason.USER_STOPPED, "TV"))
    }

    @Test
    fun everySnackbarReasonHasItsOwnMessage() {
        val reasons = EndReason.entries.filter { it != EndReason.CONNECT_FAILED }
        val ids = reasons.mapNotNull { SenderCopy.endedSnackbar(it, "TV")?.res()?.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun castCodeNetworkHint() {
        val noLan = SenderCopy.castCodeNetworkHint(hasLan = false, prefixSupported = false)
        assertEquals(UiText.Res(R.string.sender_cast_code_no_wifi), noLan)
        val unsupported = SenderCopy.castCodeNetworkHint(hasLan = true, prefixSupported = false)
        assertEquals(UiText.Res(R.string.sender_cast_code_unsupported_network), unsupported)
        assertNull(SenderCopy.castCodeNetworkHint(hasLan = true, prefixSupported = true))
    }

    @Test
    fun pinTimeoutIsNotAttributedToTheTv() {
        // PIN 路径超时不归因到电视：不带设备名参数
        assertTrue(SenderCopy.endedSnackbar(EndReason.PIN_TIMEOUT, "TV").res().args.isEmpty())
        assertNotEquals(
            SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, null, ConnectFailure.QR_PAIR_TIMEOUT),
            SenderCopy.endedSnackbar(EndReason.CONNECT_FAILED, null, ConnectFailure.CODE_PAIR_TIMEOUT),
        )
        assertTrue(SenderCopy.isLongText("x".repeat(21)))
        assertFalse(SenderCopy.isLongText("ok"))
    }

    @Test
    fun gameModeDescriptionHonoursAudioAndFpsCap() {
        val game = QualityProfile.GAME
        val withAudio = SenderCopy.qualityDescription(game).res()
        assertEquals(R.string.sender_quality_desc_game_audio_late, withAudio.id)
        assertEquals(listOf(UiText.Res(R.string.sender_quality_desc_game)), withAudio.args)
        // 声音关闭时不提声音晚
        assertEquals(UiText.Res(R.string.sender_quality_desc_game), SenderCopy.qualityDescription(game, audioOn = false))
        assertEquals(UiText.Res(R.string.sender_quality_desc_game_capped), SenderCopy.qualityDescription(game, audioOn = false, fpsCapped = FpsCapReason.THERMAL))
        assertEquals(UiText.Res(R.string.sender_quality_desc_game_remembered), SenderCopy.qualityDescription(game, audioOn = false, fpsCapped = FpsCapReason.REMEMBERED))
        // 普通档：档位摘要作为参数（1080p · 30 fps · 8 Mbps）
        val balanced = SenderCopy.qualityDescription(QualityProfile.BALANCED).res()
        assertEquals(R.string.sender_quality_desc_balanced, balanced.id)
        assertEquals(listOf(UiText.Res(R.string.sender_quality_summary, listOf(1080, 30, 8))), balanced.args)
    }

    @Test
    fun gameModeSwitchingAndHints() {
        val game = QualityProfile.GAME
        assertEquals(UiText.Res(R.string.sender_quality_switching_game), SenderCopy.qualitySwitching(game))
        assertEquals(UiText.Res(R.string.sender_quality_switching), SenderCopy.qualitySwitching(QualityProfile.HD))
        assertEquals(UiText.Res(R.string.sender_quality_switched_game), SenderCopy.qualitySwitched(game))
        assertEquals(UiText.Res(R.string.sender_quality_switched, listOf(UiText.Res(R.string.sender_quality_hd))), SenderCopy.qualitySwitched(QualityProfile.HD))
        assertEquals(UiText.Res(R.string.sender_measured_latency_game, listOf(85)), SenderCopy.measuredLatency(85, inGame = false))
        assertEquals(UiText.Res(R.string.sender_measured_latency, listOf(85)), SenderCopy.measuredLatency(85))

        val failed = SenderCopy.hint(GameHint.SwitchFailed(QualityProfile.BALANCED))
        assertEquals(UiText.Res(R.string.sender_hint_switch_failed, listOf(UiText.Res(R.string.sender_quality_balanced))), failed.title)
        assertEquals(R.string.sender_hint_poor_network_action, SenderCopy.hint(GameHint.PoorNetwork).action)
        assertEquals(R.string.sender_hint_legacy_receiver_action, SenderCopy.hint(GameHint.LegacyReceiver).action)
        assertNull(SenderCopy.hint(GameHint.NextTime).action)
        // 解码和网络都不明显偏慢：只给数字，不猜原因
        val unmet = SenderCopy.hint(GameHint.LatencyUnmet(140, null))
        assertEquals(UiText.Res(R.string.sender_hint_latency_unmet, listOf(140)), unmet.title)
        assertNull(unmet.detail)
        assertEquals(UiText.Res(R.string.sender_hint_latency_decoder), SenderCopy.hint(GameHint.LatencyUnmet(140, LatencyCause.DECODER)).detail)
    }

    @Test
    fun qualityLabelsAreDistinct() {
        assertEquals(QualityProfile.entries.size, QualityProfile.entries.map { it.labelRes }.toSet().size)
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 10_000_000_000L
        assertEquals(UiText.Res(R.string.sender_time_just_now), relativeTime(now - 30_000, now))
        assertEquals(UiText.Plural(R.plurals.sender_time_minutes_ago, 5), relativeTime(now - 5 * 60_000, now))
        assertEquals(UiText.Plural(R.plurals.sender_time_hours_ago, 2), relativeTime(now - 2 * 3_600_000, now))
        assertEquals(UiText.Plural(R.plurals.sender_time_days_ago, 3), relativeTime(now - 3 * 86_400_000L, now))
    }
}
