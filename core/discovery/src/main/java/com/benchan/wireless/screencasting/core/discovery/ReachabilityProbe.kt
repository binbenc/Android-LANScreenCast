package com.benchan.wireless.screencasting.core.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

/** TCP 连通性探测：最近设备直连 IP（组播被屏蔽但 IP 没变时，PRD 8.4）、手动 IP、投屏码前的预检。 */
class ReachabilityProbe @Inject constructor() {
    suspend fun isReachable(host: String, port: Int, timeoutMs: Int = 1_500): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        }.getOrDefault(false)
    }
}
