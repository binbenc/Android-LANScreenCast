package com.benchan.wireless.screencasting.core.discovery

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.devices.RecentDevicesStore
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererStore
import com.benchan.wireless.screencasting.core.data.dlna.LocalDlnaIdentity
import com.benchan.wireless.screencasting.core.discovery.dlna.DlnaMerge
import com.benchan.wireless.screencasting.core.discovery.dlna.DlnaRendererResolver
import com.benchan.wireless.screencasting.core.discovery.mdns.BrowseEvent
import com.benchan.wireless.screencasting.core.discovery.mdns.NsdBrowser
import com.benchan.wireless.screencasting.core.discovery.ssdp.SsdpHit
import com.benchan.wireless.screencasting.core.discovery.ssdp.SsdpSearcher
import com.benchan.wireless.screencasting.core.net.MulticastLockHolder
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发送端多路径并行发现（PRD 4.1①、8.4）：mDNS + SSDP + 最近设备直连 IP，结果按 deviceId 合并。
 * SSDP 里的电视自带 DLNA 要先拉设备描述（名字取 friendlyName；没有 AVTransport 不显示；地址必须在来源主机上），
 * 本应用接收端的 DMR 并入它的 WSC 行（PRD 8.1.1 B，技术方案 3.13.2）。
 * 进入发现页时 [start]，离开时 [stop]（期间持有 MulticastLock）。网络变化时重建 mDNS 会话。
 */
@Singleton
class DeviceDiscovery @Inject constructor(
    private val browser: NsdBrowser,
    private val ssdp: SsdpSearcher,
    private val probe: ReachabilityProbe,
    private val recent: RecentDevicesStore,
    private val network: NetworkMonitor,
    private val multicastLock: MulticastLockHolder,
    private val resolver: DlnaRendererResolver,
    private val dlnaStore: DlnaRendererStore,
    private val localDlna: LocalDlnaIdentity,
) {
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 本轮搜索开始时间（UI 用来计算 T+3s / T+10s，交互稿 02/04）。 */
    var startedAt: Long = 0
        private set

    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        startedAt = System.currentTimeMillis()
        _devices.value = emptyList()
        _searching.value = true
        multicastLock.acquire(OWNER)
        WscLog.i(TAG, "discovery start")
        job = scope.launch {
            // mDNS：网络变化时重建；每 60 秒也重建一次（长时间运行后解析停滞的已知问题）
            launch {
                network.lan.map { it?.address }.distinctUntilChanged().collectLatest { addr ->
                    if (addr == null) return@collectLatest
                    while (isActive) {
                        val session = launch {
                            browser.browse(network.lanNetwork()).collect { e ->
                                when (e) {
                                    is BrowseEvent.Found -> upsert(e.device)
                                    is BrowseEvent.Lost -> Unit // 不立即移除，避免列表闪烁；以 SSDP / 探测补充
                                    is BrowseEvent.Failed -> WscLog.w(TAG, "mdns failed ${e.errorCode}")
                                }
                            }
                        }
                        delay(MDNS_RESTART_MS)
                        session.cancel()
                    }
                }
            }
            // SSDP：前 15 秒每 5 秒一次，之后每 15 秒一次；第一轮额外发 ssdp:all
            launch {
                var round = 0
                while (isActive) {
                    ssdp.search(network.lan.value?.address, includeAll = round == 0).forEach { hit -> launch { onSsdpHit(hit) } }
                    delay(if (round++ < 3) 5_000 else 15_000)
                }
            }
            // 最近设备：直接用缓存的 IP 探测
            launch {
                recent.devices.value.forEach { r ->
                    launch {
                        if (probe.isReachable(r.host, r.port)) {
                            upsert(
                                DiscoveredDevice(
                                    deviceId = r.deviceId, name = r.name, host = r.host, port = r.port,
                                    fingerprintHex = r.fingerprint, sources = setOf(DiscoverySource.RECENT),
                                    lastSeenAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
            }
            // 最近推送过的 DLNA 电视：按上次的描述地址直接探测（电视重启后端口可能变，尽力而为）
            launch {
                dlnaStore.records.value.filter { it.location != null }.take(MAX_RECENT_DLNA).forEach { rec ->
                    launch {
                        val loc = rec.location ?: return@launch
                        val host = DlnaRendererResolver.hostOf(loc) ?: return@launch
                        val ref = resolver.resolve(loc, host, null) ?: return@launch
                        if (ref.udn != rec.udn || isSelf(ref, host)) return@launch
                        upsert(DlnaRendererResolver.toDevice(ref, DiscoverySource.RECENT, System.currentTimeMillis()))
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _searching.value = false
        multicastLock.release(OWNER)
        WscLog.i(TAG, "discovery stop, found ${_devices.value.size}")
    }

    /** 重新搜索（诊断页“刷新”）。 */
    fun restart(scope: CoroutineScope) {
        stop()
        resolver.invalidate()
        start(scope)
    }

    private suspend fun onSsdpHit(hit: SsdpHit) {
        when (hit) {
            is SsdpHit.Wsc -> {
                upsert(hit.device)
                val loc = hit.location ?: return
                val ref = resolver.resolve(loc, hit.host, null) ?: return
                if (isSelf(ref, hit.host)) return
                // 本应用接收端：DMR 信息挂到 WSC 行上（D13-1：可以推送）
                _devices.update { list -> list.map { if (it.deviceId == hit.device.deviceId) it.copy(dlna = ref) else it } }
            }
            is SsdpHit.Renderer -> {
                if (isSelf(null, hit.host)) return
                val ref = resolver.resolve(hit.location, hit.host, hit.server) ?: return
                if (isSelf(ref, hit.host)) return
                upsert(DlnaRendererResolver.toDevice(ref, DiscoverySource.SSDP, System.currentTimeMillis()))
            }
        }
    }

    private fun isSelf(ref: DlnaRendererRef?, host: String): Boolean =
        DlnaMerge.isSelf(ref, host, network.lan.value?.ip, runCatching { localDlna.udn }.getOrNull())

    private fun upsert(device: DiscoveredDevice) {
        _devices.update { list ->
            val existing = list.firstOrNull { it.deviceId == device.deviceId }
            val merged = existing?.mergeWith(device) ?: device
            if (existing == null) WscLog.i(TAG, "found ${device.protocol} via ${device.sources.joinToString()} after ${System.currentTimeMillis() - startedAt}ms")
            DlnaMerge.fold(listOf(merged) + list.filterNot { it.deviceId == device.deviceId })
        }
    }

    private companion object {
        const val TAG = "DeviceDiscovery"
        const val OWNER = "sender-discovery"
        const val MDNS_RESTART_MS = 60_000L
        const val MAX_RECENT_DLNA = 5
    }
}
