package com.benchan.wireless.screencasting.core.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 长辈模式里家人固定的一台电视（PRD 8.1.4 B，决策 #41）：最多 2 台，按钮叫法可改（≤8 个字）。
 * 同一台设备可能同时能镜像（WSC 接收端）和推送（DLNA），两个身份都记下来。
 */
@Serializable
data class ElderTarget(
    /** WSC 接收端的 deviceId（能镜像时）。 */
    val wscId: String? = null,
    /** DLNA 渲染器 UDN（能推送时）。 */
    val udn: String? = null,
    /** 设备名（家人选择时看到的名字）。 */
    val name: String,
    /** 按钮上的叫法（默认 = 设备名）。 */
    val label: String = name,
) {
    val canMirror: Boolean get() = wscId != null
    val canPush: Boolean get() = udn != null

    /** 稳定键（界面去重 / 选中状态）。 */
    val key: String get() = wscId ?: "dlna:$udn"

    companion object {
        /** 叫法最多 8 个字（按 Unicode 码点计，交互稿 E03 计数器“4 / 8”）。 */
        const val MAX_LABEL = 8
        const val MAX_TARGETS = 2

        /** 截到 [MAX_LABEL] 个字（不拆开代理对）。 */
        fun clampLabel(text: String): String {
            val t = text.replace('\n', ' ')
            val count = t.codePointCount(0, t.length)
            if (count <= MAX_LABEL) return t
            return t.substring(0, t.offsetByCodePoints(0, MAX_LABEL))
        }

        fun labelLength(text: String): Int = text.codePointCount(0, text.length)

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val serializer = ListSerializer(serializer())

        fun encode(list: List<ElderTarget>): String = json.encodeToString(serializer, list)

        fun decode(text: String?): List<ElderTarget> =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }
}
