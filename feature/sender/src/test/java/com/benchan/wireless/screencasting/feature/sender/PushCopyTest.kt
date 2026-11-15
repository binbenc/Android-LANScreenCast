package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.feature.sender.ui.UiText
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只测“选哪条资源、带什么参数”，不断言具体语言的文字（英文 / 中文由资源文件提供）。 */
class PushCopyTest {
    private val video = PushItemInfo(2, MediaKind.VIDEO, remote = false, title = "beach.mp4", mime = "video/mp4")
    private val url = PushItemInfo(0, MediaKind.VIDEO, remote = true, title = "index.m3u8", mime = null)

    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())
    private fun dot(a: UiText, b: UiText) = res(R.string.push_join_dot, a, b)
    private fun plural(id: Int, n: Int) = UiText.Plural(id, n, listOf(n))

    @Test
    fun failureCardsGiveANextStep() {
        val f = PushCopy.failure(PushError.UNSUPPORTED_FORMAT, "Bedroom TV", video, ownApp = false)
        assertEquals(PushCopy.Action.TRANSCODE, f.primary)
        assertEquals(PushCopy.Action.INSTALL_APP, f.tertiary)
        assertEquals(res(R.string.push_fail_format_detail_transcode, "Bedroom TV"), f.detail)
        assertEquals(PushCopy.Action.USE_MIRROR, PushCopy.failure(PushError.UNSUPPORTED_FORMAT, "Study box", video, ownApp = true).tertiary)
        // 网址 / 音乐不给转换
        val urlFail = PushCopy.failure(PushError.UNSUPPORTED_FORMAT, "Bedroom TV", url, false)
        assertEquals(PushCopy.Action.CHANGE_FILE, urlFail.primary)
        assertEquals(res(R.string.push_fail_format_detail, "Bedroom TV"), urlFail.detail)
        assertEquals(PushCopy.Action.EDIT_URL, PushCopy.failure(PushError.URL_FAILED, "Bedroom TV", url, false).primary)
        // 本应用接收端 vs 电视自带投屏的拒绝文案不同
        assertEquals(res(R.string.push_fail_rejected_own_title, "Study box"), PushCopy.failure(PushError.REJECTED, "Study box", video, true).title)
        assertEquals(res(R.string.push_fail_rejected_title, "Study box"), PushCopy.failure(PushError.REJECTED, "Study box", video, false).title)
        // 每种错误都给出下一步
        PushError.entries.forEach { e -> assertTrue(PushCopy.failure(e, "TV", video, false).primary.labelRes != 0) }
    }

    @Test
    fun endPageCopy() {
        val info = PushEndInfo(PushEndReason.STOPPED_ON_TV, 1, 30_000, false, 2_892_000, 3, 12, 0, 0)
        assertEquals(res(R.string.push_end_stopped_on_tv), PushCopy.endTitle(info, "Bedroom TV").first)
        assertEquals(dot(plural(R.plurals.push_count_video, 3), plural(R.plurals.push_count_photo, 12)), PushCopy.endSummary(info))
        assertNull(PushCopy.endSummary(info.copy(videoCount = 0, photoCount = 0)))
        assertEquals(res(R.string.push_end_device_lost, "Bedroom TV"), PushCopy.endTitle(info.copy(reason = PushEndReason.DEVICE_LOST), "Bedroom TV").first)
        assertEquals("48:12", PushCopy.time(2_892_000))
        assertEquals("1:00:00", PushCopy.time(3_600_000))
        assertEquals("—", PushCopy.time(null))
        // 读屏时长：12 分 48 秒（有小时时三段）
        assertEquals(
            res(R.string.push_join_space, plural(R.plurals.push_spoken_minutes, 12), plural(R.plurals.push_spoken_seconds, 48)),
            PushCopy.spokenTime(768_000),
        )
        assertEquals(plural(R.plurals.push_spoken_seconds, 5), PushCopy.spokenTime(5_000))
        assertEquals(res(R.string.push_unknown), PushCopy.spokenTime(null))
    }

    @Test
    fun transcodeOfferNumbers() {
        assertEquals(res(R.string.push_under_a_minute), PushCopy.minutes(40_000))
        assertEquals(plural(R.plurals.push_about_minutes, 6), PushCopy.minutes(350_000))
        assertNull(PushCopy.minutes(null))
        assertEquals(plural(R.plurals.push_minutes, 6), PushCopy.duration(350_000))
        assertEquals(res(R.string.push_remaining, plural(R.plurals.push_minutes, 6)), PushCopy.remaining(350_000))
        assertNull(PushCopy.remaining(null))
        assertEquals("1.4 GB", PushCopy.size(1_503_238_553))
        assertEquals("820 MB", PushCopy.size(820L * 1024 * 1024))
        val t = TranscodeUi(0, TranscodeUi.Phase.OFFER, hdr = true, codecUnsupported = true, videoCodec = "video/hevc")
        assertEquals(
            res(R.string.push_reason_camera_format, "fireworks.mp4", dot(UiText.Raw("HEVC"), UiText.Raw("HDR")), "Bedroom TV"),
            PushCopy.transcodeReason(t, "Bedroom TV", "fireworks.mp4"),
        )
        assertEquals(UiText.Raw("DTS"), PushCopy.codecName("audio/vnd.dts"))
        assertEquals(res(R.string.push_codec_dolby_vision), PushCopy.codecName("video/dolby-vision"))
    }

    @Test
    fun subtitlesAndUrlInput() {
        assertEquals(dot(res(R.string.push_kind_video), res(R.string.push_auto_next)), PushCopy.itemSubtitle(video, 12))
        assertEquals(
            dot(dot(res(R.string.push_kind_audio), UiText.Raw("3 / 8")), res(R.string.push_auto_next)),
            PushCopy.itemSubtitle(video.copy(kind = MediaKind.AUDIO), 8),
        )
        assertEquals(res(R.string.push_kind_image), PushCopy.itemSubtitle(video.copy(kind = MediaKind.IMAGE), 8))
        assertEquals(dot(res(R.string.push_kind_url), res(R.string.push_kind_video)), PushCopy.itemSubtitle(url, 1))
        assertEquals(dot(res(R.string.push_sub_all), UiText.Raw("Hisense")), PushCopy.dlnaSubtitle(false, false, "Hisense"))
        assertEquals(res(R.string.push_sub_audio_only), PushCopy.dlnaSubtitle(true, false, null))
        assertEquals(res(R.string.push_sub_pushing), PushCopy.dlnaSubtitle(false, true, "Hisense"))
        assertTrue(PushCopy.isPushableUrl(" https://cdn.example.com/a.m3u8 "))
        assertFalse(PushCopy.isPushableUrl("ftp://x/a.mp4"))
        assertFalse(PushCopy.isPushableUrl("https://"))
        assertEquals(res(R.string.push_url_hint_ext, res(R.string.push_kind_video)), PushCopy.urlKindHint("https://x/a.mp4"))
        assertEquals(res(R.string.push_url_hint_default), PushCopy.urlKindHint("https://www.example.com/watch?v=1"))
        assertTrue(PushCopy.isAudioOnly(ProtocolInfo.parseList("http-get:*:audio/mpeg:*")))
        assertFalse(PushCopy.isAudioOnly(ProtocolInfo.parseList("http-get:*:audio/mpeg:*,http-get:*:video/mp4:*")))
        assertFalse(PushCopy.isAudioOnly(null))
    }

    @Test
    fun round1EndPagesAreHonest() {
        val base = PushEndInfo(PushEndReason.USER_STOPPED, 0, null, false, 50_000, 1, 0, 0, 0)
        // 失败后点“结束推送”：如实写没有成功（EMU-5），说明行用失败卡标题
        val failed = PushCopy.endTitle(base.copy(failure = PushError.UNREACHABLE), "Study box")
        assertEquals(res(R.string.push_end_failed), failed.first)
        assertEquals(res(R.string.push_fail_unreachable_title, "Study box"), failed.second)
        assertEquals(res(R.string.push_end_user_stopped), PushCopy.endTitle(base, "Study box").first)
        assertEquals(res(R.string.push_end_item_finished), PushCopy.endTitle(base.copy(reason = PushEndReason.LIST_FINISHED), "Bedroom TV").first)
        assertEquals(res(R.string.push_end_list_finished), PushCopy.endTitle(base.copy(reason = PushEndReason.LIST_FINISHED, videoCount = 3), "Bedroom TV").first)
        assertEquals(res(R.string.push_end_all_failed), PushCopy.endTitle(base.copy(reason = PushEndReason.ALL_FAILED), "Bedroom TV").first)
        val noNet = PushCopy.endTitle(base.copy(reason = PushEndReason.START_FAILED, failure = PushError.NO_NETWORK), "Bedroom TV")
        assertEquals(res(R.string.push_end_start_failed), noNet.first)
        assertEquals(res(R.string.push_end_start_failed_no_network), noNet.second)
        assertEquals(
            res(R.string.push_end_start_failed_unreachable, "Bedroom TV"),
            PushCopy.endTitle(base.copy(reason = PushEndReason.START_FAILED), "Bedroom TV").second,
        )
        assertTrue(base.copy(videoCount = 0, urlCount = 1).urlOnly)
        // 20 秒超时：[重试][换一个文件]（UX P2-15）
        assertEquals(PushCopy.Action.CHANGE_FILE, PushCopy.failure(PushError.START_TIMEOUT, "Bedroom TV", video, false).secondary)
        // 只有 HEVC / HDR 才说“手机相机的高效格式”（PM P2-9）
        val vp9 = TranscodeUi(0, TranscodeUi.Phase.OFFER, codecUnsupported = true, videoCodec = "video/x-vnd.on2.vp9")
        val reason = PushCopy.transcodeReason(vp9, "Bedroom TV", "a.webm") as UiText.Res
        assertNotEquals(R.string.push_reason_camera_format, reason.id)
        assertEquals(res(R.string.push_reason_codec, "a.webm", UiText.Raw("VP9"), "Bedroom TV"), reason)
    }
}

/** 遥控页缩略图按剩余高度收缩：多项推送（胶片条）或大字号时更早改用行首小图，保证进度条 / 音量条首屏可见。 */
class PushRemoteLayoutTest {
    private fun h(viewport: Int, filmStrip: Boolean = false, font: Float = 1f, big: Boolean = false) =
        com.benchan.wireless.screencasting.feature.sender.ui.push.thumbnailHeight(
            androidx.compose.ui.unit.Dp(328f), androidx.compose.ui.unit.Dp(viewport.toFloat()), big, filmStrip, font,
        )

    @org.junit.Test
    fun thumbnailShrinksToAvailableRoom() {
        // 空间充足：16:9 原始高度
        org.junit.Assert.assertEquals(328f / (16f / 9f), h(900)!!.value, 0.01f)
        // 空间有限：只占余量
        org.junit.Assert.assertEquals(140f, h(440)!!.value, 0.01f)
        // 多一行胶片条：余量不足 → 行首小图
        org.junit.Assert.assertNull(h(440, filmStrip = true))
        // 150% 字号：其余内容变高，更早改用小图
        org.junit.Assert.assertNull(h(440, font = 1.5f))
        org.junit.Assert.assertNotNull(h(700, filmStrip = true, font = 1.5f))
    }
}
