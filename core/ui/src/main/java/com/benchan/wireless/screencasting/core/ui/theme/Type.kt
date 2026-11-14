package com.benchan.wireless.screencasting.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 字体层级（交互稿 05）：不打包字体，系统字体即 Roboto + Noto Sans CJK；中文字重只用 400/500/600；
 * 中文行高 1.5~1.6；数字一律等宽（tnum），延迟跳动时不抖动。TV 字号 ≈ 手机 × 1.27。
 */
private fun style(size: Int, line: Int, weight: Int, tnum: Boolean = false) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = 0.sp,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

val PhoneTypography = Typography(
    headlineLarge = style(28, 36, 600),
    headlineSmall = style(24, 32, 600),
    headlineMedium = style(26, 34, 600),
    titleLarge = style(20, 28, 500),
    titleMedium = style(16, 24, 500),
    titleSmall = style(14, 20, 500),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 22, 400),
    bodySmall = style(12, 18, 400),
    labelLarge = style(14, 20, 500),
    labelMedium = style(12, 16, 500),
    labelSmall = style(11, 16, 500),
    displaySmall = style(40, 48, 500, tnum = true),
)

/** TV（设计基准 960×540 dp）。最小正文 16，辅助信息最小 14。 */
val TvTypography = Typography(
    displayMedium = style(48, 58, 600),
    displaySmall = style(44, 52, 500, tnum = true),
    headlineSmall = style(26, 34, 600),
    headlineMedium = style(30, 38, 600),
    titleLarge = style(22, 30, 600),
    titleMedium = style(20, 28, 500),
    bodyLarge = style(18, 28, 400),
    bodyMedium = style(16, 24, 400),
    bodySmall = style(14, 20, 400),
    labelLarge = style(16, 22, 500),
    labelMedium = style(14, 20, 500),
    labelSmall = style(14, 20, 500),
)

/** 计时 / 统计大数字（手机 40/48，等宽数字）。 */
val DisplayNumber = style(40, 48, 500, tnum = true)

/** PIN、投屏码：等宽 + 0.08em 字距（交互稿 05）。 */
val PinStyleTv = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
val PinStylePhone = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
val MonoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
