package com.benchan.wireless.screencasting.feature.receiver

import androidx.compose.ui.unit.IntSize
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.feature.receiver.session.ControlNotice
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverCopy
import com.benchan.wireless.screencasting.feature.receiver.session.UiText
import com.benchan.wireless.screencasting.feature.receiver.session.pausedText
import com.benchan.wireless.screencasting.feature.receiver.session.uiText
import com.benchan.wireless.screencasting.feature.receiver.session.StandbyInfo
import com.benchan.wireless.screencasting.feature.receiver.ui.PlaybackCopy
import com.benchan.wireless.screencasting.feature.receiver.ui.screenSaverOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverCopyTest {

    @Test
    fun dlnaPeerLabelHidesSystemAgents() {
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("Linux/4.9 UPnP/1.0 Portable SDK for UPnP devices/1.6"))
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7)"))
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("okhttp/4.12.0"))
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel(null))
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("  "))
        assertEquals(uiText(R.string.rx_app_bilibili), ReceiverCopy.dlnaPeerLabel("Dalvik/2.1.0 (Linux; Android 14) BiliBili/7.50"))
        assertEquals(uiText(R.string.rx_app_iqiyi), ReceiverCopy.dlnaPeerLabel("IQIYI-Android/15.1"))
        assertEquals(UiText.Raw("Kodi"), ReceiverCopy.dlnaPeerLabel("Kodi/20.2 (Linux; Android 11)"))
        // 未知产品名（工具 / 库）一律用通用描述（E7）
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("curl/8.5.0"))
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("SomeTool/1.0"))
    }

    @Test
    fun castCodeHiddenWhenPortFallsBack() {
        val base = StandbyInfo("Living room TV", "192.168.1.8", WscProtocol.PREFERRED_PORT, null, "wsc://x", true, true, false, false)
        assertNull(base.copy(lanAvailable = false).castCodeUnavailableReason)
        val portReason = base.copy(port = 40123).castCodeUnavailableReason
        assertNotNull(portReason)
        assertEquals(uiText(R.string.rx_cast_code_port_busy), portReason)
        val netReason = base.copy(castCodeNetworkSupported = false).castCodeUnavailableReason
        assertNotNull(netReason)
        assertNotEquals(portReason, netReason)
    }

    @Test
    fun versionNoticeSaysWhoMustUpdate() {
        assertEquals(uiText(R.string.rx_notice_peer_outdated, "Pixel"), ReceiverCopy.versionMismatchNotice("Pixel", peerVersion = 0, localVersion = 2))
        assertEquals(uiText(R.string.rx_notice_local_outdated, "Pixel"), ReceiverCopy.versionMismatchNotice("Pixel", peerVersion = 5, localVersion = 2))
        // 名字为空时用“对方设备”资源作为参数
        assertEquals(
            uiText(R.string.rx_notice_peer_outdated, uiText(R.string.rx_peer_device)),
            ReceiverCopy.versionMismatchNotice(" ", peerVersion = 0, localVersion = 2),
        )
    }

    @Test
    fun screenSaverStaysInsideSafeArea() {
        val container = IntSize(1920, 1080)
        val content = IntSize(600, 200)
        repeat(200) { seed ->
            val o = screenSaverOffset(seed, container, content, padX = 96, padY = 54)
            assertTrue(o.x >= 96 && o.x + content.width <= container.width - 96)
            assertTrue(o.y >= 54 && o.y + content.height <= container.height - 54)
        }
        // 内容比可用区域还大时贴着安全区左上角，不出现负坐标
        val big = screenSaverOffset(1, IntSize(500, 300), IntSize(800, 400), 96, 54)
        assertEquals(96, big.x)
        assertEquals(54, big.y)
    }

    @Test
    fun playbackHintsFollowDeviceType() {
        assertEquals(uiText(R.string.rx_hint_tv), PlaybackCopy.firstFrameHint(isTv = true))
        assertEquals(uiText(R.string.rx_hint_touch), PlaybackCopy.firstFrameHint(isTv = false))
        assertEquals(uiText(R.string.rx_hint_tv_control_entry), PlaybackCopy.firstFrameHint(isTv = true, controlEntry = true))
        assertEquals(uiText(R.string.rx_hint_tv_controlling), PlaybackCopy.firstFrameHint(isTv = true, controlEntry = true, controlling = true))
        assertNotEquals(PlaybackCopy.overlayCloseHint(true), PlaybackCopy.overlayCloseHint(false))
    }

    @Test
    fun qualityLabelMarksMissingLowLatency() {
        assertEquals(UiText.Raw("—"), PlaybackCopy.quality(0, null, game = false))
        assertEquals(UiText.Raw("720p"), PlaybackCopy.quality(720, null, game = false))
        assertEquals(
            uiText(R.string.rx_quality_with_resolution, "720p", uiText(R.string.rx_badge_game_mode)),
            PlaybackCopy.quality(720, QualityProfile.GAME, game = true),
        )
        val legacy = PlaybackCopy.quality(0, QualityProfile.GAME, game = false, senderLegacy = true) as UiText.Res
        assertEquals(R.string.rx_quality_low_latency_off, legacy.id)
        val next = PlaybackCopy.quality(0, QualityProfile.GAME, game = false, senderLegacy = false) as UiText.Res
        assertEquals(R.string.rx_quality_low_latency_next, next.id)
        assertEquals(PlaybackCopy.GAME_MODE_BADGE, uiText(R.string.rx_badge_game_mode))
        assertNotEquals(PlaybackCopy.badgeA11y(PlaybackCopy.GAME_MODE_BADGE), PlaybackCopy.badgeA11y(PlaybackCopy.FPS_CAPPED_BADGE))
    }

    @Test
    fun deniedNoticeUsesPluralCooldown() {
        val n = ControlNotice.Denied(30)
        assertEquals(UiText.Plural(R.plurals.rx_ctl_denied_cooldown, 30, listOf(30)), n.detail)
        // 暂停原因作为嵌套参数
        val paused = ControlNotice.Paused(ControlReason.RECONNECTING)
        assertEquals(uiText(R.string.rx_ctl_paused_with_reason, pausedText(ControlReason.RECONNECTING)), paused.title)
        assertNotNull(paused.detail)
        assertNull(ControlNotice.Paused(null).detail)
    }
}
