package com.benchan.wireless.screencasting.protocol.dlna

import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpMessage
import com.benchan.wireless.screencasting.core.net.ssdp.SsdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.URLEncoder
import kotlin.random.Random

/** WSC 入口信息（写进 SSDP 扩展头，发送端的第二条发现路径）。 */
data class WscAdvert(val port: Int, val deviceId: String, val fingerprintPrefix: String, val name: String)

/**
 * DMR 的 SSDP 通告与应答（技术方案 3.6）：启动时 NOTIFY ssdp:alive ×3（间隔 100~200ms），之后每 max-age/2 重发；
 * 按 MX 随机延迟应答 M-SEARCH；退出时 ssdp:byebye。需要调用方持有 MulticastLock。
 */
class SsdpAdvertiser(
    private val local: Inet4Address,
    private val udn: String,
    private val location: String,
    private val wsc: WscAdvert?,
) {
    private var job: Job? = null
    private var socket: SsdpSocket? = null

    private val notificationTypes: List<String> = listOf("upnp:rootdevice", "uuid:$udn", DlnaDescriptions.DEVICE_TYPE) + DlnaService.entries.map { it.type }

    fun start(scope: CoroutineScope) {
        val sock = runCatching { SsdpSocket(local, SsdpMessage.PORT) }.getOrElse {
            WscLog.w(TAG, "cannot bind SSDP port 1900", it)
            return
        }
        socket = sock
        job = scope.launch(Dispatchers.IO) {
            launch {
                repeat(3) {
                    notifyAll("ssdp:alive", sock)
                    delay(150)
                }
                while (isActive) {
                    delay(MAX_AGE * 1000L / 2)
                    notifyAll("ssdp:alive", sock)
                }
            }
            while (isActive && !sock.isClosed) {
                val p = runCatching { sock.receive() }.getOrNull() ?: continue
                val m = p.message
                if (!m.isSearch || m["MAN"]?.contains("ssdp:discover") != true) continue
                val st = m["ST"] ?: continue
                val targets = matchingTargets(st)
                if (targets.isEmpty()) continue
                val mx = (m["MX"]?.toIntOrNull() ?: 1).coerceIn(1, 3)
                launch {
                    delay(Random.nextLong(0, mx * 300L))
                    targets.forEach { t -> runCatching { sock.sendTo(response(t), p.from, p.fromPort) } }
                }
            }
        }
        WscLog.i(TAG, "SSDP advertising $udn")
    }

    fun stop() {
        socket?.let { s -> runCatching { notifyAll("ssdp:byebye", s) } }
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }

    /** M-SEARCH 的 ST 匹配（UPnP DA 1.1 第 1.3.2 节）。 */
    fun matchingTargets(st: String): List<String> = when (st) {
        "ssdp:all" -> notificationTypes
        in notificationTypes -> listOf(st)
        else -> emptyList()
    }

    private fun usn(nt: String) = if (nt == "uuid:$udn") nt else "uuid:$udn::$nt"

    fun response(st: String): SsdpMessage = SsdpMessage(
        "HTTP/1.1 200 OK",
        commonHeaders(linkedMapOf("CACHE-CONTROL" to "max-age=$MAX_AGE", "EXT" to "", "LOCATION" to location, "SERVER" to SERVER, "ST" to st, "USN" to usn(st))),
    )

    private fun notifyAll(nts: String, sock: SsdpSocket) {
        for (nt in notificationTypes) {
            val headers = linkedMapOf("HOST" to "${SsdpMessage.ADDRESS}:${SsdpMessage.PORT}", "NT" to nt, "NTS" to nts, "USN" to usn(nt))
            if (nts == "ssdp:alive") {
                headers["CACHE-CONTROL"] = "max-age=$MAX_AGE"
                headers["LOCATION"] = location
                headers["SERVER"] = SERVER
            }
            runCatching { sock.sendMulticast(SsdpMessage("NOTIFY * HTTP/1.1", commonHeaders(headers))) }
        }
    }

    private fun commonHeaders(h: LinkedHashMap<String, String>): LinkedHashMap<String, String> {
        wsc?.let {
            h[SsdpMessage.X_WSC_PORT] = it.port.toString()
            h[SsdpMessage.X_WSC_ID] = it.deviceId
            h[SsdpMessage.X_WSC_FP] = it.fingerprintPrefix
            h[SsdpMessage.X_WSC_NAME] = URLEncoder.encode(it.name, "UTF-8")
            h[SsdpMessage.X_WSC_VERSION] = "1"
        }
        return h
    }

    companion object {
        private const val TAG = "SsdpAdvertiser"
        const val MAX_AGE = 1800
        const val SERVER = "Android/10 UPnP/1.0 WSC/1.0"
    }
}
