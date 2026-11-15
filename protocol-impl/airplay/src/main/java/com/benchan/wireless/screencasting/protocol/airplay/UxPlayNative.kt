package com.benchan.wireless.screencasting.protocol.airplay

import androidx.annotation.Keep

/**
 * 原生层回调（bridge/uxplay_jni.c 在 JNI_OnLoad 里按名字 + 签名查找这些方法，名字与签名不能改，见 app keepRules/jni.keep）。
 * 除特别说明外都在 UxPlay 的原生线程（httpd / RTP / 镜像线程）上调用，实现不能阻塞太久，也不能在这里调用 nativeStop。
 */
@Keep
interface UxPlayCallbacks {
    /** dnssd 垫片请求注册 / 注销 mDNS 服务（_raop._tcp / _airplay._tcp）。 */
    fun registerService(type: String, name: String, port: Int, keys: Array<String>, values: Array<String>)
    fun unregisterService(type: String)

    fun onConnectionInit()
    fun onConnectionDestroy()

    /** reason：1 = 网络中断，2 = 不支持的视频网址来源（UxPlay conn_reset）。 */
    fun onConnectionReset(reason: Int)
    /** 显示 Apple 设备配对 PIN；空串表示收起（PIN 已作废）。 */
    fun onDisplayPin(pin: String)

    /** 发送端完成配对后请求建立会话；返回 false 拒绝（R-09）。会在 httpd 线程上同步等待。 */
    fun onClientRequest(deviceId: String, model: String, name: String): Boolean

    fun onVideoCodec(h265: Boolean, width: Int, height: Int)
    fun onVideoData(data: ByteArray, ntpTimeLocal: Long, h265: Boolean)
    fun onVideoFlush()

    /** UxPlay reset_type_t。 */
    fun onVideoReset(type: Int)
    fun onMirrorRunning(running: Boolean)

    /** ct：1 PCM、2 ALAC、4 AAC-LC、8 AAC-ELD；usingScreen = 镜像同时的音频。 */
    fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean)

    /** ALAC 已在原生层解码：交错 S16 立体声 44.1 kHz。 */
    fun onAudioPcm(pcm: ShortArray, count: Int)

    /** AAC-LC / AAC-ELD 原始帧（或未压缩 PCM），由 Kotlin 解码。 */
    fun onAudioCompressed(data: ByteArray, ct: Int)
    fun onAudioVolume(volumeDb: Float)
    fun onAudioFlush()
    fun onAudioMetadata(data: ByteArray)
    fun onAudioCoverArt(data: ByteArray)

    /** 视频网址：[location] 是 UxPlay httpd 提供的本地 playlist（http://localhost:<port>/...）。 */
    fun onHlsPlay(location: String, startPosition: Float)
    fun onHlsSeek(position: Float)
    fun onHlsRate(rate: Float)
    fun onHlsStop()
    fun onHlsPlaylistRemove(): Float

    /** 填充 [out]（7 项：时长、位置、可拖动起点、可拖动长度、速率、缓冲空、缓冲满，单位秒）；返回是否仍在播放。 */
    fun hlsPlaybackInfo(out: DoubleArray): Boolean
    fun onLog(level: Int, message: String)
}

/**
 * UxPlay（GPL-3.0）`lib/` 的 JNI 门面（技术方案 3.8）。只有 `-Pwsc.airplay=true` 时才会编译出 libwscairplay.so；
 * 否则 [load] 返回 false，接收端显示“当前版本未包含兼容 Apple 设备模块”。
 */
@Keep
object UxPlayNative {
    @Volatile
    private var loaded: Boolean? = null

    fun load(): Boolean = loaded ?: synchronized(this) {
        loaded ?: runCatching {
            System.loadLibrary("wscairplay")
            true
        }.getOrDefault(false).also { loaded = it }
    }

    /**
     * 启动 RAOP/AirPlay HTTP 服务并通过 dnssd 垫片注册 mDNS。[flags] 见 [UxPlayFlags]。
     * 返回 0 成功，负数见 [UxPlayStartError]。
     */
    @JvmStatic external fun nativeStart(
        callbacks: UxPlayCallbacks,
        name: String,
        hwAddr: ByteArray,
        flags: Int,
        keyFile: String,
        language: String?,
    ): Int

    /** 停止服务并等待原生线程退出。不能在 [UxPlayCallbacks] 回调线程上调用。 */
    @JvmStatic external fun nativeStop()

    /** 自检：FFmpeg ALAC 解码器可以创建。 */
    @JvmStatic external fun nativeSelfTest(): Boolean
}
