package com.benchan.wireless.screencasting.core.discovery.mdns

import com.benchan.wireless.screencasting.core.protocol.WscProtocol

/** mDNS TXT 记录（PRD 8.3.1）：v=协议版本, id=设备ID, n=设备名, caps=h264,h265,opus, fp=证书指纹前 8 字节。 */
data class WscTxt(
    val version: Int,
    val deviceId: String,
    val name: String,
    val caps: List<String>,
    val fingerprintPrefix: String,
) {
    fun toAttributes(): Map<String, String> = linkedMapOf(
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
            )
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
