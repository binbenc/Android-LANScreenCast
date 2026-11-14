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
import org.webrtc.ScreenCapturerAndroid
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
 * - 整个会话只调用一次 ScreenCapturerAndroid.startCapture()（内部先 registerCallback 再 createVirtualDisplay），
 *   断线重连只做 ICE restart，绝不 stop/start 采集器，尺寸变化走 changeCaptureFormat。
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
        fun onLocalOffer(sdp: String, iceRestart: Boolean)
        fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onMediaState(state: MediaState)
        fun onFirstConnected()
        fun onProjectionStopped(reason: ProjectionStopReason)
        fun onStats(stats: SenderStatsSnapshot, latencyMs: Int?)
        fun onQualityChanged(profile: QualityProfile, byRemote: Boolean)
        fun onAudioState(state: AudioState)
        fun onCapturedAppVisible(visible: Boolean)
        fun onProtectedContent(suspected: Boolean)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wsc-sender-rtc") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statsParser = StatsParser()

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null
    private var dataChannel: DataChannel? = null
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

        // —— 视频：ScreenCapturerAndroid + 保底帧率 + 受保护内容检测 ——
        val h = SurfaceTextureHelper.create("wsc-capture", egl)
        helper = h
        val vs = f.createVideoSource(true)
        videoSource = vs
        val detector = ProtectedContentDetector { suspected ->
            sendDc(DcMessage.ProtectedContent(suspected))
            listener.onProtectedContent(suspected)
        }
        val c = ScreenCapturerAndroid(projectionData, projectionCallback)
        capturer = c
        c.initialize(h, context, FrameRepeater(vs.capturerObserver, h, minFps = 5, frameTap = detector::onFrame))
        val (sw, sh) = screenSize()
        captureSize = CaptureSize.fit(sw, sh, profile)
        c.startCapture(captureSize.width, captureSize.height, profile.fps)
        WscLog.i(TAG, "capture started ${captureSize.width}x${captureSize.height}@${profile.fps}")
        if (Build.VERSION.SDK_INT >= 34) {
            c.mediaProjection?.registerCallback(contentCallback, mainHandler)
        }
        registerDisplayListener()

        val vt = f.createVideoTrack("wsc-video", vs)
        videoTrack = vt

        // —— PeerConnection ——
        val peer = f.createPeerConnection(RtcConfig.hostOnly(), observer) ?: error("createPeerConnection returned null")
        pc = peer
        videoSender = peer.addTrack(vt, listOf(STREAM_ID))
        preferH264(peer)
        if (audioEnabled) {
            val asrc = f.createAudioSource(RtcConfig.noAudioProcessing())
            audioSource = asrc
            val at = f.createAudioTrack("wsc-audio", asrc)
            audioTrack = at
            // 游戏档：音频放进不同的 stream，解除唇音同步，视频走最低延迟（技术方案 3.4）
            peer.addTrack(at, listOf(if (profile.avSync) STREAM_ID else "$STREAM_ID-audio"))
        } else {
            listener.onAudioState(AudioState.OFF)
        }
        dataChannel = peer.createDataChannel(DC_LABEL, DataChannel.Init().apply { ordered = true })
            .also { it.registerObserver(dcObserver(it)) }
        videoSender?.let { QualityApplier.apply(it, profile) }
        createOffer(iceRestart = false)
    }

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

    private fun createOffer(iceRestart: Boolean) {
        val peer = pc ?: return
        if (iceRestart) peer.restartIce()
        peer.createOffer(object : SimpleSdpObserver("offer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val munged = SessionDescription(sdp.type, SdpMunger.tuneOpus(sdp.description))
                executor.execute {
                    peer.setLocalDescription(object : SimpleSdpObserver("local offer") {
                        override fun onSetSuccess() {
                            listener.onLocalOffer(munged.description, iceRestart)
                        }
                    }, munged)
                }
            }
        }, MediaConstraints())
    }

    fun onRemoteAnswer(sdp: String) = executor.execute {
        val peer = pc ?: return@execute
        peer.setRemoteDescription(object : SimpleSdpObserver("remote answer") {
            override fun onSetSuccess() {
                executor.execute {
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
        remoteDescriptionSet = false
        createOffer(iceRestart = true)
    }

    fun setQuality(q: QualityProfile, fromRemote: Boolean = false) = executor.execute {
        if (q == profile) return@execute
        profile = q
        videoSender?.let { QualityApplier.apply(it, q) }
        val (sw, sh) = screenSize()
        val size = CaptureSize.fit(sw, sh, q)
        captureSize = size
        capturer?.changeCaptureFormat(size.width, size.height, q.fps)
        pc?.setBitrate(q.minBitrateBps, null, q.maxBitrateBps)
        if (!fromRemote) sendDc(DcMessage.SetQuality(q))
        listener.onQualityChanged(q, fromRemote)
        WscLog.i(TAG, "quality -> $q ${size.width}x${size.height}")
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
        if (size == captureSize) return
        captureSize = size
        capturer?.changeCaptureFormat(size.width, size.height, profile.fps)
        WscLog.i(TAG, "resize -> ${size.width}x${size.height}")
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
            pc?.getStats { report ->
                val s = statsParser.parseSender(report.toEntries()) ?: return@getStats
                val r = lastReceiverStats
                val latency = LatencyEstimator.estimate(s.fps, s.encodeMs, s.rttMs, r?.jitterBufferMs, r?.decodeMs)
                if (!s.hostOnly) WscLog.w(TAG, "selected candidate pair is not host-only")
                listener.onStats(s, latency)
                sendDc(
                    DcMessage.SenderStats(
                        bitrateKbps = s.bitrateKbps, fps = s.fps, width = s.width, height = s.height,
                        rttMs = s.rttMs, encodeMs = s.encodeMs, latencyMs = latency, lossPercent = s.lossPercent,
                        codec = s.encoder, profile = profile,
                    ),
                )
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun dcObserver(dc: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            if (dc.state() == DataChannel.State.OPEN) {
                sendDc(DcMessage.Audio(if (audioEnabled) AudioState.ON else AudioState.NO_PERMISSION))
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            when (val m = DcMessage.decodeOrNull(String(bytes))) {
                is DcMessage.ReceiverStats -> lastReceiverStats = m
                is DcMessage.SetQuality -> setQuality(m.profile, fromRemote = true)
                else -> Unit
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            WscLog.i(TAG, "ice $state")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val s = newState.toMediaState()
            WscLog.i(TAG, "pc $s")
            if (s == MediaState.CONNECTED) {
                executor.execute {
                    if (!everConnected) {
                        everConnected = true
                        // 局域网带宽充裕：抬高 GCC 起始码率到档位上限的 50%（技术方案 3.2）
                        pc?.setBitrate(profile.minBitrateBps, profile.startBitrateBps, profile.maxBitrateBps)
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
                runCatching { pc?.close(); pc?.dispose() }
                runCatching { videoSource?.dispose() }
                runCatching { audioSource?.dispose() }
                runCatching { helper?.dispose() }
                audioCapture?.release()
                runCatching { factory?.dispose() }
                runCatching { adm?.release() }
            } finally {
                pc = null; capturer = null; factory = null; dataChannel = null
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
