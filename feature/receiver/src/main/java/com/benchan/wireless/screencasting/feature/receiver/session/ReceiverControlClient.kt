package com.benchan.wireless.screencasting.feature.receiver.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.control.CaptureGeometry
import com.benchan.wireless.screencasting.core.control.TextCoalescer
import com.benchan.wireless.screencasting.core.control.PinchPlan
import com.benchan.wireless.screencasting.core.control.PinchTracker
import com.benchan.wireless.screencasting.core.control.PinchWheelCoalescer
import com.benchan.wireless.screencasting.core.control.WheelCoalescer
import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.control.InputGeometry
import com.benchan.wireless.screencasting.core.control.PointD
import com.benchan.wireless.screencasting.core.control.PointerCoalescer
import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.control.RejectReason
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.core.rtc.receiver.ReceiverRtcSession

/**
 * 接收端反向控制客户端（PRD F23；交互稿 R06–R08；PROTOCOL.md 第 6 节）。只用于 WSC 镜像会话。
 *
 * - 请求 / 取消 / 退出：`wsc` 上的 CTL_REQ / CTL_END；发送端的 CTL_STATE 是唯一的状态来源。
 * - 触摸：视图坐标 → 去掉黑边 → 解码帧归一化坐标 → [PointerCoalescer] → `wsc-input`。落在黑边上的按下直接丢弃。
 * - 被拒 / 超时 / 取消 / 被收回后 10 秒内不能再次请求（交互稿 R08）。
 *
 * 线程：触摸与按键来自主线程，DataChannel 消息与帧尺寸来自 RTC / 解码线程，所以公开方法都加锁。
 */
internal class ReceiverControlClient(
    private val rtc: ReceiverRtcSession,
    private val peerSupportsControl: Boolean,
    private val isTv: Boolean,
    private val onState: (ReceiverControl) -> Unit,
    private val onNotice: (ControlNotice) -> Unit,
) {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val mono: () -> Long = { SystemClock.uptimeMillis() }
    private val coalescer = PointerCoalescer(mono)
    private val textBuf = TextCoalescer(mono)
    private val wheelBuf = WheelCoalescer(mono)
    private val pinch = PinchTracker(mono)
    private val pinchWheel = PinchWheelCoalescer(mono)
    private val unsupportedShown = HashSet<String>()

    /** 发送端最近回过 NO_EDITOR（没有选中的输入框）：TV 光标模式下 ENTER 据此当作确定键（遥控器“确定”发 KEYCODE_ENTER 的兜底）。 */
    @Volatile
    var editorMissing: Boolean = false
        private set
    private val geometries = ArrayDeque<CaptureGeometry>()
    private var state = ReceiverControl(supported = peerSupportsControl)
    private var rid = 0
    private var pausedNoticeShown = false
    private var blockedNoticeShown = false
    private var localTouchNoticeShown = false
    private var closed = false

    private val requestTimeout = Runnable {
        synchronized(lock) {
            if (!state.requesting) return@Runnable
            // 对方没有回应 CTL_REQ（旧版本 / 未开启）：按“未开启”提示
            update(state.copy(requesting = false))
        }
        onNotice(ControlNotice.NotEnabled)
    }

    private val sensitiveLong = Runnable {
        val still = synchronized(lock) { !closed && state.phase == ControlPhase.PAUSED && state.reason == ControlReason.SENSITIVE_APP }
        if (still) onNotice(ControlNotice.SensitiveLong)
    }

    fun close() {
        synchronized(lock) { closed = true }
        main.removeCallbacks(requestTimeout)
        main.removeCallbacks(sensitiveLong)
    }

    // ————————————————————————— 用户操作 —————————————————————————

    /** 控制条“请求控制”。 */
    fun request() {
        val now = System.currentTimeMillis()
        val notice: ControlNotice? = synchronized(lock) {
            when {
                closed -> return
                state.controlling || state.phase == ControlPhase.PENDING || state.requesting -> return
                now < state.cooldownUntil -> return
                !peerSupportsControl -> ControlNotice.Unsupported
                else -> {
                    rid++
                    update(state.copy(requesting = true))
                    // want = 本端已实现的能力（PM 走查 G10）
                    rtc.sendDc(DcMessage.CtlRequest(rid, IMPLEMENTED))
                    main.postDelayed(requestTimeout, REQUEST_REPLY_TIMEOUT_MS)
                    null
                }
            }
        }
        notice?.let(onNotice)
    }

    /** 等待确认时“取消请求”。 */
    fun cancelRequest() = endControl()

    /** 控制条“退出控制”。 */
    fun exit() = endControl()

    private fun endControl() {
        synchronized(lock) {
            if (closed) return
            coalescer.cancel()?.let(::sendInput)
            update(state.copy(requesting = false))
            rtc.sendDc(DcMessage.CtlEnd(ControlReason.RECEIVER_END))
        }
        main.removeCallbacks(requestTimeout)
    }

    /** 控制条“返回 / 主页 / 最近任务”、返回键、方向键、编辑键。先发出尚未发送的文字，保证顺序。 */
    fun key(k: RemoteKey) {
        synchronized(lock) {
            if (!canSend()) return
            flushText()
            sendInput(coalescer.key(k))
        }
    }

    /** 发送端能力（CTL_STATE.feat）。 */
    val features: Set<String> get() = synchronized(lock) { state.feat.toSet() }

    // ————————————————————————— B 期：滚动与文字 —————————————————————————

    /** 一次滚动（TV 光标顶 / 底边继续按，dy = ±1/3）。视图坐标。 */
    fun wheel(viewW: Int, viewH: Int, x: Float, y: Float, dy: Double) = synchronized(lock) {
        if (!canSend()) {
            WscLog.d(TAG, "wheel dropped: not active (${state.phase})")
            return@synchronized
        }
        val p = clamped(viewW, viewH, x, y) ?: run {
            WscLog.d(TAG, "wheel dropped: no video rect")
            return@synchronized
        }
        val gen = InputGeometry.pickGeneration(state.frameWidth, state.frameHeight, geometries.toList()) ?: run {
            WscLog.d(TAG, "wheel dropped: no matching geometry")
            return@synchronized
        }
        WscLog.d(TAG, "wheel sent ${if (dy > 0) "down" else "up"}")
        flushText()
        // 边缘滚动的锚点向内收 5%：光标停在顶 / 底边（y = 0 / 1）时锚点仍落在内容内（发送端另有夹取兜底）
        val ax = InputCodec.round4(p.x.coerceIn(EDGE_INSET, 1 - EDGE_INSET))
        val ay = InputCodec.round4(p.y.coerceIn(EDGE_INSET, 1 - EDGE_INSET))
        sendInput(InputMessage.Wheel(coalescer.nextSeq(), gen, ax, ay, dy.coerceIn(-1.0, 1.0)))
    }

    /** 鼠标滚轮（[notches] > 0 = 向下滚动），50 ms 内合并。视图坐标。 */
    fun mouseWheel(viewW: Int, viewH: Int, x: Float, y: Float, notches: Double) = synchronized(lock) {
        if (!canSend()) return@synchronized
        val p = clamped(viewW, viewH, x, y) ?: return@synchronized
        wheelBuf.scroll(p.x, p.y, notches)?.let(::sendWheel)
    }

    // ————————————————————————— C 期：双指缩放 —————————————————————————

    /**
     * 第二根手指按下：取消单指手势、开始跟踪捏合。视图坐标。
     * @return 是否开始（对方不支持 / 当前不能控制 / 落在黑边上时为 false）。
     */
    fun pinchStart(viewW: Int, viewH: Int, ax: Float, ay: Float, bx: Float, by: Float): PinchStart {
        val result = synchronized(lock) {
            if (!canSend()) return PinchStart.INACTIVE
            coalescer.cancel()?.let(::sendInput)
            when {
                ControlFeatures.MULTI !in state.feat -> PinchStart.UNSUPPORTED
                // 任一手指落在黑边上：不跟踪，提示“双指需在画面内”
                InputGeometry.viewToNormalized(viewW, viewH, state.frameWidth, state.frameHeight, ax, ay) == null ||
                    InputGeometry.viewToNormalized(viewW, viewH, state.frameWidth, state.frameHeight, bx, by) == null -> PinchStart.OUTSIDE
                else -> {
                    pinch.start(clamped(viewW, viewH, ax, ay)!!, clamped(viewW, viewH, bx, by)!!, state.frameWidth, state.frameHeight)
                    PinchStart.STARTED
                }
            }
        }
        when (result) {
            PinchStart.UNSUPPORTED -> hintOnce(ControlNotice.PinchUnsupported)
            PinchStart.OUTSIDE -> hintOnce(ControlNotice.PinchOutside)
            else -> Unit
        }
        return result
    }

    fun pinchMove(viewW: Int, viewH: Int, ax: Float, ay: Float, bx: Float, by: Float) = synchronized(lock) {
        if (!pinch.active) return@synchronized
        val a = clamped(viewW, viewH, ax, ay) ?: return@synchronized
        val b = clamped(viewW, viewH, bx, by) ?: return@synchronized
        pinch.update(a, b)
    }

    /** 任一手指抬起：发送一次完整的捏合（PINCH 宏）。@return 执行时长（毫秒）；变化太小 / 不能发送返回 null。 */
    fun pinchEnd(): Long? = synchronized(lock) {
        val plan = pinch.end() ?: return@synchronized null
        if (!canSend()) return@synchronized null
        sendPinch(plan)
        plan.dur
    }

    /** 鼠标 Ctrl + 滚轮 = 捏合（滚轮向上 = 放大），50 ms 内合并。视图坐标。 */
    fun ctrlWheel(viewW: Int, viewH: Int, x: Float, y: Float, notches: Double) {
        val unsupported = synchronized(lock) {
            if (!canSend()) return
            if (ControlFeatures.MULTI !in state.feat) {
                true
            } else {
                val p = clamped(viewW, viewH, x, y) ?: return
                pinchWheel.scroll(p.x, p.y, notches)
                false
            }
        }
        if (unsupported) hintOnce(ControlNotice.PinchUnsupported)
    }

    private fun sendPinch(p: PinchPlan) {
        val gen = InputGeometry.pickGeneration(state.frameWidth, state.frameHeight, geometries.toList()) ?: return
        WscLog.d(TAG, "pinch sent ${if (p.to > p.from) "in" else "out"}")
        sendInput(
            InputMessage.Pinch(
                coalescer.nextSeq(), gen, InputCodec.round4(p.x), InputCodec.round4(p.y),
                InputCodec.round4(p.from).coerceAtLeast(0.0001), InputCodec.round4(p.to).coerceAtLeast(0.0001), p.dur,
                (Math.round(p.ang * 10_000) / 10_000.0).coerceIn(-Math.PI, Math.PI),
            ),
        )
    }

    /** 键盘逐字输入（50 ms 合并）。 */
    fun typeChar(c: Char) = synchronized(lock) {
        if (!canSend()) return@synchronized
        textBuf.type(c)?.let(::sendText)
    }

    /** R07 / TV“输入文字”：整段发送（超长自动分多条）。 */
    fun sendTextBlock(text: String) = synchronized(lock) {
        if (!canSend() || text.isEmpty()) return@synchronized
        editorMissing = false
        flushText()
        TextCoalescer.chunks(text).forEach(::sendText)
    }

    /**
     * 对方系统版本不支持某能力。[force] = 用户主动按了不可用的按钮：每次都提示（UX 走查 G4）；
     * 否则（按键被降级）每次控制每种提示一次。
     */
    fun notifyUnsupported(feature: String, force: Boolean = false) {
        val notice = synchronized(lock) {
            if (!unsupportedShown.add(feature) && !force) return
            when (feature) {
                ControlFeatures.TEXT -> ControlNotice.TextUnsupported
                ControlFeatures.DPAD -> ControlNotice.DpadUnsupported
                ControlFeatures.MEDIA -> ControlNotice.MediaUnsupported
                else -> return
            }
        }
        onNotice(notice)
    }

    /** 每次控制只提示一次的操作建议（看不到焦点、方向键模式长按）。 */
    fun hintOnce(notice: ControlNotice) {
        val show = synchronized(lock) { unsupportedShown.add("hint:" + notice.title) }
        if (show) onNotice(notice)
    }

    private fun flushText() {
        textBuf.flush()?.let(::sendText)
    }

    private fun sendText(t: String) {
        sendInput(InputMessage.Text(coalescer.nextSeq(), t))
    }

    private fun sendWheel(w: WheelCoalescer.Scroll) {
        val gen = InputGeometry.pickGeneration(state.frameWidth, state.frameHeight, geometries.toList()) ?: return
        sendInput(InputMessage.Wheel(coalescer.nextSeq(), gen, InputCodec.round4(w.x), InputCodec.round4(w.y), w.dy))
    }

    // ————————————————————————— 触摸（主线程）—————————————————————————

    /** @return 是否被转发（false = 落在黑边 / 当前不能控制）。 */
    fun pointerDown(viewW: Int, viewH: Int, x: Float, y: Float): Boolean = synchronized(lock) {
        if (!canSend()) return@synchronized false
        val p = InputGeometry.viewToNormalized(viewW, viewH, state.frameWidth, state.frameHeight, x, y) ?: return@synchronized false
        val gen = InputGeometry.pickGeneration(state.frameWidth, state.frameHeight, geometries.toList()) ?: return@synchronized false
        coalescer.down(p.x, p.y, gen).forEach(::sendInput)
        true
    }

    fun pointerMove(viewW: Int, viewH: Int, x: Float, y: Float) = synchronized(lock) {
        if (!coalescer.active) return@synchronized
        if (!canSend()) {
            coalescer.cancel()?.let(::sendInput)
            return@synchronized
        }
        val p = clamped(viewW, viewH, x, y) ?: return@synchronized
        coalescer.move(p.x, p.y)?.let(::sendInput)
    }

    fun pointerUp(viewW: Int, viewH: Int, x: Float, y: Float) = synchronized(lock) {
        if (!coalescer.active) return@synchronized
        val p = clamped(viewW, viewH, x, y)
        val m = if (p == null || !canSend()) coalescer.cancel() else coalescer.up(p.x, p.y)
        m?.let(::sendInput)
    }

    fun pointerCancel() = synchronized(lock) { coalescer.cancel()?.let(::sendInput) }

    /** 每帧调用：发出缓冲的移动 / 按住不动的保活、到期的文字与滚轮。 */
    fun tick() = synchronized(lock) {
        coalescer.tick()?.let(::sendInput)
        if (canSend()) {
            textBuf.tick()?.let(::sendText)
            wheelBuf.tick()?.let(::sendWheel)
            pinchWheel.tick()?.let(::sendPinch)
        }
    }

    /** 当前不能控制时用户触摸了画面：暂停态提示一次（交互稿 12 章“输入被丢弃并提示一次”）。 */
    fun touchedWhileBlocked() {
        val notice = synchronized(lock) {
            if (state.phase != ControlPhase.PAUSED || pausedNoticeShown) return
            pausedNoticeShown = true
            ControlNotice.Paused(state.reason)
        }
        onNotice(notice)
    }

    // ————————————————————————— 来自发送端 —————————————————————————

    fun onDataChannel(m: DcMessage) {
        when (m) {
            is DcMessage.Geometry -> synchronized(lock) {
                geometries.removeAll { it.gen == m.g }
                geometries.addLast(CaptureGeometry(m.g, m.cw, m.ch, m.sw, m.sh))
                while (geometries.size > MAX_GEOMETRIES) geometries.removeFirst()
            }
            is DcMessage.CtlState -> onControlState(m)
            else -> Unit
        }
    }

    fun onInput(m: InputMessage) {
        if (m !is InputMessage.Reject) return
        // 发送端丢弃了输入：几何过期 / 本机有人触摸 → 本地结束手势，避免继续发送无效的移动
        if (m.why == RejectReason.GEOM_STALE || m.why == RejectReason.LOCAL_TOUCH || m.why == RejectReason.BLOCKED_TARGET) {
            synchronized(lock) { coalescer.cancel() }
        }
        // 受保护区域 / 本机触摸：每次控制各提示一次（PM 走查 G8）；密码框 / 没有输入框：每次都提示（发送端已限速）
        val notice = synchronized(lock) {
            when {
                m.why == RejectReason.BLOCKED_TARGET && !blockedNoticeShown -> ControlNotice.Blocked.also { blockedNoticeShown = true }
                m.why == RejectReason.LOCAL_TOUCH && !localTouchNoticeShown -> ControlNotice.LocalTouch.also { localTouchNoticeShown = true }
                m.why == RejectReason.SECURE_FIELD -> ControlNotice.SecureField
                m.why == RejectReason.NO_EDITOR -> ControlNotice.NoEditor.also { editorMissing = true }
                m.why == RejectReason.UNSUPPORTED && unsupportedShown.add("irej-unsupported") -> ControlNotice.UnsupportedOp
                else -> null
            }
        }
        notice?.let(onNotice)
    }

    fun onFrameSize(width: Int, height: Int) = synchronized(lock) {
        if (state.frameWidth != width || state.frameHeight != height) update(state.copy(frameWidth = width, frameHeight = height))
    }

    private fun onControlState(m: DcMessage.CtlState) {
        val now = System.currentTimeMillis()
        var notice: ControlNotice? = null
        synchronized(lock) {
            if (closed) return
            val prev = state
            val wasEngaged = prev.requesting || prev.phase == ControlPhase.PENDING || prev.controlling
            var next = prev.copy(phase = m.state, reason = m.reason, feat = m.feat, requesting = false)
            when (m.state) {
                ControlPhase.PENDING -> next = next.copy(pendingDeadline = now + (m.remainMs ?: CONFIRM_MS))
                ControlPhase.PAUSED -> {
                    if (prev.phase != ControlPhase.PAUSED || prev.reason != m.reason) {
                        if (prev.phase != ControlPhase.PAUSED) {
                            pausedNoticeShown = true
                            notice = ControlNotice.Paused(m.reason)
                        }
                        // D-3 兜底：敏感界面暂停超过 10 秒，提示让对方点一下手机或切换应用
                        main.removeCallbacks(sensitiveLong)
                        if (m.reason == ControlReason.SENSITIVE_APP) main.postDelayed(sensitiveLong, SENSITIVE_LONG_MS)
                    }
                }
                ControlPhase.ACTIVE -> {
                    pausedNoticeShown = false
                    if (!prev.controlling) {
                        blockedNoticeShown = false
                        localTouchNoticeShown = false
                        unsupportedShown.clear()
                    }
                }
                ControlPhase.IDLE -> if (wasEngaged) {
                    val cooldown = now + (m.remainMs ?: COOLDOWN_MS)
                    notice = when (m.reason) {
                        ControlReason.USER_DENIED -> ControlNotice.Denied(((cooldown - now) + 999) / 1_000).also { next = next.copy(cooldownUntil = cooldown) }
                        ControlReason.TIMEOUT -> ControlNotice.Timeout.also { next = next.copy(cooldownUntil = cooldown) }
                        ControlReason.COOLDOWN -> null.also { next = next.copy(cooldownUntil = cooldown) }
                        ControlReason.CANCELLED -> null.also { next = next.copy(cooldownUntil = cooldown) }
                        // 对方主动收回后接收端同样冷却 10 秒（正式规则，PROTOCOL.md 6.2）
                        ControlReason.REVOKED -> ControlNotice.Revoked.also { next = next.copy(cooldownUntil = cooldown) }
                        ControlReason.LOCKED -> if (prev.controlling) ControlNotice.Locked else ControlNotice.LockedRequest
                        ControlReason.RECEIVER_END, ControlReason.SESSION_END -> null
                        else -> if (prev.controlling) ControlNotice.Revoked else null
                    }
                }
                ControlPhase.UNAVAILABLE -> if (wasEngaged) {
                    // 不可用的回复同样冷却 10 秒（PM 走查 G2）
                    if (!prev.controlling) next = next.copy(cooldownUntil = now + (m.remainMs ?: COOLDOWN_MS))
                    notice = when (m.reason) {
                        ControlReason.SERVICE_OFF -> when {
                            prev.controlling -> ControlNotice.Revoked
                            m.guide == false -> ControlNotice.ServiceOffNoGuide
                            else -> ControlNotice.ServiceOff
                        }
                        ControlReason.AAPM -> ControlNotice.AdvancedProtection
                        else -> if (prev.controlling) ControlNotice.Revoked else ControlNotice.NotEnabled
                    }
                }
            }
            if (!next.controlling || next.phase != ControlPhase.ACTIVE) coalescer.cancel()?.let(::sendInput)
            update(next)
            if (prev.phase != next.phase) WscLog.i(TAG, "control ${prev.phase} -> ${next.phase} ${next.reason ?: ""}")
        }
        main.removeCallbacks(requestTimeout)
        notice?.let(onNotice)
    }

    // ————————————————————————— 内部 —————————————————————————

    private fun canSend(): Boolean = !closed && state.phase == ControlPhase.ACTIVE && state.frameWidth > 0 && state.frameHeight > 0

    /** 移动 / 抬起：手指滑进黑边时夹到视频边缘（按下时已确认在内容区）。 */
    private fun clamped(viewW: Int, viewH: Int, x: Float, y: Float): PointD? {
        val rect = InputGeometry.videoRectInView(viewW, viewH, state.frameWidth, state.frameHeight) ?: return null
        val cx = x.toDouble().coerceIn(rect.left, rect.right)
        val cy = y.toDouble().coerceIn(rect.top, rect.bottom)
        return PointD((cx - rect.left) / rect.width, (cy - rect.top) / rect.height)
    }

    private fun sendInput(m: InputMessage) {
        rtc.sendInput(m)
    }

    private fun update(next: ReceiverControl) {
        state = next
        onState(next)
    }

    private companion object {
        const val TAG = "ReceiverControl"
        const val MAX_GEOMETRIES = 4
        const val COOLDOWN_MS = 10_000L
        const val CONFIRM_MS = 30_000L
        const val REQUEST_REPLY_TIMEOUT_MS = 5_000L

        /** 敏感界面暂停多久后提示“请让对方点一下手机或切换一次应用”。 */
        const val SENSITIVE_LONG_MS = 10_000L

        /** 边缘滚动锚点距内容边缘的比例。 */
        const val EDGE_INSET = 0.05

        /** 本端已实现、会请求的能力（C 期再加 multi）。 */
        val IMPLEMENTED = listOf(
            ControlFeatures.TAP, ControlFeatures.STROKE, ControlFeatures.GLOBAL, ControlFeatures.WHEEL,
            ControlFeatures.DPAD, ControlFeatures.TEXT, ControlFeatures.MEDIA, ControlFeatures.MULTI,
        )
    }
}
