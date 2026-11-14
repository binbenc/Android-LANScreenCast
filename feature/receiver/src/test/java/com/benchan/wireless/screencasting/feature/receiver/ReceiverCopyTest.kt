package com.benchan.wireless.screencasting.feature.receiver

import androidx.compose.ui.unit.IntSize
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.feature.receiver.session.ReceiverCopy
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
        assertEquals("哔哩哔哩", ReceiverCopy.dlnaPeerLabel("Dalvik/2.1.0 (Linux; Android 14) BiliBili/7.50"))
        assertEquals("爱奇艺", ReceiverCopy.dlnaPeerLabel("IQIYI-Android/15.1"))
        assertEquals("Kodi", ReceiverCopy.dlnaPeerLabel("Kodi/20.2 (Linux; Android 11)"))
        // 未知产品名（工具 / 库）一律用通用描述（E7）
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("curl/8.5.0"))
        assertEquals(ReceiverCopy.DLNA_UNKNOWN_APP, ReceiverCopy.dlnaPeerLabel("SomeTool/1.0"))
    }

    @Test
    fun castCodeHiddenWhenPortFallsBack() {
        val base = StandbyInfo("客厅电视", "192.168.1.8", WscProtocol.PREFERRED_PORT, null, "wsc://x", true, true, false, false)
        assertNull(base.copy(lanAvailable = false).castCodeUnavailableReason)
        val portReason = base.copy(port = 40123).castCodeUnavailableReason
        assertNotNull(portReason)
        assertTrue(portReason!!.contains("端口"))
        val netReason = base.copy(castCodeNetworkSupported = false).castCodeUnavailableReason
        assertNotNull(netReason)
        assertNotEquals(portReason, netReason)
    }

    @Test
    fun versionNoticeSaysWhoMustUpdate() {
        assertTrue(ReceiverCopy.versionMismatchNotice("小张的手机", peerVersion = 0, localVersion = 2).contains("在 小张的手机 上更新"))
        assertTrue(ReceiverCopy.versionMismatchNotice("小张的手机", peerVersion = 5, localVersion = 2).startsWith("本机"))
        assertTrue(ReceiverCopy.versionMismatchNotice("", peerVersion = 0, localVersion = 2).contains("对方设备"))
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
        assertTrue(PlaybackCopy.firstFrameHint(isTv = true).contains("确定键"))
        assertTrue(PlaybackCopy.firstFrameHint(isTv = false).contains("轻点"))
        assertNotEquals(PlaybackCopy.overlayCloseHint(true), PlaybackCopy.overlayCloseHint(false))
    }
}
