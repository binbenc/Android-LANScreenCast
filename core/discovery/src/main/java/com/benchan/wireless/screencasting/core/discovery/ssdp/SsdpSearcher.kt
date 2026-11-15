package com.benchan.wireless.screencasting.core.discovery.ssdp

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpMessage
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpPacket
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpSocket
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import com.benchan.wireless.screencasting.protocol.upnp.UpnpServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.URLDecoder
import javax.inject.Inject

/** 一条 SSDP 响应的解析结果。 */
sealed interface SsdpHit {
    val host: String

    /** 设备描述地址（还没校验；拉取前由 RendererDescriber 校验与来源主机一致）。 */
    val location: String?

    /** 带 X-WSC-* 头 = 本应用接收端（mDNS 失效时的 WSC 入口）；它的 DMR 可以接收推送（D13-1）。 */
    data class Wsc(val device: DiscoveredDevice, override val location: String?) : SsdpHit {
        override val host: String get() = device.host
    }

    /** 普通 MediaRenderer / AVTransport = 电视自带 DLNA（F13 推送）。名字要等设备描述（不用 SERVER 头当名字）。 */
    data class Renderer(val uuid: String, override val host: String, override val location: String, val server: String?) : SsdpHit
}

/**
 * 发送端 SSDP 搜索（PRD 8.4 第二条组播路径、技术方案 3.13.2）：每轮发 MediaRenderer:1 与 AVTransport:1；
 * [includeAll] 时再发一次 ssdp:all（部分电视只回应它）。
 */
class SsdpSearcher @Inject constructor() {

    suspend fun search(localAddress: Inet4Address?, includeAll: Boolean = false, windowMs: Long = 2_500): List<SsdpHit> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, SsdpHit>()
        runCatching {
            SsdpSocket(localAddress, 0).use { socket ->
                val targets = listOf(SsdpMessage.MEDIA_RENDERER, UpnpServices.AV_TRANSPORT)
                repeat(2) { targets.forEach { st -> socket.sendMulticast(SsdpMessage.search(st, mx = 2)) } }
                if (includeAll) socket.sendMulticast(SsdpMessage.search("ssdp:all", mx = 2))
                val deadline = System.currentTimeMillis() + windowMs
                while (isActive && System.currentTimeMillis() < deadline) {
                    val p = socket.receive() ?: continue
                    if (p.from == localAddress) continue
                    val hit = parse(p) ?: continue
                    val key = when (hit) {
                        is SsdpHit.Wsc -> "wsc:" + hit.device.deviceId
                        is SsdpHit.Renderer -> "dlna:" + hit.uuid
                    }
                    found[key] = when (val old = found[key]) {
                        is SsdpHit.Wsc -> if (hit is SsdpHit.Wsc) SsdpHit.Wsc(old.device.mergeWith(hit.device), old.location ?: hit.location) else old
                        else -> old ?: hit
                    }
                }
            }
        }.onFailure { WscLog.w(TAG, "ssdp search failed", it) }
        found.values.toList()
    }

    /** 统计组播是否通畅：发 ssdp:all，返回 [windowMs] 内回应的不同主机数（网络诊断用）。 */
    suspend fun countResponders(localAddress: Inet4Address?, windowMs: Long = 2_000): Int = withContext(Dispatchers.IO) {
        val hosts = HashSet<String>()
        runCatching {
            SsdpSocket(localAddress, 0).use { socket ->
                socket.sendMulticast(SsdpMessage.search("ssdp:all", mx = 1))
                val deadline = System.currentTimeMillis() + windowMs
                while (isActive && System.currentTimeMillis() < deadline) {
                    val p = socket.receive() ?: continue
                    if (p.from != localAddress) p.from.hostAddress?.let(hosts::add)
                }
            }
        }
        hosts.size
    }

    companion object {
        private const val TAG = "SsdpSearcher"

        fun parse(packet: SsdpPacket, now: Long = System.currentTimeMillis()): SsdpHit? {
            val m = packet.message
            if (!m.isResponse && !m.isNotify) return null
            val host = packet.from.hostAddress ?: return null
            val wscPort = m[SsdpMessage.X_WSC_PORT]?.toIntOrNull()
            val usn = m["USN"]?.substringBefore("::")?.removePrefix("uuid:")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val location = m["LOCATION"]?.trim()?.takeIf { it.isNotEmpty() }
            if (wscPort != null && wscPort in 1..65535) {
                return SsdpHit.Wsc(
                    DiscoveredDevice(
                        deviceId = m[SsdpMessage.X_WSC_ID] ?: usn,
                        name = m[SsdpMessage.X_WSC_NAME]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: host,
                        host = host,
                        port = wscPort,
                        version = m[SsdpMessage.X_WSC_VERSION]?.toIntOrNull(),
                        fingerprintPrefix = m[SsdpMessage.X_WSC_FP]?.let(Fingerprint::prefixFromTxt),
                        sources = setOf(DiscoverySource.SSDP),
                        lastSeenAt = now,
                    ),
                    location,
                )
            }
            val st = m["ST"] ?: m["NT"] ?: return null
            if (!st.contains("MediaRenderer", ignoreCase = true) && !st.contains("AVTransport", ignoreCase = true)) return null
            return SsdpHit.Renderer(usn, host, location ?: return null, m["SERVER"])
        }
    }
}
