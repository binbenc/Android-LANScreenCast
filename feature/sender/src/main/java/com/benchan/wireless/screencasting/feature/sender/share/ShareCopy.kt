package com.benchan.wireless.screencasting.feature.sender.share

import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.feature.sender.ui.uiPlural
import com.benchan.wireless.screencasting.feature.sender.ui.uiText
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind

/**
 * 分享页文案（交互稿第 14 章 S01–S08）：只决定用哪条资源、带哪些参数（values = 英文默认，values-zh = 中文），
 * 由界面层解析。纯逻辑，单测覆盖。固定文案直接在界面里用 `R.string.share_*`。
 */
object ShareCopy {

    /** 读屏用：把“ · ”换成本语言的逗号（各引擎对间隔号的读法不一，UX P2-6）。 */
    fun spoken(text: String, separator: String): String = text.replace(" · ", separator)

    /** “3 个视频 · 3 张照片 · 2 首音乐”的各段；界面用“ · ”连接。 */
    fun summary(kinds: List<MediaKind>): List<UiText> {
        val v = kinds.count { it == MediaKind.VIDEO }
        val p = kinds.count { it == MediaKind.IMAGE }
        val a = kinds.count { it == MediaKind.AUDIO }
        return listOfNotNull(
            v.takeIf { it > 0 }?.let { uiPlural(R.plurals.share_count_videos, it) },
            p.takeIf { it > 0 }?.let { uiPlural(R.plurals.share_count_photos, it) },
            a.takeIf { it > 0 }?.let { uiPlural(R.plurals.share_count_music, it) },
        )
    }

    fun ignored(n: Int): Pair<UiText, UiText> = uiPlural(R.plurals.share_ignored_title, n) to uiText(R.string.share_ignored_detail)

    fun unreadable(n: Int, reason: UnreadableReason): Pair<UiText, UiText> = uiPlural(R.plurals.share_unreadable_title, n) to unreadableDetail(reason)

    fun unreadableDetail(reason: UnreadableReason): UiText = when (reason) {
        UnreadableReason.NO_PERMISSION -> uiText(R.string.share_unreadable_no_permission)
        UnreadableReason.GENERIC -> uiText(R.string.share_unreadable_generic)
    }

    fun noSpaceDetail(itemCount: Int, required: Long, available: Long): UiText {
        val need = PushCopy.size(required) ?: "—"
        val have = PushCopy.size(available.coerceAtLeast(0)) ?: "—"
        return if (itemCount <= 1) uiText(R.string.share_no_space_detail_single, need, have)
        else uiPlural(R.plurals.share_no_space_detail_multi, itemCount, itemCount, need, have)
    }

    fun notReady(n: Int): UiText = uiPlural(R.plurals.share_not_ready, n)

    /** 推送中某项复制失败（替代通用的“手机收回了读取授权”，PM P1-8）。 */
    fun copyFailure(f: CopyFailure): Pair<UiText, UiText> = when (f) {
        CopyFailure.NO_SPACE -> uiText(R.string.share_copy_no_space_title) to uiText(R.string.share_copy_no_space_detail)
        CopyFailure.TOO_LARGE -> uiText(R.string.share_copy_too_large_title) to uiText(R.string.share_copy_too_large_detail)
        CopyFailure.READ_FAILED -> uiText(R.string.share_copy_read_failed_title) to uiText(R.string.share_copy_read_failed_detail)
        CopyFailure.GONE -> uiText(R.string.share_copy_gone_title) to uiText(R.string.share_copy_gone_detail)
    }

    fun onlyFirst(n: Int, bytes: Long): UiText = uiPlural(R.plurals.share_only_first, n, n, PushCopy.size(bytes) ?: "—")

    // —— 准备进度（S01 / S02 / S03）——

    /** 0~100；大小未知时 null。 */
    fun percent(copied: Long, total: Long?): Int? = total?.takeIf { it > 0 }?.let { ((copied * 100) / it).toInt().coerceIn(0, 100) }

    /** “正在准备文件 43%” / “正在准备文件 · 已读取 320 MB”。 */
    fun preparing(copied: Long, total: Long?): UiText {
        val p = percent(copied, total)
        return if (p != null) uiText(R.string.share_preparing_percent, p) else uiText(R.string.share_preparing_read, PushCopy.size(copied) ?: "0 MB")
    }

    /** “1.2 GB / 2.8 GB”（与语言无关）/ “已读取 320 MB”。 */
    fun bytes(copied: Long, total: Long?): UiText =
        if (total != null && total > 0) UiText.Raw("${PushCopy.size(copied) ?: "0 MB"} / ${PushCopy.size(total)}")
        else uiText(R.string.share_bytes_read, PushCopy.size(copied) ?: "0 MB")

    /** 剩余时间只按档变化（交互稿 S02：不逐秒跳；超过 60 秒改写“文件较大…可以先离开”）。 */
    fun remaining(ms: Long?): UiText? {
        ms ?: return null
        return when {
            ms <= 10_000 -> uiText(R.string.share_remaining_10s)
            ms <= 20_000 -> uiText(R.string.share_remaining_20s)
            ms <= 30_000 -> uiText(R.string.share_remaining_30s)
            ms <= 60_000 -> uiText(R.string.share_remaining_1min)
            else -> ((ms + 59_999) / 60_000).toInt().let { uiPlural(R.plurals.share_remaining_long, it) }
        }
    }

    /** 读屏每 25% 播报一次（交互稿 S01 读屏）。 */
    fun announceStep(percent: Int?): Int? = percent?.let { (it / 25) * 25 }

    fun firstUseNote(transferVideo: Boolean): Pair<UiText, UiText> = uiText(R.string.share_first_use_title) to
        uiText(if (transferVideo) R.string.share_first_use_transfer else R.string.share_first_use_copy)

    fun webMirroring(tv: String): UiText = uiText(R.string.share_web_mirroring, tv)

    // —— 设备列表 ——

    fun directAudioOnly(name: Any): UiText = uiText(R.string.share_direct_audio_only, name)
    fun directMissed(name: Any): UiText = uiText(R.string.share_direct_missed, name)
    fun directFinding(name: Any): UiText = uiText(R.string.share_direct_finding, name)

    // —— 冲突（S07）——
    fun bannerMirroring(tv: Any): Pair<UiText, UiText> = uiText(R.string.share_banner_mirroring_title, tv) to uiText(R.string.share_banner_mirroring_detail)

    /** [status] 是推送状态（String 或 [UiText]）。 */
    fun bannerPushing(tv: String, status: Any): Pair<UiText, UiText> = uiText(R.string.share_banner_pushing_title, tv) to uiText(R.string.share_banner_pushing_detail, status)

    fun stopMirrorTitle(to: String, connecting: Boolean): UiText =
        uiText(if (connecting) R.string.share_stop_mirror_title_connecting else R.string.share_stop_mirror_title, to)

    fun stopMirrorBody(from: String?): UiText = from?.let { uiText(R.string.share_stop_mirror_body_named, it) } ?: uiText(R.string.share_stop_mirror_body)

    fun stopMirrorConfirm(connecting: Boolean): UiText =
        uiText(if (connecting) R.string.share_stop_mirror_confirm_connecting else R.string.share_stop_mirror_confirm)

    fun replaceTitle(tv: String): UiText = uiText(R.string.share_replace_title, tv)

    /** [newSummary] 是已解析的摘要（“3 个视频 · 2 张照片” / “网址”）。 */
    fun replaceBody(current: String?, index: Int, count: Int, newSummary: String): UiText {
        val now: Any = current?.let {
            if (count > 1) uiText(R.string.share_replace_now_multi, it, index + 1, count) else uiText(R.string.share_replace_now, it)
        } ?: ""
        return uiText(R.string.share_replace_body, now, newSummary)
    }

    fun switchTitle(from: Any, to: String): UiText = uiText(R.string.share_switch_title, from, to)

    // —— 准备与连接（S02）/ 已开始（S03）——
    fun preparingFirst(multi: Boolean): UiText = uiText(if (multi) R.string.share_preparing_first else R.string.share_preparing_file)
    fun connectStep(tv: Any): UiText = uiText(R.string.share_connect_step, tv)
    fun started(tv: Any, photo: Boolean): UiText = uiText(if (photo) R.string.share_started_photo else R.string.share_started_play, tv)
    fun restPreparing(n: Int, percent: Int?): UiText =
        if (percent != null) uiPlural(R.plurals.share_rest_preparing_percent, n, n, percent) else uiPlural(R.plurals.share_rest_preparing, n)
    fun allReady(n: Int): UiText = uiPlural(R.plurals.share_all_ready, n)
    fun itemSkipped(index: Int, gone: Boolean): UiText =
        uiText(if (gone) R.string.share_item_skipped_gone else R.string.share_item_skipped_interrupted, index + 1)
}
