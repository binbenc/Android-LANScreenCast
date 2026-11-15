package com.benchan.wireless.screencasting.feature.control

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.benchan.wireless.screencasting.core.control.RectD
import com.benchan.wireless.screencasting.core.ui.theme.inversePrimaryDark
import com.benchan.wireless.screencasting.core.ui.theme.inversePrimaryLight
import com.benchan.wireless.screencasting.core.ui.theme.inverseOnSurfaceDark
import com.benchan.wireless.screencasting.core.ui.theme.inverseOnSurfaceLight
import com.benchan.wireless.screencasting.core.ui.theme.inverseSurfaceDark
import com.benchan.wireless.screencasting.core.ui.theme.inverseSurfaceLight
import com.benchan.wireless.screencasting.core.ui.theme.warningDark
import com.benchan.wireless.screencasting.core.ui.theme.warningLight
import kotlin.math.abs

/**
 * 屏幕边缘胶囊（交互稿 R05）：由无障碍服务添加 TYPE_ACCESSIBILITY_OVERLAY 窗口，**不申请悬浮窗权限**。
 *
 * - 贴右边缘，胶囊高 40 dp（窗口 / 触控区 48 dp），可上下拖动、松手吸边（只能停在右边缘）；不可关闭，唯一操作是“收回”。
 * - inverseSurface 底 + inverseOnSurface 字 + inversePrimary“收回”；暂停态圆点为 warning 色。
 * - 胶囊会被录进镜像画面（接收端也能看到“正在被控制”）；远端注入落在胶囊矩形内直接丢弃（[onRect] 上报屏幕矩形）。
 *
 * 只在主线程调用。
 */
@SuppressLint("ViewConstructor")
internal class ControlOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val onRect: (RectD?) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var dot: View? = null
    private var label: TextView? = null
    private var revoke: TextView? = null
    private var onRevoke: () -> Unit = {}
    private var onContinue: () -> Unit = {}
    private var continueView: TextView? = null
    private var continueActionId: Int = View.NO_ID
    private var params: WindowManager.LayoutParams? = null
    private var state: OverlayState? = null

    /** 敏感界面暂停前的位置；非 null = 胶囊已为系统对话框让位（模拟器走查：62% 处盖住权限弹窗的“拒绝”）。 */
    private var yBeforeSensitive: Int? = null

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private val dark: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun show(s: OverlayState, revokeAction: () -> Unit, continueAction: () -> Unit = {}) {
        onRevoke = revokeAction
        onContinue = continueAction
        val wasSensitive = state?.canContinue == true
        state = s
        if (root == null) attach()
        bind(s)
        if (s.canContinue != wasSensitive) yieldToDialog(s.canContinue)
    }

    /**
     * 敏感界面多为居中对话框（权限 / 安装 / 授权），按钮落在屏幕 50–70% 高度：暂停期间把胶囊挪到 85%，
     * 离开后回到原位；暂停期间用户拖动过则保留用户位置。底部弹出式对话框仍可能被盖住，可拖动。
     */
    private fun yieldToDialog(sensitive: Boolean) {
        val p = params ?: return
        val v = root ?: return
        if (sensitive) {
            yBeforeSensitive = p.y
            p.y = clampY((screenHeight() * 0.85f).toInt())
        } else {
            p.y = clampY(yBeforeSensitive ?: return)
            yBeforeSensitive = null
        }
        runCatching { wm.updateViewLayout(v, p) }
        v.post { reportRect() }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
        yBeforeSensitive = null
        state = null
        onRect(null)
    }

    fun onConfigurationChanged() {
        val s = state ?: return
        // 深浅色 / 旋转变化：重新套色并把位置夹回屏幕内
        bind(s)
        params?.let { p ->
            p.y = clampY(p.y)
            root?.let { runCatching { wm.updateViewLayout(it, p) } }
        }
        root?.post { reportRect() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach() {
        val capsule = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(6), 0)
        }
        val d = View(context)
        capsule.addView(d, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        val l = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            maxLines = 1
            // 文案过长时省略，“收回”优先保证完整（UX 走查 G9）
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.6f).toInt()
        }
        capsule.addView(l)
        // “继续”（D-3 兜底）：只在因敏感界面暂停时出现，只有手机本人能点（远端注入落在胶囊内被丢弃）
        val cont = TextView(context).apply {
            text = context.getString(R.string.ctl_overlay_continue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(32)
            minWidth = dp(48)
            visibility = View.GONE
            setOnClickListener { onContinue() }
        }
        capsule.addView(cont, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(4) })
        val r = TextView(context).apply {
            text = context.getString(R.string.ctl_overlay_revoke)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            minHeight = dp(32)
        }
        capsule.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(8) })
        val frame = FrameLayout(context).apply {
            addView(capsule, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(40), Gravity.CENTER_VERTICAL or Gravity.END))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            // 默认放在屏幕 62% 高度：避开投屏页 R04 控制卡的“收回控制 / 回到桌面”按钮（真机走查）
            y = clampY((screenHeight() * 0.62f).toInt())
            title = "wsc-control-capsule"
        }
        frame.setOnTouchListener(DragListener(r))
        // TalkBack 双击 = performClick：整块胶囊即“收回”（触摸由 DragListener 处理，不会走到这里）
        frame.setOnClickListener { onRevoke() }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportRect() }
        runCatching { wm.addView(frame, p) }.onFailure { return }
        root = frame
        continueView = cont
        dot = d
        label = l
        revoke = r
        params = p
    }

    private fun bind(s: OverlayState) {
        val capsule = (root?.getChildAt(0) as? LinearLayout) ?: return
        val bg = if (dark) inverseSurfaceDark else inverseSurfaceLight
        val fg = if (dark) inverseOnSurfaceDark else inverseOnSurfaceLight
        val accent = if (dark) inversePrimaryDark else inversePrimaryLight
        // 反相底上的 warning 用反相 token：浅色主题用 warningDark、深色主题用 warningLight（UX 走查 G8）
        val warn = if (dark) warningLight else warningDark
        capsule.background = GradientDrawable().apply {
            setColor(bg.toArgb())
            val r = dp(20).toFloat()
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        }
        capsule.elevation = dp(6).toFloat()
        dot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((if (s.paused) warn else accent).toArgb())
        }
        label?.apply {
            text = s.label
            setTextColor(fg.toArgb())
        }
        revoke?.setTextColor(accent.toArgb())
        continueView?.apply {
            // “继续”中性色（inverseOnSurface），“收回”仍为 inversePrimary 且在最外侧（交互稿 v1.0.4）
            setTextColor(fg.toArgb())
            visibility = if (s.canContinue) View.VISIBLE else View.GONE
        }
        root?.contentDescription = if (s.canContinue) context.getString(R.string.ctl_overlay_paused_desc) else s.label
        root?.let { v ->
            // 敏感暂停：默认点击仍为“收回控制”，另加自定义操作“继续控制”（避免读屏用户误触“继续”）
            if (continueActionId != View.NO_ID) {
                androidx.core.view.ViewCompat.removeAccessibilityAction(v, continueActionId)
                continueActionId = View.NO_ID
            }
            if (s.canContinue) {
                continueActionId = androidx.core.view.ViewCompat.addAccessibilityAction(v, context.getString(R.string.ctl_overlay_continue_action)) { _, _ -> onContinue(); true }
            }
            // TalkBack：把点按动作命名为“收回控制”（UX 建议 A8）
            androidx.core.view.ViewCompat.replaceAccessibilityAction(
                v, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.ctl_overlay_revoke_action),
            ) { _, _ -> onRevoke(); true }
        }
    }

    private fun reportRect() {
        val v = root ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        onRect(RectD(loc[0].toDouble(), loc[1].toDouble(), (loc[0] + v.width).toDouble(), (loc[1] + v.height).toDouble()))
    }

    private fun screenHeight(): Int = if (android.os.Build.VERSION.SDK_INT >= 30) {
        wm.currentWindowMetrics.bounds.height()
    } else {
        @Suppress("DEPRECATION")
        android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }.y
    }

    /** 夹在状态栏 / 挖孔之下、导航栏之上（UX 走查 G9）。 */
    private fun clampY(y: Int): Int {
        val (top, bottom) = verticalInsets()
        val min = top + dp(8)
        val max = (screenHeight() - bottom - dp(48) - dp(8)).coerceAtLeast(min)
        return y.coerceIn(min, max)
    }

    private fun verticalInsets(): Pair<Int, Int> {
        if (android.os.Build.VERSION.SDK_INT < 30) return dp(48) to dp(48)
        val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        return insets.top to insets.bottom
    }

    /** 上下拖动吸边；没有拖动的点按：点在“收回”上 = 收回（TalkBack 下整块胶囊点按即收回）。 */
    private inner class DragListener(private val revokeView: View) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawY = 0f
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startY = p.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (!dragging && abs(dy) > slop) dragging = true
                    if (dragging) {
                        p.y = clampY(startY + dy.toInt())
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        yBeforeSensitive = null // 用户自己选的位置优先，结束暂停时不再挪回
                        p.y = clampY(p.y)
                        runCatching { wm.updateViewLayout(v, p) }
                        v.post { reportRect() }
                    } else {
                        val loc = IntArray(2)
                        revokeView.getLocationOnScreen(loc)
                        val onRevokeView = e.rawX >= loc[0] - dp(4) && e.rawX <= loc[0] + revokeView.width + dp(8)
                        val cv = continueView
                        val onContinueView = cv != null && cv.visibility == View.VISIBLE && run {
                            val c = IntArray(2)
                            cv.getLocationOnScreen(c)
                            e.rawX >= c[0] - dp(4) && e.rawX < loc[0] - dp(4)
                        }
                        when {
                            onRevokeView -> onRevoke()
                            onContinueView -> onContinue()
                        }
                    }
                }
            }
            return true
        }
    }
}
