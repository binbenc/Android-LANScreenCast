package com.benchan.wireless.screencasting.core.ui

import com.benchan.wireless.screencasting.core.ui.theme.ElderScale
import com.benchan.wireless.screencasting.core.ui.theme.ElderTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElderScaleTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.01f)

    @Test
    fun multiplierFollowsDesignTable() {
        // 交互稿第 16 章：系统 100% → ×1.3；130% → ×1.3；160% → ×1.25；200% → ×1.0
        near(1.3f, ElderScale.multiplier(1.0f))
        near(1.3f, ElderScale.multiplier(1.3f))
        near(1.25f, ElderScale.multiplier(1.6f))
        near(1.0f, ElderScale.multiplier(2.0f))
        // 边界：更小的系统字体不超过 1.3；更大的不低于 1.0；异常值按 100% 处理
        near(1.3f, ElderScale.multiplier(0.85f))
        near(1.0f, ElderScale.multiplier(2.5f))
        near(1.3f, ElderScale.multiplier(Float.NaN))
        near(1.3f, ElderScale.multiplier(0f))
    }

    @Test
    fun prdMinimumsApplyAtDefaultFontScale() {
        val t = ElderTypography.forFontScale(1f)
        near(32f, t.headline.fontSize.value)
        near(26f, t.title.fontSize.value)
        near(22f, t.label.fontSize.value)
        near(20f, t.body.fontSize.value)
        near(18f, t.bodySmall.fontSize.value)
        near(36f, t.digitCell.fontSize.value)
    }

    @Test
    fun renderedSizeNeverExceedsTwiceNormal() {
        val levels = listOf(24f to 32f, 20f to 26f, 14f to 22f, 16f to 20f, 14f to 18f, 28f to 36f)
        for (fs in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.6f, 1.8f, 2f, 2.5f)) {
            for ((normal, min) in levels) {
                val sp = ElderScale.size(normal, min, fs)
                // 渲染尺寸不超过普通字号的 2 倍；系统字体本身超过 200% 时不比普通字号更大
                assertTrue("fs=$fs normal=$normal sp=$sp", sp * fs <= maxOf(normal * 2f, normal * fs) + 0.01f)
                // 不会比普通字号还小
                assertTrue("fs=$fs normal=$normal sp=$sp", sp >= normal - 0.01f)
            }
        }
    }

    @Test
    fun scalesDownBetween130And200Percent() {
        // 160%：m = 1.25 → 正文 20 × 1.25 / 1.3 ≈ 19.2，渲染 30.8 ≤ 32
        near(20f * 1.25f / 1.3f, ElderScale.size(16f, 20f, 1.6f))
    }

    @Test
    fun buttonTextNeverSmallerThanBody() {
        for (fs in listOf(0.85f, 1f, 1.3f, 1.45f, 1.6f, 1.8f, 2f, 2.5f)) {
            val t = ElderTypography.forFontScale(fs)
            assertTrue("fs=$fs", t.label.fontSize.value >= t.body.fontSize.value - 0.01f)
        }
    }

    @Test
    fun systemAt200PercentNoExtraEnlargement() {
        near(16f, ElderScale.size(16f, 20f, 2f))
        near(14f, ElderScale.size(14f, 22f, 2f))
    }
}
