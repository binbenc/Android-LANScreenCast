package com.benchan.wireless.screencasting.protocol.dlna.dmc

import android.net.Uri
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.ProtocolInfo
import com.benchan.wireless.screencasting.protocol.upnp.RendererDescription
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushState

/** 推送目标（设备列表里的一台 DMR）。 */
data class PushTarget(
    val description: RendererDescription,
    val server: String?,
    val sink: List<ProtocolInfo>?,
    /** 报告用：发现路径与耗时。 */
    val discoveryPath: String? = null,
    val discoveryMs: Long? = null,
) {
    val udn: String get() = description.udn
    val name: String get() = description.displayName
    val host: String get() = description.host
}

/**
 * 推送引擎的输入（PRD 8.1.1 A“与 F14 的边界”：任意 content:// 或网址，不和 App 内选择器绑定）。
 * 显示名只在手机界面用，不写日志、不发给电视以外的任何地方。
 */
sealed interface PushInput {
    data class Local(val uri: Uri, val mimeHint: String? = null, val displayName: String? = null) : PushInput
    data class Remote(val url: String) : PushInput
}

enum class PushSourceKind { PICKER, FILES, URL, SHARE }

/** 列表中的一项（界面展示用）。 */
data class PushItemInfo(
    val index: Int,
    val kind: MediaKind,
    val remote: Boolean,
    /** 本地文件名或网址最后一段（只在手机界面显示）。 */
    val title: String,
    val mime: String?,
    val uri: Uri? = null,
    val sizeBytes: Long? = null,
    val transcoded: Boolean = false,
    /** 列表里这一项电视放不了，已跳过（交互稿 P08）。 */
    val failed: Boolean = false,
    /** 电视已取走这一项（照片“已发送”，交互稿 P09）。 */
    val served: Boolean = false,
)

/** 转换后推送（PRD 8.1.1 F2）的界面状态。 */
data class TranscodeUi(
    val index: Int,
    val phase: Phase,
    /** BEFORE_PUSH / AFTER_FAILURE。 */
    val offeredAt: OfferedAt = OfferedAt.BEFORE_PUSH,
    val progress: Int = 0,
    val etaMs: Long? = null,
    val estimatedDurationMs: Long? = null,
    val requiredBytes: Long? = null,
    val availableBytes: Long? = null,
    /** 电量 <20% 且没在充电：确认卡标红。 */
    val batteryLow: Boolean = false,
    val batteryPercent: Int? = null,
    /** 只是音轨手机解不了：提供“只转换画面（无声音）”。 */
    val videoOnlyAvailable: Boolean = false,
    val hdr: Boolean = false,
    val containerUnsupported: Boolean = false,
    val codecUnsupported: Boolean = false,
    /** 之前这台电视对同类格式失败过。 */
    val failedBefore: Boolean = false,
    /** 列表推送：显示“本次列表里其他需要转换的视频也自动转换”（交互稿 P10）。 */
    val inPlaylist: Boolean = false,
    /** 视频文件名（只在手机界面显示）。 */
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val processedMs: Long? = null,
    val durationMs: Long? = null,
) {
    enum class Phase {
        /** 提议转换（确认卡）。 */
        OFFER,
        RUNNING,

        /** 手机过热暂停。 */
        PAUSED_THERMAL,

        /** 空间不足（开始前或过程中）。 */
        NO_SPACE,

        /** 转换失败。 */
        FAILED,

        /** 手机也解不了这个视频。 */
        PHONE_UNSUPPORTED,
    }

    enum class OfferedAt { BEFORE_PUSH, AFTER_FAILURE }
}

/** 推送结束信息（结束页）。 */
data class PushEndInfo(
    val reason: PushEndReason,
    val lastIndex: Int,
    val lastPositionMs: Long?,
    /** 失联 / 网络变化且电视支持跳转时，结束页提供“从 mm:ss 继续推送”（交互稿 P14、Q14-G）。 */
    val canResume: Boolean,
    val durationMs: Long,
    val videoCount: Int,
    val photoCount: Int,
    val audioCount: Int,
    val urlCount: Int,
    /** 结束时当前项处于失败 / 没能开始：结束页如实描述（第 1 轮 EMU-5、PM P1-3）。 */
    val failure: com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError? = null,
) {
    val itemCount: Int get() = videoCount + photoCount + audioCount + urlCount
    val urlOnly: Boolean get() = urlCount > 0 && urlCount == itemCount
}

/** 列表里某项失败、已自动跳过（Snackbar“第 n 项无法播放，已跳到下一项”）。 */
data class PushSkipNotice(val index: Int, val at: Long)

data class PushSessionState(
    val sessionId: String,
    val target: PushTarget,
    val items: List<PushItemInfo>,
    val engine: PushState,
    val transcode: TranscodeUi? = null,
    val startedAt: Long,
    val ended: PushEndInfo? = null,
    val skipped: PushSkipNotice? = null,
    /** 照片切换后 10 秒电视仍未取走（交互稿 P09“这台电视显示照片比较慢”）。 */
    val photoSlow: Boolean = false,
    /** 推到本应用接收端（P05 一开始就提示“请在 xx 上点允许”）。 */
    val ownApp: Boolean = false,
) {
    val current: PushItemInfo? get() = items.getOrNull(engine.index)
    val isActive: Boolean get() = ended == null
}
