package com.benchan.wireless.screencasting.protocol.airplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.benchan.wireless.screencasting.core.common.Ids
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.protocol.airplay.api.AirPlayReceiver
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleCompatState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 兼容 Apple 设备（非官方）接收端（F15、PRD R-08、技术方案 3.8）。
 *
 * 已实现：构建开关、JNI 门面与回调、mDNS 注册转接（NsdManager）、镜像 H.264/H.265 → MediaCodec → Surface 的解码骨架、PIN 透传。
 * 未完成（见 docs/dev/开发进度.md F15）：
 * - TODO(F15-native): third_party 源码（UxPlay lib、libplist、OpenSSL 3.x、FFmpeg ALAC）的 Android 交叉编译；
 * - TODO(F15-audio): AAC-ELD / ALAC 解码与 Oboe 输出；
 * - TODO(F15-hls): HLS 视频网址（本地 playlist 代理 + ExoPlayer）。
 */
@Singleton
class UxPlayAirPlayReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AirPlayReceiver, UxPlayCallbacks {
    private val _state = MutableStateFlow<AppleCompatState>(AppleCompatState.Stopped)
    override val state: StateFlow<AppleCompatState> = _state.asStateFlow()

    private val advertisers = HashMap<String, android.net.nsd.NsdManager.RegistrationListener>()
    private val nsd = context.getSystemService(android.net.nsd.NsdManager::class.java)
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var pin: String? = null
    private var peerName: String = ""

    override fun start(deviceName: String, requirePin: Boolean) {
        if (_state.value !is AppleCompatState.Stopped && _state.value !is AppleCompatState.Failed) return
        if (!UxPlayNative.load()) {
            _state.value = AppleCompatState.Failed("native library not packaged")
            WscLog.w(TAG, "libwscairplay.so missing (build with -Pwsc.airplay=true and third_party sources)")
            return
        }
        val dir = File(context.noBackupFilesDir, "apple-compat").apply { mkdirs() }
        val rc = UxPlayNative.nativeStart(this, deviceName, hardwareAddress(dir), requirePin, File(dir, "server.pem").absolutePath)
        _state.value = if (rc == 0) AppleCompatState.Advertising(null) else AppleCompatState.Failed("start failed $rc")
    }

    override fun stop() {
        if (UxPlayNative.load()) runCatching { UxPlayNative.nativeStop() }
        advertisers.keys.toList().forEach(::unregisterService)
        releaseDecoder()
        _state.value = AppleCompatState.Stopped
    }

    override fun setVideoSurface(surface: Surface?) {
        this.surface = surface
        if (surface == null) releaseDecoder()
    }

    /** UxPlay 需要一个稳定的 6 字节“硬件地址”作为设备标识；随机生成并保存（不读取真实 MAC）。 */
    private fun hardwareAddress(dir: File): ByteArray {
        val f = File(dir, "hwaddr")
        f.takeIf { it.exists() }?.readBytes()?.takeIf { it.size == 6 }?.let { return it }
        return Ids.randomBytes(6).also { it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte(); f.writeBytes(it) }
    }

    // —— 原生回调 ——

    override fun registerService(type: String, name: String, port: Int, keys: Array<String>, values: Array<String>) {
        unregisterService(type)
        val info = android.net.nsd.NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            keys.indices.forEach { i -> setAttribute(keys[i], values.getOrElse(i) { "" }) }
        }
        val l = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: android.net.nsd.NsdServiceInfo) = WscLog.i(TAG, "registered $type")
            override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) = WscLog.w(TAG, "register $type failed $errorCode")
            override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) = Unit
        }
        advertisers[type] = l
        runCatching { nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l) }
    }

    override fun unregisterService(type: String) {
        advertisers.remove(type)?.let { runCatching { nsd.unregisterService(it) } }
    }

    override fun onConnectionInit() = WscLog.i(TAG, "client connected")
    override fun onConnectionDestroy() {
        WscLog.i(TAG, "client disconnected")
        releaseDecoder()
        _state.value = AppleCompatState.Advertising(pin)
    }

    override fun onDisplayPin(pin: String) {
        this.pin = pin
        _state.value = AppleCompatState.Advertising(pin)
    }

    override fun onClientRequest(deviceId: String, model: String, name: String): Boolean {
        peerName = name.ifBlank { model }
        // TODO(F15-consent): 接入接收端 ConsentBroker（R-09 单路规则）；原生回调在 UxPlay 线程上同步等待结果
        return true
    }

    override fun onVideoCodec(h265: Boolean, width: Int, height: Int) {
        releaseDecoder()
        val s = surface ?: return
        runCatching {
            val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            decoder = MediaCodec.createDecoderByType(mime).apply {
                val fmt = MediaFormat.createVideoFormat(mime, width.coerceAtLeast(16), height.coerceAtLeast(16))
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                configure(fmt, s, null, 0)
                start()
            }
            _state.value = AppleCompatState.Mirroring(peerName)
        }.onFailure { WscLog.w(TAG, "decoder init failed", it) }
    }

    override fun onVideoData(data: ByteArray, ntpTimeLocal: Long, h265: Boolean) {
        val d = decoder ?: return
        runCatching {
            val idx = d.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                d.getInputBuffer(idx)?.apply { clear(); put(data) }
                d.queueInputBuffer(idx, 0, data.size, ntpTimeLocal / 1000, 0)
            }
            val info = MediaCodec.BufferInfo()
            var out = d.dequeueOutputBuffer(info, 0)
            while (out >= 0) {
                d.releaseOutputBuffer(out, true)
                out = d.dequeueOutputBuffer(info, 0)
            }
        }
    }

    override fun onMirrorRunning(running: Boolean) {
        if (!running) releaseDecoder()
    }

    override fun onLog(level: Int, message: String) {
        if (level <= 3) WscLog.w("uxplay", message) else WscLog.d("uxplay", message)
    }

    private fun releaseDecoder() {
        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        decoder = null
    }

    private companion object {
        const val TAG = "AppleCompat"
    }
}
