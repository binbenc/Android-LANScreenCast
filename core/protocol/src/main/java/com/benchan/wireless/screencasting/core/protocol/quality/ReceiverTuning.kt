package com.benchan.wireless.screencasting.core.protocol.quality

/**
 * 接收端低延迟参数（F18，PRD 8.1.3 E；技术方案 3.4 “低延迟三项组合”）。所有待扫参的数值集中在这里。
 *
 * 字段试验和解码器在建立 PeerConnectionFactory 时一次性确定，会话中改变只能重建媒体连接（[QualityProfile.needsMediaRebuild]）。
 *
 * @property playoutDelayMaxMs `WebRTC-ForcePlayoutDelay` 的 max_ms（min_ms 固定 0）。
 * @property lowLatencyDecoder 是否使用低延迟解码器工厂（API 30+ `KEY_LOW_LATENCY` + 厂商私有键，失败回退标准解码器）。
 * @property audioFastAccelerate NetEq 快速追赶（`RTCConfiguration.audioJitterBufferFastAccelerate`）：缓冲积压时更积极地加速播放。
 * @property audioJitterMaxPackets NetEq 最大缓冲包数（`audioJitterBufferMaxPackets`，每包 20 ms）；null = libwebrtc 默认（200）。
 */
data class ReceiverTuning(
    val playoutDelayMaxMs: Int,
    val lowLatencyDecoder: Boolean,
    val audioFastAccelerate: Boolean,
    val audioJitterMaxPackets: Int?,
) {
    companion object {
        /**
         * 普通档（流畅 / 均衡 / 高清）：沿用 MVP 实际生效的值（max_ms=100），不改变既有行为。
         * TODO(M0-T4): 扫参 100 / 150 校准。
         */
        val NORMAL = ReceiverTuning(playoutDelayMaxMs = 100, lowLatencyDecoder = false, audioFastAccelerate = false, audioJitterMaxPackets = null)

        /**
         * 游戏档：max_ms=0（`min=max=0` 走 libwebrtc 的“零播放延迟”路径：来一帧解一帧、不为平滑加缓冲，抖动时允许跳帧，PRD 8.1.3 E 渲染）。
         * 音频缓冲上限 25 包（约 500 ms）+ 快速追赶，避免声音越拖越晚。
         * TODO(M0-T4): 真机扫参 max_ms = 0 / 20 / 50，以统计里 jitterBufferDelay 明显低于均衡档为生效判据（PRD 8.1.3 J-2）；
         *  若 0 在某些设备上卡顿明显，改用 20~50（min=0 且 max>0 走低延迟渲染器）。
         */
        val GAME = ReceiverTuning(playoutDelayMaxMs = 0, lowLatencyDecoder = true, audioFastAccelerate = true, audioJitterMaxPackets = 25)

        /** 未知档位（null，例如旧版发送端没带 profile）按普通档。 */
        fun of(profile: QualityProfile?): ReceiverTuning = if (profile?.lowLatency == true) GAME else NORMAL
    }
}
