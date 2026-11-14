package com.benchan.wireless.screencasting.core.protocol.quality

/** 每秒一次的网络观测值（来自 WebRTC getStats）。 */
data class NetworkSample(
    val availableOutgoingBitrateBps: Long?,
    val packetLossPercent: Double,
    val rttMs: Int?,
)

/**
 * “自动调节”：网络变差时自动降为“流畅”（PRD 10 网络变差、交互稿 07）。
 * 连续 [badSecondsToDowngrade] 秒满足任一条件即降档：丢包 > 5%、可用带宽 < 当前档位上限的 40%、RTT > 150ms。
 * 只降不升（升档交给用户，避免来回切换），用户可在 Snackbar 上“撤销”。
 */
class AutoQualityPolicy(
    private val badSecondsToDowngrade: Int = 5,
    private val lossThresholdPercent: Double = 5.0,
    private val bandwidthRatio: Double = 0.4,
    private val rttThresholdMs: Int = 150,
) {
    private var badStreak = 0

    /** @return 需要切换到的档位；不需要切换时返回 null。 */
    fun onSample(current: QualityProfile, enabled: Boolean, sample: NetworkSample): QualityProfile? {
        if (!enabled || current == QualityProfile.SMOOTH) {
            badStreak = 0
            return null
        }
        val bwBad = sample.availableOutgoingBitrateBps?.let { it < current.maxBitrateBps * bandwidthRatio } ?: false
        val bad = sample.packetLossPercent > lossThresholdPercent || bwBad || (sample.rttMs ?: 0) > rttThresholdMs
        badStreak = if (bad) badStreak + 1 else 0
        if (badStreak >= badSecondsToDowngrade) {
            badStreak = 0
            return QualityProfile.SMOOTH
        }
        return null
    }

    fun reset() {
        badStreak = 0
    }
}
