package com.benchan.wireless.screencasting.core.discovery.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
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

    private data class Registration(val serviceName: String, val serviceType: String, val port: Int, val attributes: Map<String, String>)

    /** 最近一次 [register] 的参数（[setDlnaPort] 变化时用它重新注册）。 */
    private var current: Registration? = null

    /** 本机 DLNA 接收（DMR）的 HTTP 端口；null = 没开。只对 `_wsc._tcp` 注册生效（TXT 的 `dlna` 键）。 */
    private var dlnaPort: Int? = null

    /** 调用过 [setDlnaPort] 之后才接管 `dlna` 键；之前保留调用方 attributes 里的值（例如直接用 `WscTxt(dlnaPort = …)`）。 */
    private var dlnaPortManaged = false

    /**
     * 注册（或替换）服务。调用过 [setDlnaPort] 后，`_wsc._tcp` 的 TXT 按其当前值自动加上 / 去掉 `dlna` 键
     * （覆盖 [attributes] 里的同名键）。
     */
    @Synchronized
    fun register(serviceName: String, serviceType: String, port: Int, attributes: Map<String, String>) {
        doRegister(Registration(serviceName, serviceType, port, attributes))
    }

    /**
     * DLNA 接收启停或端口变化时调用（null = 已停）。值有变化且当前已注册 `_wsc._tcp` 时重新注册服务以更新 TXT
     * （NsdManager 没有单独更新 TXT 的接口）；尚未注册时只记下，下次 [register] 带上。
     */
    @Synchronized
    fun setDlnaPort(port: Int?) {
        val p = port?.takeIf { it in 1..65535 }
        if (dlnaPortManaged && p == dlnaPort) return
        dlnaPortManaged = true
        dlnaPort = p
        val reg = current ?: return
        if (reg.serviceType != WscProtocol.SERVICE_TYPE) return
        WscLog.i(TAG, "dlna port -> ${p ?: "off"}, re-register")
        doRegister(reg)
    }

    private fun doRegister(reg: Registration) {
        unregister()
        current = reg
        val (serviceName, serviceType, port) = reg
        val attributes = if (dlnaPortManaged && serviceType == WscProtocol.SERVICE_TYPE) WscTxt.withDlnaPort(reg.attributes, dlnaPort) else reg.attributes
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
        current = null
        registeredName = null
        _state.value = AdvertiseState.IDLE
    }

    private companion object {
        const val TAG = "NsdAdvertiser"
    }
}
