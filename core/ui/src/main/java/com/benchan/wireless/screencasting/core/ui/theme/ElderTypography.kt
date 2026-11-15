package com.benchan.wireless.screencasting.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 长辈模式字号（PRD 8.1.4 E，交互稿第 16 章“长辈字号与触控”）。
 *
 * 长辈字号 = 普通字号 × m，m = clamp(2.0 / fontScale, 1.0, 1.3)，仍以 sp 计算（系统字体倍数照常叠加）；
 * PRD 下限优先于倍数（正文 ≥20、按钮 ≥22、主按钮 26、状态标题 32，均指系统 100% 时）；
 * 最终渲染尺寸不超过普通字号的 2 倍（系统已开 200% 时不再放大）——这条上限优先于下限。
 * 纯逻辑，单测覆盖。**不改 LocalDensity**（会连图标、间距、系统组件一起放大）。
 */
object ElderScale {
    const val MAX_MULTIPLIER = 1.3f
    const val MAX_TOTAL = 2.0f

    /** m = clamp(2.0 / fontScale, 1.0, 1.3)。 */
    fun multiplier(fontScale: Float): Float {
        val fs = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        return (MAX_TOTAL / fs).coerceIn(1f, MAX_MULTIPLIER)
    }

    /**
     * 某一层级的长辈字号（sp）。
     * @param normalSp 普通模式同层级字号
     * @param elderSp 交互稿表格里系统 100% 时的长辈字号（已含 PRD 下限：正文 20、按钮 22、主按钮 26、标题 32）
     *
     * 结果 = elderSp × (m / 1.3)，不小于普通字号；渲染尺寸（sp × fontScale）不超过普通字号的 2 倍
     * （系统字体本身超过 200% 时只保证不比普通字号更大）。
     */
    fun size(normalSp: Float, elderSp: Float, fontScale: Float): Float {
        val fs = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        val scaled = maxOf(elderSp * multiplier(fs) / MAX_MULTIPLIER, normalSp)
        val cap = maxOf(normalSp * MAX_TOTAL / fs, normalSp)
        return minOf(scaled, cap)
    }
}

/** 长辈页面用的一套字号（只在长辈页面使用，通过 [LocalElderTypography] 提供）。 */
@Immutable
data class ElderTypography(
    /** 页面状态标题：“正在投到 客厅电视”（普通 headlineSmall 24 → 32）。 */
    val headline: TextStyle,
    /** 首页主按钮主文字（普通 titleLarge 20 → 26）。 */
    val title: TextStyle,
    /** 所有按钮文字（PRD 下限 22；普通基准按 16 计，保证任何倍数下不小于正文）。 */
    val label: TextStyle,
    /** 正文（普通 bodyLarge 16，PRD 下限 20）。 */
    val body: TextStyle,
    /** 按钮副文字 / 说明第二行（普通 bodyMedium 14 → 18）。 */
    val bodySmall: TextStyle,
    /** 4 个数字的格子（普通 PIN 28 → 36）。 */
    val digitCell: TextStyle,
    /** 数字键盘的键（→ 32）。 */
    val digitKey: TextStyle,
) {
    companion object {
        private fun style(normal: Float, min: Float, lineRatio: Float, weight: Int, fontScale: Float, mono: Boolean = false): TextStyle {
            val size = ElderScale.size(normal, min, fontScale)
            return TextStyle(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = size.sp,
                lineHeight = (size * lineRatio).sp,
                fontWeight = FontWeight(weight),
                letterSpacing = 0.sp,
                fontFeatureSettings = if (mono) "tnum" else null,
            )
        }

        /** 按当前系统字体倍数生成（交互稿表格：系统 100% 时 32 / 26 / 22 / 20 / 18 / 36 / 32）。 */
        fun forFontScale(fontScale: Float): ElderTypography = ElderTypography(
            headline = style(24f, 32f, 1.25f, 600, fontScale),
            title = style(20f, 26f, 1.31f, 500, fontScale),
            // 按钮文字的普通基准取 16（同正文）：系统字体大于约 145% 时按钮字也不会比正文小（UX P1-4）
            label = style(16f, 22f, 1.36f, 500, fontScale),
            body = style(16f, 20f, 1.5f, 400, fontScale),
            bodySmall = style(14f, 18f, 1.45f, 400, fontScale),
            digitCell = style(28f, 36f, 1.2f, 500, fontScale, mono = true),
            digitKey = style(24f, 32f, 1.2f, 500, fontScale, mono = true),
        )
    }
}

/** 长辈页面的触控尺寸（交互稿第 16 章：所有可点元素 ≥64 dp）。 */
object ElderDimens {
    val MinTouch = 64.dp
    val MainButton = 112.dp
    val MainButtonPair = 96.dp
    val SecondaryButton = 88.dp
    val StopButton = 72.dp
    val Key = 72.dp
    val Row = 88.dp
    val Gap = 16.dp
}

val LocalElderTypography = staticCompositionLocalOf { ElderTypography.forFontScale(1f) }

/** 为长辈页面提供 [ElderTypography]：只读取系统字体倍数，不覆盖 Density。 */
@Composable
fun ProvideElderTypography(content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val typo = remember(fontScale) { ElderTypography.forFontScale(fontScale) }
    CompositionLocalProvider(LocalElderTypography provides typo, content = content)
}
