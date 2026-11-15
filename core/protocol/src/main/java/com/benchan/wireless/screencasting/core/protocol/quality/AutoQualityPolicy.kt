package com.benchan.wireless.screencasting.core.protocol.quality

/** 每秒一次的网络观测值（来自 WebRTC getStats）。 */
data class NetworkSample(
    val availableOutgoingBitrateBps: Long?,
    val packetLossPercent: Double,
    val rttMs: Int?,
)

/** “自动调节”的决定。 */
sealed interface AutoQualityDecision {
    /** 普通档：降为 [to]（“流畅”）。 */
    data class Downgrade(val to: QualityProfile) : AutoQualityDecision

    /**
     * 游戏档：网络持续变差，但**不切档**（PRD 8.1.3 F “网络差”）。档内的降码率 / 降分辨率由 WebRTC 拥塞控制
     * 配合 MAINTAIN_FRAMERATE 自动完成（保帧率），这里只让界面提示一次“网络较差”并给出 [切到流畅]。
     */
    data object PoorNetworkInGame : AutoQualityDecision
}

/**
 * “自动调节”：网络变差时自动降为“流畅”（PRD 10 网络变差、交互稿 07）。
 * 连续 [badSecondsToDowngrade] 秒满足任一条件即触发：丢包 > 5%、可用带宽 < 当前档位上限的 40%、RTT > 150ms。
 * 只降不升（升档交给用户，避免来回切换），用户可在 Snackbar 上“撤销”。
 *
 * 游戏档（F18）：永不降成“流畅”（30 帧 + 普通缓冲与游戏模式目的相反），改为 [AutoQualityDecision.PoorNetworkInGame]，
 * 判定窗口 [gameBadSeconds] 秒（PRD 8.1.3 F：持续 10 秒），带宽条件为“低于档位下限”，且不依赖“自动调节”开关（只提示、不改档）。
 */
class AutoQualityPolicy(
    private val badSecondsToDowngrade: Int = 5,
    private val gameBadSeconds: Int = 10,
    private val lossThresholdPercent: Double = 5.0,
    private val bandwidthRatio: Double = 0.4,
    private val rttThresholdMs: Int = 150,
) {
    private var badStreak = 0

    /** 连续“网络正常”的秒数（提示位 ⑦ 在网络恢复后丢弃，交互稿 G04）。 */
    var goodStreak = 0
        private set

    /** @return 需要执行的决定；不需要时返回 null。 */
    fun onSample(current: QualityProfile, enabled: Boolean, sample: NetworkSample): AutoQualityDecision? {
        // 游戏档的“网络较差”提示与“自动调节”开关无关（PRD 8.1.3 F：游戏档本来就不切档，只提示）
        if ((!enabled && !current.lowLatency) || current == QualityProfile.SMOOTH) {
            badStreak = 0
            return null
        }
        // 游戏档按 PRD：可用码率低于档位下限才算差；其他档沿用“低于上限的 40%”
        val bwLimit = if (current.lowLatency) current.minBitrateBps.toDouble() else current.maxBitrateBps * bandwidthRatio
        val bwBad = sample.availableOutgoingBitrateBps?.let { it < bwLimit } ?: false
        val bad = sample.packetLossPercent > lossThresholdPercent || bwBad || (sample.rttMs ?: 0) > rttThresholdMs
        badStreak = if (bad) badStreak + 1 else 0
        goodStreak = if (bad) 0 else goodStreak + 1
        val limit = if (current.lowLatency) gameBadSeconds else badSecondsToDowngrade
        if (badStreak >= limit) {
            badStreak = 0
            return if (current.lowLatency) AutoQualityDecision.PoorNetworkInGame else AutoQualityDecision.Downgrade(QualityProfile.SMOOTH)
        }
        return null
    }

    fun reset() {
        badStreak = 0
        goodStreak = 0
    }
}
