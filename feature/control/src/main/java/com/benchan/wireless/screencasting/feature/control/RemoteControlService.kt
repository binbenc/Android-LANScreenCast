package com.benchan.wireless.screencasting.feature.control

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.benchan.wireless.screencasting.core.control.RectD
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 反向控制无障碍服务（PRD F23、决策 #16）。
 *
 * 只做三件事：注入手势（dispatchGesture）、执行白名单全局动作（返回 / 主页 / 最近任务）、显示边缘胶囊（TYPE_ACCESSIBILITY_OVERLAY，
 * 不需要悬浮窗权限）。不读取屏幕内容、不过滤按键；C 期起只订阅前台窗口切换事件，用事件里的应用标识判断敏感界面
 * （安装器、权限弹窗、无障碍设置等）并暂停注入（见 res/xml/remote_control_service.xml）。
 *
 * 服务本身不持有任何会话状态：没有已授权的投屏会话时它什么也不做；是否注入完全由 [SenderControlCoordinator] 决定。
 */
@AndroidEntryPoint
class RemoteControlService : AccessibilityService() {
    @Inject
    lateinit var bridge: AccessibilityBridge

    private var thread: HandlerThread? = null
    private var overlay: ControlOverlay? = null

    internal var injector: InputInjector? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        val t = HandlerThread("wsc-inject").also { it.start() }
        thread = t
        injector = A11yGestureInjector(this, Handler(t.looper)) { bridge.sinkPoint }
        bridge.attach(this)
    }

    /**
     * C 期：只处理前台窗口切换事件，只取事件元数据里的包名 / 类名（不调用 getSource()，也拿不到界面内容），
     * 交给协调器判断是否为敏感界面（安装器、无障碍设置）。【需真机验证】不申请读取窗口内容时 packageName 是否仍可用。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        bridge.onWindowState(e.packageName?.toString(), e.className?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        bridge.detach(this)
        hideOverlay()
        hideSink()
        injector?.clear()
        injector = null
        thread?.quitSafely()
        thread = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    internal fun showOverlay(state: OverlayState, onRevoke: () -> Unit, onContinue: () -> Unit = {}) {
        mainExecutor.execute {
            val o = overlay ?: ControlOverlay(this, getSystemService(WindowManager::class.java)) { rect -> bridge.overlayRect = rect }
                .also { overlay = it }
            o.show(state, onRevoke, onContinue)
        }
    }

    internal fun hideOverlay() {
        mainExecutor.execute {
            overlay?.hide()
            overlay = null
        }
    }

    private var sink: android.view.View? = null

    /**
     * 透明“吸收窗口”（8 dp，屏幕左上角，只在投屏会话期间存在）：中止进行中的笔画时，注入器把取消手势派发到这里，
     * 系统给原笔画发 ACTION_CANCEL，而取消手势本身被本窗口吞掉，不会点到任何应用（用户决策 D5）。
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    internal fun showSink() {
        mainExecutor.execute {
            if (sink != null) return@execute
            val size = (8 * resources.displayMetrics.density).toInt()
            val v = android.view.View(this).apply {
                setOnTouchListener { _, _ -> true }
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val p = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
                title = "wsc-control-sink"
                if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            val wm = getSystemService(WindowManager::class.java)
            if (runCatching { wm.addView(v, p) }.isFailure) return@execute
            sink = v
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                bridge.sinkPoint = com.benchan.wireless.screencasting.core.control.PointD(loc[0] + view.width / 2.0, loc[1] + view.height / 2.0)
                bridge.sinkRect = RectD(loc[0].toDouble(), loc[1].toDouble(), (loc[0] + view.width).toDouble(), (loc[1] + view.height).toDouble())
            }
        }
    }

    /** 会话结束后延迟移除，确保最后一个取消手势已被系统处理。 */
    internal fun hideSink(delayMs: Long = 0) {
        val r = Runnable {
            sink?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
            sink = null
            bridge.sinkPoint = null
            bridge.sinkRect = null
        }
        if (delayMs > 0) android.os.Handler(mainLooper).postDelayed(r, delayMs) else mainExecutor.execute(r)
    }

    /** 状态栏 / 通知栏区域（屏幕坐标），远端注入不能从这里开始（防止下拉通知栏）。 */
    internal fun statusBarRect(): RectD? {
        val wm = getSystemService(WindowManager::class.java) ?: return null
        return if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val top = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            RectD(0.0, 0.0, m.bounds.width().toDouble(), top.coerceAtLeast(minStatusBarPx()).toDouble())
        } else {
            @Suppress("DEPRECATION")
            val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
            RectD(0.0, 0.0, size.x.toDouble(), minStatusBarPx().toDouble())
        }
    }

    /** 状态栏高度兜底：系统资源（Android 10 没有 WindowMetrics）。 */
    private fun minStatusBarPx(): Int {
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) Resources.getSystem().getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }
}
