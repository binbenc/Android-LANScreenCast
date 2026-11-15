package com.benchan.wireless.screencasting.core.rtc.sender

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.core.protocol.quality.CaptureSize
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.rtc.MediaState
import com.benchan.wireless.screencasting.core.rtc.RtcConfig
import com.benchan.wireless.screencasting.core.rtc.RtcEngine
import com.benchan.wireless.screencasting.core.rtc.SdpMunger
import com.benchan.wireless.screencasting.core.rtc.SimpleSdpObserver
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyEstimator
import com.benchan.wireless.screencasting.core.rtc.stats.SenderStatsSnapshot
import com.benchan.wireless.screencasting.core.rtc.stats.StatsParser
import com.benchan.wireless.screencasting.core.rtc.toEntries
import com.benchan.wireless.screencasting.core.rtc.toMediaState
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** MediaProjection 被系统停止的原因（决策 #17）。 */
enum class ProjectionStopReason { SCREEN_LOCKED, SYSTEM_OR_USER }

/**
 * 发送端镜像会话（PRD S-01~S-08、技术方案 3.2 / 4.2 / 4.3）。
 *
 * 生命周期约束（Android 14+）：
 * - 必须由已经以 mediaProjection 类型 startForeground 的服务创建（本类不负责前台服务）；
 * - 整个会话只调用一次 WscScreenCapturer.startCapture()（内部先 registerCallback 再 createVirtualDisplay），
 *   断线重连只做 ICE restart，绝不 stop/start 采集器，尺寸变化走 changeCaptureFormat。
 *
 * 媒体重建（F18 会话中切进 / 切出游戏档，PROTOCOL.md 4.3）：[rebuildMedia] 保留采集器、VideoSource、音视频轨道和工厂，
 * 只新建 PeerConnection（新的 ICE / DTLS，音频按新档位放进同一个或不同的 stream），发出 `OFFER{rebuild}`；
 * 旧 PeerConnection 在收到应答时才关闭（此时接收端已丢弃它；先关旧连接再应用应答，避免同时占用两个硬件编码器）。
 * 每个 PeerConnection 有自己的媒体代号，旧代号的回调一律丢弃，关闭旧连接不会被当成断线。不重新申请录屏授权。
 *
 * 所有 PeerConnection 操作都在单线程 [executor] 上执行。
 */
class SenderRtcSession(
    private val context: Context,
    private val engine: RtcEngine,
    private val projectionData: Intent,
    initialProfile: QualityProfile,
    private val audioEnabled: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        /** [rebuild] = 这条 OFFER 来自 [rebuildMedia] 新建的 PeerConnection。 */
        fun onLocalOffer(sdp: String, iceRestart: Boolean, rebuild: Boolean)
        fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onMediaState(state: MediaState)
        fun onFirstConnected()
        fun onProjectionStopped(reason: ProjectionStopReason)
        /** [receiver] = 接收端最近一次 RSTATS（旧版本接收端 / 尚未收到时为 null）。 */
        fun onStats(stats: SenderStatsSnapshot, latencyMs: Int?, receiver: DcMessage.ReceiverStats?)

        /** [rebuildMedia] 无法新建连接（已恢复原连接，媒体不受影响）：调用方退回“只改分辨率帧率、下次投屏生效”。 */
        fun onRebuildUnavailable(profile: QualityProfile) = Unit

        /** 接收端确认重建后的新连接首帧已上屏（DataChannel `REBUILT`，F18 切换成功的判据）。 */
        fun onRebuilt(pauseMs: Long) = Unit
        fun onQualityChanged(profile: QualityProfile, byRemote: Boolean)
        fun onAudioState(state: AudioState)
        fun onCapturedAppVisible(visible: Boolean)
        fun onProtectedContent(suspected: Boolean)

        /** DataChannel `wsc` 打开（含 ICE restart 后仍保持的情况只回调一次）：反向控制据此发出当前 CTL_STATE / GEOM。 */
        fun onDataChannelOpen() = Unit

        /** `wsc` 上发送端自己不处理的消息（反向控制 CTL_REQ / CTL_END 等），在 RTC 线程回调。 */
        fun onDataChannelMessage(message: DcMessage) = Unit

        /** `wsc-input` 上已解码、已校验的输入消息（PROTOCOL.md 4.2），在 RTC 线程回调。 */
        fun onInputMessage(message: InputMessage) = Unit

        /**
         * 采集几何变化（反向控制坐标换算，PROTOCOL.md 6.3）：采集帧 [captureWidth]×[captureHeight]，
         * 被采集内容 [contentWidth]×[contentHeight]，此刻屏幕 [screenWidth]×[screenHeight]（逻辑像素，含当前旋转）。
         */
        fun onCaptureGeometry(captureWidth: Int, captureHeight: Int, contentWidth: Int, contentHeight: Int, screenWidth: Int, screenHeight: Int) = Unit
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wsc-sender-rtc") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statsParser = StatsParser()

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null

    /** 重建期间等待接收端应答的旧 PeerConnection（及其 DataChannel），收到应答时关闭。 */
    private var retiring: Retiring? = null

    /** 当前 PeerConnection 的代号：每次 [rebuildMedia] 加一，旧代号的回调丢弃。 */
    @Volatile
    private var mediaGen = 0

    /** 当前代号是否已经连通过（重建后的首次连通要重新抬高起始码率）。 */
    private var genConnected = false
    private var capturer: WscScreenCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null
    private var dataChannel: DataChannel? = null
    private var inputChannel: DataChannel? = null
    private var contentSize = 0 to 0
    private var audioCapture: PlaybackAudioCapture? = null
    private var statsTask: ScheduledFuture<*>? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var everConnected = false

    @Volatile
    private var closing = false

    @Volatile
    var profile: QualityProfile = initialProfile
        private set

    private var captureSize = CaptureSize(0, 0)

    /** 游戏模式降帧（过热 / 跟不上 60 帧，PRD 8.1.3 F / H）：采集与编码帧率上限；null = 按档位。 */
    private var fpsCap: Int? = null

    /** 降帧是否要告诉接收端（STATS.capFps → 电视 3 秒“已降为 30 帧”）；“记住的 30 帧”开局即生效，不算降帧。 */
    private var announceCap = true
    private var repeater: FrameRepeater? = null
    private var lastContentFrames = 0L
    private var lastContentAtMs = 0L
    private val effectiveFps: Int get() = fpsCap?.let { minOf(it, profile.fps) } ?: profile.fps
    private var lastReceiverStats: DcMessage.ReceiverStats? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (closing) return
            val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
                context.getSystemService(PowerManager::class.java)?.isInteractive == false
            WscLog.w(TAG, "MediaProjection stopped by system (locked=$locked)")
            listener.onProjectionStopped(if (locked) ProjectionStopReason.SCREEN_LOCKED else ProjectionStopReason.SYSTEM_OR_USER)
        }
    }

    /** Android 14+ 单应用共享：内容尺寸变化 / 被共享 App 可见性变化（需要单独注册，上游只转发 onStop）。 */
    private val contentCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            executor.execute { resizeTo(width, height) }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            sendDc(DcMessage.AppSwitched(isVisible))
            listener.onCapturedAppVisible(isVisible)
        }
    }

    fun start() = executor.execute {
        runCatching { startInternal() }.onFailure {
            WscLog.e(TAG, "start failed", it)
            listener.onMediaState(MediaState.FAILED)
        }
    }

    private fun startInternal() {
        engine.initialize()
        val egl = engine.eglBase.eglBaseContext
        val capture = PlaybackAudioCapture({ capturer?.mediaProjection }) { state -> listener.onAudioState(state) }
            .also { it.enabled = audioEnabled }
        audioCapture = capture
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseStereoInput(true)
            .setInputSampleRate(48_000)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback(capture)
            .createAudioDeviceModule()
        // 关闭内置麦克风 AudioRecord：采集线程只从 AudioBufferCallback 取数据（不打开麦克风，见 PlaybackAudioCapture）
        audioModule.setAudioRecordEnabled(false)
        adm = audioModule
        val f = PeerConnectionFactory.builder()
            .setOptions(engine.factoryOptions())
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
            .createPeerConnectionFactory()
        factory = f

        // —— 视频：WscScreenCapturer（释放旧 Surface 的 ScreenCapturerAndroid 改写版）+ 保底帧率 + 受保护内容检测 ——
        val h = SurfaceTextureHelper.create("wsc-capture", egl)
        helper = h
        val vs = f.createVideoSource(true)
        videoSource = vs
        val detector = ProtectedContentDetector { suspected ->
            sendDc(DcMessage.ProtectedContent(suspected))
            listener.onProtectedContent(suspected)
        }
        val c = WscScreenCapturer(projectionData, projectionCallback)
        capturer = c
        val fr = FrameRepeater(vs.capturerObserver, h, minFps = 5, frameTap = detector::onFrame)
        repeater = fr
        c.initialize(h, context, fr)
        val (sw, sh) = screenSize()
        captureSize = CaptureSize.fit(sw, sh, profile)
        c.startCapture(captureSize.width, captureSize.height, effectiveFps)
        contentSize = sw to sh
        emitGeometry()
        WscLog.i(TAG, "capture started ${captureSize.width}x${captureSize.height}@${profile.fps}")
        if (Build.VERSION.SDK_INT >= 34) {
            c.mediaProjection?.registerCallback(contentCallback, mainHandler)
        }
        registerDisplayListener()

        val vt = f.createVideoTrack("wsc-video", vs)
        videoTrack = vt
        if (audioEnabled) {
            val asrc = f.createAudioSource(RtcConfig.noAudioProcessing())
            audioSource = asrc
            audioTrack = f.createAudioTrack("wsc-audio", asrc)
        } else {
            listener.onAudioState(AudioState.OFF)
        }

        // —— PeerConnection ——
        pc = createPeer(f)
        createOffer(iceRestart = false)
    }

    /**
     * 在 [executor] 上：新建 PeerConnection 并挂上现有的音视频轨道和两条 DataChannel（首次建立与 [rebuildMedia] 共用）。
     * 同一条轨道可以挂到同一工厂的新 PeerConnection 上；旧连接 dispose 时只释放它自己的轨道引用。
     */
    private fun createPeer(f: PeerConnectionFactory): PeerConnection {
        val vt = videoTrack ?: error("no video track")
        val gen = mediaGen
        val peer = f.createPeerConnection(RtcConfig.hostOnly(), PeerObserver(gen)) ?: error("createPeerConnection returned null")
        videoSender = peer.addTrack(vt, listOf(STREAM_ID))
        preferH264(peer)
        // 游戏档：音频放进不同的 stream，解除唇音同步，视频走最低延迟（技术方案 3.4）
        audioTrack?.let { peer.addTrack(it, listOf(if (profile.avSync) STREAM_ID else "$STREAM_ID-audio")) }
        dataChannel = peer.createDataChannel(DC_LABEL, DataChannel.Init().apply { ordered = true })
            .also { it.registerObserver(dcObserver(it)) }
        // 反向控制输入通道（PROTOCOL.md 4.2）：negotiated，接收端在设置远端描述后创建同一 id 的通道；旧版本接收端不创建，数据被丢弃
        inputChannel = peer.createDataChannel(RtcConfig.INPUT_CHANNEL_LABEL, RtcConfig.inputChannelInit())
            .also { it.registerObserver(inputObserver(it)) }
        videoSender?.let { QualityApplier.apply(it, profile, effectiveFps) }
        return peer
    }

    /**
     * 会话中切换到需要重建媒体连接的档位（[QualityProfile.needsMediaRebuild]）：先在本地应用分辨率 / 帧率，
     * 再新建 PeerConnection 并发出 `OFFER{rebuild}`。重复调用（切换超时回退）时，上一次尚未应答的连接直接关闭。
     */
    fun rebuildMedia(q: QualityProfile, capFps: Int? = null) = executor.execute {
        val f = factory ?: run {
            listener.onRebuildUnavailable(q)
            return@execute
        }
        val prevProfile = profile
        val prevCap = fpsCap
        disposeRetiring()
        val old = pc
        val oldDc = dataChannel
        val oldInput = inputChannel
        val oldSender = videoSender
        val genBefore = mediaGen
        runCatching {
            fpsCap = capFps
            applyCaptureProfile(q)
            if (old != null) {
                oldDc?.unregisterObserver()
                oldInput?.unregisterObserver()
                retiring = Retiring(old, oldDc, oldInput)
            }
            mediaGen++
            genConnected = false
            remoteDescriptionSet = false
            pendingRemoteIce.clear()
            statsTask?.cancel(false)
            statsTask = null
            statsParser = StatsParser()
            lastReceiverStats = null
            pc = createPeer(f)
            WscLog.i(TAG, "media rebuild #$mediaGen for $q")
            createOffer(iceRestart = false, rebuild = true)
        }.onFailure {
            // 新连接建不起来：恢复旧连接（它一直在发送），调用方退回“只改分辨率帧率”（交互稿 G02 “下次生效”）
            WscLog.e(TAG, "media rebuild failed, keeping current connection", it)
            val half = pc
            if (half != null && half !== old) runCatching { half.close(); half.dispose() }
            retiring = null
            if (old != null) {
                mediaGen = genBefore
                pc = old
                dataChannel = oldDc?.also { dc -> dc.registerObserver(dcObserver(dc)) }
                inputChannel = oldInput?.also { dc -> dc.registerObserver(inputObserver(dc)) }
                videoSender = oldSender
                remoteDescriptionSet = true
                genConnected = true
                startStats()
            }
            fpsCap = prevCap
            profile = prevProfile
            listener.onRebuildUnavailable(q)
        }
    }

    /** 游戏模式降帧 / 恢复 60 帧（采集器不重启）。 */
    fun setFrameRateCap(capFps: Int?, announce: Boolean = true) = executor.execute {
        announceCap = announce
        if (fpsCap == capFps) return@execute
        fpsCap = capFps
        capturer?.changeCaptureFormat(captureSize.width, captureSize.height, effectiveFps)
        videoSender?.let { QualityApplier.apply(it, profile, effectiveFps) }
        WscLog.i(TAG, "frame rate cap -> ${capFps ?: "none"} (${effectiveFps} fps)")
    }

    /** 在 [executor] 上：关闭等待替换的旧 PeerConnection。 */
    private fun disposeRetiring() {
        val r = retiring ?: return
        retiring = null
        runCatching { r.dc?.close(); r.dc?.dispose() }
        runCatching { r.input?.close(); r.input?.dispose() }
        runCatching { r.peer.close(); r.peer.dispose() }
        WscLog.i(TAG, "retired previous peer connection")
    }

    private class Retiring(val peer: PeerConnection, val dc: DataChannel?, val input: DataChannel?)

    private fun preferH264(peer: PeerConnection) {
        val f = factory ?: return
        val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)?.codecs ?: return
        val sorted = caps.sortedBy { c ->
            when (c.name.uppercase()) {
                "H264" -> 0
                "VP8" -> 1
                "RTX", "RED", "ULPFEC", "FLEXFEC-03" -> 3
                else -> 2
            }
        }
        peer.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?.setCodecPreferences(sorted)
    }

    private fun createOffer(iceRestart: Boolean, rebuild: Boolean = false) {
        val peer = pc ?: return
        val gen = mediaGen
        if (iceRestart) peer.restartIce()
        peer.createOffer(object : SimpleSdpObserver("offer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val munged = SessionDescription(sdp.type, SdpMunger.tuneOpus(sdp.description))
                executor.execute {
                    if (gen != mediaGen) return@execute
                    peer.setLocalDescription(object : SimpleSdpObserver("local offer") {
                        override fun onSetSuccess() {
                            if (gen == mediaGen) listener.onLocalOffer(munged.description, iceRestart, rebuild)
                        }
                    }, munged)
                }
            }
        }, MediaConstraints())
    }

    fun onRemoteAnswer(sdp: String) = executor.execute {
        val peer = pc ?: return@execute
        val gen = mediaGen
        // 重建：接收端已丢弃旧连接，先关旧连接（释放编码器）再让新连接开始发送
        disposeRetiring()
        peer.setRemoteDescription(object : SimpleSdpObserver("remote answer") {
            override fun onSetSuccess() {
                executor.execute {
                    if (gen != mediaGen) return@execute
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { peer.addIceCandidate(it) }
                    pendingRemoteIce.clear()
                }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun onRemoteIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int) = executor.execute {
        if (!SdpMunger.isHostCandidate(candidate)) {
            WscLog.w(TAG, "drop non-host remote candidate")
            return@execute
        }
        val c = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        if (remoteDescriptionSet) pc?.addIceCandidate(c) else pendingRemoteIce += c
    }

    /** 断线恢复：ICE restart（不重新授权录屏，技术方案 4.3）。 */
    fun restartIce() = executor.execute {
        disposeRetiring()
        remoteDescriptionSet = false
        createOffer(iceRestart = true)
    }

    fun setQuality(q: QualityProfile, fromRemote: Boolean = false, capFps: Int? = null) = executor.execute {
        if (q == profile && capFps == fpsCap) return@execute
        fpsCap = capFps
        applyCaptureProfile(q)
        videoSender?.let { QualityApplier.apply(it, q, effectiveFps) }
        pc?.setBitrate(q.minBitrateBps, null, q.maxBitrateBps)
        if (!fromRemote) sendDc(DcMessage.SetQuality(q))
        listener.onQualityChanged(q, fromRemote)
    }

    /** 在 [executor] 上：记录档位并按它调整采集尺寸 / 帧率（采集器不重启）。 */
    private fun applyCaptureProfile(q: QualityProfile) {
        profile = q
        val (sw, sh) = screenSize()
        val size = CaptureSize.fit(sw, sh, q)
        captureSize = size
        capturer?.changeCaptureFormat(size.width, size.height, effectiveFps)
        emitGeometry()
        WscLog.i(TAG, "quality -> $q ${size.width}x${size.height}@$effectiveFps")
    }

    fun setAudioEnabled(enabled: Boolean) {
        audioCapture?.enabled = enabled
        audioTrack?.setEnabled(enabled)
        sendDc(DcMessage.Audio(if (enabled) AudioState.ON else AudioState.OFF))
    }

    fun sendDc(message: DcMessage) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(DcMessage.encode(message).toByteArray()), false)) }
    }

    private fun resizeTo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val size = CaptureSize.fit(width, height, profile)
        val contentChanged = contentSize != (width to height)
        contentSize = width to height
        if (size == captureSize) {
            if (contentChanged) emitGeometry()
            return
        }
        captureSize = size
        capturer?.changeCaptureFormat(size.width, size.height, effectiveFps)
        emitGeometry()
        WscLog.i(TAG, "resize -> ${size.width}x${size.height}")
    }

    /** 在 RTC 线程上调用：把当前采集几何报告给反向控制（只有尺寸，不含任何画面内容）。 */
    private fun emitGeometry() {
        val (cw, ch) = contentSize
        if (cw <= 0 || ch <= 0) return
        val (sw, sh) = screenSize()
        listener.onCaptureGeometry(captureSize.width, captureSize.height, cw, ch, sw, sh)
    }

    /** 反向控制：发送端只在 `wsc-input` 上回 IREJ。 */
    fun sendInput(message: InputMessage) {
        val dc = inputChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(InputCodec.encode(message).toByteArray()), false)) }
    }

    private fun inputObserver(dc: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            WscLog.i(TAG, "input channel ${dc.state()}")
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            // 未知类型 / 畸形 / 越界：忽略（日志不记录内容，只在调用方计数）
            InputCodec.decodeOrNull(String(bytes))?.let(listener::onInputMessage)
        }
    }

    /** 旋转检测（API 29~33 的唯一途径；34+ 作为兜底，与 onCapturedContentResize 去重）。 */
    private fun registerDisplayListener() {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                executor.execute {
                    val (w, h) = screenSize()
                    resizeTo(w, h)
                }
            }
        }
        dm.registerDisplayListener(l, mainHandler)
        displayListener = l
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        display?.getRealSize(p)
        return if (p.x > 0 && p.y > 0) p.x to p.y else 1080 to 1920
    }

    private fun startStats() {
        statsTask?.cancel(false)
        statsTask = executor.scheduleWithFixedDelay({
            val gen = mediaGen
            val parser = statsParser
            pc?.getStats { report ->
                if (gen != mediaGen) return@getStats
                val s = parser.parseSender(report.toEntries())?.copy(captureFps = contentFps()) ?: return@getStats
                val r = lastReceiverStats
                // 帧间隔按档位目标帧率（含降帧上限）算，不按瞬时出帧率（F18 走查 P0-3）
                val latency = LatencyEstimator.estimate(effectiveFps, s.encodeMs, s.rttMs, r?.jitterBufferMs, r?.decodeMs, r?.procMs, s.pacerMs)
                if (!s.hostOnly) WscLog.w(TAG, "selected candidate pair is not host-only")
                listener.onStats(s, latency, r)
                sendDc(
                    DcMessage.SenderStats(
                        bitrateKbps = s.bitrateKbps, fps = s.fps, width = s.width, height = s.height,
                        rttMs = s.rttMs, encodeMs = s.encodeMs, latencyMs = latency, lossPercent = s.lossPercent,
                        codec = s.encoder, profile = profile, pacerMs = s.pacerMs,
                        capFps = fpsCap?.takeIf { announceCap && profile.lowLatency && it < profile.fps },
                    ),
                )
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    /** 最近一个统计周期的采集内容帧率。 */
    private fun contentFps(): Int? {
        val fr = repeater ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val frames = fr.contentFrames
        val fps = if (lastContentAtMs > 0 && now > lastContentAtMs) ((frames - lastContentFrames) * 1000 / (now - lastContentAtMs)).toInt() else null
        lastContentFrames = frames
        lastContentAtMs = now
        return fps
    }

    private fun dcObserver(dc: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            if (dc !== dataChannel) return
            if (dc.state() == DataChannel.State.OPEN) {
                sendDc(DcMessage.Audio(if (audioEnabled) AudioState.ON else AudioState.NO_PERMISSION))
                listener.onDataChannelOpen()
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (dc !== dataChannel) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            when (val m = DcMessage.decodeOrNull(String(bytes))) {
                is DcMessage.ReceiverStats -> lastReceiverStats = m
                is DcMessage.SetQuality -> setQuality(m.profile, fromRemote = true)
                is DcMessage.Rebuilt -> listener.onRebuilt(m.pauseMs)
                null -> Unit
                else -> listener.onDataChannelMessage(m)
            }
        }
    }

    /** 每个 PeerConnection 一个观察者：[gen] 不是当前代号时（已被重建替换）丢弃回调。 */
    private inner class PeerObserver(private val gen: Int) : PeerConnection.Observer {
        private val current: Boolean get() = gen == mediaGen

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            WscLog.i(TAG, "ice #$gen $state")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val s = newState.toMediaState()
            WscLog.i(TAG, "pc #$gen $s")
            if (!current) return
            if (s == MediaState.CONNECTED) {
                executor.execute {
                    if (!current) return@execute
                    if (!genConnected) {
                        genConnected = true
                        // 局域网带宽充裕：抬高 GCC 起始码率到档位上限的 50%（技术方案 3.2）；重建后的新连接同样需要，缩短切换后的模糊期
                        pc?.setBitrate(profile.minBitrateBps, profile.startBitrateBps, profile.maxBitrateBps)
                    }
                    if (!everConnected) {
                        everConnected = true
                        listener.onFirstConnected()
                    }
                    startStats()
                }
            }
            listener.onMediaState(s)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            if (!current) return
            if (!SdpMunger.isHostCandidate(candidate.sdp)) return // 零外联：只发 host 候选
            listener.onLocalIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dc: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    /** 释放全部资源（PRD S-07：停止后 1 秒内释放 PeerConnection、编码器和采集）。阻塞至多 [timeoutMs]。 */
    fun close(timeoutMs: Long = 1_000) {
        closing = true
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            try {
                statsTask?.cancel(false)
                displayListener?.let { context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
                if (Build.VERSION.SDK_INT >= 34) runCatching { capturer?.mediaProjection?.unregisterCallback(contentCallback) }
                runCatching { capturer?.stopCapture() }
                runCatching { capturer?.dispose() }
                runCatching { dataChannel?.unregisterObserver(); dataChannel?.close(); dataChannel?.dispose() }
                runCatching { inputChannel?.unregisterObserver(); inputChannel?.close(); inputChannel?.dispose() }
                mediaGen++
                disposeRetiring()
                runCatching { pc?.close(); pc?.dispose() }
                runCatching { videoSource?.dispose() }
                runCatching { audioSource?.dispose() }
                runCatching { helper?.dispose() }
                audioCapture?.release()
                runCatching { factory?.dispose() }
                runCatching { adm?.release() }
            } finally {
                pc = null; capturer = null; factory = null; dataChannel = null; inputChannel = null
                done.countDown()
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        executor.shutdown()
        WscLog.i(TAG, "closed")
    }

    private companion object {
        const val TAG = "SenderRtc"
        const val STREAM_ID = "wsc"
        const val DC_LABEL = "wsc"
    }
}
