package com.benchan.wireless.screencasting.feature.sender.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.benchan.wireless.screencasting.core.common.Hex
import com.benchan.wireless.screencasting.core.common.Ids
import com.benchan.wireless.screencasting.core.common.Base64Url
import com.benchan.wireless.screencasting.core.common.SessionEndGuard
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.devices.RecentDevice
import com.benchan.wireless.screencasting.core.data.devices.RecentDevicesStore
import com.benchan.wireless.screencasting.core.data.game.GameModeStore
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.data.report.GameReport
import com.benchan.wireless.screencasting.core.data.report.SessionRecord
import com.benchan.wireless.screencasting.core.data.report.SessionReportStore
import com.benchan.wireless.screencasting.core.data.report.SessionStatsAccumulator
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.protocol.VersionNegotiation
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
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
import com.benchan.wireless.screencasting.core.protocol.message.WscMessage
import com.benchan.wireless.screencasting.core.protocol.message.WscPayload
import com.benchan.wireless.screencasting.core.protocol.pairing.QrProof
import com.benchan.wireless.screencasting.core.protocol.quality.AutoQualityDecision
import com.benchan.wireless.screencasting.core.protocol.quality.AutoQualityPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.FpsCapReason
import com.benchan.wireless.screencasting.core.protocol.quality.GameHint
import com.benchan.wireless.screencasting.core.protocol.quality.GameHintQueue
import com.benchan.wireless.screencasting.core.protocol.quality.GameHintType
import com.benchan.wireless.screencasting.core.protocol.quality.GameModeMonitor
import com.benchan.wireless.screencasting.core.protocol.quality.GameSample
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGradeTracker
import com.benchan.wireless.screencasting.core.protocol.quality.NetworkSample
import com.benchan.wireless.screencasting.core.protocol.quality.QualityChange
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import com.benchan.wireless.screencasting.core.protocol.quality.QualitySwitchPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.SwitchTimeoutAction
import com.benchan.wireless.screencasting.core.protocol.session.ReconnectBackoff
import com.benchan.wireless.screencasting.core.protocol.session.SessionEvent
import com.benchan.wireless.screencasting.core.protocol.session.SessionStateMachine
import com.benchan.wireless.screencasting.core.rtc.MediaState
import com.benchan.wireless.screencasting.core.rtc.RtcEngine
import com.benchan.wireless.screencasting.core.rtc.sender.ProjectionStopReason
import com.benchan.wireless.screencasting.core.rtc.sender.SenderRtcSession
import com.benchan.wireless.screencasting.core.rtc.stats.SenderStatsSnapshot
import com.benchan.wireless.screencasting.core.security.identity.DeviceIdentity
import com.benchan.wireless.screencasting.core.security.identity.Fingerprint
import com.benchan.wireless.screencasting.core.security.identity.IdentityStore
import com.benchan.wireless.screencasting.core.security.pairing.SenderPairing
import com.benchan.wireless.screencasting.core.security.tls.ServerPin
import com.benchan.wireless.screencasting.core.security.trust.TrustedDeviceStore
import com.benchan.wireless.screencasting.core.signaling.SignalingConnectException
import com.benchan.wireless.screencasting.core.signaling.SignalingConnection
import com.benchan.wireless.screencasting.core.signaling.SignalingEvent
import com.benchan.wireless.screencasting.core.signaling.WscClient
import com.benchan.wireless.screencasting.feature.control.ControlLink
import com.benchan.wireless.screencasting.feature.control.SenderControlCoordinator
import com.benchan.wireless.screencasting.feature.sender.service.ScreenCastService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发送端会话编排（PRD 6.1 / 8.1 / 8.4 / 第 10 节；技术方案 2.3、4.1~4.3）：
 * 发现 → TLS 信令 → HELLO → 配对（PIN / 投屏码 / 二维码 / 仅确认）→ 录屏授权 → WebRTC → 断线重连 → 结束 + 本机报告。
 *
 * 所有状态变更都在单线程调度器 [dispatcher] 上进行，WebRTC / 信令回调先切回该调度器。
 */
@Singleton
class SenderController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identityStore: IdentityStore,
    private val trusted: TrustedDeviceStore,
    private val recent: RecentDevicesStore,
    private val settings: SettingsRepository,
    private val reports: SessionReportStore,
    private val rtcEngine: RtcEngine,
    private val control: SenderControlCoordinator,
    private val gameStore: GameModeStore,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SenderEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SenderEvent> = _events.asSharedFlow()

    private var identity: DeviceIdentity? = null
    private var connection: SignalingConnection? = null
    private var readerJob: Job? = null
    private var timeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var mediaWatchdog: Job? = null
    private var sm = SessionStateMachine(Role.SENDER)
    private var sessionId: String = Ids.newSessionId()
    private var target: CastTarget? = null
    private var receiverFp: Fingerprint? = null
    private var receiverId: String? = null
    private var receiverName: String? = null
    private var pairing: SenderPairing? = null
    private var pairMethod: PairMethod? = null
    private var rtc: SenderRtcSession? = null
    private var profile: QualityProfile = QualityProfile.DEFAULT
    private var autoAdjust = true
    private var audioState = AudioState.OFF
    private val autoPolicy = AutoQualityPolicy()
    private val gradeTracker = LatencyGradeTracker()
    private var accumulator = SessionStatsAccumulator()
    private var connectStartedAt = 0L
    private var captureGrantedAt = 0L
    private var firstMediaAt = 0L
    private var lastAutoDowngradeFrom: QualityProfile? = null
    private val endGuard = SessionEndGuard()
    private var sessionGen = 0L

    /** 首帧前（从未连通过）ICE 失败后已做的 ICE restart 次数（E3）。 */
    private var preMediaIceRestarts = 0

    /** 接收端 HELLO 里的能力（F18 据此决定能否会话中重建媒体连接）。 */
    private var receiverCaps: List<String> = emptyList()

    /** 进行中的档位切换（F18 媒体重建）；null = 没有。 */
    private var qualitySwitch: QualitySwitch? = null
    private var switchTimeout: Job? = null

    /** 断线重连期间选了需要重建的档位：编码参数已即时生效，媒体恢复后再重建（记录切换前的档位，用于失败回退）。 */
    private var pendingRebuildFrom: QualityProfile? = null

    /** F18 提示位（交互稿 G04）：每类每次投屏最多一次，按优先级一次一张。 */
    private val hints = GameHintQueue()

    /** F18 “达不到目标”判定（延迟 / 60 帧）。 */
    private val gameMonitor = GameModeMonitor()

    /** F18 游戏模式降为 30 帧的原因（null = 没降）。 */
    private var fpsCap: FpsCapReason? = null
    private val capFps: Int? get() = if (fpsCap != null && profile.lowLatency) CAPPED_FPS else null

    private var game = GameTracker()

    /** 当前音频是否和视频在不同的 stream（只在开局 / 重建时按档位决定；旧版接收端中途切游戏不变，PM 走查 P2-13）。 */
    private var audioSplit = false
    private var statsTicks = 0
    private var thermalListener: android.os.PowerManager.OnThermalStatusChangedListener? = null

    private data class QualitySwitch(val from: QualityProfile, val to: QualityProfile, val startedAt: Long, val rollback: Boolean)

    private val receiverSupportsRebuild: Boolean get() = WscProtocol.Caps.GAME in receiverCaps

    /** 用户选中一台设备（列表 / 扫码 / 投屏码 / 手动 IP / Tile）。 */
    fun connect(newTarget: CastTarget) = scope.launch {
        if (rtc != null) {
            WscLog.w(TAG, "connect ignored: session active")
            return@launch
        }
        resetSession()
        sessionGen = endGuard.begin()
        target = newTarget
        connectStartedAt = System.currentTimeMillis()
        _state.value = SenderState.Connecting(newTarget)
        val id = withContext(Dispatchers.IO) { identityStore.loadOrCreate() }
        identity = id
        val s = settings.current()
        profile = s.quality
        autoAdjust = s.autoAdjustQuality
        val pin = pinFor(newTarget)
        sm.onEvent(SessionEvent.TransportConnecting)
        val conn = try {
            WscClient(id).connect(newTarget.host, newTarget.port, pin)
        } catch (e: SignalingConnectException) {
            WscLog.w(TAG, "connect failed: ${e.message}")
            end(if (e.certificateMismatch) EndReason.CERTIFICATE_MISMATCH else EndReason.CONNECT_FAILED, sendBye = null, failure = ConnectFailure.UNREACHABLE)
            return@launch
        } catch (e: Exception) {
            WscLog.w(TAG, "connect failed", e)
            end(EndReason.CONNECT_FAILED, sendBye = null, failure = ConnectFailure.UNREACHABLE)
            return@launch
        }
        attach(conn)
        sm.onEvent(SessionEvent.TransportOpened)
        // 告诉接收端本机是否仍信任它：本机移除了信任时接收端会改走配对，而不是直接放行（PROTOCOL.md 2.1）
        send(helloPayload(id, s.deviceName, resume = false, trustsReceiver = isReceiverTrustedLocally()))
        armTimeout(HELLO_TIMEOUT_MS) { end(EndReason.CONNECT_FAILED, sendBye = null, failure = ConnectFailure.HANDSHAKE_TIMEOUT) }
    }

    private fun pinFor(t: CastTarget): ServerPin {
        t.fingerprintHex?.let { return ServerPin.Full(Fingerprint.fromHex(it)) }
        t.deviceId?.let { id -> trusted.findById(id)?.let { return ServerPin.Full(it.fingerprint) } }
        t.fingerprintPrefix?.let { return ServerPin.Prefix(it) }
        // 投屏码 / 手动 IP 首次配对：不预先钉扎，由 SPAKE2 把双方看到的指纹绑进 AAD（技术方案 3.5.4）
        return ServerPin.None
    }

    private fun helloPayload(id: DeviceIdentity, name: String, resume: Boolean, trustsReceiver: Boolean? = null) = Hello(
        ver = WscProtocol.VERSION,
        deviceId = id.deviceId,
        name = name,
        model = Build.MODEL.orEmpty(),
        role = "sender",
        // ctl：本构建支持被反向控制（是否可用由 DataChannel 上的 CTL_STATE 告知，PROTOCOL.md 第 6 节）
        caps = listOf(WscProtocol.Caps.H264, WscProtocol.Caps.OPUS, WscProtocol.Caps.CTL),
        resume = resume,
        trusted = if (resume) null else trustsReceiver,
    )

    private fun attach(conn: SignalingConnection) {
        connection = conn
        receiverFp = conn.peerFingerprint
        readerJob?.cancel()
        readerJob = scope.launch {
            conn.events.collect { e ->
                if (conn !== connection) return@collect
                when (e) {
                    is SignalingEvent.Message -> onMessage(e.message)
                    is SignalingEvent.Malformed -> {
                        send(ErrorPayload(ErrorCode.BAD_MESSAGE))
                        end(EndReason.INTERNAL_ERROR, sendBye = null)
                    }
                    is SignalingEvent.Closed -> onSignalingClosed()
                }
            }
        }
    }

    private suspend fun onMessage(msg: WscMessage) {
        val p = msg.payload
        val t = sm.onEvent(
            SessionEvent.Received(
                msg.type,
                trusted = (p as? Hello)?.trusted == true && isReceiverTrustedLocally(),
                confirmRequired = (p as? Hello)?.confirmRequired == true,
                resumeOk = (p as? Hello)?.resumeOk == true,
                errorCode = (p as? ErrorPayload)?.code,
            ),
        )
        if (!t.accepted) {
            WscLog.w(TAG, "rejected ${msg.type} in ${t.from}")
            send(ErrorPayload(t.rejected ?: ErrorCode.BAD_MESSAGE))
            end(EndReason.INTERNAL_ERROR, sendBye = null)
            return
        }
        when (p) {
            is Hello -> onHello(p)
            is PairPin -> onPeerPoint(p)
            is PairOk -> onPairOk(p)
            is Answer -> rtc?.onRemoteAnswer(p.sdp)
            is IceCandidate -> rtc?.onRemoteIce(p.candidate, p.sdpMid, p.sdpMLineIndex)
            is Bye -> {
                WscLog.i(TAG, "receiver said BYE ${p.reason}")
                end(if (p.reason == ByeReason.NO_DISPLAY) EndReason.RECEIVER_NO_DISPLAY else EndReason.REMOTE_CLOSED, sendBye = null)
            }
            is ErrorPayload -> onError(p)
            else -> Unit
        }
    }

    private fun isReceiverTrustedLocally(): Boolean = receiverFp?.let(trusted::isTrusted) == true

    private suspend fun onHello(h: Hello) {
        cancelTimeout()
        if (VersionNegotiation.negotiate(WscProtocol.VERSION, h.ver) == null) {
            send(ErrorPayload(ErrorCode.VERSION_UNSUPPORTED, min = WscProtocol.MIN_COMPATIBLE_VERSION, max = WscProtocol.VERSION))
            end(EndReason.VERSION_UNSUPPORTED, sendBye = null)
            return
        }
        // 重连恢复：HELLO{resumeOk}
        if (reconnectJob != null) {
            if (h.resumeOk == true) {
                WscLog.i(TAG, "resume accepted, restarting ICE")
                reconnectJob?.cancel()
                reconnectJob = null
                // 同一会话 30 秒内重连成功：反向控制自动续上，不再确认（决策 #16 细化）
                control.setPaused(ControlReason.RECONNECTING, false)
                if (qualitySwitch != null) {
                    // 档位切换途中信令断开：接收端可能还停在旧连接或只收到一半，直接按当前档位再重建一次（任何状态下都安全）
                    clearSwitch()
                    audioSplit = profile.lowLatency
                    rtc?.rebuildMedia(profile, capFps)
                } else {
                    rtc?.restartIce()
                }
                watchMedia()
            } else {
                end(EndReason.RECONNECT_FAILED, sendBye = null)
            }
            return
        }
        receiverId = h.deviceId
        receiverName = h.name
        receiverCaps = h.caps
        val t = target ?: return
        val named = t.copy(name = h.name.ifBlank { t.name }, deviceId = h.deviceId)
        target = named
        val locallyTrusted = isReceiverTrustedLocally()
        when {
            h.trusted == true && locallyTrusted && h.confirmRequired != true -> onAuthenticated()
            h.trusted == true && locallyTrusted -> {
                pairMethod = PairMethod.CONFIRM
                send(PairRequest(deviceName(), Build.MODEL.orEmpty(), PairMethod.CONFIRM))
                val deadline = System.currentTimeMillis() + WscProtocol.PAIR_CONFIRM_TIMEOUT_MS
                _state.value = SenderState.AwaitingTvConfirm(named, deadline)
                armTimeout(WscProtocol.PAIR_CONFIRM_TIMEOUT_MS + 2_000) { end(EndReason.CONFIRM_TIMEOUT, ByeReason.USER_STOP) }
            }
            t.qrNonce != null -> {
                pairMethod = PairMethod.QR
                send(PairRequest(deviceName(), Build.MODEL.orEmpty(), PairMethod.QR))
                val mac = QrProof.compute(t.qrNonce, identity!!.fingerprint.bytes, receiverFp!!.bytes, sessionId)
                send(PairQr(Base64Url.encode(mac)))
                armTimeout(PAIR_REPLY_TIMEOUT_MS) { end(EndReason.CONNECT_FAILED, ByeReason.USER_STOP, failure = ConnectFailure.QR_PAIR_TIMEOUT) }
            }
            t.castCodePin != null -> {
                pairMethod = PairMethod.CODE
                send(PairRequest(deviceName(), Build.MODEL.orEmpty(), PairMethod.CODE))
                startSpake(t.castCodePin)
                armTimeout(PAIR_REPLY_TIMEOUT_MS) { end(EndReason.CONNECT_FAILED, ByeReason.USER_STOP, failure = ConnectFailure.CODE_PAIR_TIMEOUT) }
            }
            else -> {
                pairMethod = PairMethod.PIN
                send(PairRequest(deviceName(), Build.MODEL.orEmpty(), PairMethod.PIN))
                val deadline = System.currentTimeMillis() + WscProtocol.PAIR_CONFIRM_TIMEOUT_MS
                _state.value = SenderState.AwaitingPin(named, deadline)
                armTimeout(WscProtocol.PAIR_CONFIRM_TIMEOUT_MS + 2_000) { end(EndReason.PIN_TIMEOUT, ByeReason.USER_STOP) }
            }
        }
    }

    private suspend fun deviceName(): String = settings.current().deviceName

    /** 用户在 05A 输满 4 位 PIN（自动提交）。 */
    fun submitPin(pin: String) = scope.launch {
        val s = _state.value as? SenderState.AwaitingPin ?: return@launch
        _state.value = s.copy(verifying = true, error = false)
        startSpake(pin)
    }

    private fun startSpake(pin: String) {
        val id = identity ?: return
        val rid = receiverId ?: return
        val fp = receiverFp ?: return
        val p = SenderPairing(sessionId, pin, id.deviceId, rid, id.fingerprint, fp)
        pairing = p
        send(PairPin(p.publicPoint))
    }

    private fun onPeerPoint(p: PairPin) {
        val pr = pairing ?: return
        val mac = runCatching { pr.onPeerPoint(p.point) }.getOrElse {
            WscLog.w(TAG, "invalid receiver point")
            send(ErrorPayload(ErrorCode.BAD_MESSAGE))
            scope.launch { end(EndReason.INTERNAL_ERROR, sendBye = null) }
            return
        }
        send(PairConfirm(mac))
    }

    private suspend fun onPairOk(ok: PairOk) {
        cancelTimeout()
        val method = pairMethod
        if (method == PairMethod.PIN || method == PairMethod.CODE) {
            if (pairing?.verifyReceiver(ok.mac) != true) {
                // 接收端的确认 MAC 不对：可能有中间人，绝不信任
                WscLog.w(TAG, "receiver confirmation MAC invalid")
                send(ErrorPayload(ErrorCode.UNAUTHENTICATED))
                end(EndReason.CERTIFICATE_MISMATCH, sendBye = null)
                return
            }
        }
        if (method != PairMethod.CONFIRM) {
            val rid = receiverId
            val fp = receiverFp
            if (rid != null && fp != null) trusted.add(rid, receiverName ?: target?.name.orEmpty(), fp)
        }
        onAuthenticated()
    }

    private fun onAuthenticated() {
        val t = target ?: return
        WscLog.i(TAG, "authenticated with ${t.name} via ${pairMethod ?: "trusted"}")
        receiverFp?.let { trusted.touch(it, receiverName) }
        val rid = receiverId
        val fp = receiverFp
        if (rid != null && fp != null) {
            recent.record(RecentDevice(rid, receiverName ?: t.name, t.host, t.port, fp.hex, System.currentTimeMillis()))
        }
        _state.value = SenderState.ReadyToCapture(t.copy(fingerprintHex = fp?.hex))
    }

    private suspend fun onError(e: ErrorPayload) {
        cancelTimeout()
        when (e.code) {
            ErrorCode.PIN_MISMATCH -> {
                pairing = null
                val s = _state.value
                if (pairMethod == PairMethod.CODE) {
                    end(EndReason.CAST_CODE_WRONG, sendBye = null)
                } else if (s is SenderState.AwaitingPin) {
                    _state.value = s.copy(error = true, attemptsLeft = e.attemptsLeft, verifying = false)
                    _events.tryEmit(SenderEvent.PinIncorrect(e.attemptsLeft))
                    armTimeout((s.deadlineAt - System.currentTimeMillis()).coerceAtLeast(1_000) + 2_000) {
                        end(EndReason.PIN_TIMEOUT, ByeReason.USER_STOP)
                    }
                }
            }
            ErrorCode.PAIR_REJECTED -> end(EndReason.REJECTED, sendBye = null)
            ErrorCode.PAIR_TIMEOUT -> end(if (pairMethod == PairMethod.PIN) EndReason.PIN_TIMEOUT else EndReason.CONFIRM_TIMEOUT, sendBye = null)
            ErrorCode.PAIR_LOCKED -> end(EndReason.PAIR_LOCKED, sendBye = null)
            ErrorCode.VERSION_UNSUPPORTED -> end(EndReason.VERSION_UNSUPPORTED, sendBye = null)
            ErrorCode.BUSY -> end(EndReason.BUSY, sendBye = null)
            ErrorCode.QR_INVALID -> end(EndReason.QR_EXPIRED, sendBye = null)
            ErrorCode.RESUME_FAILED -> end(EndReason.RECONNECT_FAILED, sendBye = null)
            // 错误码只写日志，界面显示可读文案（EndReason.snackbarText）
            ErrorCode.UNAUTHENTICATED -> end(EndReason.AUTH_FAILED, sendBye = null)
            else -> {
                WscLog.w(TAG, "receiver error ${e.code}")
                end(EndReason.INTERNAL_ERROR, sendBye = null)
            }
        }
    }

    /** 录屏授权被拒（PRD 10）。 */
    fun onCaptureDenied() = scope.launch {
        end(EndReason.CAPTURE_DENIED, ByeReason.USER_STOP)
    }

    /** 由前台服务在 startForeground(mediaProjection) 之后调用（Android 14+ 顺序，PRD S-01）。 */
    fun onProjectionGranted(serviceContext: Context, data: Intent) = scope.launch {
        val t = (_state.value as? SenderState.ReadyToCapture)?.target ?: (_state.value as? SenderState.Starting)?.target
        if (t == null || connection?.isOpen != true) {
            WscLog.w(TAG, "projection granted but no authenticated session")
            stopService()
            if (t != null) end(EndReason.CONNECT_FAILED, sendBye = null, failure = ConnectFailure.LOST)
            return@launch
        }
        captureGrantedAt = System.currentTimeMillis()
        _state.value = SenderState.Starting(t)
        val s = settings.current()
        val hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val audio = s.audioEnabled && hasMicPermission
        audioState = when {
            !s.audioEnabled -> AudioState.OFF
            !hasMicPermission -> AudioState.NO_PERMISSION
            else -> AudioState.ON
        }
        val session = SenderRtcSession(serviceContext, rtcEngine, data, profile, audio, rtcListener(sessionGen))
        rtc = session
        // 反向控制只存在于 WSC 镜像会话（PRD F23）：授权仍需接收端请求 + 本机确认
        val peer = receiverName ?: t.name
        control.attach(object : ControlLink {
            override val peerName: String = peer
            override fun sendDc(message: DcMessage) = session.sendDc(message)
            override fun sendInput(message: InputMessage) = session.sendInput(message)
        })
        session.start()
        // F18：这台接收端以前跟不上 60 帧 → 开始就用 30 帧（交互稿 15 层级 ⑤）
        audioSplit = profile.lowLatency
        if (profile.lowLatency && gameStore.receiver(receiverId)?.prefer30Fps == true) {
            fpsCap = FpsCapReason.REMEMBERED
            // 记住的 30 帧开局即生效，不算“降帧”：不让电视显示“已降为 30 帧”
            session.setFrameRateCap(CAPPED_FPS, announce = false)
        }
        registerThermalListener()
        // 首帧超时：60 秒还没有连上媒体就判定失败（交互稿 10 统计口径）
        armTimeout(FIRST_MEDIA_TIMEOUT_MS) { end(EndReason.CONNECT_FAILED, ByeReason.USER_STOP, failure = ConnectFailure.FIRST_FRAME_TIMEOUT) }
    }

    /**
     * 每个会话一个监听器，回调按会话代号过滤：会话结束后迟到的回调（例如用户点停止后系统再报“录屏已停止”）
     * 不能再改状态、覆盖结束页（E4）。
     */
    private fun rtcListener(gen: Long) = object : SenderRtcSession.Listener {
        private fun onCurrent(block: suspend () -> Unit) {
            scope.launch { if (endGuard.isCurrent(gen)) block() }
        }

        override fun onLocalOffer(sdp: String, iceRestart: Boolean, rebuild: Boolean) {
            // profile：接收端据此选择播放缓冲 / 解码器（F18，PROTOCOL.md 4.3）；旧版接收端忽略未知字段
            onCurrent { send(Offer(sdp, iceRestart, profile = profile.name, rebuild = rebuild)) }
        }

        override fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
            onCurrent { send(IceCandidate(candidate, sdpMid, sdpMLineIndex)) }
        }

        override fun onMediaState(state: MediaState) {
            onCurrent { onMedia(state) }
        }

        override fun onFirstConnected() {
            onCurrent { if (firstMediaAt == 0L) firstMediaAt = System.currentTimeMillis() }
        }

        override fun onProjectionStopped(reason: ProjectionStopReason) {
            onCurrent {
                if (reason == ProjectionStopReason.SCREEN_LOCKED) {
                    end(EndReason.SCREEN_LOCKED, ByeReason.SCREEN_LOCKED)
                } else {
                    end(EndReason.PROJECTION_STOPPED, ByeReason.PROJECTION_STOPPED)
                }
            }
        }

        override fun onStats(stats: SenderStatsSnapshot, latencyMs: Int?, receiver: DcMessage.ReceiverStats?) {
            onCurrent { onRtcStats(stats, latencyMs, receiver) }
        }

        override fun onRebuildUnavailable(profile: QualityProfile) {
            onCurrent { onRebuildUnavailable(profile) }
        }

        override fun onRebuilt(pauseMs: Long) {
            onCurrent { onRebuiltConfirmed(pauseMs) }
        }

        override fun onQualityChanged(profile: QualityProfile, byRemote: Boolean) {
            onCurrent {
                this@SenderController.profile = profile
                updateCasting { it.copy(profile = profile) }
            }
        }

        override fun onAudioState(state: AudioState) {
            onCurrent {
                if (audioState == AudioState.NO_PERMISSION || audioState == AudioState.OFF) return@onCurrent
                audioState = state
                updateCasting { it.copy(audio = state) }
                rtc?.sendDc(com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage.Audio(state))
            }
        }

        override fun onCapturedAppVisible(visible: Boolean) {
            onCurrent {
                updateCasting { it.copy(appVisible = visible) }
                // 被共享的应用不在前台：接收端看不到画面，暂停反向控制（避免盲操作其他应用）
                control.setPaused(ControlReason.APP_HIDDEN, !visible)
            }
        }

        override fun onProtectedContent(suspected: Boolean) {
            onCurrent {
                updateCasting { it.copy(protectedSuspected = suspected) }
                control.setPaused(ControlReason.PROTECTED, suspected)
            }
        }

        // —— 反向控制（PRD F23）：直接转交，协调器在会话结束（detach）后忽略迟到的回调 ——

        override fun onDataChannelOpen() {
            if (endGuard.isCurrent(gen)) control.onDataChannelOpen()
        }

        override fun onDataChannelMessage(message: DcMessage) {
            if (endGuard.isCurrent(gen)) control.onDataChannelMessage(message)
        }

        override fun onInputMessage(message: InputMessage) {
            if (endGuard.isCurrent(gen)) control.onInput(message)
        }

        override fun onCaptureGeometry(captureWidth: Int, captureHeight: Int, contentWidth: Int, contentHeight: Int, screenWidth: Int, screenHeight: Int) {
            if (endGuard.isCurrent(gen)) control.onGeometry(captureWidth, captureHeight, contentWidth, contentHeight, screenWidth, screenHeight)
        }
    }

    private fun onMedia(state: MediaState) {
        val t = target ?: return
        when (state) {
            MediaState.CONNECTED -> {
                sm.onEvent(SessionEvent.MediaConnected)
                mediaWatchdog?.cancel()
                reconnectShowJob?.cancel()
                reconnectShowJob = null
                // 档位切换中新连接 ICE 连通还不算成功：要等接收端回 REBUILT（首帧已上屏，PM 走查 P0-1）
                qualitySwitch?.let { sw ->
                    WscLog.i(TAG, "quality switch ${sw.from} -> ${sw.to}: media connected after ${System.currentTimeMillis() - sw.startedAt}ms, waiting for first frame")
                }
                val recovered = _state.value is SenderState.Reconnecting
                when (val cur = _state.value) {
                    is SenderState.Starting -> {
                        cancelTimeout()
                        _state.value = SenderState.Casting(
                            t, System.currentTimeMillis(), profile, autoAdjust, audioState, receiverLegacy = !receiverSupportsRebuild,
                            fpsCapped = fpsCap, measuredGameLatencyMs = gameStore.receiver(receiverId)?.latencyP50Ms,
                            gameIntroAcknowledged = gameStore.introAcknowledged, audioSplit = audioSplit,
                        )
                        // 默认档就是游戏：开始即按游戏模式协商（不经过切换停顿）
                        onGameProfile(atStart = true)
                    }
                    is SenderState.Reconnecting -> {
                        if (reconnectJob == null) control.setPaused(ControlReason.RECONNECTING, false)
                        accumulator.onRecovered()
                        _state.value = SenderState.Casting(
                            t, castStartedAtOr(cur.since), profile, autoAdjust, audioState, receiverLegacy = !receiverSupportsRebuild,
                            hint = hints.current, fpsCapped = fpsCap, measuredGameLatencyMs = gameStore.receiver(receiverId)?.latencyP50Ms,
                            gameIntroAcknowledged = gameStore.introAcknowledged, audioSplit = audioSplit,
                        )
                        _events.tryEmit(SenderEvent.Recovered)
                        WscLog.i(TAG, "media recovered after ${System.currentTimeMillis() - cur.since}ms")
                    }
                    else -> Unit
                }
                if (castingSince == 0L) castingSince = System.currentTimeMillis()
                // 断线期间选过需要重建的档位：恢复后补做重建，让低延迟部分也生效
                if (recovered) pendingRebuildFrom?.let { from ->
                    pendingRebuildFrom = null
                    if (QualityProfile.needsMediaRebuild(from, profile)) startSwitch(from, profile, rollback = false)
                }
            }
            MediaState.DISCONNECTED, MediaState.FAILED -> {
                if (rtc == null) return
                // 档位切换中的新连接：失败交给切换超时回退处理，不进入“重连中”
                if (qualitySwitch != null) return
                if (castingSince == 0L) {
                    // 首帧前从未连通：不是“断线重连”，保持“正在建立连接”。FAILED 时 ICE restart 重试，用尽后以连接失败结束（E3）
                    if (state == MediaState.FAILED) {
                        if (preMediaIceRestarts < MAX_PRE_MEDIA_ICE_RESTARTS) {
                            preMediaIceRestarts++
                            WscLog.w(TAG, "ICE failed before first media, restart #$preMediaIceRestarts")
                            rtc?.restartIce()
                        } else {
                            scope.launch { end(EndReason.CONNECT_FAILED, ByeReason.USER_STOP, failure = ConnectFailure.MEDIA_BLOCKED) }
                        }
                    }
                    return
                }
                sm.onEvent(SessionEvent.MediaDisconnected)
                if (connection?.isOpen == true && reconnectJob == null) {
                    // 只是 ICE 断开、信令还在：直接 ICE restart（FAILED 立即，DISCONNECTED 给 2 秒自愈）
                    mediaWatchdog?.cancel()
                    mediaWatchdog = scope.launch {
                        if (state == MediaState.DISCONNECTED) delay(2_000)
                        enterReconnecting()
                        rtc?.restartIce()
                        watchMedia()
                    }
                }
            }
            else -> Unit
        }
    }

    private var castingSince = 0L
    private var reconnectShowJob: Job? = null
    private fun castStartedAtOr(fallback: Long) = if (castingSince > 0) castingSince else fallback

    /** 媒体在 30 秒窗口内没恢复就结束（PRD 8.4）。 */
    private fun watchMedia() {
        mediaWatchdog?.cancel()
        val since = (_state.value as? SenderState.Reconnecting)?.since ?: System.currentTimeMillis()
        mediaWatchdog = scope.launch {
            delay((WscProtocol.RECONNECT_WINDOW_MS - (System.currentTimeMillis() - since)).coerceAtLeast(5_000))
            if (_state.value is SenderState.Reconnecting) end(EndReason.RECONNECT_FAILED, ByeReason.RECONNECT_TIMEOUT)
        }
    }

    private fun enterReconnecting() {
        val t = target ?: return
        control.setPaused(ControlReason.RECONNECTING, true)
        if (_state.value is SenderState.Reconnecting) return
        accumulator.onDisconnected()
        _state.value = SenderState.Reconnecting(t, attempt = 0, since = System.currentTimeMillis(), nextRetryAt = null, profile = profile)
    }

    private fun onSignalingClosed() {
        val s = _state.value
        WscLog.w(TAG, "signaling closed in $s")
        connection = null
        when {
            // 首帧前信令断开：从未连通过，按连接失败结束而不是显示“重连”（E3）
            rtc != null && s is SenderState.Starting && castingSince == 0L -> scope.launch { end(EndReason.CONNECT_FAILED, sendBye = null, failure = ConnectFailure.LOST) }
            rtc != null && (s is SenderState.Casting || s is SenderState.Starting || s is SenderState.Reconnecting) -> startReconnect()
            s is SenderState.Ended || s is SenderState.Idle -> Unit
            else -> scope.launch { end(EndReason.CONNECT_FAILED, sendBye = null, failure = ConnectFailure.LOST) }
        }
    }

    /**
     * 信令断开：不停 MediaProjection / VirtualDisplay，按 0.5/1/2/4… 秒退避重连（最长 30 秒），
     * 连上后用同一个会话 ID 发 HELLO{resume}，再 ICE restart（技术方案 4.3）。
     */
    private fun startReconnect() {
        if (reconnectJob != null) return
        sm.onEvent(SessionEvent.TransportLost)
        control.setPaused(ControlReason.RECONNECTING, true)
        val t = target ?: return
        val fp = receiverFp ?: return
        val id = identity ?: return
        val since = System.currentTimeMillis()
        // 前 1 秒静默（避免瞬断闪烁，交互稿 08A）
        reconnectShowJob?.cancel()
        val showJob = scope.launch {
            delay(1_000)
            enterReconnecting()
        }
        reconnectShowJob = showJob
        reconnectJob = scope.launch {
            val backoff = ReconnectBackoff()
            while (true) {
                val elapsed = System.currentTimeMillis() - since
                val wait = backoff.nextDelay(elapsed)
                if (wait == null) {
                    showJob.cancel()
                    reconnectJob = null
                    end(EndReason.RECONNECT_FAILED, sendBye = null)
                    return@launch
                }
                (_state.value as? SenderState.Reconnecting)?.let {
                    _state.value = it.copy(attempt = backoff.attempt, nextRetryAt = System.currentTimeMillis() + wait)
                }
                delay(wait)
                val conn = runCatching { WscClient(id).connect(t.host, t.port, ServerPin.Full(fp), timeoutMs = 3_000) }.getOrNull()
                    ?: continue
                WscLog.i(TAG, "signaling reconnected (attempt ${backoff.attempt})")
                attach(conn)
                sm.onEvent(SessionEvent.TransportOpened)
                send(helloPayload(id, deviceName(), resume = true))
                // 等 HELLO{resumeOk}；onHello 会取消本 job。超时则继续下一轮。
                delay(3_000)
                if (connection === conn && reconnectJob != null) {
                    conn.close("resume timeout")
                    connection = null
                }
            }
        }
    }

    private fun onRtcStats(s: SenderStatsSnapshot, latencyMs: Int?, r: DcMessage.ReceiverStats?) {
        latencyMs?.let(accumulator::addLatency)
        accumulator.addBitrate(s.bitrateKbps)
        accumulator.codec = s.encoder
        val now = System.currentTimeMillis()
        val grade = latencyMs?.let { gradeTracker.update(it, now) }
        updateCasting {
            it.copy(stats = CastingStats(latencyMs, grade, s.bitrateKbps, s.width, s.height, s.fps))
        }
        if (qualitySwitch != null || _state.value !is SenderState.Casting) return
        when (val d = autoPolicy.onSample(profile, autoAdjust, NetworkSample(s.availableOutgoingBitrateBps, s.lossPercent, s.rttMs))) {
            is AutoQualityDecision.Downgrade -> if (d.to != profile) {
                lastAutoDowngradeFrom = profile
                _events.tryEmit(SenderEvent.AutoDowngraded(profile))
                applyProfile(profile, d.to)
            }
            // 游戏档不切档（档内由 WebRTC 拥塞控制 + MAINTAIN_FRAMERATE 降码率 / 分辨率、保帧率），提示位 ⑦（交互稿 G04）
            AutoQualityDecision.PoorNetworkInGame -> offerHint(GameHint.PoorNetwork)
            null -> Unit
        }
        // 网络恢复：还没看的“网络较差”卡片直接丢弃（交互稿 G04 何时可见）
        if (autoPolicy.goodStreak >= NETWORK_RECOVERED_SECONDS && hints.current?.type == GameHintType.POOR_NETWORK) {
            hints.invalidate(GameHintType.POOR_NETWORK)
            publishHints()
        }
        // 插上电源：未看的“电量低”卡片丢弃（UX 走查 P2-16）
        if (++statsTicks % BATTERY_CHECK_SECONDS == 0 && hints.current?.type == GameHintType.LOW_BATTERY && isCharging()) {
            hints.invalidate(GameHintType.LOW_BATTERY)
            publishHints()
        }
        if (profile.lowLatency) onGameStats(s, latencyMs, r)
    }

    /** 游戏模式每秒判定：延迟达不到 / 60 帧跟不上（PRD 8.1.3 F）。 */
    private fun onGameStats(s: SenderStatsSnapshot, latencyMs: Int?, r: DcMessage.ReceiverStats?) {
        latencyMs?.let(game::addLatency)
        r?.lowLatency?.let { game.lowLatencyDecoder = it }
        val sample = GameSample(
            latencyMs, senderFps = s.fps, receiverFps = r?.fps, receiverDecodeMs = r?.decodeMs, receiverLowLatency = r?.lowLatency,
            captureFps = s.captureFps, rttMs = s.rttMs, lossPercent = s.lossPercent,
        )
        // 旧版接收端不做“达不到”判定：延迟高的原因是版本，已由提示位 ⑤ 说明（PM 走查 P1-4）
        when (val f = gameMonitor.onSample(sample, expectedFps = capFps ?: profile.fps, checkLatency = receiverSupportsRebuild)) {
            is GameModeMonitor.Finding.LatencyUnmet -> offerHint(GameHint.LatencyUnmet(f.medianMs, f.cause))
            GameModeMonitor.Finding.LatencyRecovered -> {
                hints.invalidate(GameHintType.LATENCY_UNMET)
                publishHints()
            }
            is GameModeMonitor.Finding.FpsLow -> {
                capFrameRate(if (f.receiverCause) FpsCapReason.RECEIVER else FpsCapReason.SENDER)
                offerHint(GameHint.FpsDropped(receiverCause = f.receiverCause))
                // 接收端原因：这台电视下次直接用 30 帧（跑满 60 帧时清除，见 FullRate）
                if (f.receiverCause) receiverId?.let { gameStore.recordReceiver(it, prefer30Fps = true) }
            }
            // 在这台电视上跑满了 60 帧：清除“只能 30 帧”的记忆（PM 走查 P1-6）
            GameModeMonitor.Finding.FullRate -> receiverId?.let { id ->
                if (gameStore.receiver(id)?.prefer30Fps == true) gameStore.recordReceiver(id, prefer30Fps = false)
            }
            null -> Unit
        }
    }

    /** 游戏模式降为 30 帧（过热 / 跟不上 60 帧）；仍保持最低缓冲（PRD 8.1.3 F / H）。 */
    private fun capFrameRate(reason: FpsCapReason) {
        if (fpsCap != null || !profile.lowLatency) return
        fpsCap = reason
        game.fpsCapReason = reason.name
        rtc?.setFrameRateCap(CAPPED_FPS, announce = reason != FpsCapReason.REMEMBERED)
        updateCasting { it.copy(fpsCapped = reason) }
        WscLog.i(TAG, "game mode capped to $CAPPED_FPS fps: $reason")
    }

    private fun offerHint(h: GameHint) {
        if (hints.offer(h)) {
            game.hints += h.type.name
            publishHints()
        }
    }

    private fun publishHints() {
        updateCasting { it.copy(hint = hints.current) }
    }

    /** 进入 / 离开游戏模式时的记账与“电量低”检查（交互稿 G04 ⑨：进入游戏模式时电量 <20% 且未充电）。 */
    private fun onGameProfile(atStart: Boolean) {
        val now = System.currentTimeMillis()
        if (profile.lowLatency) {
            if (game.enter(now, atStart)) {
                gameMonitor.reset()
                if (!receiverSupportsRebuild) offerHint(GameHint.LegacyReceiver)
                lowBatteryPercent()?.let { offerHint(GameHint.LowBattery(it)) }
                checkThermalNow()
            }
        } else {
            game.leave(now)
            gameMonitor.reset()
            if (fpsCap != null) {
                fpsCap = null
                updateCasting { it.copy(fpsCapped = null) }
            }
        }
    }

    private fun isCharging(): Boolean = runCatching {
        context.getSystemService(android.os.BatteryManager::class.java)?.isCharging == true
    }.getOrDefault(false)

    /** 进入游戏模式 / 重试 60 帧时手机已经是“严重”温度：直接降帧（温度没有变化就没有回调，PM 走查 P1-3）。 */
    private fun checkThermalNow() {
        val status = runCatching { context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus }.getOrNull() ?: return
        if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && profile.lowLatency && fpsCap == null) {
            capFrameRate(FpsCapReason.THERMAL)
            offerHint(GameHint.Thermal)
        }
    }

    private fun lowBatteryPercent(): Int? = runCatching {
        val bm = context.getSystemService(android.os.BatteryManager::class.java) ?: return null
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (pct in 0 until LOW_BATTERY_PERCENT && !bm.isCharging) pct else null
    }.getOrNull()

    /** 手机温度达到系统“严重”级别：游戏模式降为 30 帧（PRD 8.1.3 H）；更高级别交给系统限频。 */
    private fun registerThermalListener() {
        val pm = context.getSystemService(android.os.PowerManager::class.java) ?: return
        val l = android.os.PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE) {
                scope.launch {
                    if (rtc != null && profile.lowLatency && fpsCap == null) {
                        capFrameRate(FpsCapReason.THERMAL)
                        offerHint(GameHint.Thermal)
                    }
                }
            }
        }
        runCatching { pm.addThermalStatusListener(context.mainExecutor, l) }.onSuccess { thermalListener = l }
    }

    private fun unregisterThermalListener() {
        val l = thermalListener ?: return
        thermalListener = null
        runCatching { context.getSystemService(android.os.PowerManager::class.java)?.removeThermalStatusListener(l) }
    }

    /** 新连接建不起来（交互稿 G02“下次生效”）：只改分辨率与帧率，提示“低延迟将在下次投屏生效”。 */
    /**
     * 接收端确认新连接首帧已上屏（`REBUILT`）：切换成功。停顿取接收端测得的值（PRD 8.1.3 I）。
     * 回退成功后才出提示位 ①（回退本身也失败则不出 ①，进入“重连中”，PM 走查 P1-2）。
     */
    private fun onRebuiltConfirmed(pauseMs: Long) {
        val sw = qualitySwitch ?: return
        WscLog.i(TAG, "quality switch ${sw.from} -> ${sw.to} done: receiver pause ${pauseMs}ms, total ${System.currentTimeMillis() - sw.startedAt}ms${if (sw.rollback) " (rollback)" else ""}")
        game.onSwitchDone(pauseMs)
        clearSwitch()
        audioSplit = sw.to.lowLatency
        updateCasting { it.copy(audioSplit = audioSplit) }
        if (sw.rollback) offerHint(GameHint.SwitchFailed(sw.to))
    }

    private fun onRebuildUnavailable(q: QualityProfile) {
        val sw = qualitySwitch
        if (sw == null || sw.to != q) return
        clearSwitch()
        profile = q
        rtc?.setQuality(q, capFps = capFps)
        updateCasting { it.copy(profile = q) }
        onGameProfile(atStart = false)
        if (q.lowLatency) offerHint(GameHint.NextTime)
    }

    /** 提示位 [知道了]。 */
    fun dismissHint(type: GameHintType) = scope.launch {
        hints.dismiss(type)
        publishHints()
    }

    /** 提示位上的出路按钮（“切到流畅”等，交互稿 G04）。 */
    fun hintAction(type: GameHintType) = scope.launch {
        hints.dismiss(type)
        publishHints()
        when (type) {
            GameHintType.POOR_NETWORK -> setQualityNow(QualityProfile.SMOOTH)
            GameHintType.AUDIO_LATE -> setQualityNow(QualityProfile.BALANCED)
            else -> Unit
        }
    }

    /** G01 首次说明卡的结果：开启 = 记住并立即切换；取消 = 档位不变（下次选“游戏”还会出现）。 */
    fun answerGameIntro(enable: Boolean) = scope.launch {
        updateCasting { it.copy(gameIntroPending = false) }
        if (!enable) return@launch
        gameStore.acknowledgeIntro()
        updateCasting { it.copy(gameIntroAcknowledged = true) }
        setQualityNow(QualityProfile.GAME)
    }

    private inline fun updateCasting(block: (SenderState.Casting) -> SenderState.Casting) {
        val s = _state.value
        if (s is SenderState.Casting) _state.value = block(s)
    }

    // —— 投屏中的用户操作（交互稿 07）——

    fun setQuality(q: QualityProfile) = scope.launch { setQualityNow(q) }

    private suspend fun setQualityNow(q: QualityProfile) {
        // 切换进行中（≤5 秒）不能再切（PRD 8.1.3 D），界面同时禁用分段按钮
        if (qualitySwitch != null) return
        if (q == profile) {
            // 已降为 30 帧时再点一次“游戏”= 重新尝试 60 帧（交互稿 15 状态表）
            if (q.lowLatency && fpsCap != null) {
                fpsCap = null
                gameMonitor.reset()
                rtc?.setFrameRateCap(null)
                updateCasting { it.copy(fpsCapped = null) }
                checkThermalNow()
            }
            return
        }
        // G01：本机第一次选“游戏”先讲清取舍
        if (q.lowLatency && !gameStore.introAcknowledged && _state.value is SenderState.Casting) {
            updateCasting { it.copy(gameIntroPending = true) }
            return
        }
        autoPolicy.reset()
        // 会话中改档只对本次投屏有效，默认画质只在设置里改（产品拍板，PM 走查 P1-7）
        applyProfile(profile, q)
    }

    fun undoAutoDowngrade() = scope.launch {
        if (qualitySwitch != null) return@launch
        lastAutoDowngradeFrom?.let { prev ->
            lastAutoDowngradeFrom = null
            applyProfile(profile, prev)
        }
    }

    /**
     * 会话中改档（F18，PRD 8.1.3 D）：
     * - 不需要重建（分辨率 / 帧率 / 码率变化）→ DataChannel SET_QUALITY 即时生效；
     * - 需要重建（切进 / 切出游戏档：接收端播放缓冲、解码器、音画分组都变）且接收端支持 → 重建媒体连接（[startSwitch]）；
     * - 接收端版本旧 → 只改分辨率和帧率，提示“对方版本较旧”；
     * - 断线重连中 → 先改编码参数，媒体恢复后补做重建。
     */
    private fun applyProfile(from: QualityProfile, to: QualityProfile) {
        profile = to
        if (!to.lowLatency) fpsCap = null
        if (to.lowLatency && fpsCap == null && gameStore.receiver(receiverId)?.prefer30Fps == true) fpsCap = FpsCapReason.REMEMBERED
        val session = rtc
        if (session == null || from == to) {
            updateCasting { it.copy(profile = to) }
            return
        }
        when (QualitySwitchPolicy.plan(from, to, receiverSupportsRebuild, streaming = _state.value is SenderState.Casting)) {
            QualityChange.SET_QUALITY -> {
                session.setQuality(to, capFps = capFps)
                updateCasting { it.copy(profile = to, fpsCapped = fpsCap) }
                onGameProfile(atStart = false)
            }
            QualityChange.SET_QUALITY_LEGACY -> {
                // 旧版接收端：只改分辨率与帧率，提示位 ⑤（在 onGameProfile 里）
                session.setQuality(to, capFps = capFps)
                updateCasting { it.copy(profile = to, fpsCapped = fpsCap) }
                onGameProfile(atStart = false)
            }
            QualityChange.DEFER_REBUILD -> {
                session.setQuality(to, capFps = capFps)
                if (pendingRebuildFrom == null) pendingRebuildFrom = from
                (_state.value as? SenderState.Reconnecting)?.let { _state.value = it.copy(profile = to) }
            }
            QualityChange.REBUILD -> startSwitch(from, to, rollback = false)
        }
    }

    /** 重建媒体连接切到 [to]；5 秒内新连接没连通就回到 [from]（回退也失败则按普通断线处理）。 */
    private fun startSwitch(from: QualityProfile, to: QualityProfile, rollback: Boolean) {
        val session = rtc ?: return
        profile = to
        qualitySwitch = QualitySwitch(from, to, System.currentTimeMillis(), rollback)
        updateCasting { it.copy(profile = to, switchingTo = to, switchingFrom = from, switchingRollback = rollback, fpsCapped = fpsCap) }
        // 切出游戏 / 切进游戏的记账在发出切换时进行（时长从这里起算，停顿另计）
        onGameProfile(atStart = false)
        session.rebuildMedia(to, capFps)
        switchTimeout?.cancel()
        switchTimeout = scope.launch {
            delay(QualitySwitchPolicy.TIMEOUT_MS)
            onSwitchTimeout()
        }
        WscLog.i(TAG, "quality switch $from -> $to${if (rollback) " (rollback)" else ""}")
    }

    private suspend fun onSwitchTimeout() {
        val sw = qualitySwitch ?: return
        WscLog.w(TAG, "quality switch ${sw.from} -> ${sw.to} timed out (rollback=${sw.rollback})")
        if (QualitySwitchPolicy.onTimeout(sw.rollback) == SwitchTimeoutAction.ROLLBACK) {
            // 回到原档位（PRD 8.1.3 D：“切换没有成功，已保持‘均衡’”）
            switchTimeout = null
            game.switchFailures++
            if (!sw.from.lowLatency) fpsCap = null
            // 提示位 ① 等回退的首帧确认后再出（onRebuiltConfirmed）
            startSwitch(sw.to, sw.from, rollback = true)
        } else {
            // 回退也没连上：如实进入“重连中”，再按当前档位重建一次（重建对接收端任何状态都安全），30 秒窗口照旧
            clearSwitch()
            enterReconnecting()
            audioSplit = profile.lowLatency
            rtc?.rebuildMedia(profile, capFps)
            watchMedia()
        }
    }

    private fun clearSwitch() {
        switchTimeout?.cancel()
        switchTimeout = null
        qualitySwitch = null
        updateCasting { it.copy(switchingTo = null, switchingFrom = null, switchingRollback = false) }
    }

    fun setAutoAdjust(enabled: Boolean) = scope.launch {
        autoAdjust = enabled
        autoPolicy.reset()
        updateCasting { it.copy(autoAdjust = enabled) }
        settings.setAutoAdjust(enabled)
    }

    fun setAudioEnabled(enabled: Boolean) = scope.launch {
        if (audioState == AudioState.NO_PERMISSION) return@launch
        audioState = if (enabled) AudioState.ON else AudioState.OFF
        rtc?.setAudioEnabled(enabled)
        updateCasting { it.copy(audio = audioState) }
        settings.setAudioEnabled(enabled)
    }

    /** App 内、通知、Tile 三处停止（PRD S-07）。 */
    fun stop() = scope.launch { end(EndReason.USER_STOPPED, ByeReason.USER_STOP) }

    /** 在配对 / 说明卡阶段取消。 */
    fun cancel() = scope.launch { end(EndReason.USER_STOPPED, ByeReason.USER_STOP) }

    /** 用户看过结束提示后回到列表。 */
    fun acknowledge() = scope.launch {
        if (_state.value is SenderState.Ended) _state.value = SenderState.Idle
    }

    val isSessionActive: Boolean get() = rtc != null

    private suspend fun end(reason: EndReason, sendBye: ByeReason?, detail: String? = null, failure: ConnectFailure? = null) {
        // 不可取消 + 幂等（E1 / E4）：end() 常在超时 / 重连 / 看门狗 Job 里被调用，而它会取消这些 Job；
        // 不加保护时第一个挂起点（关闭会话）就抛 CancellationException，会话不关、服务不停、状态卡住。
        endGuard.end(sessionGen) {
            val t = target
            cancelTimeout()
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectShowJob?.cancel()
            mediaWatchdog?.cancel()
            switchTimeout?.cancel()
            switchTimeout = null
            qualitySwitch = null
            unregisterThermalListener()
            game.leave(System.currentTimeMillis())
            // 停止投屏 = 同时收回控制；会话结束立即停止注入（PRD 风险表）。先取被控制时长写进本机报告（D8）
            val controlledMs = control.controlledDurationMs()
            control.detach()
            if (sendBye != null) send(Bye(sendBye))
            val conn = connection
            connection = null
            readerJob?.cancel()
            conn?.close("end $reason")
            val session = rtc
            rtc = null
            if (session != null) {
                withContext(Dispatchers.IO) { session.close() }
                stopService()
                writeReport(reason, controlledMs)
            }
            sm.onEvent(SessionEvent.Closed)
            WscLog.i(TAG, "session ended: $reason ${failure ?: ""} ${detail.orEmpty()}")
            _state.value = if (reason == EndReason.USER_STOPPED) SenderState.Idle else SenderState.Ended(t, reason, detail, failure)
        }
    }

    private fun writeReport(reason: EndReason, controlledMs: Long) {
        val t = target ?: return
        val firstFrameMs = if (firstMediaAt > 0 && captureGrantedAt > 0) firstMediaAt - captureGrantedAt else null
        runCatching {
            reports.add(
                SessionRecord(
                    sessionId = sessionId,
                    protocol = CastProtocol.WSC,
                    role = "SENDER",
                    peerName = receiverName ?: t.name,
                    startedAt = connectStartedAt,
                    endedAt = System.currentTimeMillis(),
                    discoveryPath = t.source.name,
                    firstFrameMs = firstFrameMs,
                    success = firstFrameMs != null && firstMediaAt - connectStartedAt <= 60_000,
                    latencyP50Ms = accumulator.percentile(0.5),
                    latencyP95Ms = accumulator.percentile(0.95),
                    latencyHistogram = accumulator.histogram.toList(),
                    avgBitrateKbps = accumulator.avgBitrateKbps(),
                    disconnects = accumulator.disconnects,
                    autoRecovered = accumulator.autoRecovered,
                    endReason = reason.name,
                    codec = accumulator.codec,
                    androidVersion = Build.VERSION.RELEASE.orEmpty(),
                    deviceModel = Build.MODEL.orEmpty(),
                    controlledMs = controlledMs.takeIf { it >= 1_000 },
                    game = game.report(receiverSupportsRebuild),
                ),
            )
            // 记住这台接收端上的游戏模式实测延迟（下次档位说明里显示，交互稿 15 层级 ⑤）
            val p50 = game.latencyP50()
            if (p50 != null) receiverId?.let { gameStore.recordReceiver(it, latencyP50Ms = p50) }
        }.onFailure { WscLog.w(TAG, "write report failed", it) }
    }

    private fun stopService() {
        runCatching { context.stopService(Intent(context, ScreenCastService::class.java)) }
    }

    private fun send(payload: WscPayload): Boolean {
        val conn = connection ?: return false
        val type = com.benchan.wireless.screencasting.core.protocol.message.WscCodec.typeOf(payload)
        if (type != MessageType.ERROR && type != MessageType.BYE) {
            val t = sm.onEvent(SessionEvent.Sent(type))
            if (!t.accepted) {
                WscLog.w(TAG, "state machine refused to send $type in ${t.from}")
                return false
            }
        }
        return conn.send(payload, sessionId)
    }

    private fun armTimeout(ms: Long, onTimeout: suspend () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            timeoutJob = null
            onTimeout()
        }
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    private fun resetSession() {
        sm = SessionStateMachine(Role.SENDER)
        sessionId = Ids.newSessionId()
        pairing = null
        pairMethod = null
        receiverFp = null
        receiverId = null
        receiverName = null
        accumulator = SessionStatsAccumulator()
        autoPolicy.reset()
        connectStartedAt = 0
        captureGrantedAt = 0
        firstMediaAt = 0
        castingSince = 0
        lastAutoDowngradeFrom = null
        audioState = AudioState.OFF
        preMediaIceRestarts = 0
        receiverCaps = emptyList()
        qualitySwitch = null
        pendingRebuildFrom = null
        hints.reset()
        gameMonitor.reset()
        fpsCap = null
        game = GameTracker()
        audioSplit = false
        statsTicks = 0
    }

    @Suppress("unused")
    private fun fingerprintHexOrNull(): String? = receiverFp?.let { Hex.encode(it.bytes) }

    private companion object {
        const val TAG = "SenderController"
        const val HELLO_TIMEOUT_MS = 6_000L
        const val PAIR_REPLY_TIMEOUT_MS = 8_000L
        const val FIRST_MEDIA_TIMEOUT_MS = 60_000L
        const val MAX_PRE_MEDIA_ICE_RESTARTS = 2

        /** 游戏模式降帧后的帧率（PRD 8.1.3 F / H）。 */
        const val CAPPED_FPS = 30

        /** 电量低于该百分比且未充电时，进入游戏模式提示一次（PRD 8.1.3 H）。 */
        const val LOW_BATTERY_PERCENT = 20

        /** 连续多少秒网络正常算“已恢复”（丢弃未看的“网络较差”卡片）。 */
        const val NETWORK_RECOVERED_SECONDS = 5

        /** 每隔多少秒检查一次是否插上电源。 */
        const val BATTERY_CHECK_SECONDS = 10
    }
}

/** 游戏模式报告记账（PRD 8.1.3 I）：只在单线程调度器上使用。 */
private class GameTracker {
    private var since = 0L
    private var durationMs = 0L
    private var enteredAtStart: Boolean? = null
    private var switches = 0
    private var maxPauseMs: Long? = null
    private val latency = SessionStatsAccumulator()
    var switchFailures = 0
    var lowLatencyDecoder: Boolean? = null
    var fpsCapReason: String? = null
    val hints = linkedSetOf<String>()

    /** @return 是否是本次新进入（之前不在游戏模式）。 */
    fun enter(now: Long, atStart: Boolean): Boolean {
        if (since > 0) return false
        since = now
        if (enteredAtStart == null) enteredAtStart = atStart
        return true
    }

    fun leave(now: Long) {
        if (since == 0L) return
        durationMs += now - since
        since = 0
    }

    fun onSwitchDone(pauseMs: Long) {
        switches++
        maxPauseMs = maxOf(maxPauseMs ?: 0, pauseMs)
    }

    fun addLatency(ms: Int) = latency.addLatency(ms)

    fun latencyP50(): Int? = latency.percentile(0.5)

    fun report(receiverSupportsGame: Boolean): GameReport? {
        val start = enteredAtStart ?: return null
        return GameReport(
            durationMs = durationMs, enteredAtStart = start, switches = switches, switchFailures = switchFailures,
            maxSwitchPauseMs = maxPauseMs, latencyHistogram = latency.histogram.toList(), latencyP50Ms = latency.percentile(0.5),
            latencyP95Ms = latency.percentile(0.95), lowLatencyDecoder = lowLatencyDecoder, fpsCapReason = fpsCapReason,
            hints = hints.toList(), receiverSupportsGame = receiverSupportsGame,
        )
    }
}
