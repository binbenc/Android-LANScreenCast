package com.benchan.wireless.screencasting.feature.receiver.session

import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade

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
)

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
)
