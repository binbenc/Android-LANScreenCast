package com.benchan.wireless.screencasting.protocol.upnp

import com.benchan.wireless.screencasting.core.common.net.LanAddress
import java.net.URI

/**
 * 零外联 / 防 SSRF（PRD 8.1.1 E、技术方案 3.13.2）：控制点只访问 http、IPv4 字面量、局域网地址；
 * 设备描述里的地址还必须与 SSDP 响应来源 IP 相同。
 */
object UrlGuard {
    /** http + 局域网 IPv4 字面量（不做 DNS）。 */
    fun isLanHttp(url: String): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        if (!u.scheme.equals("http", ignoreCase = true)) return false
        if (u.userInfo != null) return false
        return LanAddress.isLanIpv4Literal(u.host)
    }

    /** [isLanHttp] 且主机就是 [sourceHost]（SSDP 响应来源）。 */
    fun isSameLanHost(url: String, sourceHost: String): Boolean {
        if (!isLanHttp(url)) return false
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host == sourceHost
    }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
}
