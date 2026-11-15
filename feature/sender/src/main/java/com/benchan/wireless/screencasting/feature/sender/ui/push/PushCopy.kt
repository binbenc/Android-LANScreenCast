package com.benchan.wireless.screencasting.feature.sender.ui.push

import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushEndInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushItemInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase

/** 推送界面的文案（交互稿第 13 章 P01–P15）。纯逻辑，单测覆盖。界面不出现错误码。 */
object PushCopy {
    fun kindLabel(kind: MediaKind): String = when (kind) {
        MediaKind.VIDEO -> "视频"
        MediaKind.AUDIO -> "音乐"
        MediaKind.IMAGE -> "照片"
    }

    /** 设备行副标题（P01）。 */
    fun dlnaSubtitle(audioOnly: Boolean, pushing: Boolean, disambiguation: String?): String {
        val base = when {
            pushing -> "正在推送"
            audioOnly -> "只能推送音乐"
            else -> "推送视频、照片、音乐、网址"
        }
        return if (disambiguation != null && !pushing) "$base · $disambiguation" else base
    }

    /** 列表顶部横幅第二行（P01）：“播放中 · 3 / 12”。 */
    fun bannerStatus(s: PushSessionState): String {
        val e = s.engine
        val status = when {
            s.transcode?.phase == TranscodeUi.Phase.RUNNING -> "转换中"
            s.transcode?.phase == TranscodeUi.Phase.PAUSED_THERMAL -> "转换已暂停"
            e.phase == PushPhase.AWAITING_CHOICE -> "等你选择"
            e.phase == PushPhase.PLAYING -> "播放中"
            e.phase == PushPhase.PAUSED -> "已暂停"
            e.phase == PushPhase.SHOWING -> "已发送"
            e.phase == PushPhase.FAILED -> "没有成功"
            else -> "加载中"
        }
        return if (e.count > 1) "$status · ${e.index + 1} / ${e.count}" else status
    }

    /** 遥控页状态点（P05/P06/P13）。 */
    fun statusLabel(s: PushSessionState): String {
        val e = s.engine
        return when {
            s.transcode?.phase == TranscodeUi.Phase.RUNNING || s.transcode?.phase == TranscodeUi.Phase.PAUSED_THERMAL -> "转换中"
            s.transcode?.phase == TranscodeUi.Phase.FAILED -> "没有成功"
            e.phase == PushPhase.AWAITING_CHOICE -> "等你选择"
            e.phase == PushPhase.PREPARING || e.phase == PushPhase.LOADING -> "加载中"
            e.phase == PushPhase.PLAYING -> "播放中"
            e.phase == PushPhase.PAUSED -> "已暂停"
            e.phase == PushPhase.SHOWING -> if (s.current?.served == true) "已发送" else "正在发送…"
            e.phase == PushPhase.FAILED -> "没有成功"
            else -> ""
        }
    }

    /** 内容副标题：“视频 · 播完自动播下一个” / “音乐 · 2 / 8 · 播完自动播下一个”。 */
    fun itemSubtitle(item: PushItemInfo, count: Int): String {
        if (item.remote) return "网址 · ${kindLabel(item.kind)}"
        val parts = mutableListOf(kindLabel(item.kind))
        if (count > 1 && item.kind == MediaKind.AUDIO) parts += "${item.index + 1} / $count"
        if (count > 1 && item.kind != MediaKind.IMAGE) parts += "播完自动播下一个"
        return parts.joinToString(" · ")
    }

    /** 失败卡（P13）。 */
    data class Failure(val title: String, val detail: String, val primary: Action, val secondary: Action?, val tertiary: Action? = null)

    enum class Action(val label: String) {
        TRANSCODE("转换后推送"), CHANGE_FILE("换一个文件"), INSTALL_APP("在电视上安装本应用"), USE_MIRROR("改用屏幕镜像"),
        RETRY("重试"), OTHER_DEVICE("换一台设备"), RESCAN("重新搜索"), DIAGNOSTICS("网络诊断"),
        EDIT_URL("修改网址"), LOCAL_FILE("换成本地文件"), RESELECT("重新选择"), TRY_ORIGINAL("直接尝试原文件"), CANCEL("取消"),
    }

    fun failure(error: PushError, tv: String, item: PushItemInfo?, ownApp: Boolean): Failure {
        val localVideo = item != null && !item.remote && item.kind == MediaKind.VIDEO
        val third = if (ownApp) Action.USE_MIRROR else Action.INSTALL_APP
        return when (error) {
            PushError.UNSUPPORTED_FORMAT -> if (localVideo) {
                Failure("电视无法播放这个文件", "$tv 可能不支持它的格式。可以在手机上转换后再推送。", Action.TRANSCODE, Action.CHANGE_FILE, third)
            } else {
                Failure("电视无法播放这个文件", "$tv 可能不支持它的格式", Action.CHANGE_FILE, null, third)
            }
            PushError.REJECTED -> if (ownApp) {
                Failure("$tv 上选择了拒绝", "请在电视上点「允许」后重试", Action.RETRY, Action.OTHER_DEVICE)
            } else {
                Failure("$tv 拒绝了推送", "部分电视要先在设置里打开「允许投屏」或「DMR」", Action.RETRY, Action.OTHER_DEVICE)
            }
            PushError.UNREACHABLE -> Failure("连不上 $tv", "请确认电视已开机，并且和手机连的是同一个路由器", Action.RESCAN, Action.DIAGNOSTICS)
            PushError.URL_FAILED -> Failure("电视打不开这个网址", "可能填的是网页地址而不是视频文件地址，或者电视没法联网", Action.EDIT_URL, Action.LOCAL_FILE)
            PushError.START_TIMEOUT -> Failure("$tv 20 秒内没有开始播放", "请看一下电视是否停在投屏界面或弹出了提示", Action.RETRY, Action.CHANGE_FILE)
            PushError.NO_NETWORK -> Failure("手机没有连接 Wi‑Fi", "手机和电视要连同一个路由器", Action.DIAGNOSTICS, null)
            PushError.DECODE_FAILED -> Failure("无法处理这张照片", "照片文件可能已损坏，或格式这台手机不支持", Action.CHANGE_FILE, null)
            PushError.FILE_UNREADABLE -> Failure("无法读取文件，请重新选择", "手机收回了这个文件的读取授权", Action.RESELECT, null)
            PushError.TRANSCODE_FAILED -> Failure("转换失败", "临时文件已删除", Action.TRY_ORIGINAL, Action.CHANGE_FILE)
            PushError.NEEDS_TRANSCODE -> Failure("$tv 可能放不了这个视频", "可以在手机上转换后再推送", Action.TRANSCODE, Action.TRY_ORIGINAL)
        }
    }

    /** 结束页（P14）标题与说明。 */
    fun endTitle(info: PushEndInfo, tv: String): Pair<String, String> = when (info.reason) {
        // 失败后结束：如实说没有成功（第 1 轮 EMU-5）
        PushEndReason.USER_STOPPED -> info.failure?.let { "推送没有成功" to failure(it, tv, null, false).title } ?: ("已结束推送" to "$tv 已停止播放")
        PushEndReason.STOPPED_ON_TV -> "电视已停止播放" to "$tv 上的播放被停止了，推送已结束"
        PushEndReason.LIST_FINISHED -> if (info.itemCount > 1) "播放列表已放完" to "推送已结束" else "已播放完毕" to "推送已结束"
        PushEndReason.INACTIVE -> "推送已自动结束" to "推送没有成功后，10 分钟没有操作"
        PushEndReason.ALL_FAILED -> "电视放不了列表里的内容" to "列表里的每一项 $tv 都没能播放"
        PushEndReason.START_FAILED -> "没能开始推送" to when (info.failure) {
            PushError.NO_NETWORK -> "手机没有连接 Wi‑Fi。手机和电视要连同一个路由器"
            else -> "连不上 $tv。请确认电视已开机，并且和手机连的是同一个路由器"
        }
        PushEndReason.TAKEN_OVER -> "其他设备已开始在这台电视上投屏" to "$tv 正在播放其他设备推送的内容"
        PushEndReason.DEVICE_LOST -> "与 $tv 的连接已断开" to "电视可能已关机，或离开了这个网络"
        PushEndReason.NETWORK_CHANGED -> "手机网络已变化，推送已结束" to "请确认手机和电视连的是同一个路由器"
        PushEndReason.IDLE_TIMEOUT -> "照片推送因长时间没有操作已结束" to "30 分钟没有翻页"
    }

    /** 结束页摘要：“视频 3 个 · 照片 12 张”。 */
    fun endSummary(info: PushEndInfo): String = listOfNotNull(
        info.videoCount.takeIf { it > 0 }?.let { "视频 $it 个" },
        info.photoCount.takeIf { it > 0 }?.let { "照片 $it 张" },
        info.audioCount.takeIf { it > 0 }?.let { "音乐 $it 首" },
        info.urlCount.takeIf { it > 0 }?.let { "网址 $it 个" },
    ).joinToString(" · ")

    /** “约 6 分钟” / “不到 1 分钟”（P10）。 */
    fun minutes(ms: Long?): String? {
        ms ?: return null
        if (ms < 60_000) return "不到 1 分钟"
        return "约 ${(ms + 30_000) / 60_000} 分钟"
    }

    /** “1.4 GB” / “820 MB”。 */
    fun size(bytes: Long?): String? {
        bytes ?: return null
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024) else "${mb.toLong().coerceAtLeast(1)} MB"
    }

    /** P10 原因说明，技术名放括号里。 */
    fun transcodeReason(t: TranscodeUi, tv: String, title: String): String {
        val tech = listOfNotNull(codecName(t.videoCodec), if (t.hdr) "HDR" else null).joinToString(" · ")
        val what = when {
            t.failedBefore -> "$tv 之前放不了这种格式的视频"
            // 只有 HEVC / HDR 才是“手机相机的高效格式”（第 1 轮 PM P2-9）
            t.hdr || t.videoCodec.equals("video/hevc", true) -> "“$title”是手机相机的高效格式（$tech），$tv 没有表示支持这种格式。"
            tech.isNotEmpty() && t.codecUnsupported -> "“$title”的视频编码（$tech）$tv 没有表示支持。"
            t.containerUnsupported -> "“$title”的文件格式 $tv 没有表示支持。"
            else -> "$tv 可能放不了“$title”。"
        }
        return what
    }

    fun codecName(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "video/hevc" -> "HEVC"
        "video/avc" -> "H.264"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/dolby-vision" -> "杜比视界"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        else -> mime.substringAfter('/').uppercase()
    }

    /** mm:ss / h:mm:ss（等宽数字显示）。 */
    fun time(ms: Long?): String {
        if (ms == null || ms < 0) return "—"
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
    }

    /** 读屏：“12 分 48 秒”。 */
    fun spokenTime(ms: Long?): String {
        if (ms == null) return "未知"
        val t = ms / 1000
        val h = t / 3600
        val m = t / 60 % 60
        val sec = t % 60
        return buildString {
            if (h > 0) append("$h 小时 ")
            if (h > 0 || m > 0) append("$m 分 ")
            append("$sec 秒")
        }
    }

    /** 网址输入实时识别（P04）。 */
    fun urlKindHint(url: String): String {
        val (mime, kind) = com.benchan.wireless.screencasting.protocol.upnp.MediaMime.guessForUrl(url.trim())
        return if (mime == null) "按视频推送" else "按扩展名识别：${kindLabel(kind)}"
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
}
