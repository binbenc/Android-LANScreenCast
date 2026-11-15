package com.benchan.wireless.screencasting.feature.sender.ui.push

import androidx.annotation.StringRes
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.uiPlural
import com.benchan.wireless.screencasting.feature.sender.ui.uiText
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushEndInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushItemInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase

/**
 * 推送界面的文案（交互稿第 13 章 P01–P15）。纯逻辑，单测覆盖：只决定用哪条字符串资源和参数（[UiText]），
 * 由界面 / 通知按系统语言解析（values = 英文，values-zh = 中文）。界面不出现错误码。
 */
object PushCopy {
    @StringRes
    fun kindLabelRes(kind: MediaKind): Int = when (kind) {
        MediaKind.VIDEO -> R.string.push_kind_video
        MediaKind.AUDIO -> R.string.push_kind_audio
        MediaKind.IMAGE -> R.string.push_kind_image
    }

    fun kindLabel(kind: MediaKind): UiText = uiText(kindLabelRes(kind))

    /** 设备行副标题（P01）。 */
    fun dlnaSubtitle(audioOnly: Boolean, pushing: Boolean, disambiguation: String?): UiText {
        val base = uiText(
            when {
                pushing -> R.string.push_sub_pushing
                audioOnly -> R.string.push_sub_audio_only
                else -> R.string.push_sub_all
            },
        )
        return if (disambiguation != null && !pushing) listOf(base, UiText.Raw(disambiguation)).joinDot() else base
    }

    /** 列表顶部横幅第二行（P01）：“播放中 · 3 / 12”。 */
    fun bannerStatus(s: PushSessionState): UiText {
        val e = s.engine
        val status = when {
            s.transcode?.phase == TranscodeUi.Phase.RUNNING -> R.string.push_status_transcoding
            s.transcode?.phase == TranscodeUi.Phase.PAUSED_THERMAL -> R.string.push_status_transcode_paused
            e.phase == PushPhase.AWAITING_CHOICE -> R.string.push_status_awaiting_choice
            e.phase == PushPhase.PLAYING -> R.string.push_status_playing
            e.phase == PushPhase.PAUSED -> R.string.push_status_paused
            e.phase == PushPhase.SHOWING -> R.string.push_status_sent
            e.phase == PushPhase.FAILED -> R.string.push_status_failed
            else -> R.string.push_status_loading
        }
        return if (e.count > 1) listOf(uiText(status), counter(e.index, e.count)).joinDot() else uiText(status)
    }

    /** 遥控页状态点（P05/P06/P13）；null = 不显示。 */
    fun statusLabel(s: PushSessionState): UiText? {
        val e = s.engine
        val id = when {
            s.transcode?.phase == TranscodeUi.Phase.RUNNING || s.transcode?.phase == TranscodeUi.Phase.PAUSED_THERMAL -> R.string.push_status_transcoding
            s.transcode?.phase == TranscodeUi.Phase.FAILED -> R.string.push_status_failed
            e.phase == PushPhase.AWAITING_CHOICE -> R.string.push_status_awaiting_choice
            e.phase == PushPhase.PREPARING || e.phase == PushPhase.LOADING -> R.string.push_status_loading
            e.phase == PushPhase.PLAYING -> R.string.push_status_playing
            e.phase == PushPhase.PAUSED -> R.string.push_status_paused
            e.phase == PushPhase.SHOWING -> if (s.current?.served == true) R.string.push_status_sent else R.string.push_status_sending
            e.phase == PushPhase.FAILED -> R.string.push_status_failed
            else -> return null
        }
        return uiText(id)
    }

    /** “3 / 12”（与语言无关）。 */
    fun counter(index: Int, count: Int): UiText = UiText.Raw("${index + 1} / $count")

    /** 内容副标题：“视频 · 播完自动播下一个” / “音乐 · 2 / 8 · 播完自动播下一个”。 */
    fun itemSubtitle(item: PushItemInfo, count: Int): UiText {
        if (item.remote) return listOf(uiText(R.string.push_kind_url), kindLabel(item.kind)).joinDot()
        val parts = mutableListOf(kindLabel(item.kind))
        if (count > 1 && item.kind == MediaKind.AUDIO) parts += counter(item.index, count)
        if (count > 1 && item.kind != MediaKind.IMAGE) parts += uiText(R.string.push_auto_next)
        return parts.joinDot()
    }

    /** 失败卡（P13）。 */
    data class Failure(val title: UiText, val detail: UiText, val primary: Action, val secondary: Action?, val tertiary: Action? = null)

    enum class Action(@param:StringRes @get:StringRes val labelRes: Int) {
        TRANSCODE(R.string.push_action_transcode), CHANGE_FILE(R.string.push_action_change_file),
        INSTALL_APP(R.string.push_action_install_app), USE_MIRROR(R.string.push_action_use_mirror),
        RETRY(R.string.push_action_retry), OTHER_DEVICE(R.string.push_action_other_device), RESCAN(R.string.push_action_rescan),
        DIAGNOSTICS(R.string.push_action_diagnostics), EDIT_URL(R.string.push_action_edit_url), LOCAL_FILE(R.string.push_action_local_file),
        RESELECT(R.string.push_action_reselect), TRY_ORIGINAL(R.string.push_action_try_original), CANCEL(R.string.push_cancel),
    }

    fun failure(error: PushError, tv: String, item: PushItemInfo?, ownApp: Boolean): Failure {
        val localVideo = item != null && !item.remote && item.kind == MediaKind.VIDEO
        val third = if (ownApp) Action.USE_MIRROR else Action.INSTALL_APP
        return when (error) {
            PushError.UNSUPPORTED_FORMAT -> if (localVideo) {
                Failure(uiText(R.string.push_fail_format_title), uiText(R.string.push_fail_format_detail_transcode, tv), Action.TRANSCODE, Action.CHANGE_FILE, third)
            } else {
                Failure(uiText(R.string.push_fail_format_title), uiText(R.string.push_fail_format_detail, tv), Action.CHANGE_FILE, null, third)
            }
            PushError.REJECTED -> if (ownApp) {
                Failure(uiText(R.string.push_fail_rejected_own_title, tv), uiText(R.string.push_fail_rejected_own_detail), Action.RETRY, Action.OTHER_DEVICE)
            } else {
                Failure(uiText(R.string.push_fail_rejected_title, tv), uiText(R.string.push_fail_rejected_detail), Action.RETRY, Action.OTHER_DEVICE)
            }
            PushError.UNREACHABLE -> Failure(uiText(R.string.push_fail_unreachable_title, tv), uiText(R.string.push_fail_unreachable_detail), Action.RESCAN, Action.DIAGNOSTICS)
            PushError.URL_FAILED -> Failure(uiText(R.string.push_fail_url_title), uiText(R.string.push_fail_url_detail), Action.EDIT_URL, Action.LOCAL_FILE)
            PushError.START_TIMEOUT -> Failure(uiText(R.string.push_fail_timeout_title, tv), uiText(R.string.push_fail_timeout_detail), Action.RETRY, Action.CHANGE_FILE)
            PushError.NO_NETWORK -> Failure(uiText(R.string.push_fail_no_network_title), uiText(R.string.push_fail_no_network_detail), Action.DIAGNOSTICS, null)
            PushError.DECODE_FAILED -> Failure(uiText(R.string.push_fail_decode_title), uiText(R.string.push_fail_decode_detail), Action.CHANGE_FILE, null)
            PushError.FILE_UNREADABLE -> Failure(uiText(R.string.push_fail_unreadable_title), uiText(R.string.push_fail_unreadable_detail), Action.RESELECT, null)
            PushError.TRANSCODE_FAILED -> Failure(uiText(R.string.push_fail_transcode_title), uiText(R.string.push_fail_transcode_detail), Action.TRY_ORIGINAL, Action.CHANGE_FILE)
            PushError.NEEDS_TRANSCODE -> Failure(uiText(R.string.push_fail_needs_transcode_title, tv), uiText(R.string.push_fail_needs_transcode_detail), Action.TRANSCODE, Action.TRY_ORIGINAL)
        }
    }

    /** 结束页（P14）标题与说明。 */
    fun endTitle(info: PushEndInfo, tv: String): Pair<UiText, UiText> = when (info.reason) {
        // 失败后结束：如实说没有成功（第 1 轮 EMU-5）
        PushEndReason.USER_STOPPED -> info.failure?.let { uiText(R.string.push_end_failed) to failure(it, tv, null, false).title }
            ?: (uiText(R.string.push_end_user_stopped) to uiText(R.string.push_end_user_stopped_detail, tv))
        PushEndReason.STOPPED_ON_TV -> uiText(R.string.push_end_stopped_on_tv) to uiText(R.string.push_end_stopped_on_tv_detail, tv)
        PushEndReason.LIST_FINISHED -> uiText(if (info.itemCount > 1) R.string.push_end_list_finished else R.string.push_end_item_finished) to
            uiText(R.string.push_end_finished_detail)
        PushEndReason.INACTIVE -> uiText(R.string.push_end_inactive) to uiText(R.string.push_end_inactive_detail)
        PushEndReason.ALL_FAILED -> uiText(R.string.push_end_all_failed) to uiText(R.string.push_end_all_failed_detail, tv)
        PushEndReason.START_FAILED -> uiText(R.string.push_end_start_failed) to when (info.failure) {
            PushError.NO_NETWORK -> uiText(R.string.push_end_start_failed_no_network)
            else -> uiText(R.string.push_end_start_failed_unreachable, tv)
        }
        PushEndReason.TAKEN_OVER -> uiText(R.string.push_end_taken_over) to uiText(R.string.push_end_taken_over_detail, tv)
        PushEndReason.DEVICE_LOST -> uiText(R.string.push_end_device_lost, tv) to uiText(R.string.push_end_device_lost_detail)
        PushEndReason.NETWORK_CHANGED -> uiText(R.string.push_end_network_changed) to uiText(R.string.push_end_network_changed_detail)
        PushEndReason.IDLE_TIMEOUT -> uiText(R.string.push_end_idle) to uiText(R.string.push_notif_idle_detail)
    }

    /** 结束页摘要：“视频 3 个 · 照片 12 张”；null = 没有可写的。 */
    fun endSummary(info: PushEndInfo): UiText? = listOfNotNull(
        info.videoCount.takeIf { it > 0 }?.let { uiPlural(R.plurals.push_count_video, it) },
        info.photoCount.takeIf { it > 0 }?.let { uiPlural(R.plurals.push_count_photo, it) },
        info.audioCount.takeIf { it > 0 }?.let { uiPlural(R.plurals.push_count_audio, it) },
        info.urlCount.takeIf { it > 0 }?.let { uiPlural(R.plurals.push_count_url, it) },
    ).takeIf { it.isNotEmpty() }?.joinDot()

    /** “约 6 分钟” / “不到 1 分钟”（P10）。 */
    fun minutes(ms: Long?): UiText? {
        ms ?: return null
        if (ms < 60_000) return uiText(R.string.push_under_a_minute)
        return uiPlural(R.plurals.push_about_minutes, roundMinutes(ms))
    }

    /** 不带“约”的时长：“6 分钟” / “不到 1 分钟”（结束通知“推送了 X”、长辈页“大约还要 X”）。 */
    fun duration(ms: Long?): UiText? {
        ms ?: return null
        if (ms < 60_000) return uiText(R.string.push_under_a_minute)
        return uiPlural(R.plurals.push_minutes, roundMinutes(ms))
    }

    /** 转换剩余时间：“约剩 6 分钟”。 */
    fun remaining(ms: Long?): UiText? = duration(ms)?.let { uiText(R.string.push_remaining, it) }

    private fun roundMinutes(ms: Long): Int = ((ms + 30_000) / 60_000).toInt()

    /** “1.4 GB” / “820 MB”（与语言无关）。 */
    fun size(bytes: Long?): String? {
        bytes ?: return null
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024) else "${mb.toLong().coerceAtLeast(1)} MB"
    }

    /** P10 原因说明，技术名放括号里。 */
    fun transcodeReason(t: TranscodeUi, tv: String, title: String): UiText {
        val techParts = listOfNotNull(codecName(t.videoCodec), if (t.hdr) UiText.Raw("HDR") else null)
        val tech = techParts.takeIf { it.isNotEmpty() }?.joinDot()
        return when {
            t.failedBefore -> uiText(R.string.push_reason_failed_before, tv)
            // 只有 HEVC / HDR 才是“手机相机的高效格式”（第 1 轮 PM P2-9）
            t.hdr || t.videoCodec.equals("video/hevc", true) -> uiText(R.string.push_reason_camera_format, title, tech ?: UiText.Raw(""), tv)
            tech != null && t.codecUnsupported -> uiText(R.string.push_reason_codec, title, tech, tv)
            t.containerUnsupported -> uiText(R.string.push_reason_container, title, tv)
            else -> uiText(R.string.push_reason_generic, tv, title)
        }
    }

    /** 编码的通用名（大多与语言无关；杜比视界按语言）。 */
    fun codecName(mime: String?): UiText? = when (mime?.lowercase()) {
        null -> null
        "video/hevc" -> UiText.Raw("HEVC")
        "video/avc" -> UiText.Raw("H.264")
        "video/x-vnd.on2.vp9" -> UiText.Raw("VP9")
        "video/av01" -> UiText.Raw("AV1")
        "video/dolby-vision" -> uiText(R.string.push_codec_dolby_vision)
        "audio/vnd.dts", "audio/vnd.dts.hd" -> UiText.Raw("DTS")
        "audio/ac3" -> UiText.Raw("AC-3")
        "audio/eac3" -> UiText.Raw("E-AC-3")
        else -> UiText.Raw(mime.substringAfter('/').uppercase())
    }

    /** mm:ss / h:mm:ss（等宽数字显示）。 */
    fun time(ms: Long?): String {
        if (ms == null || ms < 0) return "—"
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
    }

    /** 读屏：“12 分 48 秒”。 */
    fun spokenTime(ms: Long?): UiText {
        if (ms == null) return uiText(R.string.push_unknown)
        val t = ms / 1000
        val h = (t / 3600).toInt()
        val m = (t / 60 % 60).toInt()
        val sec = (t % 60).toInt()
        val parts = buildList {
            if (h > 0) add(uiPlural(R.plurals.push_spoken_hours, h))
            if (h > 0 || m > 0) add(uiPlural(R.plurals.push_spoken_minutes, m))
            add(uiPlural(R.plurals.push_spoken_seconds, sec))
        }
        return parts.reduce { a, b -> uiText(R.string.push_join_space, a, b) }
    }

    /** 网址输入实时识别（P04）。 */
    fun urlKindHint(url: String): UiText {
        val (mime, kind) = com.benchan.wireless.screencasting.protocol.upnp.MediaMime.guessForUrl(url.trim())
        return if (mime == null) uiText(R.string.push_url_hint_default) else uiText(R.string.push_url_hint_ext, kindLabel(kind))
    }

    /** 只支持音频的接收端（Sink 明确只有 audio）。 */
    fun isAudioOnly(sink: List<com.benchan.wireless.screencasting.protocol.upnp.ProtocolInfo>?): Boolean {
        val http = sink?.filter { it.isHttpGet }.orEmpty()
        return http.isNotEmpty() && http.all { it.contentFormat.startsWith("audio/") }
    }

    fun isPushableUrl(url: String): Boolean {
        val u = url.trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true))) return false
        return runCatching { java.net.URI(u).host?.isNotBlank() == true }.getOrDefault(false)
    }

    /** 用“ · ”把几段文案连起来（每段各自按语言解析）。 */
    fun List<UiText>.joinDot(): UiText = when (size) {
        0 -> UiText.Raw("")
        else -> reduce { a, b -> uiText(R.string.push_join_dot, a, b) }
    }
}
