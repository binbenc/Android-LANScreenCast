package com.benchan.wireless.screencasting.core.net.ssdp

/**
 * SSDP（UPnP Device Architecture 1.1 第 1 章）报文：M-SEARCH、NOTIFY、HTTP/1.1 200 OK 响应。
 * 纯文本编解码，可单测。头字段名大小写不敏感。
 */
data class SsdpMessage(
    /** 起始行，例如 "M-SEARCH * HTTP/1.1"、"NOTIFY * HTTP/1.1"、"HTTP/1.1 200 OK"。 */
    val startLine: String,
    val headers: Map<String, String>,
) {
    val isSearch: Boolean get() = startLine.startsWith("M-SEARCH", ignoreCase = true)
    val isNotify: Boolean get() = startLine.startsWith("NOTIFY", ignoreCase = true)
    val isResponse: Boolean get() = startLine.startsWith("HTTP/", ignoreCase = true)

    operator fun get(name: String): String? = headers[name.uppercase()]

    fun encode(): String = buildString {
        append(startLine).append("\r\n")
        headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
        append("\r\n")
    }

    companion object {
        const val ADDRESS = "239.255.255.250"
        const val PORT = 1900
        const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

        /** WSC 的 SSDP 扩展头（第二条发现路径，技术方案 3.6：mDNS 失效时可从 SSDP 拿到 WSC 入口）。 */
        const val X_WSC_PORT = "X-WSC-PORT"
        const val X_WSC_ID = "X-WSC-ID"
        const val X_WSC_FP = "X-WSC-FP"
        const val X_WSC_NAME = "X-WSC-NAME"
        const val X_WSC_VERSION = "X-WSC-V"

        private const val MAX_SIZE = 8 * 1024

        fun parse(text: String): SsdpMessage? {
            if (text.length > MAX_SIZE) return null
            val lines = text.split("\r\n", "\n")
            val start = lines.firstOrNull()?.trim().orEmpty()
            if (start.isEmpty()) return null
            if (!(start.startsWith("M-SEARCH", true) || start.startsWith("NOTIFY", true) || start.startsWith("HTTP/", true))) return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
            return SsdpMessage(start, headers)
        }

        fun search(st: String, mx: Int = 2): SsdpMessage = SsdpMessage(
            "M-SEARCH * HTTP/1.1",
            linkedMapOf(
                "HOST" to "$ADDRESS:$PORT",
                "MAN" to "\"ssdp:discover\"",
                "MX" to mx.toString(),
                "ST" to st,
                "USER-AGENT" to "Android/10 UPnP/1.1 WSC/1.0",
            ),
        )

        /** CACHE-CONTROL: max-age=N → N 秒。 */
        fun maxAge(msg: SsdpMessage): Int? =
            msg["CACHE-CONTROL"]?.substringAfter("max-age", "")?.trimStart(' ', '=')?.takeWhile { it.isDigit() }?.toIntOrNull()
    }
}
