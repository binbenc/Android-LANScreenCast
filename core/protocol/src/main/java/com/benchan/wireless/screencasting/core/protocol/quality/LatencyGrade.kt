package com.benchan.wireless.screencasting.core.protocol.quality

/**
 * 延迟色阶（交互稿 03；阈值来自 PRD 1.3）：<100 极佳、100–150 良好、150–250 一般、>250 较差。
 */
enum class LatencyGrade(val label: String) {
    EXCELLENT("极佳"), GOOD("良好"), FAIR("一般"), POOR("较差");

    companion object {
        fun of(latencyMs: Int): LatencyGrade = when {
            latencyMs < 100 -> EXCELLENT
            latencyMs <= 150 -> GOOD
            latencyMs <= 250 -> FAIR
            else -> POOR
        }
    }
}

/**
 * 胶囊上的等级文字只在跨档后持续 [holdMs]（1 秒）才变化，防止 99/101 ms 来回闪（交互稿 07）。
 */
class LatencyGradeTracker(private val holdMs: Long = 1_000) {
    var current: LatencyGrade? = null
        private set
    private var pending: LatencyGrade? = null
    private var pendingSince = 0L

    fun update(latencyMs: Int, nowMs: Long): LatencyGrade {
        val g = LatencyGrade.of(latencyMs)
        val cur = current
        if (cur == null) {
            current = g
            return g
        }
        if (g == cur) {
            pending = null
            return cur
        }
        if (pending != g) {
            pending = g
            pendingSince = nowMs
        } else if (nowMs - pendingSince >= holdMs) {
            current = g
            pending = null
        }
        return current!!
    }
}
