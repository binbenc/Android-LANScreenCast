package com.benchan.wireless.screencasting.core.protocol.datachannel

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
 * WebRTC DataChannel 消息（PRD 8.3.1）：STATS / SET_QUALITY / APP_SWITCHED，以及 MVP 用到的若干状态提示。
 * JSON 以 "t" 作类型判别字段。
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
    ) : DcMessage

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
