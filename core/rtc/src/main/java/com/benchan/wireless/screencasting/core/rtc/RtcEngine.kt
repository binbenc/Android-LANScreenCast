package com.benchan.wireless.screencasting.core.rtc

import android.content.Context
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.protocol.quality.ReceiverTuning
import com.benchan.wireless.screencasting.core.rtc.stats.StatEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

/** 媒体连接状态（映射自 PeerConnectionState）。 */
enum class MediaState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

/**
 * libwebrtc 进程级初始化与共享 EGL 上下文（M0：webrtc-sdk 预编译包 150.7871.04；M1 换自建 fork，技术方案 3.1）。
 * 编码器、解码器、渲染器必须共用同一个 EGL 上下文，纹理才能零拷贝。
 */
@Singleton
class RtcEngine @Inject constructor(@ApplicationContext val context: Context) {
    val eglBase: EglBase by lazy { EglBase.create() }

    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setInjectableLogger({ msg, severity, tag ->
                    if (severity == Logging.Severity.LS_ERROR) WscLog.w("webrtc/$tag", msg)
                }, Logging.Severity.LS_WARNING)
                .createInitializationOptions(),
        )
        initialized = true
        WscLog.i(TAG, "libwebrtc initialized")
    }

    /** 只用局域网接口：忽略蜂窝和 VPN 网卡（技术方案 4.3 ICE 配置）。 */
    fun factoryOptions(): PeerConnectionFactory.Options = PeerConnectionFactory.Options().apply {
        networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
    }

    private companion object {
        const val TAG = "RtcEngine"
    }
}

object RtcConfig {
    /** 反向控制输入通道（PROTOCOL.md 4.2）：negotiated、有序、可靠，双方各自创建，不走 DCEP、不需要重新协商 SDP。 */
    const val INPUT_CHANNEL_LABEL = "wsc-input"

    /** 固定 SCTP 流 id：偶数，避开发送端（DTLS server）为 `wsc` 自动分配的奇数 id。 */
    const val INPUT_CHANNEL_ID = 100

    /** 接收端发送缓冲超过该值时丢弃中间的 MOVE（DOWN / UP / 按键从不丢）。 */
    const val INPUT_BACKPRESSURE_BYTES = 16 * 1024L

    fun inputChannelInit(): org.webrtc.DataChannel.Init = org.webrtc.DataChannel.Init().apply {
        ordered = true
        negotiated = true
        id = INPUT_CHANNEL_ID
    }

    /**
     * 零外联的 RTCConfiguration（PRD 8.3.1、技术方案 4.3）：不配置任何 STUN/TURN，只用 host 候选；
     * Android 原生 libwebrtc 默认不做 mDNS 候选混淆，局域网内直接用真实 IP。
     */
    fun hostOnly(): PeerConnection.RTCConfiguration = PeerConnection.RTCConfiguration(emptyList()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.LOW_COST
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        enableDscp = true
        // 硬编场景 CPU 不是瓶颈，关闭 CPU 过载自适应，避免误降分辨率（技术方案 3.4，收益待 M0 验证）
        enableCpuOveruseDetection = false
    }

    /**
     * 接收端低延迟字段试验（技术方案 3.4）：min_ms=0 且 max_ms>0 启用低延迟渲染路径；min=max=0 为“零播放延迟”（游戏档）。
     * 通过 PeerConnectionFactory.Builder.setFieldTrials 按工厂设置（每个接收会话 / 每次媒体重建各自一个工厂）。
     * 取值集中在 [ReceiverTuning]。TODO(M0-T4): 验证该字段试验在 150 里程碑按工厂生效（统计里 jitterBufferDelay 下降）。
     */
    fun receiverFieldTrials(maxPlayoutDelayMs: Int): String = "WebRTC-ForcePlayoutDelay/min_ms:0,max_ms:$maxPlayoutDelayMs/"

    /** 接收端 RTCConfiguration：在 [hostOnly] 基础上按档位调整音频抖动缓冲（F18）。 */
    fun receiver(tuning: ReceiverTuning): PeerConnection.RTCConfiguration = hostOnly().apply {
        audioJitterBufferFastAccelerate = tuning.audioFastAccelerate
        tuning.audioJitterMaxPackets?.let { audioJitterBufferMaxPackets = it }
    }

    /** 关闭软件音频处理（内录不需要 AEC/NS/AGC，技术方案 3.3）。新版本是否仍识别这些 constraints 待 M0 验证。 */
    fun noAudioProcessing(): MediaConstraints = MediaConstraints().apply {
        listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression", "googHighpassFilter").forEach {
            mandatory.add(MediaConstraints.KeyValuePair(it, "false"))
        }
    }
}

internal fun RTCStatsReport.toEntries(): List<StatEntry> =
    statsMap.values.map { StatEntry(it.type, it.id, it.timestampUs, it.members) }

/** 只关心成功 / 失败的 SdpObserver 适配器。 */
internal abstract class SimpleSdpObserver(private val what: String) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = WscLog.e("Sdp", "$what create failed: $error")
    override fun onSetFailure(error: String?) = WscLog.e("Sdp", "$what set failed: $error")
}

internal fun PeerConnection.PeerConnectionState.toMediaState(): MediaState = when (this) {
    PeerConnection.PeerConnectionState.NEW -> MediaState.NEW
    PeerConnection.PeerConnectionState.CONNECTING -> MediaState.CONNECTING
    PeerConnection.PeerConnectionState.CONNECTED -> MediaState.CONNECTED
    PeerConnection.PeerConnectionState.DISCONNECTED -> MediaState.DISCONNECTED
    PeerConnection.PeerConnectionState.FAILED -> MediaState.FAILED
    PeerConnection.PeerConnectionState.CLOSED -> MediaState.CLOSED
}
