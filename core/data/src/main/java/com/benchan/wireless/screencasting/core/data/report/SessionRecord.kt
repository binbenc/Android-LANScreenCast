package com.benchan.wireless.screencasting.core.data.report

import kotlinx.serialization.Serializable

/** 协议类型（PRD 14 记录字段）。 */
@Serializable
enum class CastProtocol { WSC, DLNA, AIRPLAY }

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
)
