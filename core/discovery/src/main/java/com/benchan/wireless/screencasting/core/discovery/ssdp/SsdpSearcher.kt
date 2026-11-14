package com.benchan.wireless.screencasting.core.discovery.ssdp

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.discovery.DeviceProtocol
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpMessage
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpPacket
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpSocket
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject

/**
 * 发送端 SSDP 搜索（PRD 8.4 第二条组播路径）：
 * - 带 X-WSC-* 头的响应 = 我们的接收端（mDNS 失效时的 WSC 入口）；
 * - 普通 MediaRenderer = 电视自带 DLNA（MVP 列表置灰，V1 推送）。
 */
class SsdpSearcher @Inject constructor() {

    /** 发送 M-SEARCH 并收集 [windowMs] 内的响应。 */
    suspend fun search(localAddress: Inet4Address?, windowMs: Long = 2_500): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredDevice>()
        runCatching {
            SsdpSocket(localAddress, 0).use { socket ->
                val search = SsdpMessage.search(SsdpMessage.MEDIA_RENDERER, mx = 2)
                repeat(2) { socket.sendMulticast(search) }
                val deadline = System.currentTimeMillis() + windowMs
                while (isActive && System.currentTimeMillis() < deadline) {
                    val p = socket.receive() ?: continue
                    parse(p)?.let { d -> found[d.deviceId] = found[d.deviceId]?.mergeWith(d) ?: d }
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

        fun parse(packet: SsdpPacket, now: Long = System.currentTimeMillis()): DiscoveredDevice? {
            val m = packet.message
            if (!m.isResponse && !m.isNotify) return null
            val host = packet.from.hostAddress ?: return null
            val wscPort = m[SsdpMessage.X_WSC_PORT]?.toIntOrNull()
            val usn = m["USN"]?.substringBefore("::")?.removePrefix("uuid:") ?: return null
            return if (wscPort != null && wscPort in 1..65535) {
                DiscoveredDevice(
                    deviceId = m[SsdpMessage.X_WSC_ID] ?: usn,
                    name = m[SsdpMessage.X_WSC_NAME]?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: host,
                    host = host,
                    port = wscPort,
                    version = m[SsdpMessage.X_WSC_VERSION]?.toIntOrNull(),
                    fingerprintPrefix = m[SsdpMessage.X_WSC_FP]?.let(Fingerprint::prefixFromTxt),
                    sources = setOf(DiscoverySource.SSDP),
                    lastSeenAt = now,
                )
            } else {
                val st = m["ST"] ?: m["NT"] ?: return null
                if (!st.contains("MediaRenderer", ignoreCase = true)) return null
                val location = m["LOCATION"]?.let { runCatching { URI(it) }.getOrNull() }
                DiscoveredDevice(
                    deviceId = "dlna:$usn",
                    name = m["SERVER"]?.substringAfterLast(' ')?.takeIf { it.isNotBlank() } ?: host,
                    host = host,
                    port = location?.port ?: 0,
                    protocol = DeviceProtocol.DLNA_ONLY,
                    sources = setOf(DiscoverySource.SSDP),
                    lastSeenAt = now,
                )
            }
        }
    }
}
