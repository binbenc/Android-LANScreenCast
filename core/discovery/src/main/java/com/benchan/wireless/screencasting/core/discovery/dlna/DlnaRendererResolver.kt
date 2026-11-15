package com.benchan.wireless.screencasting.core.discovery.dlna

import com.benchan.wireless.screencasting.core.discovery.DeviceProtocol
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.discovery.DlnaRendererRef
import com.benchan.wireless.screencasting.protocol.upnp.RendererDescriber
import com.benchan.wireless.screencasting.protocol.upnp.UrlGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 拉取 DLNA 设备描述与 Sink（技术方案 3.13.2.2）：按 LOCATION 缓存（成功 5 分钟、失败 30 秒），并发 ≤4。
 * 所有地址的零外联 / 同主机校验在 [RendererDescriber] 与解析器里完成。
 */
@Singleton
class DlnaRendererResolver internal constructor(
    private val describer: RendererDescriber,
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(RendererDescriber(), System::currentTimeMillis)

    private data class Entry(val ref: DlnaRendererRef?, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val permits = Semaphore(4)

    suspend fun resolve(location: String, sourceHost: String, server: String?): DlnaRendererRef? {
        cache[location]?.let { e ->
            val ttl = if (e.ref != null) SUCCESS_TTL_MS else FAILURE_TTL_MS
            if (clock() - e.at < ttl) return e.ref
        }
        // 主线程安全：调用方（DeviceDiscovery 跑在 viewModelScope / Main）不需要关心线程（第 2 轮复测）
        val ref = withContext(Dispatchers.IO) { permits.withPermit {
            val d = describer.describe(location, sourceHost, server) ?: return@withPermit null
            DlnaRendererRef(d.description, d.server, describer.sink(d.description))
        } }
        cache[location] = Entry(ref, clock())
        return ref
    }

    fun invalidate() = cache.clear()

    companion object {
        const val SUCCESS_TTL_MS = 5 * 60_000L
        const val FAILURE_TTL_MS = 30_000L

        /** 电视自带 DLNA 的一行（PRD 8.1.1 B）。 */
        fun toDevice(ref: DlnaRendererRef, source: DiscoverySource, now: Long): DiscoveredDevice = DiscoveredDevice(
            deviceId = "dlna:" + ref.udn,
            name = ref.description.displayName,
            host = ref.description.host,
            port = runCatching { URI(ref.description.location).port }.getOrDefault(-1).takeIf { it > 0 } ?: 80,
            protocol = DeviceProtocol.DLNA_ONLY,
            sources = setOf(source),
            lastSeenAt = now,
            dlna = ref,
        )

        fun hostOf(location: String): String? = UrlGuard.hostOf(location)
    }
}

/**
 * 合并规则（纯逻辑，单测覆盖）：
 * - 本机（来源 IP = 本机 IP，或 UDN = 本机 DMR 的 UDN）一律排除；
 * - 本应用接收端的 DMR（描述里有 wsc:id）并入同 ID 的 WSC 行，使该行“能镜像 + 能推送”；
 * - 其余按 deviceId（"dlna:<UDN>"）去重，同一台电视的多个 DMR 保留多行。
 */
object DlnaMerge {
    fun isSelf(ref: DlnaRendererRef?, host: String, ownIp: String?, ownUdn: String?): Boolean =
        (ownIp != null && host == ownIp) || (ref != null && ownUdn != null && ref.udn == ownUdn)

    /** 把 [list] 里能并入 WSC 行的 DLNA 行合并掉。 */
    fun fold(list: List<DiscoveredDevice>): List<DiscoveredDevice> {
        val wscIds = list.filter { it.protocol == DeviceProtocol.WSC }.associateBy { it.deviceId }
        if (wscIds.isEmpty()) return list
        val absorbed = HashMap<String, DlnaRendererRef>()
        val rest = list.filter { d ->
            val wid = d.dlna?.description?.wscId
            if (d.protocol == DeviceProtocol.DLNA_ONLY && wid != null && wscIds.containsKey(wid)) {
                absorbed[wid] = d.dlna
                false
            } else true
        }
        if (absorbed.isEmpty()) return list
        return rest.map { d -> absorbed[d.deviceId]?.let { ref -> if (d.dlna == null) d.copy(dlna = ref) else d } ?: d }
    }
}

/**
 * mDNS TXT 的 `dlna` 键 → 本应用接收端的 DMR（SSDP 不通时的补充路径，PROTOCOL.md 第 1 节）。纯逻辑，单测覆盖。
 * 描述地址固定为 `http://<host>:<port>/dlna/description.xml`；解析出的描述必须带非空 UDN、wsc:id 等于这条 WSC 行的 deviceId，
 * 且不是本机，才把 DMR 挂到 WSC 行上（效果与 SSDP 的 `SsdpHit.Wsc` 分支相同）。
 */
object MdnsDlnaLink {
    const val DESCRIPTION_PATH = "/dlna/description.xml"

    fun descriptionUrl(host: String, port: Int): String = "http://$host:$port$DESCRIPTION_PATH"

    fun accepts(ref: DlnaRendererRef, wscDeviceId: String, host: String, ownIp: String?, ownUdn: String?): Boolean =
        ref.udn.isNotBlank() && ref.description.wscId == wscDeviceId && !DlnaMerge.isSelf(ref, host, ownIp, ownUdn)
}
