package com.benchan.wireless.screencasting.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.benchan.wireless.screencasting.core.common.WscLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 监听当前局域网（Wi-Fi 优先，其次以太网——电视盒子常用有线）。
 * 网络变化时发现服务需要重建（技术方案 3.6），WebRTC 由 GATHER_CONTINUALLY 自动收集新候选。
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _lan = MutableStateFlow(snapshot())
    val lan: StateFlow<LanInfo?> = _lan.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refresh()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
    }

    init {
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { WscLog.w(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    fun refresh() {
        val next = snapshot()
        if (next != _lan.value) {
            WscLog.i(TAG, "lan changed: ${next?.let { "${it.transport} ${it.ip}/${it.prefixLength} vpn=${it.vpnActive}" } ?: "none"}")
        }
        _lan.value = next
    }

    /** 当前局域网所在的 Network（API 33+ 用于把 NSD 绑定到 Wi-Fi，技术方案 3.6）。 */
    fun lanNetwork(): Network? = cm.allNetworksCompat().firstOrNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun snapshot(): LanInfo? = runCatching {
        val networks = cm.allNetworksCompat()
        val vpn = networks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        val ordered = networks.mapNotNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
            val t = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanInfo.Transport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanInfo.Transport.ETHERNET
                else -> return@mapNotNull null
            }
            t to n
        }.sortedBy { it.first.ordinal }
        for ((transport, n) in ordered) {
            val lp = cm.getLinkProperties(n) ?: continue
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: continue
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            return@runCatching LanInfo(lp.interfaceName, la.address as Inet4Address, la.prefixLength, gw, transport, vpn)
        }
        null
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.allNetworksCompat(): List<Network> = allNetworks.toList()

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
