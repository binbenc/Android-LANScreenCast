package com.benchan.wireless.screencasting.core.discovery.mdns

import com.benchan.wireless.screencasting.core.protocol.WscProtocol

/**
 * mDNS TXT 记录（PRD 8.3.1）：v=协议版本, id=设备ID, n=设备名, caps=h264,h265,opus, fp=证书指纹前 8 字节，
 * 可选 dlna=本机 DLNA 接收（DMR）的 HTTP 端口（DLNA 接收开着时才带；旧版接收端没有，见 PROTOCOL.md 第 1 节）。
 */
data class WscTxt(
    val version: Int,
    val deviceId: String,
    val name: String,
    val caps: List<String>,
    val fingerprintPrefix: String,
    val dlnaPort: Int? = null,
) {
    fun toAttributes(): Map<String, String> = withDlnaPort(baseAttributes(), dlnaPort)

    private fun baseAttributes(): Map<String, String> = linkedMapOf(
        WscProtocol.Txt.VERSION to version.toString(),
        WscProtocol.Txt.DEVICE_ID to deviceId,
        WscProtocol.Txt.NAME to truncateUtf8(name, MAX_NAME_BYTES),
        WscProtocol.Txt.CAPS to caps.joinToString(","),
        WscProtocol.Txt.FINGERPRINT_PREFIX to fingerprintPrefix,
    )

    companion object {
        /** 每个键值对不超过 255 字节；设备名按 UTF-8 截断，不截断半个字符（技术方案 3.6）。 */
        const val MAX_NAME_BYTES = 200

        fun parse(attributes: Map<String, ByteArray?>): WscTxt? {
            fun s(k: String) = attributes[k]?.toString(Charsets.UTF_8)
            val id = s(WscProtocol.Txt.DEVICE_ID)?.takeIf { it.isNotBlank() } ?: return null
            return WscTxt(
                version = s(WscProtocol.Txt.VERSION)?.toIntOrNull() ?: 1,
                deviceId = id,
                name = s(WscProtocol.Txt.NAME).orEmpty(),
                caps = s(WscProtocol.Txt.CAPS)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                fingerprintPrefix = s(WscProtocol.Txt.FINGERPRINT_PREFIX).orEmpty(),
                dlnaPort = parsePort(s(WscProtocol.Txt.DLNA_PORT)),
            )
        }

        /** 端口必须是 1..65535 的十进制整数，否则视为没有（按旧版接收端处理）。 */
        fun parsePort(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

        /** 在已有 TXT 属性上加 / 去掉 `dlna` 键（[port] 为 null 表示 DLNA 接收没开）。 */
        fun withDlnaPort(attributes: Map<String, String>, port: Int?): Map<String, String> {
            val out = LinkedHashMap(attributes)
            out.remove(WscProtocol.Txt.DLNA_PORT)
            if (port != null && port in 1..65535) out[WscProtocol.Txt.DLNA_PORT] = port.toString()
            return out
        }

        fun truncateUtf8(text: String, maxBytes: Int): String {
            if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
            val sb = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val chunk = String(Character.toChars(cp))
                val n = chunk.toByteArray(Charsets.UTF_8).size
                if (bytes + n > maxBytes) break
                sb.append(chunk)
                bytes += n
                i += Character.charCount(cp)
            }
            return sb.toString()
        }
    }
}
