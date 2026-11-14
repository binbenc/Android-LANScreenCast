package com.benchan.wireless.screencasting.core.protocol.session

import com.benchan.wireless.screencasting.core.protocol.WscProtocol

/**
 * 信令重连的指数退避（PRD 8.4：0.5s / 1s / 2s …，总时长最多 30 秒）。
 * 每一步的间隔上限 [maxDelayMs]，总等待超过 [windowMs] 后返回 null（应结束会话）。
 */
class ReconnectBackoff(
    private val initialDelayMs: Long = 500,
    private val maxDelayMs: Long = 4_000,
    private val windowMs: Long = WscProtocol.RECONNECT_WINDOW_MS,
) {
    var attempt: Int = 0
        private set

    /** @param elapsedMs 自断线起已经过的时间。返回下一次尝试前应等待的毫秒数，或 null 表示放弃。 */
    fun nextDelay(elapsedMs: Long): Long? {
        if (elapsedMs >= windowMs) return null
        val raw = initialDelayMs shl attempt.coerceAtMost(20)
        val delay = raw.coerceAtMost(maxDelayMs).coerceAtMost(windowMs - elapsedMs)
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }

    companion object {
        /** 生成完整的退避序列，便于测试与文档。 */
        fun schedule(initialDelayMs: Long = 500, maxDelayMs: Long = 4_000, windowMs: Long = WscProtocol.RECONNECT_WINDOW_MS): List<Long> {
            val b = ReconnectBackoff(initialDelayMs, maxDelayMs, windowMs)
            val out = mutableListOf<Long>()
            var elapsed = 0L
            while (true) {
                val d = b.nextDelay(elapsed) ?: break
                out += d
                elapsed += d
            }
            return out
        }
    }
}
