package com.benchan.wireless.screencasting.core.protocol.datachannel

import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 发送端声音状态（PRD S-03、PRD 10 录音权限被拒）。 */
@Serializable
enum class AudioState {
    @SerialName("ON") ON,
    /** 用户关闭了声音。 */
    @SerialName("OFF") OFF,
    /** RECORD_AUDIO 被拒绝，只投画面。 */
    @SerialName("NO_PERMISSION") NO_PERMISSION,
    /** 连续多秒静音，可能是被投 App 禁止捕获（ALLOW_CAPTURE_BY_NONE），只是推断。 */
    @SerialName("SILENT_SUSPECTED") SILENT_SUSPECTED,
}

/**
 * WebRTC DataChannel `wsc` 上的消息（PRD 8.3.1、PROTOCOL.md 4.1）：STATS / SET_QUALITY / APP_SWITCHED、MVP 用到的若干状态提示，
 * 以及反向控制的低频握手（CTL_REQ / CTL_STATE / CTL_END / GEOM）。JSON 以 "t" 作类型判别字段。
 * DataChannel 上未知的 t 一律忽略（[decodeOrNull] 返回 null），与信令通道不同。
 */
@Serializable
sealed interface DcMessage {
    /** 发送端每秒一次：编码侧统计 + 估算的端到端延迟。 */
    @Serializable
    @SerialName("STATS")
    data class SenderStats(
        val bitrateKbps: Int,
        val fps: Int,
        val width: Int,
        val height: Int,
        val rttMs: Int? = null,
        val encodeMs: Double? = null,
        val latencyMs: Int? = null,
        val lossPercent: Double = 0.0,
        val codec: String? = null,
        val profile: QualityProfile? = null,
        /** 发送端平均排队发送耗时（outbound-rtp `totalPacketSendDelay / packetsSent`，ms），F18 延迟分段用。 */
        val pacerMs: Double? = null,
        /** 游戏模式下已把帧率降到该值（过热 / 跟不上 60 帧，PRD 8.1.3 F / H）；接收端据此显示 3 秒“已降为 30 帧”。没降时省略。 */
        val capFps: Int? = null,
    ) : DcMessage

    /** 接收端每秒一次：接收与解码侧统计，用于发送端估算端到端延迟（技术方案 3.4 方法 M3）。 */
    @Serializable
    @SerialName("RSTATS")
    data class ReceiverStats(
        val jitterBufferMs: Double? = null,
        val decodeMs: Double? = null,
        val framesDropped: Long = 0,
        val framesDecoded: Long = 0,
        val fps: Int = 0,
        val decoder: String? = null,
        val hardwareDecoder: Boolean? = null,
        /** 每帧从收到第一个包到解码完成的平均耗时（inbound-rtp `totalProcessingDelay / framesDecoded`，ms），含组帧 + 缓冲 + 解码。 */
        val procMs: Double? = null,
        /** 解码器是否开启了低延迟特性（F18；null = 未使用低延迟解码器工厂 / 未知）。 */
        val lowLatency: Boolean? = null,
    ) : DcMessage

    /**
     * 收→发（F18，PROTOCOL.md 4.3）：`OFFER{rebuild}` 建立的新连接**首帧已上屏**。发送端以此判定切换成功（不是 ICE 连通），
     * [pauseMs] = 接收端从收到 `OFFER{rebuild}` 到首帧上屏的停顿。只有支持 `game` 能力的接收端会发；旧版发送端忽略。
     */
    @Serializable
    @SerialName("REBUILT")
    data class Rebuilt(val pauseMs: Long) : DcMessage

    @Serializable
    @SerialName("SET_QUALITY")
    data class SetQuality(val profile: QualityProfile) : DcMessage

    /** 单应用共享时被共享应用切到后台 / 回到前台（PRD S-02）。 */
    @Serializable
    @SerialName("APP_SWITCHED")
    data class AppSwitched(val visible: Boolean) : DcMessage

    /** 发送端推断画面可能受保护（FLAG_SECURE 黑帧，PRD S-08，交互稿 Q3）。 */
    @Serializable
    @SerialName("PROTECTED")
    data class ProtectedContent(val suspected: Boolean) : DcMessage

    @Serializable
    @SerialName("AUDIO")
    data class Audio(val state: AudioState) : DcMessage

    // —— 反向控制（PRD F23，PROTOCOL.md 4.1 / 第 6 节）——

    /** 收→发：请求控制。[rid] 是接收端的请求号，[want] 是期望的能力（见 ControlFeatures）。 */
    @Serializable
    @SerialName("CTL_REQ")
    data class CtlRequest(val rid: Int, val want: List<String> = emptyList()) : DcMessage

    /**
     * 发→收：幂等的完整控制状态（DataChannel 打开时、任何变化时都发送）。
     *
     * @property rid 对应的请求号（没有请求时省略）。
     * @property reason 进入当前状态的原因：UNAVAILABLE / IDLE 时是不可用或结束的原因，PAUSED 时是暂停原因。
     * @property feat 发送端当前能执行的能力。
     * @property g 发送端当前几何代号（与 GEOM 一致）。
     * @property remainMs PENDING：确认剩余毫秒（接收端倒计时与发送端同步）；IDLE / UNAVAILABLE：距离可以再次请求的毫秒数（冷却）。
     * @property guide UNAVAILABLE + SERVICE_OFF 时：发送端是否已显示无障碍开启步骤（通知被关闭时为 false，接收端换提示文案）。
     */
    @Serializable
    @SerialName("CTL_STATE")
    data class CtlState(
        val state: ControlPhase,
        val rid: Int? = null,
        val reason: ControlReason? = null,
        val feat: List<String> = emptyList(),
        val g: Int? = null,
        val remainMs: Long? = null,
        val guide: Boolean? = null,
    ) : DcMessage

    /** 双向：结束控制 / 取消请求（接收端退出控制、取消请求；发送端收回）。 */
    @Serializable
    @SerialName("CTL_END")
    data class CtlEnd(val reason: ControlReason = ControlReason.RECEIVER_END) : DcMessage

    /**
     * 发→收：采集几何（PROTOCOL.md 6.3）。每次采集尺寸 / 旋转 / 被共享内容尺寸变化时 [g] 加一。
     *
     * @property cw 采集帧宽（= 编码前的帧尺寸，16 对齐）。
     * @property ch 采集帧高。
     * @property sw 被采集内容的宽（全屏 = 屏幕逻辑像素宽）。
     * @property sh 被采集内容的高。
     * @property mode FULL = 整个屏幕；APP = 单应用共享（Android 14+）。
     */
    @Serializable
    @SerialName("GEOM")
    data class Geometry(val g: Int, val cw: Int, val ch: Int, val sw: Int, val sh: Int, val mode: String? = null) : DcMessage

    companion object {
        val json: Json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "t"
            encodeDefaults = false
            explicitNulls = false
        }

        fun encode(message: DcMessage): String = json.encodeToString(serializer(), message)

        fun decodeOrNull(text: String): DcMessage? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
