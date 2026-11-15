package com.benchan.wireless.screencasting.core.net

import com.benchan.wireless.screencasting.core.common.net.LanAddress
import java.net.Inet4Address
import java.net.InetAddress

/** 当前局域网信息。PRD 8.5：不读取 Wi-Fi 名称（需要定位权限），只显示网段。 */
data class LanInfo(
    val interfaceName: String?,
    val address: Inet4Address,
    val prefixLength: Int,
    val gateway: InetAddress?,
    val transport: Transport,
    val vpnActive: Boolean,
) {
    enum class Transport { WIFI, ETHERNET, OTHER }

    val ip: String get() = address.hostAddress ?: ""

    /** 例如 "192.168.1.x"（/24）或 "10.0.0.0/16"。 */
    val subnetHint: String
        get() {
            val b = address.address.map { it.toInt() and 0xFF }
            return if (prefixLength == 24) "${b[0]}.${b[1]}.${b[2]}.x" else "${networkAddress()}/$prefixLength"
        }

    fun networkAddress(): String {
        val v = InetUtils.toInt(address)
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return InetUtils.fromInt(v and mask).hostAddress ?: ""
    }

    fun sameSubnet(other: InetAddress): Boolean {
        if (other !is Inet4Address) return false
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        return (InetUtils.toInt(address) and mask) == (InetUtils.toInt(other) and mask)
    }
}

object InetUtils {
    fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    fun fromInt(v: Int): Inet4Address = InetAddress.getByAddress(
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
    ) as Inet4Address

    /**
     * 零外联检查（PRD 8.5）：局域网地址 = RFC1918 私有地址、链路本地、回环或组播。
     * 用于在诊断日志里标记意外的公网目的地址。
     */
    fun isLanAddress(a: InetAddress): Boolean = LanAddress.isLan(a)

    fun parseIpv4(text: String): Inet4Address? = LanAddress.parseIpv4(text)

    /** 解析“IP:端口”或“IP”（端口缺省时返回 [defaultPort]）。 */
    fun parseHostPort(text: String, defaultPort: Int): Pair<String, Int>? {
        val t = text.trim()
        val idx = t.lastIndexOf(':')
        val host = if (idx > 0) t.substring(0, idx) else t
        val port = if (idx > 0) t.substring(idx + 1).toIntOrNull() ?: return null else defaultPort
        if (port !in 1..65535) return null
        parseIpv4(host) ?: return null
        return host to port
    }
}
