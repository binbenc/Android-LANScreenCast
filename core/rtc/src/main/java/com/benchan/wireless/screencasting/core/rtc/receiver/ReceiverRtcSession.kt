package com.benchan.wireless.screencasting.core.rtc.receiver

import android.content.Context
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
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
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 接收端镜像会话（PRD R-02~R-04、技术方案 3.4）：应答 OFFER、硬件解码优先、低延迟字段试验、DataChannel 统计。
 * 渲染器由 UI 通过 [attachRenderer] 挂上（SurfaceViewRenderer，等比 + 黑边），断线期间保留最后一帧。
 */
class ReceiverRtcSession(
    private val context: Context,
    private val engine: RtcEngine,
    private val listener: Listener,
    private val maxPlayoutDelayMs: Int = 100,
) {
    interface Listener {
        fun onLocalAnswer(sdp: String)
        fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onMediaState(state: MediaState)
        fun onFirstFrame()
        fun onStats(stats: ReceiverStatsSnapshot, senderStats: DcMessage.SenderStats?)
        fun onDataChannelMessage(message: DcMessage)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "wsc-receiver-rtc") }
    private val statsParser = StatsParser()
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var dataChannel: DataChannel? = null
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

        override fun onFrame(frame: VideoFrame) {
            if (!gotFirst) {
                gotFirst = true
                listener.onFirstFrame()
            }
            target?.onFrame(frame)
        }
    }

    fun start() = executor.execute {
        runCatching {
            engine.initialize()
            val egl = engine.eglBase.eglBaseContext
            val audio = JavaAudioDeviceModule.builder(context)
                .setUseLowLatency(true)
                .setUseStereoOutput(true)
                .createAudioDeviceModule()
            // 接收端从不录音：确保不会创建麦克风 AudioRecord
            audio.setAudioRecordEnabled(false)
            adm = audio
            val f = PeerConnectionFactory.builder()
                .setOptions(engine.factoryOptions())
                .setFieldTrials(RtcConfig.receiverFieldTrials(maxPlayoutDelayMs))
                .setAudioDeviceModule(audio)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl, true, true))
                // 硬件解码优先；Android 包里没有 H.264 软解，所以 SDP 应答里的 H.264 只在本机能硬解时出现（R-02）
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl))
                .createPeerConnectionFactory()
            factory = f
            pc = f.createPeerConnection(RtcConfig.hostOnly(), observer) ?: error("createPeerConnection returned null")
        }.onFailure {
            WscLog.e(TAG, "start failed", it)
            listener.onMediaState(MediaState.FAILED)
        }
    }

    fun onRemoteOffer(sdp: String, iceRestart: Boolean) = executor.execute {
        val peer = pc ?: return@execute
        if (iceRestart) remoteDescriptionSet = false
        peer.setRemoteDescription(object : SimpleSdpObserver("remote offer") {
            override fun onSetSuccess() {
                executor.execute {
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { peer.addIceCandidate(it) }
                    pendingRemoteIce.clear()
                    createAnswer(peer)
                }
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun createAnswer(peer: PeerConnection) {
        peer.createAnswer(object : SimpleSdpObserver("answer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val munged = SessionDescription(sdp.type, SdpMunger.tuneOpus(sdp.description))
                executor.execute {
                    peer.setLocalDescription(object : SimpleSdpObserver("local answer") {
                        override fun onSetSuccess() = listener.onLocalAnswer(munged.description)
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

    fun sendDc(message: DcMessage) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(DcMessage.encode(message).toByteArray()), false)) }
    }

    private fun startStats() {
        statsTask?.cancel(false)
        statsTask = executor.scheduleWithFixedDelay({
            pc?.getStats { report ->
                val s = statsParser.parseReceiver(report.toEntries()) ?: return@getStats
                listener.onStats(s, lastSenderStats)
                sendDc(
                    DcMessage.ReceiverStats(
                        jitterBufferMs = s.jitterBufferMs, decodeMs = s.decodeMs, framesDropped = s.framesDropped,
                        framesDecoded = s.framesDecoded, fps = s.fps, decoder = s.decoder, hardwareDecoder = s.hardwareDecoder,
                    ),
                )
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            WscLog.i(TAG, "ice $state")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            val s = newState.toMediaState()
            WscLog.i(TAG, "pc $s")
            if (s == MediaState.CONNECTED) executor.execute { startStats() }
            listener.onMediaState(s)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            if (!SdpMunger.isHostCandidate(candidate.sdp)) return
            listener.onLocalIceCandidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack && transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                videoTrack = track
                track.addSink(proxySink)
                WscLog.i(TAG, "remote video track")
            }
        }

        override fun onDataChannel(dc: DataChannel) {
            dataChannel = dc
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit
                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val m = DcMessage.decodeOrNull(String(bytes)) ?: return
                    if (m is DcMessage.SenderStats) lastSenderStats = m
                    listener.onDataChannelMessage(m)
                }
            })
        }
    }

    fun close(timeoutMs: Long = 1_000) {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute {
            try {
                statsTask?.cancel(false)
                proxySink.target = null
                runCatching { videoTrack?.removeSink(proxySink) }
                runCatching { dataChannel?.unregisterObserver(); dataChannel?.close() }
                runCatching { pc?.close(); pc?.dispose() }
                runCatching { factory?.dispose() }
                runCatching { adm?.release() }
            } finally {
                pc = null; factory = null; dataChannel = null; videoTrack = null
                done.countDown()
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        executor.shutdown()
        WscLog.i(TAG, "closed")
    }

    private companion object {
        const val TAG = "ReceiverRtc"
    }
}
