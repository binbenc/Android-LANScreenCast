package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import kotlin.math.abs

/**
 * 键盘文字合并（PRD F23 B 期）：逐字键入的字符在 [windowMs]（50 ms）无新字符后合并为一条 TXT；
 * 遇到其他按键前先 [flush]（保证顺序）；单条不超过 [InputCodec.MAX_TEXT_CHARS]。不是线程安全的。
 */
class TextCoalescer(private val clock: () -> Long, private val windowMs: Long = 50) {
    private val buf = StringBuilder()
    private var lastAt = 0L

    val pending: Boolean get() = buf.isNotEmpty()

    /** 追加一个字符；缓冲达到上限时立即返回要发送的文本。 */
    fun type(c: Char): String? {
        buf.append(c)
        lastAt = clock()
        return if (buf.length >= InputCodec.MAX_TEXT_CHARS) flush() else null
    }

    /** 定时调用：窗口到期返回要发送的文本。 */
    fun tick(): String? = if (buf.isNotEmpty() && clock() - lastAt >= windowMs) flush() else null

    /** 立即取出缓冲（其他按键之前、控制结束时）。 */
    fun flush(): String? {
        if (buf.isEmpty()) return null
        val s = buf.toString()
        buf.setLength(0)
        return s
    }

    companion object {
        /** 整段文本（R07“输入文字”发送）按上限切分为多条 TXT，不拆开代理对。 */
        fun chunks(text: String, max: Int = InputCodec.MAX_TEXT_CHARS): List<String> {
            if (text.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                var end = (i + max).coerceAtMost(text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                out += text.substring(i, end)
                i = end
            }
            return out
        }
    }
}

/**
 * 鼠标滚轮方向约定：Android `MotionEvent.AXIS_VSCROLL` > 0 = 滚轮向上（远离用户）= 向上滚动；
 * Compose 的 `PointerInputChange.scrollDelta.y` 已取反（> 0 = 向下滚动），接收端直接用它作为“格数”（> 0 = 向下滚动）。
 */
object WheelDirection {
    /** 原生 AXIS_VSCROLL → 格数（> 0 = 向下滚动 / dy > 0）。 */
    fun notchesFromAxisVScroll(axisVScroll: Float): Double = -axisVScroll.toDouble()

    /** Compose scrollDelta.y → 格数（同号）。 */
    fun notchesFromComposeScrollDelta(scrollDeltaY: Float): Double = scrollDeltaY.toDouble()
}

/**
 * 鼠标滚轮合并（PRD F23 B 期）：同一方向在 [windowMs]（50 ms，从第一格起）内的多格合并为一条 W，
 * 每格 [perNotch]（内容高度的 8%），单条 |dy| 不超过 [maxDy]。方向变化时先发出已累积的。不是线程安全的。
 */
class WheelCoalescer(
    private val clock: () -> Long,
    private val windowMs: Long = 50,
    private val perNotch: Double = 0.08,
    private val maxDy: Double = 0.6,
) {
    private var notches = 0.0
    private var firstAt = 0L
    private var nx = 0.0
    private var ny = 0.0

    /** 待发送的滚动。 */
    data class Scroll(val x: Double, val y: Double, val dy: Double)

    /**
     * 一次滚轮事件：[deltaNotches] > 0 = 向下滚动（看下面的内容）。[x] / [y] 为归一化指针位置。
     * @return 方向变化或累积到上限时立即要发送的滚动。
     */
    fun scroll(x: Double, y: Double, deltaNotches: Double): Scroll? {
        if (deltaNotches == 0.0) return null
        var out: Scroll? = null
        if (notches != 0.0 && (notches > 0) != (deltaNotches > 0)) out = take()
        if (notches == 0.0) firstAt = clock()
        notches += deltaNotches
        nx = x
        ny = y
        if (abs(notches * perNotch) >= maxDy) out = take()
        return out
    }

    /** 定时调用：窗口到期返回累积的滚动。 */
    fun tick(): Scroll? = if (notches != 0.0 && clock() - firstAt >= windowMs) take() else null

    private fun take(): Scroll? {
        if (notches == 0.0) return null
        val dy = (notches * perNotch).coerceIn(-maxDy, maxDy)
        notches = 0.0
        return Scroll(nx, ny, dy)
    }
}

/**
 * 发送端：把一次滚动（W）合成为可注入的滑动（PRD F23 B 期）。
 *
 * - 方向：dy > 0（向下滚动、看下面的内容）→ 手指自下而上；dy < 0（向上滚动）→ 手指自上而下。
 * - 手指沿竖直方向移动 |dy| × 屏高，以指针位置为中心，整体平移到屏幕上下各 [marginFraction]（12%）的安全区内，
 *   避开状态栏下拉与底部手势导航区域；横向夹在 10%~90%。
 * - 锚点在屏幕顶 / 底 [edgeZoneFraction]（20%）内时（TV 光标顶 / 底边继续按、鼠标贴边滚动），滑动中心移到屏幕 40% / 60% 高度：
 *   顶 / 底部通常是标题栏、可折叠头部或底栏，在那里拖动不会滚动列表（模拟器复测：顶边向上滚动在设置的标题栏上起手，列表不动）。
 * - 两段：先 [moveMs] 内滑到终点（按住），再 [settleMs] 1 px 微动后抬起，抬起时几乎静止，不触发惯性甩动，滚动距离可预期。
 */
object WheelSynthesizer {
    fun synthesize(
        x: Double,
        y: Double,
        dy: Double,
        screenW: Int,
        screenH: Int,
        marginFraction: Double = 0.12,
        edgeZoneFraction: Double = 0.2,
        moveMs: Long = 200,
        settleMs: Long = 80,
    ): List<GestureCommand> {
        if (screenW <= 0 || screenH <= 0 || dy == 0.0) return emptyList()
        val top = screenH * marginFraction
        val bottom = screenH * (1 - marginFraction)
        val maxLen = bottom - top
        val len = (abs(dy) * screenH).coerceAtMost(maxLen)
        val sign = if (dy > 0) -1.0 else 1.0 // dy > 0：手指上滑
        // 贴近顶 / 底边的锚点把滑动中心移向屏幕中部
        val center = when {
            y < screenH * edgeZoneFraction -> screenH * 0.4
            y > screenH * (1 - edgeZoneFraction) -> screenH * 0.6
            else -> y
        }
        var startY = center + len / 2 * -sign
        var endY = startY + sign * len
        // 整体平移进安全区
        val lo = minOf(startY, endY)
        val hi = maxOf(startY, endY)
        val shift = when {
            lo < top -> top - lo
            hi > bottom -> bottom - hi
            else -> 0.0
        }
        startY += shift
        endY += shift
        val cx = x.coerceIn(screenW * 0.1, screenW * 0.9)
        val start = TimedPoint(cx, startY, 0)
        val end = TimedPoint(cx, endY, moveMs)
        val settle = TimedPoint(cx, endY + if (endY >= 1.0) -1.0 else 1.0, moveMs + settleMs)
        return listOf(
            GestureCommand.Segment(listOf(start, end), moveMs, first = true, last = false),
            GestureCommand.Segment(listOf(end, settle), settleMs, first = false, last = true),
        )
    }
}
