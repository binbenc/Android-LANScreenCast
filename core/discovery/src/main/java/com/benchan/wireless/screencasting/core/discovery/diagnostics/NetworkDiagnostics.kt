package com.benchan.wireless.screencasting.core.discovery.diagnostics

import com.benchan.wireless.screencasting.core.discovery.ReachabilityProbe
import com.benchan.wireless.screencasting.core.discovery.ssdp.SsdpSearcher
import com.benchan.wireless.screencasting.core.net.LanInfo
import com.benchan.wireless.screencasting.core.net.MulticastLockHolder
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import javax.inject.Inject

/** 诊断项结论：已确认（绿勾）/ 需你确认（琥珀问号）/ 有问题（红）。交互稿 04：不假装能检测无法检测的事。 */
enum class CheckStatus { CONFIRMED, NEEDS_USER, PROBLEM }

data class DiagnosticCheck(val id: String, val status: CheckStatus, val title: String, val detail: String)

/**
 * 网络诊断（F11，PRD 10“没找到”状态）。能自动判断的只有：是否连着局域网、本机网段、是否开了 VPN、
 * 组播是否有回应、指定地址是否可达；“是否同一路由器 / AP 隔离 / 访客网络”只能由用户确认。
 */
class NetworkDiagnostics @Inject constructor(
    private val network: NetworkMonitor,
    private val ssdp: SsdpSearcher,
    private val probe: ReachabilityProbe,
    private val multicastLock: MulticastLockHolder,
) {
    suspend fun run(target: Pair<String, Int>? = null): List<DiagnosticCheck> {
        network.refresh()
        val lan = network.lan.value
        val checks = mutableListOf<DiagnosticCheck>()
        if (lan == null) {
            checks += DiagnosticCheck("lan", CheckStatus.PROBLEM, "没有连接 Wi‑Fi 或有线网络", "请先连接家里的路由器，电视也要连同一个路由器")
            return checks
        }
        val transport = if (lan.transport == LanInfo.Transport.ETHERNET) "有线网络" else "Wi‑Fi"
        checks += DiagnosticCheck(
            "lan", CheckStatus.CONFIRMED, "手机已连接 $transport",
            "本机地址 ${lan.ip}，电视应为 ${lan.subnetHint}",
        )
        if (lan.vpnActive) {
            checks += DiagnosticCheck("vpn", CheckStatus.PROBLEM, "检测到 VPN 已开启", "VPN 可能让局域网设备互相看不到，请暂时关闭后重试")
        }
        if (!CastCode.supportsNetwork(lan.prefixLength)) {
            checks += DiagnosticCheck("prefix", CheckStatus.NEEDS_USER, "当前网段较大（/${lan.prefixLength}）", "投屏码只支持 /24 网段，请改用扫码连接")
        }
        multicastLock.acquire(OWNER)
        val responders = try {
            ssdp.countResponders(lan.address)
        } finally {
            multicastLock.release(OWNER)
        }
        checks += if (responders > 0) {
            DiagnosticCheck("multicast", CheckStatus.CONFIRMED, "局域网组播正常", "收到 $responders 台设备的回应")
        } else {
            DiagnosticCheck("multicast", CheckStatus.NEEDS_USER, "没有收到任何设备的组播回应", "路由器可能拦截了组播（自动发现），扫码或投屏码不受影响")
        }
        target?.let { (host, port) ->
            val ok = probe.isReachable(host, port)
            checks += if (ok) {
                DiagnosticCheck("target", CheckStatus.CONFIRMED, "可以连上 $host", "端口 $port 可达")
            } else {
                DiagnosticCheck("target", CheckStatus.PROBLEM, "连不上 $host:$port", "电视可能已关机、退出了接收页面，或不在同一网段")
            }
        }
        checks += DiagnosticCheck("router", CheckStatus.NEEDS_USER, "电视和手机连的是同一个路由器吗？", "双频路由的 2.4G 与 5G 有时互不相通")
        checks += DiagnosticCheck("isolation", CheckStatus.NEEDS_USER, "路由器是否开启了“AP 隔离”？", "也叫无线隔离 / 客户端隔离，开启后设备之间无法互访")
        checks += DiagnosticCheck("guest", CheckStatus.NEEDS_USER, "是否连接了“访客网络”？", "访客网络通常禁止设备互访，请换到主网络")
        return checks
    }

    private companion object {
        const val OWNER = "diagnostics"
    }
}
