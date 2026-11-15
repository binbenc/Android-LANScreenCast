package com.benchan.wireless.screencasting.feature.sender

import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord
import com.benchan.wireless.screencasting.feature.sender.share.DirectShareShortcuts
import com.benchan.wireless.screencasting.feature.sender.share.ReadMode
import com.benchan.wireless.screencasting.feature.sender.share.ShareClassifier
import com.benchan.wireless.screencasting.feature.sender.share.ShareCopy
import com.benchan.wireless.screencasting.feature.sender.share.ShareLimits
import com.benchan.wireless.screencasting.feature.sender.share.ShareReadPolicy
import com.benchan.wireless.screencasting.feature.sender.share.ShareText
import com.benchan.wireless.screencasting.feature.sender.share.SpacePlanner
import com.benchan.wireless.screencasting.feature.sender.share.UrlKind
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLogicTest {
    private val mb = 1024L * 1024
    private val gb = 1024L * mb

    // —— 网址提取（PRD 8.1.2 C）——

    @Test
    fun extractsFirstUrlFromVideoAppText() {
        assertEquals("https://b23.tv/AbC12xY", ShareText.firstHttpUrl("【搞笑视频合集】https://b23.tv/AbC12xY 复制打开"))
        assertEquals("https://v.example.com/a.mp4", ShareText.firstHttpUrl("看这个：https://v.example.com/a.mp4，还有 http://x.com/b"))
        assertEquals("http://h.cn/x.m3u8?t=1", ShareText.firstHttpUrl("链接（http://h.cn/x.m3u8?t=1）"))
        assertEquals("https://e.com/v.mp4", ShareText.firstHttpUrl("https://e.com/v.mp4."))
        assertEquals("HTTPS://E.COM/v", ShareText.firstHttpUrl("HTTPS://E.COM/v"))
    }

    @Test
    fun noUrlInText() {
        assertNull(ShareText.firstHttpUrl("只是一段文字"))
        assertNull(ShareText.firstHttpUrl(""))
        assertNull(ShareText.firstHttpUrl(null))
        assertNull(ShareText.firstHttpUrl("ftp://e.com/a.mp4"))
        assertNull(ShareText.firstHttpUrl("https://"))
    }

    @Test
    fun urlKindOnlyByExtension() {
        assertEquals(UrlKind.MEDIA, ShareText.urlKind("https://e.com/a.mp4?sig=1"))
        assertEquals(UrlKind.MEDIA, ShareText.urlKind("http://e.com/live/index.m3u8"))
        assertEquals(UrlKind.MEDIA, ShareText.urlKind("http://e.com/song.mp3"))
        // 短链接 / 网页一律视为网页地址（App 不访问、不展开短链接，决策 #33）
        assertEquals(UrlKind.WEB_PAGE, ShareText.urlKind("https://b23.tv/AbC12xY"))
        assertEquals(UrlKind.WEB_PAGE, ShareText.urlKind("https://www.example.com/video/123"))
        assertEquals(UrlKind.WEB_PAGE, ShareText.urlKind("https://e.com/page.html"))
    }

    // —— MIME 分类（PRD 8.1.2 C“类型判定”）——

    @Test
    fun providerMimeWins() {
        assertEquals(MediaKind.VIDEO, ShareClassifier.classify("video/mp4", "*/*", "a.jpg"))
        assertEquals(MediaKind.IMAGE, ShareClassifier.classify("image/heic", "*/*", null))
        assertEquals(MediaKind.AUDIO, ShareClassifier.classify("audio/flac", null, null))
        // 提供方明确说是 PDF：不看扩展名
        assertNull(ShareClassifier.classify("application/pdf", "*/*", "x.mp4"))
    }

    @Test
    fun octetStreamFallsBackToIntentThenExtension() {
        assertEquals(MediaKind.VIDEO, ShareClassifier.classify("application/octet-stream", "*/*", "movie.MKV"))
        assertEquals(MediaKind.VIDEO, ShareClassifier.classify("application/octet-stream", "video/*", "noext"))
        assertEquals(MediaKind.AUDIO, ShareClassifier.classify(null, null, "a.mp3"))
        assertNull(ShareClassifier.classify("application/octet-stream", "*/*", "doc.pdf"))
        assertNull(ShareClassifier.classify(null, null, null))
    }

    @Test
    fun localPlaylistsAreUnsupported() {
        assertNull(ShareClassifier.classify("application/vnd.apple.mpegurl", "*/*", "x.m3u8"))
        assertNull(ShareClassifier.classify("application/octet-stream", "*/*", "x.m3u8"))
        assertNull(ShareClassifier.classify("video/*", "video/*", "index.m3u8"))
    }

    @Test
    fun mimeForPush() {
        assertEquals("video/x-matroska", ShareClassifier.mimeFor(MediaKind.VIDEO, "application/octet-stream", "a.mkv"))
        assertEquals("image/jpeg", ShareClassifier.mimeFor(MediaKind.IMAGE, "image/jpg", "x"))
        assertEquals("video/mp4", ShareClassifier.mimeFor(MediaKind.VIDEO, "video/*", null))
    }

    // —— 读取策略（决策 #42）——

    @Test
    fun photosAndMusicAlwaysCopied() {
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.IMAGE, "content", 1 * mb, true, false, false))
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.AUDIO, "content", 5 * mb, true, false, false))
    }

    @Test
    fun videoCopiedByDefaultUntilDeviceVerification() {
        // 用户拍板：真机验证前视频默认复制
        assertFalse(ShareReadPolicy.TRANSFER_VIDEO_BY_DEFAULT)
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.VIDEO, "content", 500 * mb, true, sourceNeedsCopy = false, transferEnabled = false))
        // 超过单个复制上限且可随机读取：只能转交
        assertEquals(ReadMode.TRANSFER, ShareReadPolicy.decide(MediaKind.VIDEO, "content", 6 * gb, true, sourceNeedsCopy = true, transferEnabled = false))
    }

    @Test
    fun videoTransferWhenEnabledAndCertain() {
        assertEquals(ReadMode.TRANSFER, ShareReadPolicy.decide(MediaKind.VIDEO, "content", 500 * mb, true, false, transferEnabled = true))
        // 云盘管道 / 取不到大小 / 来源以前失败过 → 仍复制
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.VIDEO, "content", null, false, false, true))
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.VIDEO, "content", 500 * mb, false, false, true))
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.VIDEO, "content", 500 * mb, true, true, true))
        assertEquals(ReadMode.COPY, ShareReadPolicy.decide(MediaKind.VIDEO, "file", 500 * mb, true, false, true))
    }

    // —— 空间规划（交互稿 S05 ⑧⑨，Q15-C）——

    @Test
    fun spaceFitsAll() {
        val p = SpacePlanner.plan(listOf(100 * mb, 0L, 200 * mb), availableBytes = 2 * gb)
        assertTrue(p.fitsAll)
        assertEquals(300 * mb, p.requiredBytes)
    }

    @Test
    fun spaceFitsPrefixInShareOrder() {
        // 可用 1.1 GB - 余量 500 MB = 约 600 MB 预算
        val avail = 1100 * mb
        val p = SpacePlanner.plan(listOf(300 * mb, 250 * mb, 400 * mb, 10 * mb), avail)
        assertFalse(p.fitsAll)
        assertEquals(2, p.fitCount)
        assertEquals(550 * mb, p.fitBytes)
        // 按顺序累加，不跳着挑：第 3 项放不下后，第 4 项（10 MB）也不算
        assertEquals(4, p.total)
    }

    @Test
    fun spaceNothingFits() {
        val p = SpacePlanner.plan(listOf(4 * gb), 1100 * mb)
        assertEquals(0, p.fitCount)
        assertFalse(SpacePlanner.streamMayContinue(ShareLimits.RESERVE_BYTES))
        assertTrue(SpacePlanner.streamMayContinue(ShareLimits.RESERVE_BYTES + 1))
    }

    @Test
    fun totalCopyCap() {
        val p = SpacePlanner.plan(List(5) { 2 * gb }, availableBytes = 100 * gb)
        assertEquals(4, p.fitCount) // 8 GB 上限
    }

    // —— 文案 ——

    @Test
    fun copyTexts() {
        assertEquals("3 个视频 · 2 张照片", ShareCopy.summary(listOf(MediaKind.VIDEO, MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.IMAGE, MediaKind.VIDEO)))
        assertEquals("2 首音乐", ShareCopy.summary(listOf(MediaKind.AUDIO, MediaKind.AUDIO)))
        assertEquals("正在准备文件 43%", ShareCopy.preparing(43, 100))
        assertTrue(ShareCopy.preparing(320 * mb, null).startsWith("正在准备文件 · 已读取"))
        assertEquals("约剩 20 秒", ShareCopy.remaining(15_000))
        assertTrue(ShareCopy.remaining(170_000)!!.contains("约剩 3 分钟"))
        assertEquals(50, ShareCopy.announceStep(63))
        assertEquals(null, ShareCopy.percent(1, null))
        assertEquals("3 个视频，2 张照片", ShareCopy.spoken("3 个视频 · 2 张照片"))
        // 复制失败给具体原因（不说“收回了读取授权”）
        assertTrue(ShareCopy.copyFailure(com.benchan.wireless.screencasting.feature.sender.share.CopyFailure.NO_SPACE).first.contains("空间不足"))
        assertTrue(ShareCopy.copyFailure(com.benchan.wireless.screencasting.feature.sender.share.CopyFailure.TOO_LARGE).second.contains("4 GB"))
        assertFalse(ShareCopy.ELDER_NEW_TV.contains("DLNA"))
        assertFalse(ShareCopy.firstUseNote(transferVideo = false).second.contains("视频由电视直接"))
    }

    // —— Direct Share 选设备（PRD 8.1.2 H）——

    @Test
    fun directSharePicksRecentSuccessfulConfirmed() {
        val now = 100L * 24 * 3600 * 1000
        val day = 24L * 3600 * 1000
        val recs = listOf(
            DlnaRendererRecord("a", "A", confirmedAt = 1, lastSuccessAt = now - 1 * day),
            DlnaRendererRecord("b", "B", confirmedAt = 1, lastSuccessAt = now - 40 * day), // 超过 30 天
            DlnaRendererRecord("c", "C", confirmedAt = null, lastSuccessAt = now - day), // 没确认过
            DlnaRendererRecord("d", "D", confirmedAt = 1, lastSuccessAt = null, lastUsedAt = now), // 推过但没成功
            DlnaRendererRecord("e", "E", confirmedAt = 1, lastSuccessAt = now - 2 * day, audioOnly = true),
            DlnaRendererRecord("f", "F", confirmedAt = 1, lastSuccessAt = now - 3 * day),
            DlnaRendererRecord("g", "G", confirmedAt = 1, lastSuccessAt = now - 4 * day),
        )
        val picks = DirectShareShortcuts.pick(recs, now)
        assertEquals(listOf("a", "e", "f"), picks.map { it.udn })
        assertTrue(picks[1].audioOnly)
        assertTrue(picks.none { it.ownApp })
        // 快捷方式 ID 是随机令牌：格式不对 / 不是本应用前缀一律解析不出（伪造落回选设备页）
        assertEquals("0123456789abcdef0123", DirectShareShortcuts.parseToken("push:0123456789abcdef0123"))
        assertNull(DirectShareShortcuts.parseToken("push:uuid:abc-def"))
        assertNull(DirectShareShortcuts.parseToken("push:short"))
        assertNull(DirectShareShortcuts.parseToken("other"))
        assertNull(DirectShareShortcuts.parseToken(null))
    }
}
