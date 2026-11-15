package com.benchan.wireless.screencasting.feature.receiver.session

import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyBreakdown

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
    val notice: String? = null,
    /** 当前网段能否使用投屏码（/24~/30）。 */
    val castCodeNetworkSupported: Boolean = true,
) {
    /** 投屏码不可用的原因（端口回退 / 网段不支持）；null = 可用或没有网络。 */
    val castCodeUnavailableReason: String?
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
    val title: String,
    val detail: String? = null,
    val icon: NoticeIcon = NoticeIcon.INFO,
    val tone: NoticeTone = NoticeTone.WARNING,
    /** 显示时长（交互稿 R08：默认 4 s，“不支持双指缩放”6 s）。 */
    val durationMs: Long = 4_000,
) {
    // —— 请求结果 ——
    class Denied(cooldownSeconds: Long) : ControlNotice(NoticeKind.DENIED, "对方拒绝了控制请求", "$cooldownSeconds 秒后可以再次请求", NoticeIcon.CLOSE, NoticeTone.ERROR)
    data object Timeout : ControlNotice(NoticeKind.TIMEOUT, "对方 30 秒内没有回应", "已视为拒绝", NoticeIcon.HOURGLASS, NoticeTone.NEUTRAL)
    data object ServiceOff : ControlNotice(NoticeKind.SERVICE_OFF, "请在对方手机上开启无障碍服务", "对方手机已显示开启步骤", NoticeIcon.A11Y, NoticeTone.WARNING)

    /** 对方关闭了通知，看不到开启步骤（PM 走查 G2）。 */
    data object ServiceOffNoGuide : ControlNotice(NoticeKind.SERVICE_OFF, "请在对方手机上开启无障碍服务", "在对方手机「设置 → 反向控制」中按提示开启", NoticeIcon.A11Y, NoticeTone.WARNING)
    data object NotEnabled : ControlNotice(NoticeKind.NOT_ENABLED, "对方未开启反向控制", "在对方手机「设置 → 反向控制」中开启", NoticeIcon.INFO, NoticeTone.WARNING)

    /** 对方版本没有 ctl 能力（PM 走查 G3）。 */
    data object Unsupported : ControlNotice(NoticeKind.UNSUPPORTED, "对方的局域投屏版本不支持反向控制", "请在对方手机上更新到最新版本", NoticeIcon.REFRESH, NoticeTone.WARNING)
    /** F24：发送端开着长辈模式（不指向任何设置入口，长辈设置里没有反向控制）。 */
    data object ElderMode : ControlNotice(NoticeKind.NOT_ENABLED, "对方手机开着长辈模式，不能远程操作", "投屏不受影响", NoticeIcon.INFO, NoticeTone.NEUTRAL)
    data object AdvancedProtection : ControlNotice(NoticeKind.AAPM, "对方手机开启了高级保护模式，无法被控制", "投屏不受影响", NoticeIcon.LOCK, NoticeTone.NEUTRAL)

    /** 请求阶段对方已锁屏（PM 走查 G4）。 */
    data object LockedRequest : ControlNotice(NoticeKind.LOCKED_REQUEST, "对方手机已锁屏", "请对方解锁后再请求", NoticeIcon.LOCK, NoticeTone.NEUTRAL)

    // —— 控制中 ——
    data object Revoked : ControlNotice(NoticeKind.ENDED, "对方已收回控制", null, NoticeIcon.HANDBACK, NoticeTone.NEUTRAL)
    data object Locked : ControlNotice(NoticeKind.ENDED, "对方手机已锁屏，控制已结束", null, NoticeIcon.HANDBACK, NoticeTone.NEUTRAL)

    /** 控制暂停：按原因给出副文案（PM 走查 G5）。 */
    class Paused(reason: ControlReason?) : ControlNotice(NoticeKind.PAUSED, pausedTitle(reason), pausedDetail(reason), NoticeIcon.PAUSE, NoticeTone.WARNING)

    /** 落在对方受保护区域（每次控制提示一次；交互稿 v1.0.3 已同步为本文案）。 */
    data object Blocked : ControlNotice(NoticeKind.BLOCKED, "这里不能远程操作", "状态栏和屏幕边缘的控制提示只能在对方手机上操作", NoticeIcon.INFO, NoticeTone.NEUTRAL)

    /** 对方本机触摸打断了操作（交互稿 v1.0.3 R08，每次控制提示一次）。 */
    data object LocalTouch : ControlNotice(NoticeKind.LOCAL_TOUCH, "对方正在操作手机", "已让对方优先，对方停手后可以继续操作", NoticeIcon.PHONE, NoticeTone.NEUTRAL)

    /** TV：非首次获得控制（首次显示提示卡，UX 走查 G11）。 */
    data object Granted : ControlNotice(NoticeKind.HINT, "已获得控制", "菜单键打开控制条", NoticeIcon.CHECK, NoticeTone.NEUTRAL)

    // —— B 期 · 能力与文字（交互稿 v1.0.3 原文；能力类为中性说明，只有密码框用 warning）——
    data object TextUnsupported : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, "对方系统版本不支持文字输入", "需要 Android 13 及以上；点击、滑动仍可使用", NoticeIcon.KEYBOARD, NoticeTone.NEUTRAL)
    data object DpadUnsupported : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, "对方系统版本不支持方向键模式", "需要 Android 13 及以上；光标模式仍可使用", NoticeIcon.DPAD, NoticeTone.NEUTRAL)
    data object MediaUnsupported : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, "对方系统版本不支持播放 / 暂停键", "需要 Android 16 及以上；可以用光标点画面上的播放按钮", NoticeIcon.PAUSE, NoticeTone.NEUTRAL)

    /** 发送端回 IREJ{UNSUPPORTED}：每次控制提示一次（PM 走查 G9）。 */
    data object UnsupportedOp : ControlNotice(NoticeKind.UNSUPPORTED_FEATURE, "对方手机不支持这个操作", "其他操作仍可使用", NoticeIcon.INFO, NoticeTone.NEUTRAL)
    data object SecureField : ControlNotice(NoticeKind.SECURE_FIELD, "对方手机上是密码框，不能远程输入", "密码、验证码请让对方自己输入", NoticeIcon.LOCK, NoticeTone.WARNING)
    data object NoEditor : ControlNotice(NoticeKind.NO_EDITOR, "对方手机上没有选中的输入框", "先点一下要输入的位置，再输入", NoticeIcon.KEYBOARD, NoticeTone.NEUTRAL)
    data object DpadLongPress : ControlNotice(NoticeKind.HINT, "长按请切换光标模式", "方向键模式下确定键只能点击", NoticeIcon.DPAD, NoticeTone.NEUTRAL)
    /** 对方是旧版本（没有 multi 能力）：不支持双指缩放（C 期）。 */
    // —— C 期 · 多指与加固（交互稿 v1.0.4 原文）——
    data object PinchUnsupported : ControlNotice(
        NoticeKind.UNSUPPORTED_FEATURE, "对方的局域投屏版本不支持双指缩放",
        "请对方更新到最新版本；可在本机「设置 → 把接收端安装到电视」分享安装包给对方", NoticeIcon.REFRESH, NoticeTone.NEUTRAL, durationMs = 6_000,
    )

    /** 首次发出双指缩放后（每次安装一次）。 */
    data object PinchHint : ControlNotice(NoticeKind.HINT, "双指缩放会在松手后执行", "对方手机会按两指的变化缩放一次", NoticeIcon.PINCH, NoticeTone.NEUTRAL)

    /** 双指有一根落在黑边上（每次控制一次）。 */
    data object PinchOutside : ControlNotice(NoticeKind.HINT, "双指需在画面内", "两根手指都要放在对方画面上", NoticeIcon.PINCH, NoticeTone.NEUTRAL)

    /** D-3 兜底：敏感界面暂停超过 10 秒（每次暂停一次）。 */
    data object SensitiveLong : ControlNotice(NoticeKind.HINT, "控制仍在暂停", "请让对方点一下手机上的「继续控制」，或切换一次应用", NoticeIcon.HOURGLASS, NoticeTone.NEUTRAL)
    data object DpadNoFocus : ControlNotice(NoticeKind.HINT, "看不到焦点？", "按菜单键打开控制条，换成光标模式", NoticeIcon.CURSOR, NoticeTone.NEUTRAL)
}

private fun pausedTitle(reason: ControlReason?): String = when (reason) {
    ControlReason.OWN_APP -> "控制已暂停 · 对方在投屏应用内"
    ControlReason.SENSITIVE_APP -> "控制已暂停 · 这里需要对方本人操作"
    else -> "控制已暂停：${pausedText(reason)}"
}

private fun pausedDetail(reason: ControlReason?): String? = when (reason) {
    ControlReason.RECONNECTING -> "网络恢复后自动继续"
    ControlReason.PROTECTED -> "对方离开受保护的画面后自动恢复"
    ControlReason.APP_HIDDEN -> "对方回到共享的应用后自动恢复"
    ControlReason.WINDOWED -> "对方退出分屏后自动恢复"
    ControlReason.OWN_APP -> "对方离开投屏应用后自动恢复"
    ControlReason.SENSITIVE_APP -> "例如安装应用、授予权限、无障碍设置，只能由对方确认，离开后自动恢复"
    else -> null
}

/** 接收端暂停原因（交互稿 R08）。 */
fun pausedText(reason: ControlReason?): String = when (reason) {
    ControlReason.RECONNECTING -> "正在重连"
    ControlReason.PROTECTED -> "对方画面受保护"
    ControlReason.APP_HIDDEN -> "对方离开了共享的应用"
    ControlReason.WINDOWED -> "对方处于分屏"
    ControlReason.OWN_APP -> "对方在投屏应用内"
    ControlReason.SENSITIVE_APP -> "需对方本人操作"
    else -> "对方暂时不可控制"
}

/** 第二根手指按下时的结果。 */
enum class PinchStart { STARTED, UNSUPPORTED, OUTSIDE, INACTIVE }
