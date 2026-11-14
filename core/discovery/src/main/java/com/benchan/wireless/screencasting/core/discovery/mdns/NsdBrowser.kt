package com.benchan.wireless.screencasting.core.discovery.mdns

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.discovery.DiscoverySource
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import javax.inject.Inject

/** 浏览事件。 */
sealed interface BrowseEvent {
    data class Found(val device: DiscoveredDevice) : BrowseEvent
    data class Lost(val serviceName: String) : BrowseEvent
    data class Failed(val errorCode: Int) : BrowseEvent
}

/**
 * 发送端 mDNS 浏览 `_wsc._tcp`（技术方案 3.6 已知坑处理）：
 * - API ≤33 的 resolveService 不能并发 → 用 Mutex 串行；API 34+ 用 registerServiceInfoCallback；
 * - API 33+ 把发现绑定到 Wi-Fi/以太网 Network，避免在 VPN / 蜂窝接口上发现；
 * - 以 TXT 的 id 识别设备，不依赖服务名。
 */
class NsdBrowser @Inject constructor(@ApplicationContext context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wsc-nsd").apply { isDaemon = true } }
    private val resolveMutex = Mutex()

    fun browse(network: Network?): Flow<BrowseEvent> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = WscLog.i(TAG, "discovery started")
            override fun onDiscoveryStopped(serviceType: String) = WscLog.i(TAG, "discovery stopped")
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                WscLog.w(TAG, "start discovery failed $errorCode")
                trySend(BrowseEvent.Failed(errorCode))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                launch {
                    resolve(serviceInfo)?.let { trySend(BrowseEvent.Found(it)) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                trySend(BrowseEvent.Lost(serviceInfo.serviceName))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33 && network != null) {
                nsd.discoverServices(WscProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, executor, listener)
            } else {
                nsd.discoverServices(WscProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        } catch (e: Exception) {
            WscLog.w(TAG, "discoverServices threw", e)
            trySend(BrowseEvent.Failed(-1))
        }
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private suspend fun resolve(info: NsdServiceInfo): DiscoveredDevice? = resolveMutex.withLock {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            val resolved = if (Build.VERSION.SDK_INT >= 34) resolveApi34(info) else resolveLegacy(info)
            resolved?.let(::toDevice)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): NsdServiceInfo? {
        val result = CompletableDeferred<NsdServiceInfo?>()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                WscLog.w(TAG, "resolve failed $errorCode")
                result.complete(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                result.complete(serviceInfo)
            }
        })
        return result.await()
    }

    private suspend fun resolveApi34(info: NsdServiceInfo): NsdServiceInfo? {
        if (Build.VERSION.SDK_INT < 34) return null
        val result = CompletableDeferred<NsdServiceInfo?>()
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                result.complete(null)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.hostAddresses.any { it is Inet4Address }) result.complete(serviceInfo)
            }

            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(info, executor, cb)
        return try {
            result.await()
        } finally {
            runCatching { nsd.unregisterServiceInfoCallback(cb) }
        }
    }

    @Suppress("DEPRECATION")
    private fun toDevice(info: NsdServiceInfo): DiscoveredDevice? {
        val host = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
        } else {
            (info.host as? Inet4Address)?.hostAddress
        } ?: return null
        val txt = WscTxt.parse(info.attributes) ?: return null
        return DiscoveredDevice(
            deviceId = txt.deviceId,
            name = txt.name.ifBlank { info.serviceName },
            host = host,
            port = info.port,
            version = txt.version,
            caps = txt.caps,
            fingerprintPrefix = Fingerprint.prefixFromTxt(txt.fingerprintPrefix),
            sources = setOf(DiscoverySource.MDNS),
            lastSeenAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TAG = "NsdBrowser"
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}
