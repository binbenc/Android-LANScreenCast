package com.benchan.wireless.screencasting.feature.receiver.session

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * 与语言无关的界面文案：控制器 / 模型只产出资源引用 + 参数，由界面层按当前系统语言解析
 * （values = 英文默认，values-zh = 中文）。参数本身也可以是 [UiText]（例如“控制已暂停：<原因>”）。
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** 不翻译的文字（用户设置的设备名、App 品牌名等）。 */
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

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())

/** Compose 中按当前配置（语言）解析。 */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)
