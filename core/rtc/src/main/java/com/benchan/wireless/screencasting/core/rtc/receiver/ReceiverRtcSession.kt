package com.benchan.wireless.screencasting.core.rtc.receiver

import android.content.Context
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.control.PointerAction
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.core.protocol.quality.ReceiverTuning
import com.benchan.wireless.screencasting.core.rtc.MediaState
import com.benchan.wireless.screencasting.core.rtc.RtcConfig
import com.benchan.wireless.screencasting.core.rtc.RtcEngine
import com.benchan.wireless.screencasting.core.rtc.SdpMunger
import com.benchan.wireless.screencasting.core.rtc.SimpleSdpObserver
import com.benchan.wireless.screencasting.core.rtc.stats.ReceiverStatsSnapshot
import com.benchan.wireless.screencasting.core.rtc.stats.StatsParser
import com.benchan.wireless.screencasting.core.rtc.toEntries
import com.benchan.wireless.screencasting.core.rtc.toMediaState
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
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.WscLowLatencyVideoDecoderFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 接收端镜像会话（PRD R-02~R-04、技术方案 3.4）：应答 OFFER、硬件解码优先、低延迟字段试验、DataChannel 统计。
 * 渲染器由 UI 通过 [attachRenderer] 挂上（SurfaceViewRenderer，等比 + 黑边），断线期间保留最后一帧。
 *
 * 媒体重建（F18 会话中切进 / 切出游戏档，PROTOCOL.md 4.3）：[rebuild] 先关闭当前 PeerConnection / 工厂 / 音频模块
 * （释放硬件解码器，低端盒子往往只有一个实例），再按新的 [ReceiverTuning] 新建工厂（新的播放缓冲字段试验、解码器工厂）和
 * PeerConnection 并应答。渲染代理、监听器、反向控制客户端都不变，SurfaceView 在此期间保留最后一帧。
 * 每个 PeerConnection 有自己的“媒体代号”，旧代号的回调一律丢弃，因此关闭旧连接不会被当成断线。
 */
class ReceiverRtcSession(
    private val context: Context,
    private val engine: RtcEngine,
    private val listener: Listener,
    initialTuning: ReceiverTuning = ReceiverTuning.NORMAL,
) {
    interface Listener {
        fun onLocalAnswer(sdp: String)
        fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onMediaState(state: MediaState)
        fun onFirstFrame()
        fun onStats(stats: ReceiverStatsSnapshot, senderStats: DcMessage.SenderStats?)
        fun onDataChannelMessage(message: DcMessage)

        /** 反向控制：`wsc-input` 上发送端回的消息（IREJ），在 RTC 线程回调。 */
        fun onInputMessage(message: InputMessage) = Unit

        /** 解码帧尺寸（已应用旋转）变化，在解码线程回调：反向控制据此换算坐标、匹配几何代号。 */
        fun onFrameSize(width: Int, height: Int) = Unit

        /** [rebuild] 之后新连接的第一帧已送到渲染器（切换停顿结束），在解码线程回调。 */
        fun onRebuiltFirstFrame() = Unit

        /** [notifyOnNextFrame] 之后又收到了画面（用于撤下“正在重连”），在解码线程回调。 */
        fun onFrameResumed() = Unit
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wsc-receiver-rtc") }
    private var statsParser = StatsParser()

    /** 当前接收链路参数（播放缓冲 / 解码器 / 音频缓冲），只在 [executor] 上修改。 */
    @Volatile
    var tuning: ReceiverTuning = initialTuning
        private set

    /** 当前 PeerConnection 的代号：每次 [rebuild] 加一，旧代号的回调丢弃。 */
    @Volatile
    private var mediaGen = 0

    /** 低延迟解码器是否带着低延迟参数 configure 成功（null = 未使用低延迟解码器工厂 / 尚未解码）。 */
    @Volatile
    private var lowLatencyDecoder: Boolean? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null
    private var inputChannel: DataChannel? = null
    private var statsTask: ScheduledFuture<*>? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    @Volatile
    private var lastSenderStats: DcMessage.SenderStats? = null

    /** 渲染代理：UI 重建 SurfaceView 时只换目标，不动轨道；首帧回调一次。 */
    private val proxySink = object : VideoSink {
        @Volatile
        var target: VideoSink? = null

        @Volatile
        var gotFirst = false

        /** [rebuild] 后等待新连接的第一帧。 */
        @Volatile
        var awaitingRebuilt = false

        /** 等待“画面恢复”通知。 */
        @Volatile
        var awaitingAny = false

        private var lastW = 0
        private var lastH = 0

        override fun onFrame(frame: VideoFrame) {
            if (!gotFirst) {
                gotFirst = true
                listener.onFirstFrame()
            }
            if (awaitingRebuilt) {
                awaitingRebuilt = false
                listener.onRebuiltFirstFrame()
            }
            if (awaitingAny) {
                awaitingAny = false
                listener.onFrameResumed()
            }
            val w = frame.rotatedWidth
            val h = frame.rotatedHeight
            if (w != lastW || h != lastH) {
                lastW = w
                lastH = h
                listener.onFrameSize(w, h)
            }
            target?.onFrame(frame)
        }
    }

    fun start() = executor.execute {
        runCatching { createPeer(tuning) }.onFailure {
            WscLog.e(TAG, "start failed", it)
            listener.onMediaState(MediaState.FAILED)
        }
    }

    /** 在 [executor] 上：按 [t] 新建音频模块、工厂（字段试验 + 解码器工厂）和 PeerConnection。 */
    private fun createPeer(t: ReceiverTuning) {
        engine.initialize()
        val egl = engine.eglBase.eglBaseContext
        val audio = JavaAudioDeviceModule.builder(context)
            .setUseLowLatency(true)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()
        // 接收端从不录音：确保不会创建麦克风 AudioRecord
        audio.setAudioRecordEnabled(false)
        adm = audio
        lowLatencyDecoder = null
        val gen = mediaGen
        // 硬件解码优先；Android 包里没有 H.264 软解，所以 SDP 应答里的 H.264 只在本机能硬解时出现（R-02）。
        // 游戏档用低延迟解码器工厂（支持的编解码器列表与标准工厂相同，任何失败回退标准解码器）
        val decoders = if (t.lowLatencyDecoder) {
            WscLowLatencyVideoDecoderFactory(egl, LOW_LATENCY_OPERATING_RATE) { name, ll ->
                if (gen == mediaGen) {
                    lowLatencyDecoder = ll
                    WscLog.i(TAG, "decoder $name lowLatency=$ll")
                }
            }
        } else {
            DefaultVideoDecoderFactory(egl)
        }
        val f = PeerConnectionFactory.builder()
            .setOptions(engine.factoryOptions())
            .setFieldTrials(RtcConfig.receiverFieldTrials(t.playoutDelayMaxMs))
            .setAudioDeviceModule(audio)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
            .setVideoDecoderFactory(decoders)
            .createPeerConnectionFactory()
        factory = f
        pc = f.createPeerConnection(RtcConfig.receiver(t), PeerObserver(gen)) ?: error("createPeerConnection returned null")
        WscLog.i(TAG, "peer #$gen created, playoutDelayMax=${t.playoutDelayMaxMs}ms lowLatencyDecoder=${t.lowLatencyDecoder}")
    }

    /** 在 [executor] 上：关闭当前 PeerConnection 及其工厂 / 音频模块（不动渲染代理）。 */
    private fun teardownPeer() {
        statsTask?.cancel(false)
        statsTask = null
        runCatching { videoTrack?.removeSink(proxySink) }
        runCatching { dataChannel?.unregisterObserver(); dataChannel?.close() }
        runCatching { inputChannel?.unregisterObserver(); inputChannel?.close(); inputChannel?.dispose() }
        runCatching { pc?.close(); pc?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { adm?.release() }
        pc = null; factory = null; adm = null; dataChannel = null; inputChannel = null; videoTrack = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        pendingDc.clear()
        lastSenderStats = null
    }

    /**
     * 媒体重建（PROTOCOL.md 4.3 `OFFER.rebuild`）：丢弃当前连接，按 [newTuning] 新建接收链路后应答 [offerSdp]。
     * 失败时报告 FAILED，由发送端的切换超时负责回退。
     */
    fun rebuild(newTuning: ReceiverTuning, offerSdp: String) = executor.execute {
        val startedAt = System.currentTimeMillis()
        mediaGen++
        teardownPeer()
        statsParser = StatsParser()
        tuning = newTuning
        proxySink.awaitingRebuilt = true
        runCatching { createPeer(newTuning) }.onFailure {
            WscLog.e(TAG, "rebuild failed", it)
            listener.onMediaState(MediaState.FAILED)
            return@execute
        }
        WscLog.i(TAG, "media rebuilt in ${System.currentTimeMillis() - startedAt}ms")
        applyRemoteOffer(offerSdp)
    }

    fun onRemoteOffer(sdp: String, iceRestart: Boolean) = executor.execute {
        if (iceRestart) remoteDescriptionSet = false
        applyRemoteOffer(sdp)
    }

    private fun applyRemoteOffer(sdp: String) {
        val peer = pc ?: return
        val gen = mediaGen
        peer.setRemoteDescription(object : SimpleSdpObserver("remote offer") {
            override fun onSetSuccess() {
                executor.execute {
                    if (gen != mediaGen) return@execute
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { peer.addIceCandidate(it) }
                    pendingRemoteIce.clear()
                    if (inputChannel == null) {
                        // 反向控制输入通道（PROTOCOL.md 4.2）：与发送端约定的 negotiated id，旧版本发送端没有对应通道时消息被丢弃
                        inputChannel = runCatching { peer.createDataChannel(RtcConfig.INPUT_CHANNEL_LABEL, RtcConfig.inputChannelInit()) }
                            .getOrNull()?.also { it.registerObserver(inputObserver()) }
                    }
                    createAnswer(peer)
                }
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun createAnswer(peer: PeerConnection) {
        val gen = mediaGen
        peer.createAnswer(object : SimpleSdpObserver("answer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val munged = SessionDescription(sdp.type, SdpMunger.tuneOpus(sdp.description))
                executor.execute {
                    if (gen != mediaGen) return@execute
                    peer.setLocalDescription(object : SimpleSdpObserver("local answer") {
                        override fun onSetSuccess() {
                            if (gen == mediaGen) listener.onLocalAnswer(munged.description)
                        }
                    }, munged)
                }
            }
        }, MediaConstraints())
    }

    fun onRemoteIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int) = executor.execute {
        if (!SdpMunger.isHostCandidate(candidate)) return@execute
        val c = IceCandidate(sdpMid ?: "0", sdpMLineIndex, candidate)
        if (remoteDescriptionSet) pc?.addIceCandidate(c) else pendingRemoteIce += c
    }

    fun attachRenderer(sink: VideoSink) {
        proxySink.target = sink
    }

    fun detachRenderer(sink: VideoSink) {
        if (proxySink.target === sink) proxySink.target = null
    }

    /** 新会话的首帧重新计时（重连后保持 false 以免重复触发）。 */
    fun resetFirstFrame() {
        proxySink.gotFirst = false
    }

    /** 下一帧画面到达时回调 [Listener.onFrameResumed] 一次。 */
    fun notifyOnNextFrame() {
        proxySink.awaitingAny = true
    }

    /** 当前连接的 `wsc` 还没打开时排队的消息（打开后立即发送；重建时清空）。 */
    private val pendingDc = java.util.concurrent.ConcurrentLinkedQueue<DcMessage>()

    /** 可靠发送：通道未打开时排队，打开后发送（用于 `REBUILT`，首帧可能早于 DataChannel 打开）。 */
    fun sendDcWhenOpen(message: DcMessage) {
        val dc = dataChannel
        if (dc != null && dc.state() == DataChannel.State.OPEN) sendDc(message) else pendingDc += message
    }

    private fun flushPendingDc() {
        while (true) {
            val m = pendingDc.poll() ?: return
            sendDc(m)
        }
    }

    fun sendDc(message: DcMessage) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(DcMessage.encode(message).toByteArray()), false)) }
    }

    /**
     * 反向控制：发送一条输入消息。发送缓冲超过阈值时丢弃 MOVE（DOWN / UP / CANCEL / 按键从不丢）。
     * @return 是否已交给 DataChannel。
     */
    fun sendInput(message: InputMessage): Boolean {
        val dc = inputChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        if (message is InputMessage.Pointer && message.a == PointerAction.MOVE && dc.bufferedAmount() > RtcConfig.INPUT_BACKPRESSURE_BYTES) return false
        return runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(InputCodec.encode(message).toByteArray()), false)) }.getOrDefault(false)
    }

    /** 输入通道是否已打开（发送端支持反向控制且 SCTP 已连通）。 */
    val inputChannelOpen: Boolean get() = inputChannel?.state() == DataChannel.State.OPEN

    private fun inputObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            InputCodec.decodeOrNull(String(bytes))?.let(listener::onInputMessage)
        }
    }

    private fun startStats() {
        statsTask?.cancel(false)
        statsTask = executor.scheduleWithFixedDelay({
            val gen = mediaGen
            val parser = statsParser
            pc?.getStats { report ->
                if (gen != mediaGen) return@getStats
                val s = parser.parseReceiver(report.toEntries())?.copy(lowLatencyDecoder = lowLatencyDecoder) ?: return@getStats
                listener.onStats(s, lastSenderStats)
                sendDc(
                    DcMessage.ReceiverStats(
                        jitterBufferMs = s.jitterBufferMs, decodeMs = s.decodeMs, framesDropped = s.framesDropped,
                        framesDecoded = s.framesDecoded, fps = s.fps, decoder = s.decoder, hardwareDecoder = s.hardwareDecoder,
                        procMs = s.processingMs, lowLatency = s.lowLatencyDecoder,
                    ),
                )
            }
        }, 1, 1, TimeUnit.SECONDS)
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
            if (s == MediaState.CONNECTED) executor.execute { if (current) startStats() }
            listener.onMediaState(s)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            if (!current || !SdpMunger.isHostCandidate(candidate.sdp)) return
            listener.onLocalIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

        override fun onTrack(transceiver: RtpTransceiver) {
            if (!current) return
            val track = transceiver.receiver.track()
            if (track is VideoTrack && transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                videoTrack = track
                track.addSink(proxySink)
                WscLog.i(TAG, "remote video track")
            }
        }

        override fun onDataChannel(dc: DataChannel) {
            if (!current) {
                runCatching { dc.dispose() }
                return
            }
            dataChannel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() {
                    if (current && dc.state() == DataChannel.State.OPEN) flushPendingDc()
                }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (!current) return
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val m = DcMessage.decodeOrNull(String(bytes)) ?: return
                    if (m is DcMessage.SenderStats) lastSenderStats = m
                    listener.onDataChannelMessage(m)
                }
            })
            if (dc.state() == DataChannel.State.OPEN) flushPendingDc()
        }
    }

    fun close(timeoutMs: Long = 1_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            try {
                mediaGen++
                proxySink.target = null
                teardownPeer()
            } finally {
                done.countDown()
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        executor.shutdown()
        WscLog.i(TAG, "closed")
    }

    private companion object {
        const val TAG = "ReceiverRtc"

        /** 低延迟解码器的 KEY_OPERATING_RATE（游戏档 60 帧）。 */
        const val LOW_LATENCY_OPERATING_RATE = 60
    }
}
