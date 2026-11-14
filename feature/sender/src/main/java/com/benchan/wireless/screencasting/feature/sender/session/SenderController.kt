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
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.devices.RecentDevice
import com.benchan.wireless.screencasting.core.data.devices.RecentDevicesStore
import com.benchan.wireless.screencasting.core.data.report.CastProtocol
import com.benchan.wireless.screencasting.core.data.report.SessionRecord
import com.benchan.wireless.screencasting.core.data.report.SessionReportStore
import com.benchan.wireless.screencasting.core.data.report.SessionStatsAccumulator
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.protocol.VersionNegotiation
import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import com.benchan.wireless.screencasting.core.protocol.datachannel.AudioState
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
import com.benchan.wireless.screencasting.core.protocol.quality.AutoQualityPolicy
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGradeTracker
import com.benchan.wireless.screencasting.core.protocol.quality.NetworkSample
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
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

    /** 用户选中一台设备（列表 / 扫码 / 投屏码 / 手动 IP / Tile）。 */
    fun connect(newTarget: CastTarget) = scope.launch {
        if (rtc != null) {
            WscLog.w(TAG, "connect ignored: session active")
            return@launch
        }
        resetSession()
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
        caps = listOf(WscProtocol.Caps.H264, WscProtocol.Caps.OPUS),
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
                rtc?.restartIce()
                watchMedia()
            } else {
                end(EndReason.RECONNECT_FAILED, sendBye = null)
            }
            return
        }
        receiverId = h.deviceId
        receiverName = h.name
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
        val session = SenderRtcSession(serviceContext, rtcEngine, data, profile, audio, rtcListener)
        rtc = session
        session.start()
        // 首帧超时：60 秒还没有连上媒体就判定失败（交互稿 10 统计口径）
        armTimeout(FIRST_MEDIA_TIMEOUT_MS) { end(EndReason.CONNECT_FAILED, ByeReason.USER_STOP, failure = ConnectFailure.FIRST_FRAME_TIMEOUT) }
    }

    private val rtcListener = object : SenderRtcSession.Listener {
        override fun onLocalOffer(sdp: String, iceRestart: Boolean) {
            scope.launch { send(Offer(sdp, iceRestart)) }
        }

        override fun onLocalIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
            scope.launch { send(IceCandidate(candidate, sdpMid, sdpMLineIndex)) }
        }

        override fun onMediaState(state: MediaState) {
            scope.launch { onMedia(state) }
        }

        override fun onFirstConnected() {
            scope.launch { if (firstMediaAt == 0L) firstMediaAt = System.currentTimeMillis() }
        }

        override fun onProjectionStopped(reason: ProjectionStopReason) {
            scope.launch {
                if (reason == ProjectionStopReason.SCREEN_LOCKED) {
                    end(EndReason.SCREEN_LOCKED, ByeReason.SCREEN_LOCKED)
                } else {
                    end(EndReason.PROJECTION_STOPPED, ByeReason.PROJECTION_STOPPED)
                }
            }
        }

        override fun onStats(stats: SenderStatsSnapshot, latencyMs: Int?) {
            scope.launch { onRtcStats(stats, latencyMs) }
        }

        override fun onQualityChanged(profile: QualityProfile, byRemote: Boolean) {
            scope.launch {
                this@SenderController.profile = profile
                updateCasting { it.copy(profile = profile) }
            }
        }

        override fun onAudioState(state: AudioState) {
            scope.launch {
                if (audioState == AudioState.NO_PERMISSION || audioState == AudioState.OFF) return@launch
                audioState = state
                updateCasting { it.copy(audio = state) }
                rtc?.sendDc(com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage.Audio(state))
            }
        }

        override fun onCapturedAppVisible(visible: Boolean) {
            scope.launch { updateCasting { it.copy(appVisible = visible) } }
        }

        override fun onProtectedContent(suspected: Boolean) {
            scope.launch { updateCasting { it.copy(protectedSuspected = suspected) } }
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
                when (val cur = _state.value) {
                    is SenderState.Starting -> {
                        cancelTimeout()
                        _state.value = SenderState.Casting(t, System.currentTimeMillis(), profile, autoAdjust, audioState)
                    }
                    is SenderState.Reconnecting -> {
                        accumulator.onRecovered()
                        _state.value = SenderState.Casting(t, castStartedAtOr(cur.since), profile, autoAdjust, audioState)
                        _events.tryEmit(SenderEvent.Recovered)
                        WscLog.i(TAG, "media recovered after ${System.currentTimeMillis() - cur.since}ms")
                    }
                    else -> Unit
                }
                if (castingSince == 0L) castingSince = System.currentTimeMillis()
            }
            MediaState.DISCONNECTED, MediaState.FAILED -> {
                if (rtc == null) return
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
        if (_state.value is SenderState.Reconnecting) return
        accumulator.onDisconnected()
        _state.value = SenderState.Reconnecting(t, attempt = 0, since = System.currentTimeMillis(), nextRetryAt = null, profile = profile)
    }

    private fun onSignalingClosed() {
        val s = _state.value
        WscLog.w(TAG, "signaling closed in $s")
        connection = null
        when {
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

    private fun onRtcStats(s: SenderStatsSnapshot, latencyMs: Int?) {
        latencyMs?.let(accumulator::addLatency)
        accumulator.addBitrate(s.bitrateKbps)
        accumulator.codec = s.encoder
        val now = System.currentTimeMillis()
        val grade = latencyMs?.let { gradeTracker.update(it, now) }
        updateCasting {
            it.copy(stats = CastingStats(latencyMs, grade, s.bitrateKbps, s.width, s.height, s.fps))
        }
        val next = autoPolicy.onSample(profile, autoAdjust, NetworkSample(s.availableOutgoingBitrateBps, s.lossPercent, s.rttMs))
        if (next != null && next != profile) {
            lastAutoDowngradeFrom = profile
            _events.tryEmit(SenderEvent.AutoDowngraded(profile))
            rtc?.setQuality(next)
            profile = next
            updateCasting { it.copy(profile = next) }
        }
    }

    private inline fun updateCasting(block: (SenderState.Casting) -> SenderState.Casting) {
        val s = _state.value
        if (s is SenderState.Casting) _state.value = block(s)
    }

    // —— 投屏中的用户操作（交互稿 07）——

    fun setQuality(q: QualityProfile) = scope.launch {
        profile = q
        autoPolicy.reset()
        rtc?.setQuality(q)
        updateCasting { it.copy(profile = q) }
        settings.setQuality(q)
    }

    fun undoAutoDowngrade() = scope.launch {
        lastAutoDowngradeFrom?.let { prev ->
            lastAutoDowngradeFrom = null
            profile = prev
            rtc?.setQuality(prev)
            updateCasting { it.copy(profile = prev) }
        }
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
        val t = target
        cancelTimeout()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectShowJob?.cancel()
        mediaWatchdog?.cancel()
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
            writeReport(reason)
        }
        sm.onEvent(SessionEvent.Closed)
        WscLog.i(TAG, "session ended: $reason ${failure ?: ""} ${detail.orEmpty()}")
        _state.value = if (reason == EndReason.USER_STOPPED) SenderState.Idle else SenderState.Ended(t, reason, detail, failure)
    }

    private fun writeReport(reason: EndReason) {
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
                ),
            )
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
    }

    @Suppress("unused")
    private fun fingerprintHexOrNull(): String? = receiverFp?.let { Hex.encode(it.bytes) }

    private companion object {
        const val TAG = "SenderController"
        const val HELLO_TIMEOUT_MS = 6_000L
        const val PAIR_REPLY_TIMEOUT_MS = 8_000L
        const val FIRST_MEDIA_TIMEOUT_MS = 60_000L
    }
}
