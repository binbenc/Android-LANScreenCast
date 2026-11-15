package com.benchan.wireless.screencasting.core.discovery

import com.benchan.wireless.screencasting.protocol.upnp.ProtocolInfo
import com.benchan.wireless.screencasting.protocol.upnp.RendererDescription

/** 发现路径（PRD 8.4，写入投屏报告的 discoveryPath）。 */
enum class DiscoverySource { MDNS, SSDP, RECENT, QR, CAST_CODE, MANUAL }

/** 设备支持的协议：WSC（可镜像，可能同时能推送）或仅 DLNA（只能推送，PRD 8.1.1 B）。 */
enum class DeviceProtocol { WSC, DLNA_ONLY }

/** 一台可推送的 DMR（设备描述 + SSDP Server 头 + 电视声明的格式）。 */
data class DlnaRendererRef(
    val description: RendererDescription,
    val server: String?,
    /** GetProtocolInfo 的 Sink；拿不到为 null。 */
    val sink: List<ProtocolInfo>? = null,
) {
    val udn: String get() = description.udn

    /** 只支持音频的接收端（如音箱，D13-2）：Sink 明确只有 audio。 */
    val audioOnly: Boolean
        get() = sink != null && sink.any { it.isHttpGet } && sink.filter { it.isHttpGet }.none {
            it.contentFormat == "*" || it.contentFormat.startsWith("video/") || it.contentFormat.startsWith("image/") ||
                it.contentFormat.startsWith("application/")
        }
}

/**
 * 发送端看到的一台接收端。以 [deviceId]（mDNS TXT 的 id，或 "dlna:<UDN>"）为准去重，不依赖服务名（系统可能自动改名为 "TV (2)"）。
 */
data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: DeviceProtocol = DeviceProtocol.WSC,
    val version: Int? = null,
    val caps: List<String> = emptyList(),
    /** mDNS TXT / SSDP 头里的指纹前缀（8 字节）。 */
    val fingerprintPrefix: ByteArray? = null,
    /** 最近设备 / 可信列表 / 二维码里的完整指纹（hex）。 */
    val fingerprintHex: String? = null,
    val sources: Set<DiscoverySource>,
    val lastSeenAt: Long,
    /** 能推送时的 DMR 信息（电视自带 DLNA，或本应用接收端的 DLNA 接收）。 */
    val dlna: DlnaRendererRef? = null,
    /** 第一次被发现的时间（发现耗时用它，不用 lastSeenAt）。 */
    val firstSeenAt: Long = lastSeenAt,
) {
    val canMirror: Boolean get() = protocol == DeviceProtocol.WSC
    val canPush: Boolean get() = dlna != null

    override fun equals(other: Any?): Boolean = other is DiscoveredDevice && deviceId == other.deviceId && name == other.name &&
        host == other.host && port == other.port && protocol == other.protocol && sources == other.sources &&
        fingerprintHex == other.fingerprintHex && dlna == other.dlna &&
        (fingerprintPrefix?.contentEquals(other.fingerprintPrefix ?: ByteArray(0)) ?: (other.fingerprintPrefix == null))

    override fun hashCode(): Int = deviceId.hashCode() * 31 + host.hashCode()

    /** 合并来自不同路径的同一台设备：完整指纹优先，路径取并集，地址取最新。 */
    fun mergeWith(other: DiscoveredDevice): DiscoveredDevice {
        val newer = if (other.lastSeenAt >= lastSeenAt) other else this
        return newer.copy(
            name = newer.name.ifBlank { name },
            fingerprintHex = fingerprintHex ?: other.fingerprintHex,
            fingerprintPrefix = fingerprintPrefix ?: other.fingerprintPrefix,
            version = newer.version ?: version ?: other.version,
            caps = newer.caps.ifEmpty { caps },
            protocol = if (protocol == DeviceProtocol.WSC || other.protocol == DeviceProtocol.WSC) DeviceProtocol.WSC else DeviceProtocol.DLNA_ONLY,
            sources = sources + other.sources,
            // WSC 设备的端口不能被 DLNA 条目覆盖
            port = if (protocol == DeviceProtocol.WSC && other.protocol != DeviceProtocol.WSC) port
            else if (other.protocol == DeviceProtocol.WSC && protocol != DeviceProtocol.WSC) other.port else newer.port,
            dlna = newer.dlna ?: dlna ?: other.dlna,
            firstSeenAt = minOf(firstSeenAt, other.firstSeenAt),
        )
    }
}
