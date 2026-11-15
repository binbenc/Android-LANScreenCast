package com.benchan.wireless.screencasting.core.protocol.quality

/**
 * 游戏模式提示（交互稿 15 G04 “提示位”，PRD 8.1.3 F / G / H）。[priority] 越小越优先；同时成立时只显示最高的一张，
 * 关掉后显示下一张；每类每次投屏最多出现一次。
 */
enum class GameHintType(val priority: Int) {
    /** ① 切换没有成功，已保持原档位。 */
    SWITCH_FAILED(1),

    /** ② 手机温度较高，已降为 30 帧。 */
    THERMAL_30FPS(2),

    /** ③ 电视 / 手机跟不上 60 帧，已改为 30 帧。 */
    FPS_30(3),

    /** ④ 已切到 720p · 60 帧，低延迟将在下次投屏生效（做不到即时生效）。 */
    NEXT_TIME(4),

    /** ⑤ 对方的局域投屏版本较旧。 */
    LEGACY_RECEIVER(5),

    /** ⑥ 延迟达不到游戏模式的 100 ms。 */
    LATENCY_UNMET(6),

    /** ⑦ 网络较差（条件消失时丢弃）。 */
    POOR_NETWORK(7),

    /** ⑧ 声音比画面晚（当前测不出来，不触发：不显示猜测的数字）。 */
    AUDIO_LATE(8),

    /** ⑨ 电量低。 */
    LOW_BATTERY(9),
}

/** 降为 30 帧的原因。 */
enum class FpsCapReason { THERMAL, RECEIVER, SENDER, REMEMBERED }

/** 延迟达不到时的主要原因（PRD 8.1.3 F）。 */
enum class LatencyCause { DECODER, NETWORK }

/** 一张提示卡。 */
sealed class GameHint(val type: GameHintType) {
    data class SwitchFailed(val kept: QualityProfile) : GameHint(GameHintType.SWITCH_FAILED)
    data object Thermal : GameHint(GameHintType.THERMAL_30FPS)
    data class FpsDropped(val receiverCause: Boolean) : GameHint(GameHintType.FPS_30)
    data object NextTime : GameHint(GameHintType.NEXT_TIME)
    data object LegacyReceiver : GameHint(GameHintType.LEGACY_RECEIVER)
    /** [cause] 为 null：解码与网络都不明显偏慢，只给数字不给原因（不猜）。 */
    data class LatencyUnmet(val latencyMs: Int, val cause: LatencyCause?) : GameHint(GameHintType.LATENCY_UNMET)
    data object PoorNetwork : GameHint(GameHintType.POOR_NETWORK)
    data class LowBattery(val percent: Int) : GameHint(GameHintType.LOW_BATTERY)

    /** 系统已经替用户改了设置（warningContainer，交互稿 G04 颜色规则）。 */
    val warning: Boolean get() = type == GameHintType.THERMAL_30FPS || type == GameHintType.FPS_30
}

/**
 * 提示队列（纯逻辑）：情境提示每类每次投屏最多一次；按优先级只暴露一张；[知道了] 关闭本类；条件消失时 [invalidate]。
 * ①“切换没有成功”和 ④“下次生效”是用户这次操作的直接结果，**每次都提示**（新的覆盖旧的，产品拍板）。
 */
class GameHintQueue {
    private val shown = mutableSetOf<GameHintType>()
    private val pending = mutableMapOf<GameHintType, GameHint>()

    /** 当前应显示的提示（没有则 null）。 */
    val current: GameHint? get() = pending.values.minByOrNull { it.type.priority }

    /** @return 是否被接受（情境提示：本次投屏该类第一次出现；操作结果：总是接受）。 */
    fun offer(hint: GameHint): Boolean {
        if (hint.type in shown && hint.type !in REPEATABLE) return false
        shown += hint.type
        pending[hint.type] = hint
        return true
    }

    fun dismiss(type: GameHintType) {
        pending.remove(type)
    }

    /** 条件已不成立（例如网络恢复）：未看的卡片直接丢弃。 */
    fun invalidate(type: GameHintType) {
        pending.remove(type)
    }

    fun reset() {
        shown.clear()
        pending.clear()
    }

    companion object {
        /** 不受“每类每次投屏一次”限制的类型。 */
        val REPEATABLE = setOf(GameHintType.SWITCH_FAILED, GameHintType.NEXT_TIME)
    }
}

/**
 * 每秒一次的游戏模式观测值（发送端统计 + 接收端 RSTATS）。
 *
 * @property captureFps 采集侧“内容帧率”：VirtualDisplay 真正出的新帧（不含静止画面的保底重发）；画面静止 / 30 帧游戏时本来就低。
 * @property senderFps 发送端实际编码发出的帧率（outbound-rtp framesPerSecond）。
 * @property receiverFps 接收端显示帧率（旧版接收端 / 没有 RSTATS 时为 null）。
 */
data class GameSample(
    val latencyMs: Int?,
    val senderFps: Int,
    val receiverFps: Int?,
    val receiverDecodeMs: Double?,
    val receiverLowLatency: Boolean?,
    val captureFps: Int? = null,
    val rttMs: Int? = null,
    val lossPercent: Double = 0.0,
)

/**
 * 游戏模式“达不到目标”的判定（PRD 8.1.3 F；数值实测后校准，集中在构造参数）：
 * - 延迟：进入游戏模式稳定 [settleSeconds] 秒后，最近 [windowSeconds] 秒估算延迟的中位数连续 [unmetSeconds] 秒 > [latencyLimitMs]。
 *   原因：解码段 > [slowDecodeMs] 或解码器不支持低延迟 → 电视解码慢；RTT > [badRttMs] 或丢包 > [badLossPercent] → 网络；
 *   两者都不明显 → 不给原因（不把“解码不慢”当成“网络差”）。之后中位数连续 [windowSeconds] 秒 ≤ [recoveredMs] → [Finding.LatencyRecovered]。
 * - 60 帧跟不上只看**相对值**，画面本身帧率低（静止、30 帧游戏）不算：
 *   接收端原因 = 发送端实际发出 ≥ [fullFps] 帧而接收端显示 < 发出 × [keepRatio]；
 *   发送端原因 = 采集内容 ≥ [fullFps] 帧而实际发出 < 内容 × [keepRatio]（编码跟不上）。持续 [windowSeconds] 秒才报。
 * - 跑满 60 帧：发出 ≥ [fullFps] 且接收端显示 ≥ 发出 × [fullRatio] 持续 [fullRateSeconds] 秒 → [Finding.FullRate]（用于清除“这台电视只能 30 帧”的记忆）。
 * 每类只报一次；调用方在离开游戏档 / 改帧率后 [reset]。
 */
class GameModeMonitor(
    private val settleSeconds: Int = 10,
    private val windowSeconds: Int = 10,
    private val unmetSeconds: Int = 8,
    private val latencyLimitMs: Int = 120,
    private val recoveredMs: Int = 100,
    private val slowDecodeMs: Double = 40.0,
    private val badRttMs: Int = 40,
    private val badLossPercent: Double = 2.0,
    private val fullFps: Int = 50,
    private val keepRatio: Double = 0.75,
    private val fullRatio: Double = 0.9,
    private val fullRateSeconds: Int = 30,
) {
    sealed interface Finding {
        data class LatencyUnmet(val medianMs: Int, val cause: LatencyCause?) : Finding
        data object LatencyRecovered : Finding
        data class FpsLow(val receiverCause: Boolean) : Finding
        data object FullRate : Finding
    }

    private var seconds = 0
    private val latencies = ArrayDeque<Int>()
    private var overStreak = 0
    private var underStreak = 0
    private var rxLowStreak = 0
    private var txLowStreak = 0
    private var fullStreak = 0
    private var latencyReported = false
    private var recoveredReported = false
    private var fpsReported = false
    private var fullReported = false

    /**
     * @param expectedFps 当前目标帧率（已降为 30 帧时不再做 60 帧判定）。
     * @param checkLatency false 时不做延迟判定（例如旧版接收端：延迟高的原因是版本，已经由提示位 ⑤ 说明）。
     */
    fun onSample(s: GameSample, expectedFps: Int, checkLatency: Boolean = true): Finding? {
        seconds++
        s.latencyMs?.let {
            latencies.addLast(it)
            while (latencies.size > windowSeconds) latencies.removeFirst()
        }
        if (seconds <= settleSeconds) return null
        if (expectedFps >= 60) {
            val rx = s.receiverFps
            val rxLow = rx != null && s.senderFps >= fullFps && rx < s.senderFps * keepRatio
            val cap = s.captureFps
            val txLow = cap != null && cap >= fullFps && s.senderFps < cap * keepRatio
            rxLowStreak = if (rxLow) rxLowStreak + 1 else 0
            txLowStreak = if (txLow) txLowStreak + 1 else 0
            if (!fpsReported && (rxLowStreak >= windowSeconds || txLowStreak >= windowSeconds)) {
                fpsReported = true
                return Finding.FpsLow(receiverCause = rxLowStreak >= windowSeconds)
            }
            val full = rx != null && s.senderFps >= fullFps && rx >= s.senderFps * fullRatio
            fullStreak = if (full) fullStreak + 1 else 0
            if (!fullReported && fullStreak >= fullRateSeconds) {
                fullReported = true
                return Finding.FullRate
            }
        }
        if (checkLatency && latencies.size >= windowSeconds / 2) {
            val median = latencies.sorted()[latencies.size / 2]
            if (!latencyReported) {
                overStreak = if (median > latencyLimitMs) overStreak + 1 else 0
                if (overStreak >= unmetSeconds) {
                    latencyReported = true
                    return Finding.LatencyUnmet(median, causeOf(s))
                }
            } else if (!recoveredReported) {
                underStreak = if (median <= recoveredMs) underStreak + 1 else 0
                if (underStreak >= windowSeconds) {
                    recoveredReported = true
                    return Finding.LatencyRecovered
                }
            }
        }
        return null
    }

    private fun causeOf(s: GameSample): LatencyCause? = when {
        (s.receiverDecodeMs ?: 0.0) > slowDecodeMs || s.receiverLowLatency == false -> LatencyCause.DECODER
        (s.rttMs ?: 0) > badRttMs || s.lossPercent > badLossPercent -> LatencyCause.NETWORK
        else -> null
    }

    fun reset() {
        seconds = 0
        latencies.clear()
        overStreak = 0
        underStreak = 0
        rxLowStreak = 0
        txLowStreak = 0
        fullStreak = 0
        latencyReported = false
        recoveredReported = false
        fpsReported = false
        fullReported = false
    }
}
