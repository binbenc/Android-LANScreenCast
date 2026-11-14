package com.benchan.wireless.screencasting.core.discovery.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.benchan.wireless.screencasting.core.common.WscLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 注册状态（网络诊断页展示）。 */
enum class AdvertiseState { IDLE, REGISTERING, REGISTERED, FAILED }

/**
 * 接收端通过 NsdManager 注册 mDNS 服务（技术方案 3.6）。端口来自 WSC 服务实际绑定的端口。
 * 同一实例可注册任意服务类型（AirPlay 兼容模式会注册 _airplay._tcp / _raop._tcp）。
 */
class NsdAdvertiser @Inject constructor(@ApplicationContext context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private val _state = MutableStateFlow(AdvertiseState.IDLE)
    val state: StateFlow<AdvertiseState> = _state.asStateFlow()

    @Volatile
    var registeredName: String? = null
        private set

    @Synchronized
    fun register(serviceName: String, serviceType: String, port: Int, attributes: Map<String, String>) {
        unregister()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
            attributes.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredName = serviceInfo.serviceName
                _state.value = AdvertiseState.REGISTERED
                WscLog.i(TAG, "registered $serviceType as '${serviceInfo.serviceName}' :$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _state.value = AdvertiseState.FAILED
                WscLog.w(TAG, "register $serviceType failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                WscLog.i(TAG, "unregistered $serviceType")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                WscLog.w(TAG, "unregister failed: $errorCode")
            }
        }
        listener = l
        _state.value = AdvertiseState.REGISTERING
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure {
            _state.value = AdvertiseState.FAILED
            WscLog.w(TAG, "registerService threw", it)
        }
    }

    @Synchronized
    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
        registeredName = null
        _state.value = AdvertiseState.IDLE
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
    }
}
