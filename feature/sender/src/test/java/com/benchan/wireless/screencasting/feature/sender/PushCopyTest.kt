package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushEndInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushItemInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.ProtocolInfo
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushCopyTest {
    private val video = PushItemInfo(2, MediaKind.VIDEO, remote = false, title = "海边.mp4", mime = "video/mp4")
    private val url = PushItemInfo(0, MediaKind.VIDEO, remote = true, title = "index.m3u8", mime = null)

    @Test
    fun failureCardsGiveANextStepAndNoErrorCodes() {
        val f = PushCopy.failure(PushError.UNSUPPORTED_FORMAT, "卧室电视", video, ownApp = false)
        assertEquals(PushCopy.Action.TRANSCODE, f.primary)
        assertEquals(PushCopy.Action.INSTALL_APP, f.tertiary)
        assertEquals(PushCopy.Action.USE_MIRROR, PushCopy.failure(PushError.UNSUPPORTED_FORMAT, "书房盒子", video, ownApp = true).tertiary)
        // 网址 / 音乐不给转换
        assertEquals(PushCopy.Action.CHANGE_FILE, PushCopy.failure(PushError.UNSUPPORTED_FORMAT, "卧室电视", url, false).primary)
        assertEquals(PushCopy.Action.EDIT_URL, PushCopy.failure(PushError.URL_FAILED, "卧室电视", url, false).primary)
        assertEquals("书房盒子 上选择了拒绝", PushCopy.failure(PushError.REJECTED, "书房盒子", video, true).title)
        PushError.entries.forEach { e ->
            val c = PushCopy.failure(e, "卧室电视", video, false)
            assertFalse(Regex("\\b[4-7]\\d\\d\\b").containsMatchIn(c.title + c.detail))
        }
    }

    @Test
    fun endPageCopy() {
        val info = PushEndInfo(PushEndReason.STOPPED_ON_TV, 1, 30_000, false, 2_892_000, 3, 12, 0, 0)
        assertEquals("电视已停止播放", PushCopy.endTitle(info, "卧室电视").first)
        assertEquals("视频 3 个 · 照片 12 张", PushCopy.endSummary(info))
        assertEquals("与 卧室电视 的连接已断开", PushCopy.endTitle(info.copy(reason = PushEndReason.DEVICE_LOST), "卧室电视").first)
        assertEquals("48:12", PushCopy.time(2_892_000))
        assertEquals("1:00:00", PushCopy.time(3_600_000))
        assertEquals("—", PushCopy.time(null))
        assertEquals("12 分 48 秒", PushCopy.spokenTime(768_000))
    }

    @Test
    fun transcodeOfferNumbers() {
        assertEquals("不到 1 分钟", PushCopy.minutes(40_000))
        assertEquals("约 6 分钟", PushCopy.minutes(350_000))
        assertNull(PushCopy.minutes(null))
        assertEquals("1.4 GB", PushCopy.size(1_503_238_553))
        assertEquals("820 MB", PushCopy.size(820L * 1024 * 1024))
        val t = TranscodeUi(0, TranscodeUi.Phase.OFFER, hdr = true, codecUnsupported = true, videoCodec = "video/hevc")
        assertEquals("“烟花.mp4”是手机相机的高效格式（HEVC · HDR），卧室电视 没有表示支持这种格式。", PushCopy.transcodeReason(t, "卧室电视", "烟花.mp4"))
        assertEquals("DTS", PushCopy.codecName("audio/vnd.dts"))
    }

    @Test
    fun subtitlesAndUrlInput() {
        assertEquals("视频 · 播完自动播下一个", PushCopy.itemSubtitle(video, 12))
        assertEquals("音乐 · 3 / 8 · 播完自动播下一个", PushCopy.itemSubtitle(video.copy(kind = MediaKind.AUDIO), 8))
        assertEquals("照片", PushCopy.itemSubtitle(video.copy(kind = MediaKind.IMAGE), 8))
        assertEquals("推送视频、照片、音乐、网址 · 海信", PushCopy.dlnaSubtitle(false, false, "海信"))
        assertEquals("只能推送音乐", PushCopy.dlnaSubtitle(true, false, null))
        assertEquals("正在推送", PushCopy.dlnaSubtitle(false, true, "海信"))
        assertTrue(PushCopy.isPushableUrl(" https://cdn.example.com/a.m3u8 "))
        assertFalse(PushCopy.isPushableUrl("ftp://x/a.mp4"))
        assertFalse(PushCopy.isPushableUrl("https://"))
        assertEquals("按扩展名识别：视频", PushCopy.urlKindHint("https://x/a.mp4"))
        assertEquals("按视频推送", PushCopy.urlKindHint("https://www.example.com/watch?v=1"))
        assertTrue(PushCopy.isAudioOnly(ProtocolInfo.parseList("http-get:*:audio/mpeg:*")))
        assertFalse(PushCopy.isAudioOnly(ProtocolInfo.parseList("http-get:*:audio/mpeg:*,http-get:*:video/mp4:*")))
        assertFalse(PushCopy.isAudioOnly(null))
    }

    @Test
    fun round1EndPagesAreHonest() {
        val base = PushEndInfo(PushEndReason.USER_STOPPED, 0, null, false, 50_000, 1, 0, 0, 0)
        // 失败后点“结束推送”：如实写没有成功（EMU-5）
        assertEquals("推送没有成功", PushCopy.endTitle(base.copy(failure = PushError.UNREACHABLE), "书房盒子").first)
        assertEquals("已结束推送", PushCopy.endTitle(base, "书房盒子").first)
        assertEquals("已播放完毕", PushCopy.endTitle(base.copy(reason = PushEndReason.LIST_FINISHED), "卧室电视").first)
        assertEquals("播放列表已放完", PushCopy.endTitle(base.copy(reason = PushEndReason.LIST_FINISHED, videoCount = 3), "卧室电视").first)
        assertEquals("电视放不了列表里的内容", PushCopy.endTitle(base.copy(reason = PushEndReason.ALL_FAILED), "卧室电视").first)
        assertEquals("没能开始推送", PushCopy.endTitle(base.copy(reason = PushEndReason.START_FAILED, failure = PushError.NO_NETWORK), "卧室电视").first)
        assertTrue(base.copy(videoCount = 0, urlCount = 1).urlOnly)
        // 20 秒超时：[重试][换一个文件]（UX P2-15）
        assertEquals(PushCopy.Action.CHANGE_FILE, PushCopy.failure(PushError.START_TIMEOUT, "卧室电视", video, false).secondary)
        // 只有 HEVC / HDR 才说“手机相机的高效格式”（PM P2-9）
        val vp9 = TranscodeUi(0, TranscodeUi.Phase.OFFER, codecUnsupported = true, videoCodec = "video/x-vnd.on2.vp9")
        assertFalse(PushCopy.transcodeReason(vp9, "卧室电视", "a.webm").contains("手机相机"))
    }
}
