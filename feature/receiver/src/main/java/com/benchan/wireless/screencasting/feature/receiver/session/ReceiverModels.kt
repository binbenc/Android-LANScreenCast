package com.benchan.wireless.screencasting.feature.receiver.session

import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyBreakdown
import com.benchan.wireless.screencasting.feature.receiver.R

/** 待机页信息（交互稿 11）。 */
data class StandbyInfo(
    val deviceName: String,
    val ip: String?,
    val port: Int?,
    val castCode: CastCode?,
    val qrContent: String?,
    val listening: Boolean,
    val lanAvailable: Boolean,
    val appleCompatAvailable: Boolean,
    val appleCompatEnabled: Boolean,
    /** 回到待机页时短暂显示的提示，例如“小张的 Pixel 8 已断开”“发送端已锁屏”。 */
    val notice: UiText? = null,
    /** 当前网段能否使用投屏码（/24~/30）。 */
    val castCodeNetworkSupported: Boolean = true,
) {
    /** 投屏码不可用的原因（端口回退 / 网段不支持）；null = 可用或没有网络。 */
    val castCodeUnavailableReason: UiText?
        get() = if (castCode != null) null else ReceiverCopy.castCodeUnavailableReason(lanAvailable, port, castCodeNetworkSupported)
}

/** 投屏请求确认弹窗（交互稿 12；DLNA 陌生设备推送沿用此弹窗，无 PIN 区域）。 */
data class ConfirmRequest(
    val id: Long,
    val peerName: String,
    val peerAddress: String,
    val protocol: CastProtocol,
    /** 4 位 PIN；null 表示仅确认（可信设备“每次都确认”、DLNA）。 */
    val pin: String?,
    val firstTime: Boolean,
    /** 已有会话时的新请求：标题改为“…请求替换当前投屏”。 */
    val replacing: Boolean,
    val deadlineAt: Long,
)

/** 镜像播放中的统计（交互稿 13 统计浮层）。 */
data class PlaybackStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val latencyMs: Int? = null,
    val grade: LatencyGrade? = null,
    val dropPercent: Double = 0.0,
    val decoder: String? = null,
    val hardwareDecoder: Boolean? = null,
    val codec: String? = null,
    /** 最近 60 秒延迟（统计浮层折线图）。 */
    val latencyHistory: List<Int> = emptyList(),
    /** F18：延迟分段（网络 / 缓冲 / 解码 / 显示），null = 没有足够统计。 */
    val breakdown: LatencyBreakdown? = null,
    /** F18：低延迟解码器是否生效（null = 未使用低延迟解码器）。 */
    val lowLatencyDecoder: Boolean? = null,
)

/** 当前镜像会话（WSC）。 */
data class MirrorSession(
    val sessionId: String,
    val peerName: String,
    val peerAddress: String,
    val startedAt: Long,
    val firstFrame: Boolean = false,
    val reconnecting: Boolean = false,
    val reconnectAttempt: Int = 0,
    val audio: AudioState = AudioState.ON,
    val appVisible: Boolean = true,
    val protectedSuspected: Boolean = false,
    val stats: PlaybackStats = PlaybackStats(),
    /** 反向控制（PRD F23，交互稿 R06–R08）。 */
    val control: ReceiverControl = ReceiverControl(),
    /** 发送端当前画质档位（OFFER / SET_QUALITY / STATS；旧版发送端可能一直为 null）。 */
    val profile: QualityProfile? = null,
    /** F18：正在重建媒体连接切换档位（保留最后一帧，显示“正在切换到游戏模式…”）。 */
    val switching: Boolean = false,
    /**
     * F18：本机接收链路确实按游戏模式建立（低缓冲 + 低延迟解码器）。旧版发送端只发 `SET_QUALITY{GAME}`、不重建时为 false，
     * 界面不显示“游戏模式”标签和目标值，避免名不副实。
     */
    val gameMode: Boolean = false,
    /** F18：发送端已把游戏模式降为 30 帧（STATS.capFps）；变为 true 时右上显示 3 秒“已降为 30 帧”（交互稿 G05）。 */
    val fpsCapped: Boolean = false,
    /** F18：发送端是 F18 之前的版本（OFFER 不带档位）：它的“游戏”档在本机没有低延迟优化。 */
    val senderLegacy: Boolean = false,
)

/** 接收端看到的反向控制状态（来自发送端 CTL_STATE）。 */
data class ReceiverControl(
    /** 发送端 HELLO 带 ctl 能力（本构建能被控制；是否已开启看 [phase]）。 */
    val supported: Boolean = false,
    /** 发送端最近一次 CTL_STATE；null = 还没收到。 */
    val phase: ControlPhase? = null,
    val reason: ControlReason? = null,
    val feat: List<String> = emptyList(),
    /** 已发出 CTL_REQ、尚未收到回应。 */
    val requesting: Boolean = false,
    /** PENDING：发送端自动拒绝的墙钟时间（倒计时与发送端同步）。 */
    val pendingDeadline: Long = 0,
    /** 被拒 / 超时 / 取消 / 被收回后，这个墙钟时间之前不能再次请求（交互稿 R08）。 */
    val cooldownUntil: Long = 0,
    /** 解码帧尺寸（已旋转），用于换算触点。 */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
) {
    val controlling: Boolean get() = phase == ControlPhase.ACTIVE || phase == ControlPhase.PAUSED
    val waiting: Boolean get() = requesting || phase == ControlPhase.PENDING
}

/** R08 提示的类型。HINT = 中性操作建议。 */
enum class NoticeKind { DENIED, TIMEOUT, SERVICE_OFF, NOT_ENABLED, UNSUPPORTED, AAPM, LOCKED_REQUEST, PAUSED, ENDED, BLOCKED, LOCAL_TOUCH, UNSUPPORTED_FEATURE, SECURE_FIELD, NO_EDITOR, HINT }

/** R08 提示图标（交互稿 v1.0.3 图标表）。 */
enum class NoticeIcon { CLOSE, HOURGLASS, A11Y, INFO, REFRESH, LOCK, PAUSE, PHONE, HANDBACK, CHECK, KEYBOARD, DPAD, CURSOR, PINCH }

/** 图标色：ERROR / WARNING / NEUTRAL（onSurfaceVariant）。正文统一 onSurface。 */
enum class NoticeTone { ERROR, WARNING, NEUTRAL }

/** 交互稿 R08 的提示（“发生了什么 + 怎么办”，底部 4 秒）。图标与图标色按交互稿 v1.0.3。 */
sealed class ControlNotice(
    val kind: NoticeKind,
    val title: UiText,
    val detail: UiText? = null,
    val icon: NoticeIcon = NoticeIcon.INFO,
    val tone: NoticeTone = NoticeTone.WARNING,
    /** 显示时长（交互稿 R08：默认 4 s，“不支持双指缩放”6 s）。 */
    val durationMs: Long = 4_000,
) {
    // —— 请求结果 ——
    class Denied(cooldownSeconds: Long) : ControlNotice(
        NoticeKind.DENIED, uiText(R.string.rx_ctl_denied),
        uiPlural(R.plurals.rx_ctl_denied_cooldown, cooldownSeconds.toInt()), NoticeIcon.CLOSE, NoticeTone.ERROR,
    )
    data object Timeout : ControlNotice(NoticeKind.TIMEOUT, uiText(R.string.rx_ctl_timeout), uiText(R.string.rx_ctl_timeout_detail), NoticeIcon.HOURGLASS, NoticeTone.NEUTRAL)
    data object ServiceOff : ControlNotice(NoticeKind.SERVICE_OFF, uiText(R.string.rx_ctl_service_off), uiText(R.string.rx_ctl_service_off_detail), NoticeIcon.A11Y, NoticeTone.WARNING)

    /** 对方关闭了通知，看不到开启步骤（PM 走查 G2）。 */
    data object ServiceOffNoGuide : ControlNotice(NoticeKind.SERVICE_OFF, uiText(R.string.rx_ctl_service_off), uiText(R.string.rx_ctl_service_off_no_guide), NoticeIcon.A11Y, NoticeTone.WARNING)
    data object NotEnabled : ControlNotice(NoticeKind.NOT_ENABLED, uiText(R.string.rx_ctl_not_enabled), uiText(R.string.rx_ctl_not_enabled_detail), NoticeIcon.INFO, NoticeTone.WARNING)

    /** 对方版本没有 ctl 能力（PM 走查 G3）。 */
    data object Unsupported : ControlNotice(NoticeKind.UNSUPPORTED, uiText(R.string.rx_ctl_unsupported), uiText(R.string.rx_ctl_update_peer), NoticeIcon.REFRESH, NoticeTone.WARNING)
    /** F24：发送端开着长辈模式（不指向任何设置入口，长辈设置里没有反向控制）。 */
    data object ElderMode : ControlNotice(NoticeKind.NOT_ENABLED, uiText(R.string.rx_ctl_elder_mode), uiText(R.string.rx_ctl_casting_unaffected), NoticeIcon.INFO, NoticeTone.NEUTRAL)
    data object AdvancedProtection : ControlNotice(NoticeKind.AAPM, uiText(R.string.rx_ctl_aapm), uiText(R.string.rx_ctl_casting_unaffected), NoticeIcon.LOCK, NoticeTone.NEUTRAL)

    /** 请求阶段对方已锁屏（PM 走查 G4）。 */
    data object LockedRequest : ControlNotice(NoticeKind.LOCKED_REQUEST, uiText(R.string.rx_ctl_locked_request), uiText(R.string.rx_ctl_locked_request_detail), NoticeIcon.LOCK, NoticeTone.NEUTRAL)

    // —— 控制中 ——
    data object Revoked : ControlNotice(NoticeKind.ENDED, uiText(R.string.rx_ctl_revoked), null, NoticeIcon.HANDBACK, NoticeTone.NEUTRAL)
    data object Locked : ControlNotice(NoticeKind.ENDED, uiText(R.string.rx_ctl_locked), null, NoticeIcon.HANDBACK, NoticeTone.NEUTRAL)

    /** 控制暂停：按原因给出副文案（PM 走查 G5）。 */
    class Paused(reason: ControlReason?) : ControlNotice(NoticeKind.PAUSED, pausedTitle(reason), pausedDetail(reason), NoticeIcon.PAUSE, NoticeTone.WARNING)

    /** 落在对方受保护区域（每次控制提示一次；交互稿 v1.0.3 已同步为本文案）。 */
    data object Blocked : ControlNotice(NoticeKind.BLOCKED, uiText(R.string.rx_ctl_blocked), uiText(R.string.rx_ctl_blocked_detail), NoticeIcon.INFO, NoticeTone.NEUTRAL)

    /** 对方本机触摸打断了操作（交互稿 v1.0.3 R08，每次控制提示一次）。 */
    data object LocalTouch : ControlNotice(NoticeKind.LOCAL_TOUCH, uiText(R.string.rx_ctl_local_touch), uiText(R.string.rx_ctl_local_touch_detail), NoticeIcon.PHONE, NoticeTone.NEUTRAL)

    /** TV：非首次获得控制（首次显示提示卡，UX 走查 G11）。 */
    data object Granted : ControlNotice(NoticeKind.HINT, uiText(R.string.rx_ctl_granted), uiText(R.string.rx_ctl_granted_detail), NoticeIcon.CHECK, NoticeTone.NEUTRAL)

    // —— B 期 · 能力与文字（交互稿 v1.0.3 原文；能力类为中性说明，只有密码框用 warning）——
    data object TextUnsupported : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, uiText(R.string.rx_ctl_text_unsupported), uiText(R.string.rx_ctl_text_unsupported_detail), NoticeIcon.KEYBOARD, NoticeTone.NEUTRAL)
    data object DpadUnsupported : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, uiText(R.string.rx_ctl_dpad_unsupported), uiText(R.string.rx_ctl_dpad_unsupported_detail), NoticeIcon.DPAD, NoticeTone.NEUTRAL)
    data object MediaUnsupported : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, uiText(R.string.rx_ctl_media_unsupported), uiText(R.string.rx_ctl_media_unsupported_detail), NoticeIcon.PAUSE, NoticeTone.NEUTRAL)

    /** 发送端回 IREJ{UNSUPPORTED}：每次控制提示一次（PM 走查 G9）。 */
    data object UnsupportedOp : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, uiText(R.string.rx_ctl_op_unsupported), uiText(R.string.rx_ctl_op_unsupported_detail), NoticeIcon.INFO, NoticeTone.NEUTRAL)
    data object SecureField : ControlNotice(NoticeKind.SECURE_FIELD, uiText(R.string.rx_ctl_secure_field), uiText(R.string.rx_ctl_secure_field_detail), NoticeIcon.LOCK, NoticeTone.WARNING)
    data object NoEditor : ControlNotice(NoticeKind.NO_EDITOR, uiText(R.string.rx_ctl_no_editor), uiText(R.string.rx_ctl_no_editor_detail), NoticeIcon.KEYBOARD, NoticeTone.NEUTRAL)
    data object DpadLongPress : ControlNotice(NoticeKind.HINT, uiText(R.string.rx_ctl_dpad_long_press), uiText(R.string.rx_ctl_dpad_long_press_detail), NoticeIcon.DPAD, NoticeTone.NEUTRAL)
    /** 对方是旧版本（没有 multi 能力）：不支持双指缩放（C 期）。 */
    // —— C 期 · 多指与加固（交互稿 v1.0.4 原文）——
    data object PinchUnsupported : ControlNotice(
        NoticeKind.UNSUPPORTED_FEATURE, uiText(R.string.rx_ctl_pinch_unsupported),
        uiText(R.string.rx_ctl_pinch_unsupported_detail), NoticeIcon.REFRESH, NoticeTone.NEUTRAL, durationMs = 6_000,
    )

    /** 首次发出双指缩放后（每次安装一次）。 */
    data object PinchHint : ControlNotice(NoticeKind.HINT, uiText(R.string.rx_ctl_pinch_hint), uiText(R.string.rx_ctl_pinch_hint_detail), NoticeIcon.PINCH, NoticeTone.NEUTRAL)

    /** 双指有一根落在黑边上（每次控制一次）。 */
    data object PinchOutside : ControlNotice(NoticeKind.HINT, uiText(R.string.rx_ctl_pinch_outside), uiText(R.string.rx_ctl_pinch_outside_detail), NoticeIcon.PINCH, NoticeTone.NEUTRAL)

    /** D-3 兜底：敏感界面暂停超过 10 秒（每次暂停一次）。 */
    data object SensitiveLong : ControlNotice(NoticeKind.HINT, uiText(R.string.rx_ctl_sensitive_long), uiText(R.string.rx_ctl_sensitive_long_detail), NoticeIcon.HOURGLASS, NoticeTone.NEUTRAL)
    data object DpadNoFocus : ControlNotice(NoticeKind.HINT, uiText(R.string.rx_ctl_dpad_no_focus), uiText(R.string.rx_ctl_dpad_no_focus_detail), NoticeIcon.CURSOR, NoticeTone.NEUTRAL)
}

private fun pausedTitle(reason: ControlReason?): UiText = when (reason) {
    ControlReason.OWN_APP -> uiText(R.string.rx_ctl_paused_own_app)
    ControlReason.SENSITIVE_APP -> uiText(R.string.rx_ctl_paused_sensitive)
    else -> uiText(R.string.rx_ctl_paused_with_reason, pausedText(reason))
}

private fun pausedDetail(reason: ControlReason?): UiText? = when (reason) {
    ControlReason.RECONNECTING -> uiText(R.string.rx_ctl_paused_detail_reconnecting)
    ControlReason.PROTECTED -> uiText(R.string.rx_ctl_paused_detail_protected)
    ControlReason.APP_HIDDEN -> uiText(R.string.rx_ctl_paused_detail_app_hidden)
    ControlReason.WINDOWED -> uiText(R.string.rx_ctl_paused_detail_windowed)
    ControlReason.OWN_APP -> uiText(R.string.rx_ctl_paused_detail_own_app)
    ControlReason.SENSITIVE_APP -> uiText(R.string.rx_ctl_paused_detail_sensitive)
    else -> null
}

/** 接收端暂停原因（交互稿 R08）。 */
fun pausedText(reason: ControlReason?): UiText = uiText(
    when (reason) {
        ControlReason.RECONNECTING -> R.string.rx_ctl_pause_reconnecting
        ControlReason.PROTECTED -> R.string.rx_ctl_pause_protected
        ControlReason.APP_HIDDEN -> R.string.rx_ctl_pause_app_hidden
        ControlReason.WINDOWED -> R.string.rx_ctl_pause_windowed
        ControlReason.OWN_APP -> R.string.rx_ctl_pause_own_app
        ControlReason.SENSITIVE_APP -> R.string.rx_ctl_pause_sensitive
        else -> R.string.rx_ctl_pause_other
    },
)

/** 第二根手指按下时的结果。 */
enum class PinchStart { STARTED, UNSUPPORTED, OUTSIDE, INACTIVE }
