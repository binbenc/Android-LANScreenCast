package com.benchan.wireless.screencasting.core.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.ui.R

/*
 * 多语言约定（en 默认 / zh）：纯 JVM 模块（core/protocol、core/control…）只给枚举 / 结构化数据，
 * Android UI 层在这里把它们映射到字符串资源。feature 模块显示档位名、延迟等级时用下面的函数，
 * 不要再用 JVM 层的 `QualityProfile.label` / `LatencyGrade.label`（那两个只为纯 JVM 代码与兼容保留）。
 */

/** 画质档位名：Smooth / Balanced / High / Game（流畅 / 均衡 / 高清 / 游戏）。 */
@get:StringRes
val QualityProfile.labelRes: Int
    get() = when (this) {
        QualityProfile.SMOOTH -> R.string.core_ui_quality_smooth
        QualityProfile.BALANCED -> R.string.core_ui_quality_balanced
        QualityProfile.HD -> R.string.core_ui_quality_high
        QualityProfile.GAME -> R.string.core_ui_quality_game
    }

@Composable
fun QualityProfile.displayLabel(): String = stringResource(labelRes)

/** 非 Compose 场景（通知、Service）。 */
fun QualityProfile.displayLabel(context: Context): String = context.getString(labelRes)

/** “1080p · 30 fps · up to 8 Mbps”（交互稿 07）。 */
@Composable
fun QualityProfile.displaySummary(): String = stringResource(R.string.core_ui_quality_summary, shortEdge, fps, maxBitrateBps / 1_000_000)

fun QualityProfile.displaySummary(context: Context): String =
    context.getString(R.string.core_ui_quality_summary, shortEdge, fps, maxBitrateBps / 1_000_000)

/** 延迟等级：Excellent / Good / Fair / Poor（极佳 / 良好 / 一般 / 较差）。 */
@get:StringRes
val LatencyGrade.labelRes: Int
    get() = when (this) {
        LatencyGrade.EXCELLENT -> R.string.core_ui_latency_excellent
        LatencyGrade.GOOD -> R.string.core_ui_latency_good
        LatencyGrade.FAIR -> R.string.core_ui_latency_fair
        LatencyGrade.POOR -> R.string.core_ui_latency_poor
    }

@Composable
fun LatencyGrade.displayLabel(): String = stringResource(labelRes)

fun LatencyGrade.displayLabel(context: Context): String = context.getString(labelRes)
