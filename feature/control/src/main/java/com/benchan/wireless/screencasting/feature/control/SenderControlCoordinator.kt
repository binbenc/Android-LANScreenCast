package com.benchan.wireless.screencasting.feature.control

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.control.CaptureGeometry
import com.benchan.wireless.screencasting.core.control.ControlPolicy
import com.benchan.wireless.screencasting.core.control.GestureCommand
import com.benchan.wireless.screencasting.core.control.InjectionContext
import com.benchan.wireless.screencasting.core.control.InputGeometry
import com.benchan.wireless.screencasting.core.control.PointD
import com.benchan.wireless.screencasting.core.control.RejectLimiter
import com.benchan.wireless.screencasting.core.control.SequenceGate
import com.benchan.wireless.screencasting.core.control.StrokeSegmenter
import com.benchan.wireless.screencasting.core.control.TimedPoint
import com.benchan.wireless.screencasting.core.control.WheelSynthesizer
import com.benchan.wireless.screencasting.core.control.AutoDisablePolicy
import com.benchan.wireless.screencasting.core.control.PinchSynthesizer
import com.benchan.wireless.screencasting.core.control.SensitiveTracker
import com.benchan.wireless.screencasting.core.control.SensitiveApps
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.data.settings.SettingsRepository
import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.control.ControlStateMachine
import com.benchan.wireless.screencasting.core.protocol.control.ControlTransition
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.control.PointerAction
import com.benchan.wireless.screencasting.core.protocol.control.RejectReason
import com.benchan.wireless.screencasting.core.protocol.control.history
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import com.benchan.wireless.screencasting.feature.control.ui.ControlConsentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反向控制发送端编排（PRD F23、决策 #16；技术方案“反向控制”；交互稿 12 章 R03–R05）。
 *
 * - 授权由 [ControlStateMachine] 决定：每次会话本人确认、锁屏立即结束、重连只暂停；
 * - 输入：wsc-input 的 P / K → 序号门 → 几何代号校验 → 坐标换算 → [ControlPolicy] 门控 → [StrokeSegmenter] → 无障碍注入；
 * - 可见性：边缘胶囊（R05）、请求通知 + 确认页（R03）、投屏通知（由 ScreenCastService 根据 [ui] 构建）。
 *
 * 所有状态都在单独的 HandlerThread（wsc-control）上变更；公开方法可在任意线程调用。
 * 日志只记录状态迁移与计数，不记录坐标与对端发来的任何内容（技术方案“日志脱敏”）。
 */
@Singleton
class SenderControlCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: AccessibilityBridge,
    private val settingsRepo: SettingsRepository,
) {
    private val thread = HandlerThread("wsc-control").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + handler.asCoroutineDispatcher())
    private val mono: () -> Long = { SystemClock.elapsedRealtime() }

    private val _ui = MutableStateFlow(ControlUi())
    val ui: StateFlow<ControlUi> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<ControlEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ControlEvent> = _events.asSharedFlow()

    private var availability: ControlReason? = ControlReason.SETTING_OFF
    @Volatile private var remoteControlOn = false
    @Volatile private var autoDisableOn = false

    /** 前台敏感状态（跨会话保留，新会话开始时沿用；只在 wsc-control 线程变更）。 */
    private val sensitiveTracker = SensitiveTracker(context.packageName)

    /** 本机用户在暂停胶囊 / R04 卡点了“继续控制”：忽略当前敏感界面，直到下一次前台切换（用户决策 D-3 / PM S4）。 */
    private var sensitiveOverridden = false
    private var overriddenPackage: String? = null
    private var lastForegroundPackage: String? = null

    @Volatile private var foregroundSensitive = false
    private var sm = ControlStateMachine(mono, availability)
    private var link: ControlLink? = null
    private var geometry: CaptureGeometry? = null
    private var screen = 0 to 0
    private var generation = 0
    private val segmenter = StrokeSegmenter(mono)
    private val seqGate = SequenceGate()
    private val rejects = RejectLimiter(mono)
    private var gestureAllowed = false
    private var lastSeq = 0L
    private var tickJob: Job? = null
    private var countdownJob: Job? = null
    private var windowedJob: Job? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var injectedEvents = 0L
    private var droppedEvents = 0L
    private var guideShown = false

    /** 本次会话累计被控制时长（ACTIVE + PAUSED，毫秒）与当前授权开始时间（单调时钟，0 = 未授权）。跨线程读取（投屏报告）。 */
    private val features: List<String> = ControlFeatures.forSdk(android.os.Build.VERSION.SDK_INT)
    private val featureSet: Set<String> = features.toSet()

    /**
     * 本应用自己处于前台的 Activity 数（D9：投屏应用自身界面不允许远程操作）。不含确认页 R03（它显示期间已丢弃一切注入，
     * 计入会让每次“允许”先报一次 OWN_APP 暂停，PM 走查 S1）。
     */
    private val ownActivitiesStarted = java.util.concurrent.atomic.AtomicInteger(0)
    private val controlledTotalMs = java.util.concurrent.atomic.AtomicLong(0)
    private val controlledSince = java.util.concurrent.atomic.AtomicLong(0)

    init {
        // D9：本应用的任何界面（投屏页、设置、确认页……）在前台时暂停反向控制，防止远端操作“收回 / 关闭”等本应用自身的开关
        (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                if (activity is ControlConsentActivity) return
                if (ownActivitiesStarted.incrementAndGet() == 1) setPaused(ControlReason.OWN_APP, true)
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                if (activity is ControlConsentActivity) return
                val n = ownActivitiesStarted.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (n == 0) setPaused(ControlReason.OWN_APP, false)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        scope.launch {
            settingsRepo.settings.collect {
                remoteControlOn = it.remoteControlEnabled
                autoDisableOn = it.remoteControlAutoDisable
            }
        }
        // C 期：前台敏感界面（安装器、无障碍设置）暂停注入。只用事件里的包名 / 类名；系统界面（通知栏、音量条）与本应用的窗口不改变判断
        // 失败向收紧（PM S2）：敏感状态下，输入法 / Toast / 框架包 / 系统界面普通窗口 / 本应用窗口不清除状态
        bridge.windowStateListener = { pkg, cls ->
            scope.launch {
                if (SensitiveApps.isSensitive(pkg, cls)) lastForegroundPackage = pkg
                val changed = sensitiveTracker.onWindow(pkg, cls, imePackages())
                // “继续控制”只放行当时那个敏感界面：敏感状态变化，或切到另一个敏感界面，都恢复拦截
                if (changed || (SensitiveApps.isSensitive(pkg, cls) && pkg != overriddenPackage)) sensitiveOverridden = false
                val effective = sensitiveTracker.sensitive && !sensitiveOverridden
                foregroundSensitive = effective
                if (effective != (ControlReason.SENSITIVE_APP in sm.pauseReasons)) {
                    WscLog.i(TAG, "foreground sensitive=$effective")
                    apply(sm.setPaused(ControlReason.SENSITIVE_APP, effective))
                }
            }
        }
        scope.launch {
            combine(settingsRepo.settings.map { it.remoteControlEnabled }.distinctUntilChanged(), bridge.connected, bridge.advancedProtection) { enabled, connected, aapm ->
                when {
                    !enabled -> ControlReason.SETTING_OFF
                    aapm -> ControlReason.AAPM
                    !connected -> ControlReason.SERVICE_OFF
                    else -> null
                }
            }.distinctUntilChanged().collect { a ->
                availability = a
                if (a == null) settingsRepo.setRemoteControlAutoDisabled(false)
                if (link != null) {
                    if (a == null) bridge.showSink()
                    apply(sm.setAvailability(a))
                }
            }
        }
    }

    // ————————————————————————— 会话生命周期（SenderController 调用）—————————————————————————

    /** WSC 镜像会话开始（录屏已授权、RTC 会话已创建）。 */
    fun attach(newLink: ControlLink) = scope.launch {
        if (link != null) detachLocked()
        link = newLink
        sm = ControlStateMachine(mono, availability)
        // 新会话沿用当前暂停条件（本应用界面是否在前台）
        if (ownActivitiesStarted.get() > 0) sm.setPaused(ControlReason.OWN_APP, true)
        if (foregroundSensitive) sm.setPaused(ControlReason.SENSITIVE_APP, true)
        generation = 0
        geometry = null
        seqGate.reset()
        injectedEvents = 0
        droppedEvents = 0
        guideShown = false
        controlledTotalMs.set(0)
        controlledSince.set(0)
        bridge.showSink()
        registerScreenReceiver()
        publish()
        WscLog.i(TAG, "control attached (available=${availability == null})")
    }

    /** 会话结束：立即停止注入、收起胶囊与通知。 */
    fun detach() = scope.launch { detachLocked() }

    /**
     * 本次会话累计被控制的时长（毫秒），供本机投屏报告记录“本次被××控制 mm:ss”（用户决策 D8，只存本地）。
     * 会话结束时在 [detach] 之前调用。任意线程。
     */
    fun controlledDurationMs(): Long {
        val since = controlledSince.get()
        return controlledTotalMs.get() + if (since > 0) mono() - since else 0
    }

    private fun detachLocked() {
        if (link == null) return
        abortGesture()
        val t = sm.sessionEnded()
        markControlled(false)
        if (t.ended != null) _events.tryEmit(ControlEvent.Ended(ControlReason.SESSION_END))
        unregisterScreenReceiver()
        bridge.hideOverlay()
        bridge.hideSink()
        // “投屏结束后自动关闭无障碍服务”（C 期，默认关）
        if (AutoDisablePolicy.shouldDisableOnSessionEnd(autoDisableOn, bridge.connected.value, remoteControlOn)) {
            WscLog.i(TAG, "auto-disable accessibility service after session")
            bridge.disableService()
            // R01 据此显示“无障碍服务已按你的设置关闭”（UX G7）
            scope.launch { settingsRepo.setRemoteControlAutoDisabled(true) }
        }
        ControlNotifications.cancelRequest(context)
        countdownJob?.cancel()
        windowedJob?.cancel()
        link = null
        _ui.value = ControlUi()
        WscLog.i(TAG, "control detached (injected=$injectedEvents dropped=$droppedEvents)")
    }

    /** DataChannel `wsc` 打开：告诉接收端当前状态与几何。 */
    fun onDataChannelOpen() = scope.launch {
        sendState(sm.snapshot())
        sendGeometry()
    }

    /** `wsc` 上的控制消息。 */
    fun onDataChannelMessage(m: DcMessage) = scope.launch {
        if (link == null) return@launch
        when (m) {
            is DcMessage.CtlRequest -> onRequest(m.rid)
            is DcMessage.CtlEnd -> apply(sm.receiverEnd())
            else -> Unit
        }
    }

    /** 采集几何变化（RTC 线程回调）。 */
    fun onGeometry(captureW: Int, captureH: Int, contentW: Int, contentH: Int, screenW: Int, screenH: Int) = scope.launch {
        val g = CaptureGeometry(generation + 1, captureW, captureH, contentW, contentH)
        if (geometry?.let { it.captureWidth == captureW && it.captureHeight == captureH && it.contentWidth == contentW && it.contentHeight == contentH } == true &&
            screen == (screenW to screenH)
        ) {
            return@launch
        }
        generation = g.gen
        geometry = g
        screen = screenW to screenH
        // 几何变化：进行中的手势按旧坐标已经不准，取消
        if (segmenter.active) abortGesture()
        sendGeometry()
        // 被采集内容不是整块屏幕（单应用共享的分屏 / 小窗）→ 暂停；旋转瞬间两路回调可能短暂不一致，去抖 600 ms
        windowedJob?.cancel()
        windowedJob = scope.launch {
            delay(WINDOWED_DEBOUNCE_MS)
            val covers = InputGeometry.coversScreen(contentW, contentH, screen.first, screen.second)
            apply(sm.setPaused(ControlReason.WINDOWED, !covers))
        }
        // 当前 CTL_STATE 带几何代号
        sendState(sm.snapshot())
    }

    /** 暂停条件（RECONNECTING / PROTECTED / APP_HIDDEN）。 */
    fun setPaused(reason: ControlReason, paused: Boolean) = scope.launch { apply(sm.setPaused(reason, paused)) }

    // ————————————————————————— 发送端本人操作 —————————————————————————

    /** 本应用（不含确认页）的界面是否在前台：确认页据此提示“允许后将回到桌面”。任意线程。 */
    fun ownAppInForeground(): Boolean = ownActivitiesStarted.get() > 0

    /**
     * 本机用户在暂停胶囊 / R04 卡点“继续控制”（D-3 兜底，例如权限弹窗期间需要对方继续操作）：
     * 只放行当前这个敏感界面，前台再切换时恢复拦截。远端注入点不到胶囊与本应用界面，所以只能由手机本人触发。
     */
    fun continueDespiteSensitive() = scope.launch {
        if (!sensitiveTracker.sensitive) return@launch
        sensitiveOverridden = true
        overriddenPackage = lastForegroundPackage
        foregroundSensitive = false
        WscLog.i(TAG, "sensitive pause overridden by local user")
        apply(sm.setPaused(ControlReason.SENSITIVE_APP, false))
    }

    /** 已启用输入法的包名（输入法窗口不改变敏感判断）。 */
    private fun imePackages(): Set<String> = runCatching {
        context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }?.toSet()
    }.getOrNull() ?: emptySet()

    /** R03“允许本次”。 */
    fun allow() = scope.launch {
        if (link == null) return@launch
        apply(sm.allow())
    }

    /** R03“拒绝” / 通知“拒绝” / 返回键。 */
    fun deny() = scope.launch { if (link != null) apply(sm.deny()) }

    /** 收回控制（通知 / 边缘胶囊 / 投屏页）。 */
    fun revoke() = scope.launch { if (link != null) apply(sm.revoke()) }

    // ————————————————————————— 输入 —————————————————————————

    /** wsc-input 上的输入（RTC 线程回调）。 */
    fun onInput(m: InputMessage) = scope.launch {
        if (link == null) return@launch
        if (!seqGate.accept(m.s)) {
            droppedEvents++
            return@launch
        }
        lastSeq = m.s
        when (m) {
            is InputMessage.Pointer -> onPointer(m)
            is InputMessage.Key -> onKey(m)
            is InputMessage.Wheel -> onWheel(m)
            is InputMessage.Text -> onText(m)
            is InputMessage.Pinch -> onPinch(m)
            is InputMessage.Reject -> Unit
        }
    }

    private fun onPointer(p: InputMessage.Pointer) {
        if (p.i != 0) return // A 期单指：其余指针忽略
        if (!sm.canInject) {
            if (segmenter.active) abortGesture()
            reject(p.s, RejectReason.PAUSED)
            return
        }
        val g = geometry
        if (g == null || p.g != g.gen) {
            if (segmenter.active) abortGesture()
            reject(p.s, RejectReason.GEOM_STALE)
            return
        }
        val ctx = injectionContext()
        when (p.a) {
            PointerAction.DOWN -> {
                val pt = toScreen(g, p.x, p.y)
                val why = if (pt == null) RejectReason.BLOCKED_TARGET else ControlPolicy.checkDown(pt.x, pt.y, ctx)
                if (why != null) {
                    gestureAllowed = false
                    if (segmenter.active) abortGesture()
                    WscLog.d(TAG, "down rejected: $why")
                    reject(p.s, why)
                    return
                }
                gestureAllowed = true
                run(segmenter.onDown(TimedPoint(pt!!.x, pt.y, 0)))
                ensureTicking()
            }
            PointerAction.MOVE, PointerAction.UP -> {
                if (!gestureAllowed) return
                ControlPolicy.checkOngoing(ctx)?.let {
                    WscLog.d(TAG, "${p.a} aborted: $it")
                    abortGesture()
                    reject(p.s, it)
                    return
                }
                val samples = (p.history.map { Triple(it.x, it.y, it.dt) } + Triple(p.x, p.y, p.dt))
                    .mapNotNull { (x, y, dt) -> toScreen(g, x, y)?.let { TimedPoint(it.x, it.y, dt) } }
                if (p.a == PointerAction.MOVE) {
                    run(segmenter.onMove(samples))
                } else {
                    if (samples.size > 1) run(segmenter.onMove(samples.dropLast(1)))
                    val up = samples.lastOrNull() ?: return abortGesture()
                    run(segmenter.onUp(up))
                    gestureAllowed = false
                }
            }
            PointerAction.CANCEL -> {
                gestureAllowed = false
                abortGesture()
            }
        }
    }

    private fun onKey(k: InputMessage.Key) {
        ControlPolicy.checkKey(k.k, injectionContext(), featureSet)?.let {
            reject(k.s, it)
            return
        }
        val inj = bridge.injector ?: return reject(k.s, RejectReason.PAUSED)
        if (k.k in RemoteKey.EDITOR) {
            val why = inj.editorKey(k.k)
            if (why == null) injectedEvents++ else reject(k.s, why)
            return
        }
        val ok = inj.global(k.k)
        if (ok) injectedEvents++ else reject(k.s, RejectReason.UNSUPPORTED)
    }

    /** 滚轮 / TV 光标顶底边滚动（B 期）：在指针位置合成一次不带惯性的滑动。 */
    private fun onWheel(w: InputMessage.Wheel) {
        WscLog.d(TAG, "wheel received ${if (w.dy > 0) "down" else "up"}")
        if (!sm.canInject) {
            WscLog.d(TAG, "wheel rejected: ${sm.phase}")
            return reject(w.s, RejectReason.PAUSED)
        }
        if (segmenter.active) {
            WscLog.d(TAG, "wheel ignored: finger stroke in progress")
            return
        }
        val g = geometry
        if (g == null || w.g != g.gen) {
            WscLog.d(TAG, "wheel rejected: geometry stale")
            return reject(w.s, RejectReason.GEOM_STALE)
        }
        // 锚点落在内容边缘 / 黑边上时夹回内容区（TV 光标在顶 / 底边、鼠标滚轮贴边都会出现），只在几何不匹配时拒绝
        val pt = toScreenClamped(g, w.x, w.y) ?: run {
            WscLog.d(TAG, "wheel rejected: no geometry")
            return reject(w.s, RejectReason.GEOM_STALE)
        }
        val (sw, sh) = screen
        val cmds = WheelSynthesizer.synthesize(pt.x, pt.y, w.dy, sw, sh)
        val start = (cmds.firstOrNull() as? GestureCommand.Segment)?.points?.firstOrNull() ?: run {
            WscLog.d(TAG, "wheel ignored: no screen size")
            return
        }
        ControlPolicy.checkDown(start.x, start.y, injectionContext())?.let {
            WscLog.d(TAG, "wheel rejected: $it")
            return reject(w.s, it)
        }
        WscLog.d(TAG, "wheel -> swipe")
        run(cmds)
    }

    /** 双指缩放（C 期，PINCH 宏）：一次性合成两根手指的手势。 */
    private fun onPinch(p: InputMessage.Pinch) {
        if (!sm.canInject) return reject(p.s, RejectReason.PAUSED)
        // 单指手势未结束（接收端的取消尚未到达等竞态）：先同步中止，再执行捏合
        if (segmenter.active) {
            WscLog.d(TAG, "pinch: aborting unfinished finger stroke")
            abortGesture()
        }
        val g = geometry
        if (g == null || p.g != g.gen) return reject(p.s, RejectReason.GEOM_STALE)
        val c = toScreenClamped(g, p.x, p.y) ?: return reject(p.s, RejectReason.GEOM_STALE)
        val (sw, sh) = screen
        val width = if (sw > 0) sw.toDouble() else g.contentWidth.toDouble()
        val cmd = PinchSynthesizer.synthesize(c.x, c.y, p.from * width, p.to * width, p.ang, p.dur, sw, sh) ?: return
        val ctx = injectionContext()
        cmd.strokes.forEach { stroke ->
            val start = stroke.first()
            ControlPolicy.checkDown(start.x, start.y, ctx)?.let {
                WscLog.d(TAG, "pinch rejected: $it")
                return reject(p.s, it)
            }
        }
        WscLog.d(TAG, "pinch -> multi")
        run(listOf(cmd))
    }

    /** 文字（B 期，Android 13+）：提交到当前输入框，密码框拒绝。日志只记长度。 */
    private fun onText(t: InputMessage.Text) {
        ControlPolicy.checkText(injectionContext(), featureSet)?.let { return reject(t.s, it) }
        val inj = bridge.injector ?: return reject(t.s, RejectReason.PAUSED)
        val why = inj.text(t.text)
        WscLog.d(TAG, "text len=${t.text.length} -> ${why ?: "ok"}")
        if (why == null) injectedEvents++ else reject(t.s, why)
    }

    private fun toScreen(g: CaptureGeometry, nx: Double, ny: Double): PointD? {
        val c = InputGeometry.normalizedToContent(g, nx, ny) ?: return null
        val (sw, sh) = screen
        if (sw <= 0 || sh <= 0) return c
        // 全屏（WINDOWED 未暂停）时内容 ≈ 屏幕；按比例换算吸收几个像素的差异
        return PointD(c.x * sw / g.contentWidth, c.y * sh / g.contentHeight)
    }

    private fun toScreenClamped(g: CaptureGeometry, nx: Double, ny: Double): PointD? {
        val c = InputGeometry.normalizedToContentClamped(g, nx, ny) ?: return null
        val (sw, sh) = screen
        if (sw <= 0 || sh <= 0) return c
        return PointD(c.x * sw / g.contentWidth, c.y * sh / g.contentHeight)
    }

    private fun injectionContext(): InjectionContext {
        val km = context.getSystemService(KeyguardManager::class.java)
        return InjectionContext(
            canInject = sm.canInject && bridge.injector != null,
            keyguardLocked = km?.isKeyguardLocked == true,
            consentVisible = bridge.consentVisible,
            blocked = listOfNotNull(bridge.overlayRect, bridge.statusBarRect(), bridge.sinkRect),
        )
    }

    private fun run(commands: List<GestureCommand>) {
        if (commands.isEmpty()) return
        val inj = bridge.injector ?: run {
            WscLog.w(TAG, "no injector: ${commands.size} command(s) dropped")
            return
        }
        commands.forEach { c -> if (c !is GestureCommand.Segment || c.first || c.last) WscLog.d(TAG, "cmd ${c.traceName()}") }
        inj.onCancelled = ::onInjectionCancelled
        commands.forEach {
            inj.execute(it)
            injectedEvents++
        }
    }

    /** 注入被系统取消（本机有人触摸屏幕）：本地操作优先，放弃这一手势并告诉接收端。 */
    private fun onInjectionCancelled() {
        scope.launch {
            if (!gestureAllowed && !segmenter.active) return@launch
            WscLog.i(TAG, "injection cancelled by system -> LOCAL_TOUCH")
            gestureAllowed = false
            segmenter.onCancel()
            reject(lastSeq, RejectReason.LOCAL_TOUCH)
        }
    }

    private fun GestureCommand.traceName(): String = when (this) {
        is GestureCommand.Tap -> "tap"
        is GestureCommand.Segment -> if (first && last) "stroke" else if (first) "down" else "up"
        GestureCommand.Abort -> "abort"
        is GestureCommand.Multi -> "multi"
    }

    private fun abortGesture() {
        run(segmenter.onCancel())
        gestureAllowed = false
    }

    /** 手势进行中每 8 ms 驱动一次分段器（缓冲到期、续接刷新、空闲超时）。 */
    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive && segmenter.active) {
                delay(TICK_MS)
                run(segmenter.tick())
            }
            if (!segmenter.active) gestureAllowed = false
        }
    }

    private fun reject(seq: Long, why: RejectReason) {
        droppedEvents++
        if (rejects.shouldSend(why)) link?.sendInput(InputMessage.Reject(seq, why))
    }

    // ————————————————————————— 授权 —————————————————————————

    private fun onRequest(rid: Int) {
        val km = context.getSystemService(KeyguardManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val locked = km?.isKeyguardLocked == true || pm?.isInteractive == false
        if (availability == ControlReason.SERVICE_OFF) {
            // 能发通知才算“发送端已显示开启步骤”，接收端据此换提示文案（PM 走查 G2）
            guideShown = link?.peerName?.let { ControlNotifications.showServiceGuide(context, it) } == true
        }
        apply(sm.request(rid, deviceLocked = locked))
    }

    private fun apply(t: ControlTransition) {
        if (t.reply || t.changed) sendState(t.snapshot)
        if (!t.changed) return
        val s = t.snapshot
        WscLog.i(TAG, "control -> ${s.phase} ${s.reason ?: ""}")
        if (s.phase != ControlPhase.ACTIVE && segmenter.active) abortGesture()
        if (s.phase == ControlPhase.PENDING) onPending() else {
            countdownJob?.cancel()
            ControlNotifications.cancelRequest(context)
        }
        val wasControlled = _ui.value.controlled
        markControlled(s.phase == ControlPhase.ACTIVE || s.phase == ControlPhase.PAUSED)
        publish()
        when (s.phase) {
            ControlPhase.ACTIVE, ControlPhase.PAUSED -> {
                if (!wasControlled) seqGate.reset()
                bridge.showOverlay(
                    OverlayState(
                        s.phase == ControlPhase.PAUSED,
                        ControlCopy.overlayLabel(s.phase, s.reason, largeFont = context.resources.configuration.fontScale >= 1.3f),
                        canContinue = s.phase == ControlPhase.PAUSED && s.reason == ControlReason.SENSITIVE_APP,
                    ),
                    onRevoke = { revoke() },
                    onContinue = { continueDespiteSensitive() },
                )
            }
            else -> bridge.hideOverlay()
        }
        t.ended?.let { reason ->
            // 只有真正被控制过才提示结束（请求阶段锁屏 / 拒绝不提示，PM 走查 G5）
            if (wasControlled) _events.tryEmit(ControlEvent.Ended(reason))
            if (wasControlled && reason == ControlReason.LOCKED) ControlNotifications.showEnded(context, "已收回控制：手机已锁屏")
            if (wasControlled && reason == ControlReason.SERVICE_OFF) ControlNotifications.showServiceStopped(context)
        }
    }

    private fun onPending() {
        val peer = link?.peerName.orEmpty()
        countdownJob?.cancel()
        countdownJob = scope.launch {
            // 通知被关闭或请求渠道被设为“不显示”时直接打开确认页（无障碍服务已绑定的应用不受后台启动 Activity 限制，PM 走查 G1）
            if (!ControlNotifications.requestChannelUsable(context)) {
                runCatching { context.startActivity(ControlConsentActivity.intent(context)) }
            }
            var consentSeen = false
            while (isActive && sm.phase == ControlPhase.PENDING) {
                val left = ((sm.snapshot().remainMs ?: 0) + 999) / 1_000
                if (bridge.consentVisible) {
                    // 确认页可见期间不显示通知，避免悬浮通知盖住确认页（真机问题 R2）
                    consentSeen = true
                    ControlNotifications.cancelRequest(context)
                } else {
                    // 打开过确认页又离开：静默保留通知（不再悬浮提醒），方便回到确认页
                    ControlNotifications.showRequest(context, peer, left.toInt(), silent = consentSeen)
                }
                delay(1_000)
                apply(sm.tick())
            }
        }
    }

    private fun markControlled(on: Boolean) {
        val since = controlledSince.get()
        if (on && since == 0L) {
            controlledSince.set(mono())
        } else if (!on && since > 0) {
            controlledTotalMs.addAndGet(mono() - since)
            controlledSince.set(0)
        }
    }

    private fun publish() {
        val s = sm.snapshot()
        val prev = _ui.value
        val controlled = s.phase == ControlPhase.ACTIVE || s.phase == ControlPhase.PAUSED
        _ui.value = ControlUi(
            phase = s.phase,
            reason = s.reason,
            peerName = link?.peerName.orEmpty(),
            grantedAt = if (controlled) prev.grantedAt.takeIf { prev.controlled && it > 0 } ?: System.currentTimeMillis() else 0,
            pendingDeadline = if (s.phase == ControlPhase.PENDING) System.currentTimeMillis() + (s.remainMs ?: 0) else 0,
            inSession = link != null,
        )
    }

    private fun sendState(s: com.benchan.wireless.screencasting.core.protocol.control.ControlSnapshot) {
        val l = link ?: return
        l.sendDc(
            DcMessage.CtlState(
                state = s.phase,
                rid = s.rid,
                reason = s.reason,
                feat = if (s.phase == ControlPhase.UNAVAILABLE) emptyList() else features,
                g = geometry?.gen,
                remainMs = s.remainMs,
                guide = if (s.phase == ControlPhase.UNAVAILABLE && s.reason == ControlReason.SERVICE_OFF) guideShown else null,
            ),
        )
    }

    private fun sendGeometry() {
        val g = geometry ?: return
        val app = !InputGeometry.coversScreen(g.contentWidth, g.contentHeight, screen.first, screen.second)
        link?.sendDc(DcMessage.Geometry(g.gen, g.captureWidth, g.captureHeight, g.contentWidth, g.contentHeight, if (app) "APP" else "FULL"))
    }

    // ————————————————————————— 锁屏 / 熄屏 —————————————————————————

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // 熄屏立即结束控制；解锁后不自动恢复（交互稿 R05“终止”）
                if (intent.action == Intent.ACTION_SCREEN_OFF) scope.launch { apply(sm.locked()) }
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(Intent.ACTION_SCREEN_OFF), null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = r
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
    }

    private companion object {
        const val TAG = "SenderControl"
        const val TICK_MS = 8L
        const val WINDOWED_DEBOUNCE_MS = 600L
        const val SYSTEM_UI = "com.android.systemui"
    }
}
