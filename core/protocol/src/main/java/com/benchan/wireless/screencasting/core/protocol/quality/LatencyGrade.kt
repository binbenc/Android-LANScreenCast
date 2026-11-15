package com.benchan.wireless.screencasting.core.protocol.quality

import com.benchan.wireless.screencasting.core.common.l10n.L10n
import java.util.Locale

/**
 * 延迟色阶（交互稿 03；阈值来自 PRD 1.3）：<100 极佳、100–150 良好、150–250 一般、>250 较差。
 */
enum class LatencyGrade {
    EXCELLENT, GOOD, FAIR, POOR;

    /**
     * 等级文字（Excellent/Good/Fair/Poor，中文环境为 极佳/良好/一般/较差）。
     * Android UI 请优先用 `core/ui` 的 `LatencyGrade.labelRes` / `displayLabel()`；这里供纯 JVM 代码与兼容旧调用点。
     */
    val label: String get() = label(Locale.getDefault())

    fun label(locale: Locale): String = when (this) {
        EXCELLENT -> L10n.text("Excellent", "极佳", locale)
        GOOD -> L10n.text("Good", "良好", locale)
        FAIR -> L10n.text("Fair", "一般", locale)
        POOR -> L10n.text("Poor", "较差", locale)
    }

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
