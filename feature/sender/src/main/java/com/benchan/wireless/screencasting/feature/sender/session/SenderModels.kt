package com.benchan.wireless.screencasting.feature.sender.session

import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile

/** 发送端要连接的接收端，以及我们对它的认识程度（决定钉扎方式与配对方式）。 */
data class CastTarget(
    val deviceId: String?,
    val name: String,
    val host: String,
    val port: Int,
    val source: DiscoverySource,
    /** 完整指纹（可信列表 / 最近设备 / 二维码）。 */
    val fingerprintHex: String? = null,
    /** mDNS TXT / SSDP 中的指纹前缀。 */
    val fingerprintPrefix: ByteArray? = null,
    /** 二维码随机数（带外认证，免 PIN）。 */
    val qrNonce: ByteArray? = null,
    /** 投屏码里的 5 位一次性 PIN（免 4 位 PIN）。 */
    val castCodePin: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is CastTarget && host == other.host && port == other.port && deviceId == other.deviceId
    override fun hashCode(): Int = host.hashCode() * 31 + port
}

/** 结束原因 → 对应 PRD 第 10 节的状态与文案。 */
enum class EndReason {
    USER_STOPPED,
    /** 对方拒绝了投屏请求。 */
    REJECTED,
    /** 投屏码不正确（接收端已换新码）。 */
    CAST_CODE_WRONG,
    /** 二维码已过期或已被使用。 */
    QR_EXPIRED,
    /** 接收端认为本机没有通过认证（例如对方已移除信任且无法重新配对）。 */
    AUTH_FAILED,
    /** 30 秒内电视没有确认（可信设备“每次都确认”）。 */
    CONFIRM_TIMEOUT,
    /** PIN 配对 30 秒内没有完成（可能是电视上没点“允许”，也可能是 PIN 没输完）。 */
    PIN_TIMEOUT,
    /** 接收端没有打开投屏界面（后台收到投屏，约 10 秒内画面没有显示）。 */
    RECEIVER_NO_DISPLAY,
    /** PIN / 投屏码错误次数过多，60 秒内不能再发起。 */
    PAIR_LOCKED,
    /** 连不上（地址错误、电视未打开接收页、网络不通）。 */
    CONNECT_FAILED,
    /** 证书指纹与记录不符（可能是另一台设备冒充，或接收端重装过）。 */
    CERTIFICATE_MISMATCH,
    /** 对方版本过旧。 */
    VERSION_UNSUPPORTED,
    /** 30 秒内没能重连。 */
    RECONNECT_FAILED,
    /** 锁屏导致系统停止投屏（决策 #17）。 */
    SCREEN_LOCKED,
    /** 系统或用户在状态栏停止了录屏。 */
    PROJECTION_STOPPED,
    /** 用户拒绝了录屏授权。 */
    CAPTURE_DENIED,
    /** 接收端断开（连按两次返回 / 被新的发送端替换）。 */
    REMOTE_CLOSED,
    /** 接收端正忙。 */
    BUSY,
    INTERNAL_ERROR,
}

/** CONNECT_FAILED 的细分原因（决定提示文案与下一步，交互稿 Q 系列“错误带下一步”）。 */
enum class ConnectFailure {
    /** TCP / TLS 连不上（地址错误、电视未打开接收页、AP 隔离）。 */
    UNREACHABLE,
    /** 连上了但对方没有回 HELLO。 */
    HANDSHAKE_TIMEOUT,
    /** 二维码配对没有回应。 */
    QR_PAIR_TIMEOUT,
    /** 投屏码配对没有回应。 */
    CODE_PAIR_TIMEOUT,
    /** 已授权录屏，但 60 秒内媒体没有连通（多为路由器拦截 UDP）。 */
    FIRST_FRAME_TIMEOUT,
    /** 认证完成前信令断开。 */
    LOST,
}

/** 投屏中的实时统计（状态胶囊、通知副标题）。 */
data class CastingStats(
    val latencyMs: Int? = null,
    val grade: LatencyGrade? = null,
    val bitrateKbps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
)

/** 发送端会话状态（PRD 第 10 节状态表）。 */
sealed interface SenderState {
    data object Idle : SenderState

    data class Connecting(val target: CastTarget) : SenderState

    /** 等待用户输入电视上显示的 PIN（交互稿 05A）。 */
    data class AwaitingPin(
        val target: CastTarget,
        val deadlineAt: Long,
        val error: Boolean = false,
        val attemptsLeft: Int? = null,
        val verifying: Boolean = false,
    ) : SenderState

    /** 可信设备但策略为每次确认：等待电视点“允许”。 */
    data class AwaitingTvConfirm(val target: CastTarget, val deadlineAt: Long) : SenderState

    /** 已认证，等待录屏授权（说明卡 06 → 系统弹窗）。 */
    data class ReadyToCapture(val target: CastTarget) : SenderState

    /** 已授权、前台服务已启动，正在建立 WebRTC。 */
    data class Starting(val target: CastTarget) : SenderState

    data class Casting(
        val target: CastTarget,
        val startedAt: Long,
        val profile: QualityProfile,
        val autoAdjust: Boolean,
        val audio: AudioState,
        val stats: CastingStats = CastingStats(),
        val appVisible: Boolean = true,
        val protectedSuspected: Boolean = false,
    ) : SenderState

    data class Reconnecting(
        val target: CastTarget,
        val attempt: Int,
        val since: Long,
        val nextRetryAt: Long?,
        val profile: QualityProfile,
    ) : SenderState

    data class Ended(
        val target: CastTarget?,
        val reason: EndReason,
        val detail: String? = null,
        val failure: ConnectFailure? = null,
    ) : SenderState
}

/**
 * 会话已结束（录屏前台服务应停止）。注意 ReadyToCapture / Starting 不是终止状态：
 * 授权结果交给服务后，控制器异步切到 Starting，服务首先观察到的仍是 ReadyToCapture（A1）。
 */
val SenderState.isTerminal: Boolean
    get() = this is SenderState.Idle || this is SenderState.Ended

/** 一次性事件（Snackbar 等）。 */
sealed interface SenderEvent {
    data class AutoDowngraded(val previous: QualityProfile) : SenderEvent
    data object Recovered : SenderEvent
    data class PinIncorrect(val attemptsLeft: Int?) : SenderEvent
}
