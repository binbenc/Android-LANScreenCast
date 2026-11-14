package com.benchan.wireless.screencasting.protocol.airplay

import androidx.annotation.Keep

/** 原生层回调（C 侧通过 RegisterNatives 绑定的对象调用，方法名与签名不能改，见 jni.keep）。 */
interface UxPlayCallbacks {
    /** dnssd 垫片请求注册 / 注销 mDNS 服务（_raop._tcp / _airplay._tcp）。 */
    fun registerService(type: String, name: String, port: Int, keys: Array<String>, values: Array<String>)
    fun unregisterService(type: String)
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onDisplayPin(pin: String)
    /** iPhone 请求连接；返回 false 拒绝（R-09：已有会话时需确认替换）。 */
    fun onClientRequest(deviceId: String, model: String, name: String): Boolean
    fun onVideoCodec(h265: Boolean, width: Int, height: Int)
    fun onVideoData(data: ByteArray, ntpTimeLocal: Long, h265: Boolean)
    fun onMirrorRunning(running: Boolean)
    fun onLog(level: Int, message: String)
}

/**
 * UxPlay（GPL-3.0）`lib/` 的 JNI 门面（技术方案 3.8）。只有 `-Pwsc.airplay=true` 且 third_party 源码齐全时才会编译出
 * libwscairplay.so；否则 [load] 返回 false，接收端显示“当前版本未包含兼容 Apple 设备模块”。
 */
@Keep
object UxPlayNative {
    @Volatile
    private var loaded: Boolean? = null

    fun load(): Boolean = loaded ?: runCatching {
        System.loadLibrary("wscairplay")
        true
    }.getOrDefault(false).also { loaded = it }

    /** 启动 RAOP/AirPlay HTTP 服务并通过 dnssd 垫片注册 mDNS。返回 0 成功。 */
    @JvmStatic external fun nativeStart(callbacks: UxPlayCallbacks, name: String, hwAddr: ByteArray, requirePin: Boolean, keyFile: String): Int

    @JvmStatic external fun nativeStop()
}
