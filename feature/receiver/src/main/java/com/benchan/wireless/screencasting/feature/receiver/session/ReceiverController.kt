package com.benchan.wireless.screencasting.feature.receiver.session

import android.content.Context
import android.os.Build
import com.benchan.wireless.screencasting.core.common.Base64Url
import com.benchan.wireless.screencasting.core.common.Ids
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.data.report.SessionRecord
import com.benchan.wireless.screencasting.core.data.report.SessionReportStore
import com.benchan.wireless.screencasting.core.data.report.SessionStatsAccumulator
import com.benchan.wireless.screencasting.core.data.settings.ConnectionPolicy
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.discovery.mdns.NsdAdvertiser
import com.benchan.wireless.screencasting.core.discovery.mdns.WscTxt
import com.benchan.wireless.screencasting.core.net.MulticastLockHolder
import com.benchan.wireless.screencasting.core.net.NetworkMonitor
import com.benchan.wireless.screencasting.core.protocol.VersionNegotiation
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.core.protocol.message.Answer
import com.benchan.wireless.screencasting.core.protocol.message.Bye
import com.benchan.wireless.screencasting.core.protocol.message.ByeReason
import com.benchan.wireless.screencasting.core.protocol.message.ErrorCode
import com.benchan.wireless.screencasting.core.protocol.message.ErrorPayload
import com.benchan.wireless.screencasting.core.protocol.message.Hello
import com.benchan.wireless.screencasting.core.protocol.message.IceCandidate
import com.benchan.wireless.screencasting.core.protocol.message.MessageType
import com.benchan.wireless.screencasting.core.protocol.message.Offer
import com.benchan.wireless.screencasting.core.protocol.message.PairConfirm
import com.benchan.wireless.screencasting.core.protocol.message.PairMethod
import com.benchan.wireless.screencasting.core.protocol.message.PairOk
import com.benchan.wireless.screencasting.core.protocol.message.PairPin
import com.benchan.wireless.screencasting.core.protocol.message.PairQr
import com.benchan.wireless.screencasting.core.protocol.message.PairRequest
import com.benchan.wireless.screencasting.core.protocol.message.Role
import com.benchan.wireless.screencasting.core.protocol.message.WscCodec
import com.benchan.wireless.screencasting.core.protocol.message.WscMessage
import com.benchan.wireless.screencasting.core.protocol.message.WscPayload
import com.benchan.wireless.screencasting.core.protocol.pairing.CastCode
import com.benchan.wireless.screencasting.core.protocol.pairing.PairingGuard
import com.benchan.wireless.screencasting.core.protocol.pairing.QrNonceManager
import com.benchan.wireless.screencasting.core.protocol.pairing.QrPayload
import com.benchan.wireless.screencasting.core.protocol.pairing.QrProof
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGradeTracker
import com.benchan.wireless.screencasting.core.protocol.session.SessionEvent
import com.benchan.wireless.screencasting.core.protocol.session.SessionState
import com.benchan.wireless.screencasting.core.protocol.session.SessionStateMachine
import com.benchan.wireless.screencasting.core.rtc.MediaState
import com.benchan.wireless.screencasting.core.rtc.RtcEngine
import com.benchan.wireless.screencasting.core.rtc.receiver.ReceiverRtcSession
import com.benchan.wireless.screencasting.core.rtc.stats.LatencyEstimator
import com.benchan.wireless.screencasting.core.rtc.stats.ReceiverStatsSnapshot
import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import com.benchan.wireless.screencasting.core.security.identity.IdentityStore
import com.benchan.wireless.screencasting.core.security.pairing.ReceiverPairing
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import com.benchan.wireless.screencasting.core.signaling.SignalingConnection
import com.benchan.wireless.screencasting.core.signaling.SignalingEvent
import com.benchan.wireless.screencasting.core.signaling.WscServer
import com.benchan.wireless.screencasting.feature.receiver.service.ReceiverService
import com.benchan.wireless.screencasting.protocol.airplay.api.AirPlayReceiver
import com.benchan.wireless.screencasting.protocol.dlna.DlnaConsent
import com.benchan.wireless.screencasting.protocol.dlna.DlnaManager
import com.benchan.wireless.screencasting.protocol.dlna.DlnaPlayback
import com.benchan.wireless.screencasting.protocol.dlna.WscAdvert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.VideoSink
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 接收端编排（PRD 6.2 / 8.2 / 8.4 / 第 10 节；技术方案 3.5、4.1~4.3）：
 * 待机监听（WSS + mDNS + 投屏码 + 二维码）→ 认证（可信直连 / 允许 + PIN / 投屏码 / 二维码）→ 应答 WebRTC → 播放 →
 * 断线保留最后一帧等待恢复（30 秒）→ 结束写本机报告。
 *
 * 待机监听只在 App 前台时开启（R-05），由界面生命周期 [startListening] / [stopListening] 引用计数控制；
 * 播放中由 mediaPlayback 前台服务保活。
 */
@Singleton
class ReceiverController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityStore: IdentityStore,
    private val trusted: TrustedDeviceStore,
    private val settings: SettingsRepository,
    private val reports: SessionReportStore,
    private val rtcEngine: RtcEngine,
    private val network: NetworkMonitor,
    private val advertiser: NsdAdvertiser,
    private val multicastLock: MulticastLockHolder,
    private val consent: ConsentBroker,
    private val airPlay: Optional<AirPlayReceiver>,
    private val dlna: DlnaManager,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _standby = MutableStateFlow(
        StandbyInfo(settings.defaultDeviceName, null, null, null, null, false, false, airPlay.isPresent, false),
    )
    val standby: StateFlow<StandbyInfo> = _standby.asStateFlow()

    private val _session = MutableStateFlow<MirrorSession?>(null)
    val session: StateFlow<MirrorSession?> = _session.asStateFlow()

    val confirmRequest: StateFlow<ConfirmRequest?> = consent.current

    /** DLNA 推送播放（F09）。 */
    val dlnaPlayback: StateFlow<DlnaPlayback?> = dlna.playback
    val dlnaPlayer get() = dlna.player.exoPlayer
    private val dlnaApproved = HashMap<String, Long>()

    init {
        dlna.consent = DlnaConsent { remote, ua, title -> approveDlna(remote, ua, title) }
        scope.launch {
            dlna.playback.collect { p ->
                if (p != null) ReceiverService.start(context, p.title ?: p.controlPoint) else if (active == null) ReceiverService.stop(context)
            }
        }
    }

    /**
     * DLNA 推送确认（PRD 8.5：陌生设备需确认默认开启；确认后记住该 IP 24 小时，技术方案 3.7）。
     * 已有镜像会话时按 R-09 弹出“请求替换当前投屏”。
     */
    private suspend fun approveDlna(remote: String, userAgent: String?, title: String?): Boolean {
        val s = settings.current()
        if (!s.dlnaEnabled) return false
        val replacing = withContext(dispatcher) { active != null }
        val now = System.currentTimeMillis()
        val known = dlnaApproved[remote]?.let { now - it < DLNA_REMEMBER_MS } == true
        val needConfirm = replacing || (s.dlnaConfirmUnknown && !known)
        if (needConfirm) {
            val name = userAgent?.substringBefore('/')?.substringBefore(' ')?.takeIf { it.isNotBlank() && it.length <= 24 } ?: remote
            val r = consent.request(name, remote, CastProtocol.DLNA, pin = null, firstTime = !known, replacing = replacing)
            if (r != ConsentResult.ALLOWED) return false
        }
        dlnaApproved[remote] = now
        withContext(dispatcher) { active?.let { endActive(it, ByeReason.REPLACED, notice = null) } }
        WscLog.i(TAG, "DLNA push accepted from $remote (${title?.length ?: 0} chars title)")
        return true
    }

    fun stopDlna() = dlna.stopPlayback()

    /** 兼容 Apple 设备（非官方）的运行状态；构建开关关闭时恒为 Stopped。 */
    val appleState: StateFlow<com.benchan.wireless.screencasting.protocol.airplay.api.AppleCompatState> =
        if (airPlay.isPresent) airPlay.get().state else MutableStateFlow(com.benchan.wireless.screencasting.protocol.airplay.api.AppleCompatState.Stopped)

    fun setAppleSurface(surface: android.view.Surface?) = airPlay.ifPresent { it.setVideoSurface(surface) }

    private var listeners = 0
    private var listenJob: Job? = null
    private var server: WscServer? = null
    private var identity: DeviceIdentity? = null
    private val guard = PairingGuard()
    private val qrNonce = QrNonceManager { Ids.randomBytes(QrPayload.NONCE_BYTES) }
    private var castPin: String = Ids.randomDigits(CastCode.PIN_LENGTH)
    private var active: Active? = null
    private val renderers = mutableSetOf<VideoSink>()

    /** 界面进入前台（待机页 / 播放页）时调用。 */
    fun startListening() = scope.launch {
        listeners++
        if (listenJob?.isActive == true) return@launch
        listenJob = scope.launch { listen() }
    }

    fun stopListening() = scope.launch {
        listeners = (listeners - 1).coerceAtLeast(0)
        if (listeners == 0 && active == null) shutdownListener()
    }

    private suspend fun listen() {
        val id = withContext(Dispatchers.IO) { identityStore.loadOrCreate() }
        identity = id
        multicastLock.acquire(LOCK_OWNER)
        val srv = WscServer(id) { conn -> scope.launch { Inbound(conn).run() } }
        val port = runCatching { withContext(Dispatchers.IO) { srv.start() } }.getOrElse {
            WscLog.e(TAG, "WSC server failed to start", it)
            _standby.update { s -> s.copy(listening = false) }
            return
        }
        server = srv
        // 网络、设备名、Apple 兼容开关变化时刷新注册与待机页
        combine(network.lan, settings.settings) { lan, s -> lan to s }
            .distinctUntilChanged()
            .collectLatest { (lan, s) ->
                if (lan != null) {
                    advertiser.register(
                        s.deviceName, WscProtocol.SERVICE_TYPE, port,
                        WscTxt(WscProtocol.VERSION, id.deviceId, s.deviceName, listOf(WscProtocol.Caps.H264, WscProtocol.Caps.OPUS), id.fingerprint.txtPrefix).toAttributes(),
                    )
                } else {
                    advertiser.unregister()
                }
                airPlay.ifPresent { ap -> if (s.appleCompatEnabled) ap.start(s.deviceName, s.appleCompatPin) else ap.stop() }
                if (lan != null && s.dlnaEnabled) {
                    dlna.start(s.deviceName, WscAdvert(port, id.deviceId, id.fingerprint.txtPrefix, s.deviceName))
                } else {
                    dlna.stop()
                }
                // 二维码随机数 60 秒轮换：每秒检查一次
                while (kotlin.coroutines.coroutineContext.isActive) {
                    refreshStandby(lan?.address?.address, lan?.prefixLength ?: 0, lan?.ip, port, s.deviceName, s.appleCompatEnabled, id)
                    delay(1_000)
                }
            }
    }

    private fun refreshStandby(ipBytes: ByteArray?, prefix: Int, ip: String?, port: Int, name: String, apple: Boolean, id: DeviceIdentity) {
        val code = if (ipBytes != null && CastCode.supportsNetwork(prefix)) runCatching { CastCode.of(ipBytes, castPin) }.getOrNull() else null
        val qr = ip?.let { QrPayload(it, port, id.fingerprint.bytes, qrNonce.current(), name = name).toUri() }
        _standby.update {
            it.copy(
                deviceName = name, ip = ip, port = port, castCode = code, qrContent = qr,
                listening = true, lanAvailable = ip != null, appleCompatAvailable = airPlay.isPresent, appleCompatEnabled = apple,
            )
        }
    }

    private fun shutdownListener() {
        listenJob?.cancel()
        listenJob = null
        server?.stop()
        server = null
        advertiser.unregister()
        airPlay.ifPresent { it.stop() }
        scope.launch { dlna.stop() }
        multicastLock.release(LOCK_OWNER)
        _standby.update { it.copy(listening = false) }
        WscLog.i(TAG, "listener stopped")
    }

    fun clearNotice() {
        _standby.update { it.copy(notice = null) }
    }

    fun respondToRequest(requestId: Long, allowed: Boolean) = consent.respond(requestId, allowed)

    /** 播放页挂载 SurfaceViewRenderer。 */
    fun attachRenderer(sink: VideoSink) = scope.launch {
        renderers += sink
        active?.rtc?.attachRenderer(sink)
    }

    fun detachRenderer(sink: VideoSink) = scope.launch {
        renderers -= sink
        active?.rtc?.detachRenderer(sink)
    }

    /** 连按两次返回断开（PRD 6.2）。 */
    fun disconnect() = scope.launch {
        active?.let { endActive(it, ByeReason.RECEIVER_EXIT, notice = null) }
    }

    // ————————————————————————————— 单条信令连接 —————————————————————————————

    private inner class Inbound(val conn: SignalingConnection) {
        var sm = SessionStateMachine(Role.RECEIVER)
        var sid: String? = null
        var peerId: String = ""
        var peerName: String = ""
        val peerFp: Fingerprint = conn.peerFingerprint
        val ip: String = conn.remoteAddress
        var method: PairMethod? = null
        var pin4: String? = null
        var pairing: ReceiverPairing? = null
        var proofOk = false
        var proofMac: String? = null
        var allowed = false
        var consentJob: Job? = null
        var consentRequestId: Long? = null
        var replacing = false
        var helloDone = false

        suspend fun run() {
            sm.onEvent(SessionEvent.TransportOpened)
            val helloTimeout = scope.launch {
                delay(HELLO_TIMEOUT_MS)
                if (!helloDone) conn.close("hello timeout")
            }
            conn.events.collect { e ->
                when (e) {
                    is SignalingEvent.Message -> onMessage(e.message)
                    is SignalingEvent.Malformed -> {
                        send(ErrorPayload(ErrorCode.BAD_MESSAGE))
                        conn.close("malformed")
                    }
                    is SignalingEvent.Closed -> onClosed()
                }
            }
            helloTimeout.cancel()
        }

        fun send(payload: WscPayload) {
            val type = WscCodec.typeOf(payload)
            if (type != MessageType.ERROR && type != MessageType.BYE) {
                val t = sm.onEvent(SessionEvent.Sent(type))
                if (!t.accepted) {
                    WscLog.w(TAG, "refuse to send $type in ${t.from}")
                    return
                }
            }
            conn.send(payload, sid)
        }

        private suspend fun onMessage(msg: WscMessage) {
            val p = msg.payload
            val wasTrusted = (p is Hello) && trusted.isTrusted(peerFp)
            val confirm = (p is Hello) && confirmRequired()
            val t = sm.onEvent(
                SessionEvent.Received(msg.type, trusted = wasTrusted, confirmRequired = confirm, errorCode = (p as? ErrorPayload)?.code),
            )
            if (!t.accepted && !(p is Hello && p.resume)) {
                WscLog.w(TAG, "reject ${msg.type} in ${t.from} from $ip")
                send(ErrorPayload(t.rejected ?: ErrorCode.BAD_MESSAGE))
                conn.close("protocol violation")
                return
            }
            when (p) {
                is Hello -> onHello(msg, p, wasTrusted, confirm)
                is PairRequest -> onPairRequest(p)
                is PairPin -> onPairPin(p)
                is PairConfirm -> onPairConfirm(p)
                is PairQr -> onPairQr(p)
                is Offer -> onOffer(p)
                is IceCandidate -> activeFor()?.rtc?.onRemoteIce(p.candidate, p.sdpMid, p.sdpMLineIndex)
                is Bye -> onBye(p)
                is ErrorPayload -> conn.close("peer error ${p.code}")
                else -> Unit
            }
        }

        private suspend fun confirmRequired(): Boolean =
            settings.current().connectionPolicy == ConnectionPolicy.ALWAYS_CONFIRM || active != null || dlna.playback.value != null

        private suspend fun onHello(msg: WscMessage, h: Hello, wasTrusted: Boolean, confirm: Boolean) {
            helloDone = true
            val me = identity ?: return
            if (VersionNegotiation.negotiate(WscProtocol.VERSION, h.ver) == null) {
                send(ErrorPayload(ErrorCode.VERSION_UNSUPPORTED, min = WscProtocol.MIN_COMPATIBLE_VERSION, max = WscProtocol.VERSION))
                conn.close("version")
                return
            }
            sid = msg.sessionId ?: Ids.newSessionId()
            peerId = h.deviceId
            peerName = h.name.ifBlank { h.model.ifBlank { ip } }
            val name = settings.current().deviceName
            if (h.resume) {
                // 断线恢复（技术方案 4.3）：同一 sessionId + 同一证书指纹
                val a = active
                if (a != null && a.sid == sid && a.peerFp == peerFp) {
                    sm = SessionStateMachine(Role.RECEIVER, SessionState.AUTHENTICATED)
                    a.inbound = this
                    a.signalingLostAt = 0
                    a.reconnectTimer?.cancel()
                    conn.send(Hello(WscProtocol.VERSION, me.deviceId, name, Build.MODEL.orEmpty(), "receiver", resumeOk = true), sid)
                    WscLog.i(TAG, "session $sid resumed from $ip")
                } else {
                    conn.send(Hello(WscProtocol.VERSION, me.deviceId, name, Build.MODEL.orEmpty(), "receiver", resumeOk = false), sid)
                    conn.close("resume refused")
                }
                return
            }
            replacing = active != null || dlna.playback.value != null
            send(
                Hello(
                    WscProtocol.VERSION, me.deviceId, name, Build.MODEL.orEmpty(), "receiver",
                    caps = listOf(WscProtocol.Caps.H264, WscProtocol.Caps.OPUS),
                    trusted = wasTrusted, confirmRequired = confirm,
                ),
            )
            if (wasTrusted && !confirm) {
                WscLog.i(TAG, "trusted sender $peerName connected")
                onAuthenticated()
            }
        }

        private fun onPairRequest(r: PairRequest) {
            if (!guard.tryRequest(ip)) {
                send(ErrorPayload(ErrorCode.PAIR_LOCKED, attemptsLeft = 0))
                conn.close("rate limited")
                return
            }
            peerName = r.name.ifBlank { peerName }
            method = r.method
            when (r.method) {
                PairMethod.PIN -> {
                    val pin = Ids.randomDigits(4)
                    pin4 = pin
                    askConsent(pin = pin, firstTime = true)
                }
                PairMethod.CONFIRM -> {
                    if (!trusted.isTrusted(peerFp)) {
                        send(ErrorPayload(ErrorCode.UNAUTHENTICATED))
                        conn.close("not trusted")
                        return
                    }
                    proofOk = true
                    askConsent(pin = null, firstTime = false)
                }
                PairMethod.CODE, PairMethod.QR -> {
                    // 投屏码 / 二维码 = 在场证明，输对即配对（决策 #19）；但已有会话时仍需确认替换（R-09）
                    if (replacing) askConsent(pin = null, firstTime = true) else allowed = true
                }
            }
        }

        private fun askConsent(pin: String?, firstTime: Boolean) {
            consentJob = scope.launch {
                val result = consent.request(peerName, ip, CastProtocol.WSC, pin, firstTime, replacing)
                when (result) {
                    ConsentResult.ALLOWED -> {
                        allowed = true
                        if (proofOk) finishPairing()
                    }
                    ConsentResult.REJECTED -> {
                        send(ErrorPayload(ErrorCode.PAIR_REJECTED))
                        conn.close("rejected")
                    }
                    ConsentResult.TIMEOUT -> {
                        send(ErrorPayload(ErrorCode.PAIR_TIMEOUT))
                        conn.close("timeout")
                    }
                }
            }
        }

        private fun onPairPin(p: PairPin) {
            val me = identity ?: return
            val pin = when (method) {
                PairMethod.PIN -> pin4
                PairMethod.CODE -> castPin
                else -> null
            } ?: run {
                send(ErrorPayload(ErrorCode.BAD_MESSAGE))
                conn.close("unexpected PAIR_PIN")
                return
            }
            val pr = ReceiverPairing(sid!!, pin, peerId, me.deviceId, peerFp, me.fingerprint)
            runCatching { pr.onPeerPoint(p.point) }.onFailure {
                send(ErrorPayload(ErrorCode.BAD_MESSAGE))
                conn.close("bad point")
                return
            }
            pairing = pr
            send(PairPin(pr.publicPoint))
        }

        private fun onPairConfirm(c: PairConfirm) {
            val pr = pairing ?: return
            val mac = pr.verifySender(c.mac)
            pairing = null
            if (mac == null) {
                val left = guard.recordFailure(ip)
                WscLog.w(TAG, "PIN mismatch from $ip, $left attempts left")
                if (method == PairMethod.CODE) rotateCastPin()
                if (left == 0) {
                    send(ErrorPayload(ErrorCode.PAIR_LOCKED, attemptsLeft = 0))
                    consentJob?.cancel()
                    conn.close("locked")
                } else {
                    send(ErrorPayload(ErrorCode.PIN_MISMATCH, attemptsLeft = left))
                }
                return
            }
            guard.recordSuccess(ip)
            if (method == PairMethod.CODE) rotateCastPin() // 一次性 PIN
            proofOk = true
            proofMac = mac
            if (allowed) finishPairing()
        }

        private fun onPairQr(q: PairQr) {
            val me = identity ?: return
            val mac = runCatching { Base64Url.decode(q.mac) }.getOrNull()
            val ok = mac != null && QrProof.verify(qrNonce.current(), peerFp.bytes, me.fingerprint.bytes, sid!!, mac)
            if (!ok) {
                guard.recordFailure(ip)
                send(ErrorPayload(ErrorCode.QR_INVALID))
                conn.close("qr invalid")
                return
            }
            qrNonce.rotate() // 一次有效
            proofOk = true
            if (allowed) finishPairing()
        }

        private fun finishPairing() {
            if (method != PairMethod.CONFIRM) trusted.add(peerId, peerName, peerFp)
            send(PairOk(proofMac))
            onAuthenticated()
        }

        private fun onAuthenticated() {
            trusted.touch(peerFp, peerName)
            WscLog.i(TAG, "sender $peerName authenticated (${method ?: "trusted"})")
        }

        private fun activeFor(): Active? = active?.takeIf { it.inbound === this }

        private suspend fun onOffer(o: Offer) {
            var a = activeFor()
            if (a == null) {
                // 新会话：替换旧会话（已在确认弹窗中同意）
                active?.let { endActive(it, ByeReason.REPLACED, notice = null) }
                a = startActive(this)
            }
            a.rtc.onRemoteOffer(o.sdp, o.iceRestart)
        }

        private suspend fun onBye(b: Bye) {
            val a = activeFor()
            if (a != null) {
                val notice = when (b.reason) {
                    ByeReason.SCREEN_LOCKED -> "发送端已锁屏"
                    ByeReason.PROJECTION_STOPPED -> "$peerName 已停止投屏"
                    else -> "$peerName 已断开"
                }
                endActive(a, null, notice)
            }
            conn.close("bye")
        }

        private fun onClosed() {
            consentRequestId?.let(consent::dismiss)
            consentJob?.cancel()
            val a = activeFor() ?: return
            // 信令断开：保留 PeerConnection 与最后一帧，等待发送端 HELLO{resume}（最长 30 秒）
            a.signalingLostAt = System.currentTimeMillis()
            _session.update { it?.copy(reconnecting = true) }
            a.reconnectTimer?.cancel()
            a.reconnectTimer = scope.launch {
                delay(WscProtocol.RECONNECT_WINDOW_MS)
                if (active === a && a.inbound === this@Inbound) endActive(a, null, "$peerName 已断开")
            }
        }
    }

    // ————————————————————————————— 活动会话 —————————————————————————————

    private inner class Active(
        val sid: String,
        val peerFp: Fingerprint,
        val peerName: String,
        val peerAddress: String,
        var inbound: Inbound,
    ) {
        val startedAt = System.currentTimeMillis()
        var firstFrameAt = 0L
        var signalingLostAt = 0L
        var reconnectTimer: Job? = null
        val accumulator = SessionStatsAccumulator()
        val gradeTracker = LatencyGradeTracker()
        val history = ArrayDeque<Int>()
        lateinit var rtc: ReceiverRtcSession
    }

    private fun startActive(inbound: Inbound): Active {
        val a = Active(inbound.sid!!, inbound.peerFp, inbound.peerName, inbound.ip, inbound)
        // R-09：同时只接一路；新的镜像会话（已确认替换）结束正在进行的 DLNA 播放
        if (dlna.playback.value != null) dlna.stopPlayback()
        val rtc = ReceiverRtcSession(context, rtcEngine, rtcListener(a))
        a.rtc = rtc
        active = a
        rtc.start()
        renderers.forEach(rtc::attachRenderer)
        _session.value = MirrorSession(a.sid, a.peerName, a.peerAddress, a.startedAt)
        ReceiverService.start(context, a.peerName)
        WscLog.i(TAG, "mirror session ${a.sid} started from ${a.peerName}")
        return a
    }

    private fun rtcListener(a: Active) = object : ReceiverRtcSession.Listener {
        override fun onLocalAnswer(sdp: String) {
            scope.launch { a.inbound.send(Answer(sdp)) }
        }

        override fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
            scope.launch { a.inbound.send(IceCandidate(candidate, sdpMid, sdpMLineIndex)) }
        }

        override fun onMediaState(state: MediaState) {
            scope.launch {
                if (active !== a) return@launch
                when (state) {
                    MediaState.CONNECTED -> {
                        a.inbound.sm.onEvent(SessionEvent.MediaConnected)
                        if (a.signalingLostAt == 0L) _session.update { it?.copy(reconnecting = false) }
                        if (a.accumulator.disconnects > a.accumulator.autoRecovered) a.accumulator.onRecovered()
                    }
                    MediaState.DISCONNECTED, MediaState.FAILED -> {
                        a.accumulator.onDisconnected()
                        _session.update { it?.copy(reconnecting = true, reconnectAttempt = (it.reconnectAttempt) + 1) }
                    }
                    else -> Unit
                }
            }
        }

        override fun onFirstFrame() {
            scope.launch {
                if (a.firstFrameAt == 0L) a.firstFrameAt = System.currentTimeMillis()
                _session.update { it?.copy(firstFrame = true) }
            }
        }

        override fun onStats(stats: ReceiverStatsSnapshot, senderStats: DcMessage.SenderStats?) {
            scope.launch { onRtcStats(a, stats, senderStats) }
        }

        override fun onDataChannelMessage(message: DcMessage) {
            scope.launch {
                when (message) {
                    is DcMessage.AppSwitched -> _session.update { it?.copy(appVisible = message.visible) }
                    is DcMessage.ProtectedContent -> _session.update { it?.copy(protectedSuspected = message.suspected) }
                    is DcMessage.Audio -> _session.update { it?.copy(audio = message.state) }
                    else -> Unit
                }
            }
        }
    }

    private fun onRtcStats(a: Active, s: ReceiverStatsSnapshot, sender: DcMessage.SenderStats?) {
        if (active !== a) return
        val latency = sender?.latencyMs ?: LatencyEstimator.estimate(s.fps, sender?.encodeMs, s.rttMs, s.jitterBufferMs, s.decodeMs)
        latency?.let {
            a.accumulator.addLatency(it)
            a.history.addLast(it)
            while (a.history.size > 60) a.history.removeFirst()
        }
        a.accumulator.addBitrate(s.bitrateKbps)
        a.accumulator.framesDropped = s.framesDropped
        a.accumulator.codec = s.codec
        val grade = latency?.let { a.gradeTracker.update(it, System.currentTimeMillis()) }
        _session.update {
            it?.copy(
                stats = PlaybackStats(
                    width = s.width, height = s.height, fps = s.fps, bitrateKbps = s.bitrateKbps,
                    latencyMs = latency, grade = grade, dropPercent = s.dropPercent,
                    decoder = s.decoder, hardwareDecoder = s.hardwareDecoder, codec = s.codec,
                    latencyHistory = a.history.toList(),
                ),
            )
        }
    }

    private suspend fun endActive(a: Active, bye: ByeReason?, notice: String?) {
        if (active !== a) return
        active = null
        a.reconnectTimer?.cancel()
        if (bye != null) a.inbound.send(Bye(bye))
        a.inbound.conn.close("session end")
        renderers.forEach(a.rtc::detachRenderer)
        withContext(Dispatchers.IO) { a.rtc.close() }
        if (dlna.playback.value == null) ReceiverService.stop(context)
        _session.value = null
        _standby.update { it.copy(notice = notice) }
        writeReport(a, bye?.name ?: notice ?: "ended")
        WscLog.i(TAG, "mirror session ${a.sid} ended (${bye ?: notice})")
        if (listeners == 0) shutdownListener()
    }

    private fun writeReport(a: Active, reason: String) {
        val firstFrameMs = if (a.firstFrameAt > 0) a.firstFrameAt - a.startedAt else null
        runCatching {
            reports.add(
                SessionRecord(
                    sessionId = a.sid, protocol = CastProtocol.WSC, role = "RECEIVER", peerName = a.peerName,
                    startedAt = a.startedAt, endedAt = System.currentTimeMillis(),
                    firstFrameMs = firstFrameMs, success = firstFrameMs != null && firstFrameMs <= 60_000,
                    latencyP50Ms = a.accumulator.percentile(0.5), latencyP95Ms = a.accumulator.percentile(0.95),
                    latencyHistogram = a.accumulator.histogram.toList(), avgBitrateKbps = a.accumulator.avgBitrateKbps(),
                    framesDropped = a.accumulator.framesDropped, disconnects = a.accumulator.disconnects,
                    autoRecovered = a.accumulator.autoRecovered, endReason = reason, codec = a.accumulator.codec,
                    androidVersion = Build.VERSION.RELEASE.orEmpty(), deviceModel = Build.MODEL.orEmpty(),
                ),
            )
        }
    }

    private fun rotateCastPin() {
        castPin = Ids.randomDigits(CastCode.PIN_LENGTH)
    }

    private companion object {
        const val TAG = "ReceiverController"
        const val LOCK_OWNER = "receiver-standby"
        const val HELLO_TIMEOUT_MS = 8_000L
        const val DLNA_REMEMBER_MS = 24 * 60 * 60 * 1000L
    }
}
