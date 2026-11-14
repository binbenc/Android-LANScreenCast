package com.benchan.wireless.screencasting.core.protocol.message

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * WSC 信令消息类型（PRD 8.3.1 + 技术方案 3.5.4/4.1）。
 * 线上格式：`{"t":"OFFER","id":<uint32 自增>,"sid":"<sessionId>","v":1,"p":{...}}`。
 */
enum class MessageType(val wire: String) {
    HELLO("HELLO"),
    PAIR_REQ("PAIR_REQ"),
    PAIR_PIN("PAIR_PIN"),
    PAIR_CONFIRM("PAIR_CONFIRM"),
    PAIR_OK("PAIR_OK"),
    PAIR_QR("PAIR_QR"),
    OFFER("OFFER"),
    ANSWER("ANSWER"),
    ICE("ICE"),
    BYE("BYE"),
    ERROR("ERROR");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun fromWire(wire: String): MessageType? = byWire[wire]
    }
}

/** 发送端 / 接收端角色。 */
enum class Role { SENDER, RECEIVER }

/** 配对方式：PIN（电视显示 4 位）、投屏码（8 位里的 5 位 PIN）、二维码（带外指纹 + 随机数）、仅确认（可信设备但策略为每次确认）。 */
@Serializable
enum class PairMethod {
    @SerialName("PIN") PIN,
    @SerialName("CODE") CODE,
    @SerialName("QR") QR,
    @SerialName("CONFIRM") CONFIRM,
}

/** 错误码（ERROR 消息）。未知值解码为 [INTERNAL]（前向兼容，PROTOCOL.md 2.2）。 */
@Serializable(with = ErrorCodeSerializer::class)
enum class ErrorCode(val fatal: Boolean) {
    @SerialName("VERSION_UNSUPPORTED") VERSION_UNSUPPORTED(true),
    @SerialName("UNAUTHENTICATED") UNAUTHENTICATED(true),
    @SerialName("BAD_MESSAGE") BAD_MESSAGE(true),
    @SerialName("PAIR_REJECTED") PAIR_REJECTED(true),
    @SerialName("PAIR_TIMEOUT") PAIR_TIMEOUT(true),
    @SerialName("PIN_MISMATCH") PIN_MISMATCH(false),
    @SerialName("PAIR_LOCKED") PAIR_LOCKED(true),
    @SerialName("QR_INVALID") QR_INVALID(true),
    @SerialName("BUSY") BUSY(true),
    @SerialName("RESUME_FAILED") RESUME_FAILED(true),
    @SerialName("INTERNAL") INTERNAL(true),
}

/** BYE 原因。未知值解码为 [USER_STOP]（前向兼容，PROTOCOL.md 2.2）。 */
@Serializable(with = ByeReasonSerializer::class)
enum class ByeReason {
    /** 用户在 App、通知或 Tile 中停止。 */
    @SerialName("USER_STOP") USER_STOP,
    /** 系统停止了 MediaProjection（状态栏胶囊等）。 */
    @SerialName("PROJECTION_STOPPED") PROJECTION_STOPPED,
    /** 发送端锁屏导致系统停止投屏（Android 15 QPR1+，决策 #17）。 */
    @SerialName("SCREEN_LOCKED") SCREEN_LOCKED,
    /** 30 秒内没有重连成功。 */
    @SerialName("RECONNECT_TIMEOUT") RECONNECT_TIMEOUT,
    /** 接收端接受了新的发送端，当前会话被替换（R-09）。 */
    @SerialName("REPLACED") REPLACED,
    /** 接收端用户主动断开（连按两次返回）。 */
    @SerialName("RECEIVER_EXIT") RECEIVER_EXIT,
    /** 接收端没有可见的播放界面（后台收到可信直连，约 10 秒内界面没有打开），不在后台只放声音。 */
    @SerialName("NO_DISPLAY") NO_DISPLAY,
}

@Serializable
sealed interface WscPayload

@Serializable
data class Hello(
    val ver: Int,
    val deviceId: String,
    val name: String,
    val model: String = "",
    val role: String,
    val caps: List<String> = emptyList(),
    /** 发送端重连时为 true，并携带原 sessionId（技术方案 4.3）。 */
    val resume: Boolean = false,
    /**
     * 接收端回复：该发送端是否在可信列表中。
     * 发送端的 HELLO 也带上它：表示发送端是否信任本次 TLS 看到的接收端证书。为 false 时接收端必须走配对
     * （发送端移除了信任而接收端仍信任时，避免双方状态不一致进入死路，见 PROTOCOL.md 2.1）。
     */
    val trusted: Boolean? = null,
    /** 接收端回复：可信但策略为“每次都确认”时为 true。 */
    val confirmRequired: Boolean? = null,
    /** 接收端回复：是否接受恢复会话。 */
    val resumeOk: Boolean? = null,
) : WscPayload

@Serializable
data class PairRequest(
    val name: String,
    val model: String = "",
    val method: PairMethod,
) : WscPayload

/** SPAKE2 公开值，SEC1 未压缩点的 base64url。 */
@Serializable
data class PairPin(val point: String) : WscPayload

/** 发送端（A）的密钥确认 MAC。 */
@Serializable
data class PairConfirm(val mac: String) : WscPayload

/** 接收端（B）确认配对成功，PIN/投屏码方式时带上 B 的确认 MAC。 */
@Serializable
data class PairOk(val mac: String? = null) : WscPayload

/** 二维码配对：mac = HMAC-SHA256(n, fpA || fpB || sessionId)。 */
@Serializable
data class PairQr(val mac: String) : WscPayload

@Serializable
data class Offer(val sdp: String, val iceRestart: Boolean = false) : WscPayload

@Serializable
data class Answer(val sdp: String) : WscPayload

@Serializable
data class IceCandidate(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int) : WscPayload

@Serializable
data class Bye(val reason: ByeReason) : WscPayload

@Serializable
data class ErrorPayload(
    val code: ErrorCode,
    val message: String = "",
    val min: Int? = null,
    val max: Int? = null,
    /** PIN_MISMATCH 时剩余可尝试次数。 */
    val attemptsLeft: Int? = null,
) : WscPayload

/** 解码后的完整消息。 */
data class WscMessage(
    val type: MessageType,
    val id: Long,
    val sessionId: String?,
    val version: Int,
    val payload: WscPayload,
)
