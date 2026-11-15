package com.benchan.wireless.screencasting.core.common.net

import java.net.Inet4Address
import java.net.InetAddress

/**
 * 零外联检查（PRD 8.5）：局域网地址 = RFC1918 私有地址、链路本地、回环、组播或 100.64/10（CGNAT）。
 * 纯 Kotlin，供 JVM 模块（:protocol:upnp）与 Android 模块共用。
 */
object LanAddress {
    fun isLan(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || a.isMulticastAddress ||
            (a is Inet4Address && (a.address[0].toInt() and 0xFF) == 100 && ((a.address[1].toInt() and 0xC0) == 64))

    /** 只解析 IPv4 点分十进制字面量（不做 DNS 查询）。 */
    fun parseIpv4(text: String): Inet4Address? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val v = p.toInt()
            if (v > 255) return null
            bytes[i] = v.toByte()
        }
        return InetAddress.getByAddress(bytes) as Inet4Address
    }

    /** [host] 是局域网 IPv4 字面量。 */
    fun isLanIpv4Literal(host: String?): Boolean {
        val a = host?.let(::parseIpv4) ?: return false
        return isLan(a)
    }
}
