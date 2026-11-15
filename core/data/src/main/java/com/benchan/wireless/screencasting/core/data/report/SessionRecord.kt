package com.benchan.wireless.screencasting.core.data.report

import kotlinx.serialization.Serializable

/** 协议类型（PRD 14 记录字段）。 */
@Serializable
enum class CastProtocol {
    WSC, DLNA, AIRPLAY,

    /** 发送端 DLNA 推送（F13，和接收端的 DLNA 区分开，PRD 8.1.1 G）。 */
    DLNA_PUSH,
}

/**
 * 一次投屏会话的本地记录（PRD 14：会话 ID、发现耗时与路径、首帧耗时、延迟 P50/P95、码率、丢帧、断线次数与原因、
 * 系统版本与机型、编解码器、协议类型）。不记录 Wi-Fi 名称、投屏内容、推送的网址。
 */
@Serializable
data class SessionRecord(
    val sessionId: String,
    val protocol: CastProtocol,
    val role: String,
    val peerName: String,
    val startedAt: Long,
    val endedAt: Long,
    val discoveryPath: String? = null,
    val discoveryMs: Long? = null,
    val firstFrameMs: Long? = null,
    /** 成功 = 60 秒内出首帧（交互稿 10 统计口径）。 */
    val success: Boolean,
    val latencyP50Ms: Int? = null,
    val latencyP95Ms: Int? = null,
    /** 延迟直方图：第 i 个桶覆盖 [i*10, i*10+10) ms，最后一个桶为溢出桶。 */
    val latencyHistogram: List<Int> = emptyList(),
    val avgBitrateKbps: Int? = null,
    val framesDropped: Long = 0,
    val disconnects: Int = 0,
    val autoRecovered: Int = 0,
    val endReason: String = "",
    val codec: String? = null,
    val androidVersion: String = "",
    val deviceModel: String = "",
    /** 反向控制（F23，用户决策 D8）：本次投屏被控制的累计时长（毫秒，含暂停）；只存本地，不记坐标与内容。 */
    val controlledMs: Long? = null,
    /** DLNA 推送专属字段（PRD 8.1.1 G）；不含文件名、路径、标题、网址、令牌。 */
    val push: PushReport? = null,
    /** 游戏模式（F18，PRD 8.1.3 I）：只在本次投屏用过游戏模式时出现。延迟与其他档位分开统计。 */
    val game: GameReport? = null,
)

/** 游戏模式报告字段（PRD 8.1.3 I）。不记录游戏或 App 名称、画面内容。 */
@Serializable
data class GameReport(
    /** 游戏模式累计时长（毫秒）。 */
    val durationMs: Long,
    /** 开始即游戏（true）/ 中途切换进入（false）。 */
    val enteredAtStart: Boolean,
    val switches: Int = 0,
    val switchFailures: Int = 0,
    /** 切换停顿（发出切换到新连接连通 / 接收端首帧）的最大值（毫秒）。 */
    val maxSwitchPauseMs: Long? = null,
    /** 游戏模式期间的延迟直方图（同 [SessionRecord.latencyHistogram] 的分桶）。 */
    val latencyHistogram: List<Int> = emptyList(),
    val latencyP50Ms: Int? = null,
    val latencyP95Ms: Int? = null,
    /** 接收端解码器是否开启了低延迟特性。 */
    val lowLatencyDecoder: Boolean? = null,
    /** 降为 30 帧的原因：THERMAL / RECEIVER / SENDER / REMEMBERED。 */
    val fpsCapReason: String? = null,
    /** 出现过的提示类型（GameHintType 名称）。 */
    val hints: List<String> = emptyList(),
    /** 接收端是否支持游戏模式（HELLO caps 含 game）。 */
    val receiverSupportsGame: Boolean? = null,
)

/** DLNA 推送报告字段（PRD 8.1.1 G）。 */
@Serializable
data class PushReport(
    /** VIDEO / IMAGE / AUDIO / URL / MIXED。 */
    val contentType: String,
    /** PICKER / FILES / URL / SHARE。 */
    val source: String,
    val mimeTypes: List<String> = emptyList(),
    /** <100MB / <1GB / <4GB / >=4GB。 */
    val sizeBucket: String? = null,
    val itemCount: Int = 1,
    val switchCount: Int = 0,
    val autoAdvanceCount: Int = 0,
    val tvManufacturer: String? = null,
    val tvModel: String? = null,
    val tvServer: String? = null,
    val upnpErrorCode: Int? = null,
    val httpStatus: Int? = null,
    val fallbacks: List<String> = emptyList(),
    /** EVENTS / POLL。 */
    val syncMode: String = "POLL",
    val rangeRequests: Int = 0,
    val transcode: TranscodeReport? = null,
)

/** 转换后推送（F2）的报告字段；不含转换文件路径。 */
@Serializable
data class TranscodeReport(
    val offered: Boolean = false,
    /** BEFORE_PUSH / AFTER_FAILURE。 */
    val offeredAt: String? = null,
    /** TRANSCODE / ORIGINAL / CANCEL。 */
    val choice: String? = null,
    val sourceContainer: String? = null,
    val sourceVideoCodec: String? = null,
    val sourceAudioCodec: String? = null,
    /** <=720p / 1080p / 4K。 */
    val sourceResolution: String? = null,
    val hdr: Boolean = false,
    val output: String? = null,
    val elapsedMs: Long? = null,
    /** SUCCESS / CANCELLED / FAILED / NO_SPACE / UNSUPPORTED。 */
    val result: String? = null,
    val thermalPauses: Int = 0,
    val errorCode: Int? = null,
)
