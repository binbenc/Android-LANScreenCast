package com.benchan.wireless.screencasting.feature.sender.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.feature.sender.R

/**
 * 与语言无关的界面文案：逻辑 / ViewModel 只产出资源引用 + 参数，由界面层按当前系统语言解析
 * （values = 英文默认，values-zh = 中文）。参数本身也可以是 [UiText]（例如“已切换到“<档位>””）。
 * 纯 JVM 单测直接比较 [Res] / [Plural] 的资源 ID 与参数，不需要 Android 运行时。
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** 不翻译的文字（用户设置的设备名、叫法等）。 */
    data class Raw(val value: String) : UiText

    fun resolve(context: Context): String = resolve(context.resources)

    fun resolve(res: Resources): String = when (this) {
        is Raw -> value
        is Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *resolveArgs(res, args))
        is Plural -> res.getQuantityString(id, count, *resolveArgs(res, args))
    }

    private companion object {
        fun resolveArgs(res: Resources, args: List<Any>): Array<Any> =
            args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray()
    }
}

internal fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

internal fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

/** Compose 中按当前配置（语言）解析。 */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)

/** 画质档位名（流畅 / 均衡 / 高清 / 游戏）。不用 core 里的中文 label，按系统语言取资源。 */
@get:StringRes
internal val QualityProfile.labelRes: Int
    get() = when (this) {
        QualityProfile.SMOOTH -> R.string.sender_quality_smooth
        QualityProfile.BALANCED -> R.string.sender_quality_balanced
        QualityProfile.HD -> R.string.sender_quality_hd
        QualityProfile.GAME -> R.string.sender_quality_game
    }

internal val QualityProfile.labelText: UiText get() = uiText(labelRes)

/** 交互稿 07 的档位说明，例如“1080p · 30 帧 · 最高 8 Mbps”。 */
internal val QualityProfile.summaryText: UiText
    get() = uiText(R.string.sender_quality_summary, shortEdge, fps, maxBitrateBps / 1_000_000)

/** 延迟等级（极佳 / 良好 / 一般 / 较差）。 */
@get:StringRes
internal val LatencyGrade.labelRes: Int
    get() = when (this) {
        LatencyGrade.EXCELLENT -> R.string.sender_latency_excellent
        LatencyGrade.GOOD -> R.string.sender_latency_good
        LatencyGrade.FAIR -> R.string.sender_latency_fair
        LatencyGrade.POOR -> R.string.sender_latency_poor
    }
