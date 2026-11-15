package com.benchan.wireless.screencasting.protocol.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import com.benchan.wireless.screencasting.core.common.Ids
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.protocol.airplay.api.AirPlayReceiver
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleAdmission
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleCompatState
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleEndReason
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleFailure
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleSession
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleSessionKind
import com.benchan.wireless.screencasting.protocol.airplay.api.AppleSessionSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 兼容 Apple 设备（非官方）接收端（F15、PRD R-08、技术方案 3.8）：UxPlay lib（JNI）+ Android 媒体栈。
 *
 * - 镜像：H.264 → [MirrorVideoDecoder]（MediaCodec → Surface）；H.265 默认不通告（M1 只验证 H.264）。
 * - 音频（RAOP）：ALAC（原生 FFmpeg 解码）/ AAC-LC / AAC-ELD（MediaCodec）→ [RaopAudioPlayer]（AudioTrack）。
 * - 视频网址：UxPlay 本地 playlist 代理 → [HlsVideoPlayer]（Media3 ExoPlayer）。
 * - mDNS：dnssd 垫片 → [registerService] → NsdManager；只在 [start] 后注册（默认关闭）。
 * - PIN：pin_pw=1，发送端首次连接时在电视上显示 4 位 PIN（[AppleCompatState.Advertising.pin]）。
 * - 准入：[admission]（由接收端编排接到 ConsentBroker，实现 R-09 单路替换确认）。
 *
 * 线程：start / stop / disconnect 在专用单线程 [control] 上执行（nativeStop 会等待原生线程退出，绝不能在原生回调线程上调用）；
 * 会话状态用 [sessionLock] 保护，原生回调可能来自 httpd、RTP 音频、镜像等多个线程。
 */
@Singleton
class UxPlayAirPlayReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AirPlayReceiver, UxPlayCallbacks {
    private val _state = MutableStateFlow<AppleCompatState>(AppleCompatState.Stopped)
    override val state: StateFlow<AppleCompatState> = _state.asStateFlow()

    private val _ended = MutableSharedFlow<AppleSessionSummary>(extraBufferCapacity = 8)
    override val sessionEnded: SharedFlow<AppleSessionSummary> = _ended.asSharedFlow()

    @Volatile
    override var admission: AppleAdmission? = null

    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "apple-compat-control") }
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val advertisers = HashMap<String, NsdManager.RegistrationListener>()

    private data class Config(val name: String, val requirePin: Boolean)

    /** 仅在 [control] 线程读写。 */
    private var running: Config? = null

    private val sessionLock = Any()
    private var openConnections = 0
    private var peerName: String? = null
    private var peerModel: String = ""
    private var session: AppleSession? = null
    private var pin: String? = null

    @Volatile
    private var pendingAdmission: Job? = null

    private val video = MirrorVideoDecoder { decoder -> markFirstFrame(decoder) }
    private val audio = RaopAudioPlayer()
    private val hls = HlsVideoPlayer(
        context,
        onStarted = { beginSession(AppleSessionKind.VIDEO_URL) },
        onFinished = { error -> endSession(if (error) AppleEndReason.UNSUPPORTED else AppleEndReason.PEER_STOPPED, AppleSessionKind.VIDEO_URL) },
    )

    override val videoPlayer: androidx.media3.common.Player? get() = hls.player

    // ————————————————————————————— 生命周期（control 线程） —————————————————————————————

    override fun start(deviceName: String, requirePin: Boolean) {
        val cfg = Config(deviceName.ifBlank { "Android TV" }, requirePin)
        control.execute {
            if (running == cfg) return@execute
            if (running != null) stopNative()
            startNative(cfg)
        }
    }

    override fun stop() {
        control.execute {
            if (running != null) stopNative()
            _state.value = AppleCompatState.Stopped
        }
    }

    override fun disconnect() {
        control.execute {
            val cfg = running ?: return@execute
            WscLog.i(TAG, "disconnect requested by receiver")
            endSession(AppleEndReason.RECEIVER_STOPPED)
            // UxPlay 没有线程安全的“踢掉当前客户端”接口：重启服务（mDNS 注册会短暂撤销再恢复）
            stopNative()
            startNative(cfg)
        }
    }

    private fun startNative(cfg: Config) {
        if (!UxPlayNative.load()) {
            _state.value = AppleCompatState.Failed(AppleFailure.NOT_PACKAGED)
            WscLog.w(TAG, "native library not packaged in this build")
            return
        }
        val dir = File(context.noBackupFilesDir, "apple-compat").apply { mkdirs() }
        val flags = UxPlayFlags.of(requirePin = cfg.requirePin, hls = true, h265 = false)
        val rc = runCatching {
            UxPlayNative.nativeStart(this, cfg.name, hardwareAddress(dir), flags, File(dir, "server.pem").absolutePath, Locale.getDefault().language)
        }.getOrElse {
            WscLog.e(TAG, "nativeStart threw", it)
            -100
        }
        if (rc == 0) {
            running = cfg
            synchronized(sessionLock) { pin = null }
            _state.value = AppleCompatState.Advertising(null)
            WscLog.i(TAG, "advertising (pin=${cfg.requirePin})")
        } else {
            running = null
            _state.value = AppleCompatState.Failed(UxPlayStartError.failure(rc))
            WscLog.w(TAG, "start failed: ${UxPlayStartError.describe(rc)}")
        }
    }

    private fun stopNative() {
        // httpd 线程可能正阻塞在确认弹窗上；先取消，nativeStop 才能尽快等到线程退出
        pendingAdmission?.cancel()
        endSession(AppleEndReason.RECEIVER_STOPPED)
        runCatching { UxPlayNative.nativeStop() }.onFailure { WscLog.w(TAG, "nativeStop failed", it) }
        synchronized(advertisers) { advertisers.keys.toList() }.forEach(::unregisterService)
        video.release()
        audio.release()
        hls.release()
        synchronized(sessionLock) {
            openConnections = 0
            peerName = null
            pin = null
        }
        running = null
    }

    override fun setVideoSurface(surface: Surface?) {
        video.setSurface(surface)
    }

    /** UxPlay 需要一个稳定的 6 字节“硬件地址”作为设备标识；随机生成并保存（不读取真实 MAC）。 */
    private fun hardwareAddress(dir: File): ByteArray {
        val f = File(dir, "hwaddr")
        f.takeIf { it.exists() }?.readBytes()?.takeIf(AppleDeviceAddress::isValid)?.let { return it }
        return AppleDeviceAddress.normalize(Ids.randomBytes(6)).also { f.writeBytes(it) }
    }

    // ————————————————————————————— 会话状态 —————————————————————————————

    private fun beginSession(kind: AppleSessionKind, width: Int = 0, height: Int = 0, codec: String? = null) = synchronized(sessionLock) {
        val s = session
        val next = if (s == null) {
            WscLog.i(TAG, "session started: $kind")
            AppleSession(Ids.newSessionId(), peerName ?: context.getString(R.string.apple_compat_default_peer), peerModel, kind, System.currentTimeMillis(), width = width, height = height, codec = codec)
        } else {
            // 音频 → 镜像、镜像 → 视频网址等切换视为同一会话
            if (s.kind != kind) WscLog.i(TAG, "session switched ${s.kind} -> $kind")
            s.copy(kind = kind, width = if (width > 0) width else s.width, height = if (height > 0) height else s.height, codec = codec ?: s.codec)
        }
        session = next
        pin = null
        _state.value = AppleCompatState.Active(next)
    }

    private fun markFirstFrame(decoder: String) = synchronized(sessionLock) {
        val s = session ?: return
        if (s.firstFrameAt != null) return
        val next = s.copy(firstFrameAt = System.currentTimeMillis(), codec = listOfNotNull(s.codec, decoder.ifBlank { null }).joinToString(" / "))
        session = next
        _state.value = AppleCompatState.Active(next)
    }

    /** 结束当前会话；[onlyKind] 非空时只在当前会话是该类型时结束。 */
    private fun endSession(reason: AppleEndReason, onlyKind: AppleSessionKind? = null) {
        val ended = synchronized(sessionLock) {
            val s = session ?: return
            if (onlyKind != null && s.kind != onlyKind) return
            session = null
            _state.value = if (_state.value is AppleCompatState.Stopped) AppleCompatState.Stopped else AppleCompatState.Advertising(null)
            s
        }
        WscLog.i(TAG, "session ended: ${ended.kind} ($reason)")
        when (ended.kind) {
            AppleSessionKind.MIRROR -> video.release()
            AppleSessionKind.VIDEO_URL -> hls.release()
            AppleSessionKind.AUDIO -> Unit
        }
        audio.release()
        _ended.tryEmit(AppleSessionSummary(ended, System.currentTimeMillis(), reason))
    }

    // ————————————————————————————— 原生回调 —————————————————————————————

    override fun registerService(type: String, name: String, port: Int, keys: Array<String>, values: Array<String>) {
        unregisterService(type)
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            keys.indices.forEach { i -> runCatching { setAttribute(keys[i], values.getOrElse(i) { "" }) } }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = WscLog.i(TAG, "registered $type")
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = WscLog.w(TAG, "register $type failed $errorCode")
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        synchronized(advertisers) { advertisers[type] = l }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { WscLog.w(TAG, "registerService $type", it) }
    }

    override fun unregisterService(type: String) {
        synchronized(advertisers) { advertisers.remove(type) }?.let { runCatching { nsd.unregisterService(it) } }
    }

    override fun onConnectionInit() {
        synchronized(sessionLock) { openConnections++ }
    }

    override fun onConnectionDestroy() {
        val last = synchronized(sessionLock) {
            openConnections = (openConnections - 1).coerceAtLeast(0)
            openConnections == 0
        }
        if (last) {
            endSession(AppleEndReason.PEER_STOPPED)
            // 不在这里收起 PIN：Mac / iPhone 发完 PAIR-PIN-START 就断开连接，等用户输完 PIN 再重新连接，
            // 这里清掉的话 PIN 一闪而过、用户看不到。PIN 由原生层作废（onDisplayPin("")）、会话开始或 UI 超时收起。
            synchronized(sessionLock) { peerName = null }
        }
    }

    override fun onConnectionReset(reason: Int) {
        WscLog.w(TAG, "connection reset (reason $reason)")
        endSession(if (reason == 2) AppleEndReason.UNSUPPORTED else AppleEndReason.CONNECTION_LOST)
    }

    /** pin 为空串：原生层已作废该 PIN（配对成功 / 输错次数用尽 / 过期），收起覆盖层。 */
    override fun onDisplayPin(pin: String) {
        val shown = pin.takeIf { it.isNotEmpty() }
        synchronized(sessionLock) {
            this.pin = shown
            if (session == null && _state.value is AppleCompatState.Advertising) _state.value = AppleCompatState.Advertising(shown)
        }
        WscLog.i(TAG, if (shown != null) "pairing PIN displayed" else "pairing PIN cleared")
    }

    override fun onClientRequest(deviceId: String, model: String, name: String): Boolean {
        val display = name.ifBlank { model.ifBlank { context.getString(R.string.apple_compat_default_peer) } }
        val policy = admission
        val ok = if (policy == null) {
            true
        } else {
            runBlocking(Dispatchers.Default) {
                val job = async { withTimeoutOrNull(ADMISSION_TIMEOUT_MS) { policy.admit(display, model, deviceId) } ?: false }
                pendingAdmission = job
                try {
                    job.await()
                } catch (_: CancellationException) {
                    false // 服务停止 / 断开时取消等待（确认弹窗随之撤销）
                } finally {
                    pendingAdmission = null
                }
            }
        }
        if (ok) {
            synchronized(sessionLock) {
                peerName = display
                peerModel = model
                pin = null
            }
        }
        WscLog.i(TAG, "client request ($model) ${if (ok) "admitted" else "refused"}")
        return ok
    }

    override fun onVideoCodec(h265: Boolean, width: Int, height: Int) {
        video.configure(h265, width, height)
        beginSession(AppleSessionKind.MIRROR, width, height, if (h265) "H.265" else "H.264")
    }

    override fun onVideoData(data: ByteArray, ntpTimeLocal: Long, h265: Boolean) {
        video.submit(data, ntpTimeLocal / 1000, h265) // UxPlay 时间单位为 ns
    }

    override fun onVideoFlush() {
        video.flush()
    }

    override fun onVideoReset(type: Int) {
        when (type) {
            RESET_RTP_SHUTDOWN, RESET_RTP_TO_HLS_TEARDOWN -> {
                video.release()
                if (type == RESET_RTP_SHUTDOWN) endSession(AppleEndReason.PEER_STOPPED, AppleSessionKind.MIRROR)
            }
            RESET_HLS_SHUTDOWN, RESET_HLS_EOS -> endSession(AppleEndReason.PEER_STOPPED, AppleSessionKind.VIDEO_URL)
            RESET_NOHOLD -> endSession(AppleEndReason.RECEIVER_STOPPED)
            else -> Unit
        }
    }

    override fun onMirrorRunning(running: Boolean) {
        if (running) beginSession(AppleSessionKind.MIRROR) else endSession(AppleEndReason.PEER_STOPPED, AppleSessionKind.MIRROR)
    }

    override fun onAudioFormat(ct: Int, samplesPerFrame: Int, usingScreen: Boolean, isMedia: Boolean) {
        audio.setFormat(ct)
        if (!usingScreen) {
            val idle = synchronized(sessionLock) { session == null }
            if (idle) beginSession(AppleSessionKind.AUDIO, codec = RaopAudioCodec.fromCt(ct).name)
        }
    }

    override fun onAudioPcm(pcm: ShortArray, count: Int) {
        audio.writePcm(pcm, count)
        markAudioStarted()
    }

    override fun onAudioCompressed(data: ByteArray, ct: Int) {
        audio.writeCompressed(data, ct)
        markAudioStarted()
    }

    private fun markAudioStarted() {
        val needs = synchronized(sessionLock) { session?.let { it.kind == AppleSessionKind.AUDIO && it.firstFrameAt == null } == true }
        if (needs) markFirstFrame("")
    }

    override fun onAudioVolume(volumeDb: Float) {
        audio.setVolumeDb(volumeDb)
    }

    override fun onAudioFlush() {
        audio.flush()
    }

    override fun onAudioMetadata(data: ByteArray) {
        val track = DmapMetadata.parse(data) ?: return
        synchronized(sessionLock) {
            val s = session ?: return
            val next = s.copy(trackTitle = track.title, trackArtist = track.artist)
            session = next
            _state.value = AppleCompatState.Active(next)
        }
    }

    override fun onAudioCoverArt(data: ByteArray) = Unit // M1 不显示封面

    override fun onHlsPlay(location: String, startPosition: Float) {
        WscLog.i(TAG, "video URL requested")
        hls.play(location, startPosition)
    }

    override fun onHlsSeek(position: Float) {
        hls.seek(position)
    }

    override fun onHlsRate(rate: Float) {
        hls.setRate(rate)
    }

    override fun onHlsStop() = endSession(AppleEndReason.PEER_STOPPED, AppleSessionKind.VIDEO_URL)

    override fun onHlsPlaylistRemove(): Float = hls.pauseForPlaylistRemove()

    override fun hlsPlaybackInfo(out: DoubleArray): Boolean = hls.fillPlaybackInfo(out)

    override fun onLog(level: Int, message: String) {
        val m = UxPlayLogRedactor.redact(message)
        when {
            level <= LOGGER_ERR -> WscLog.e(UXPLAY_TAG, m)
            level <= LOGGER_WARNING -> WscLog.w(UXPLAY_TAG, m)
            else -> WscLog.i(UXPLAY_TAG, m)
        }
    }

    private companion object {
        const val TAG = "AppleCompat"
        const val UXPLAY_TAG = "uxplay"
        const val ADMISSION_TIMEOUT_MS = 35_000L
        const val LOGGER_ERR = 3
        const val LOGGER_WARNING = 4

        // UxPlay reset_type_t
        const val RESET_NOHOLD = 0
        const val RESET_RTP_SHUTDOWN = 1
        const val RESET_HLS_SHUTDOWN = 2
        const val RESET_HLS_EOS = 3
        const val RESET_RTP_TO_HLS_TEARDOWN = 5
    }
}
