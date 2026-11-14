package com.benchan.wireless.screencasting.core.discovery

/** 发现路径（PRD 8.4，写入投屏报告的 discoveryPath）。 */
enum class DiscoverySource { MDNS, SSDP, RECENT, QR, CAST_CODE, MANUAL }

/** 设备支持的协议：WSC（可镜像）或仅 DLNA（MVP 置灰，V1 推送，交互稿 03 / Q4）。 */
enum class DeviceProtocol { WSC, DLNA_ONLY }

/**
 * 发送端看到的一台接收端。以 [deviceId]（mDNS TXT 的 id）为准去重，不依赖服务名（系统可能自动改名为 "TV (2)"）。
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
) {
    override fun equals(other: Any?): Boolean = other is DiscoveredDevice && deviceId == other.deviceId && name == other.name &&
        host == other.host && port == other.port && protocol == other.protocol && sources == other.sources &&
        fingerprintHex == other.fingerprintHex && (fingerprintPrefix?.contentEquals(other.fingerprintPrefix ?: ByteArray(0)) ?: (other.fingerprintPrefix == null))

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
        )
    }
}
