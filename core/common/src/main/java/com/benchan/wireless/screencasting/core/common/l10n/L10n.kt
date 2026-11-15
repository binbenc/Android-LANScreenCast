package com.benchan.wireless.screencasting.core.common.l10n

import java.util.Locale

/**
 * 纯 JVM 模块的极简中英文选择（应用只支持 en / zh，默认英文；任意 zh 地区显示中文）。
 *
 * **只限纯 JVM 模块（core/common、core/protocol、core/control 等不能访问 Android 资源的代码）使用，且只用于确实难以
 * 拆成“枚举 / 错误码 / 结构化数据 → UI 层映射字符串资源”的少量文案。** Android 模块（core/ui、feature/\*、app、
 * protocol-impl/\*）一律用 `res/values/strings.xml`（英文）+ `res/values-zh/strings.xml`（中文）。
 *
 * 首选做法：JVM 层返回枚举 / 结构化数据，由 Android UI 层映射到字符串资源（例如 `core/ui` 的
 * `QualityProfile.labelRes` / `LatencyGrade.labelRes`）。
 *
 * 每次调用都读 [Locale.getDefault]（不缓存）：Android 13+ 的“应用语言”或系统语言切换后，框架会更新进程默认 Locale。
 * 日志不要用它——日志保持原样不翻译。
 */
object L10n {
    /** [locale] 是否为中文（zh、zh-CN、zh-TW、zh-Hant-HK…）。 */
    fun isChinese(locale: Locale = Locale.getDefault()): Boolean = locale.language.equals("zh", ignoreCase = true)

    /** 按当前默认 Locale 选择：中文环境返回 [zh]，其他一律返回 [en]。 */
    fun text(en: String, zh: String): String = text(en, zh, Locale.getDefault())

    /** 指定 [locale] 的版本，便于单测（单测不依赖 JVM 默认 Locale）。 */
    fun text(en: String, zh: String, locale: Locale): String = if (isChinese(locale)) zh else en
}
