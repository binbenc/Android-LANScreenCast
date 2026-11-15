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

/**
 * 一项诊断结论（结构化数据，文案由 UI 层映射到字符串资源，见 feature/settings 的 DiagnosticsScreens）。
 * [id] 稳定（lan / vpn / prefix / multicast / target / router / isolation / guest），供日志与测试使用。
 */
sealed class DiagnosticCheck(val id: String, val status: CheckStatus) {
    /** 没有连接 Wi‑Fi 或有线网络。 */
    data object NoLan : DiagnosticCheck("lan", CheckStatus.PROBLEM)

    /** 已连接局域网：[ethernet] 为 true 表示有线；[subnetHint] 例如 "192.168.1.x"。 */
    data class LanConnected(val ethernet: Boolean, val ip: String, val subnetHint: String) : DiagnosticCheck("lan", CheckStatus.CONFIRMED)

    data object VpnActive : DiagnosticCheck("vpn", CheckStatus.PROBLEM)

    /** 网段大于 /24，投屏码不可用。 */
    data class LargePrefix(val prefixLength: Int) : DiagnosticCheck("prefix", CheckStatus.NEEDS_USER)

    data class MulticastOk(val responders: Int) : DiagnosticCheck("multicast", CheckStatus.CONFIRMED)

    data object MulticastSilent : DiagnosticCheck("multicast", CheckStatus.NEEDS_USER)

    data class TargetReachable(val host: String, val port: Int) : DiagnosticCheck("target", CheckStatus.CONFIRMED)

    data class TargetUnreachable(val host: String, val port: Int) : DiagnosticCheck("target", CheckStatus.PROBLEM)

    /** 只能由用户确认：是否同一路由器。 */
    data object AskSameRouter : DiagnosticCheck("router", CheckStatus.NEEDS_USER)

    /** 只能由用户确认：是否开启 AP 隔离。 */
    data object AskApIsolation : DiagnosticCheck("isolation", CheckStatus.NEEDS_USER)

    /** 只能由用户确认：是否连了访客网络。 */
    data object AskGuestNetwork : DiagnosticCheck("guest", CheckStatus.NEEDS_USER)
}

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
            checks += DiagnosticCheck.NoLan
            return checks
        }
        checks += DiagnosticCheck.LanConnected(lan.transport == LanInfo.Transport.ETHERNET, lan.ip, lan.subnetHint)
        if (lan.vpnActive) {
            checks += DiagnosticCheck.VpnActive
        }
        if (!CastCode.supportsNetwork(lan.prefixLength)) {
            checks += DiagnosticCheck.LargePrefix(lan.prefixLength)
        }
        multicastLock.acquire(OWNER)
        val responders = try {
            ssdp.countResponders(lan.address)
        } finally {
            multicastLock.release(OWNER)
        }
        checks += if (responders > 0) DiagnosticCheck.MulticastOk(responders) else DiagnosticCheck.MulticastSilent
        target?.let { (host, port) ->
            checks += if (probe.isReachable(host, port)) DiagnosticCheck.TargetReachable(host, port) else DiagnosticCheck.TargetUnreachable(host, port)
        }
        checks += DiagnosticCheck.AskSameRouter
        checks += DiagnosticCheck.AskApIsolation
        checks += DiagnosticCheck.AskGuestNetwork
        return checks
    }

    private companion object {
        const val OWNER = "diagnostics"
    }
}
