package com.benchan.wireless.screencasting.core.control

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 一次捏合的计划（与 PINCH 消息字段一一对应，归一化）：中点 ([x], [y])、起止两指间距 [from] / [to]（相对内容宽度）、
 * 两指连线角度 [ang]（弧度）、时长 [dur]（毫秒）。
 */
data class PinchPlan(val x: Double, val y: Double, val from: Double, val to: Double, val ang: Double, val dur: Long)

/**
 * 接收端：跟踪两根手指的捏合（PRD F23 C 期）。坐标为归一化值，[frameW] / [frameH] 用于把间距换算成“相对内容宽度”。
 * 两指间距变化小于 [minRatioChange]（5%）视为双指轻点 / 双指拖动，不发送（双指平移不在本期范围）。
 */
class PinchTracker(private val clock: () -> Long, private val minRatioChange: Double = 0.05) {
    private var startAt = 0L
    private var startA: PointD? = null
    private var startB: PointD? = null
    private var lastA: PointD? = null
    private var lastB: PointD? = null
    private var fw = 1
    private var fh = 1

    val active: Boolean get() = startA != null

    fun start(a: PointD, b: PointD, frameW: Int, frameH: Int) {
        startAt = clock()
        startA = a
        startB = b
        lastA = a
        lastB = b
        fw = frameW.coerceAtLeast(1)
        fh = frameH.coerceAtLeast(1)
    }

    fun update(a: PointD, b: PointD) {
        if (!active) return
        lastA = a
        lastB = b
    }

    /** 任一手指抬起：结束并返回要发送的捏合（变化太小返回 null）。 */
    fun end(): PinchPlan? {
        val a0 = startA ?: return null
        val b0 = startB!!
        val a1 = lastA!!
        val b1 = lastB!!
        startA = null
        val from = span(a0, b0)
        val to = span(a1, b1)
        if (from <= 0.0 || to <= 0.0 || abs(to / from - 1) < minRatioChange) return null
        val cx = (a0.x + b0.x) / 2
        val cy = (a0.y + b0.y) / 2
        val ang = atan2((b0.y - a0.y) * fh, (b0.x - a0.x) * fw)
        val dur = (clock() - startAt).coerceIn(MIN_DUR, MAX_DUR)
        return PinchPlan(cx.coerceIn(0.0, 1.0), cy.coerceIn(0.0, 1.0), from.coerceIn(MIN_SPAN, MAX_SPAN), to.coerceIn(MIN_SPAN, MAX_SPAN), ang, dur)
    }

    fun cancel() {
        startA = null
    }

    private fun span(a: PointD, b: PointD): Double = hypot((b.x - a.x) * fw, (b.y - a.y) * fh) / fw

    companion object {
        /**
         * 捏合时长 200–400 ms（PM G7）：时长取自用户双指的实际用时，但一次性注入的整段手势末端仍在匀速移动，
         * 太短会被地图等应用当作快速甩动触发惯性缩放，太长则抬手后要等很久才看到结果（反馈延迟）。
         * 不加末端停顿：多指一次性手势无法在一笔内停顿，而多指续接正是本方案回避的不可靠路径。
         */
        const val MIN_DUR = 200L
        const val MAX_DUR = 400L
        const val MIN_SPAN = 0.02
        const val MAX_SPAN = 2.0
    }
}

/**
 * 鼠标 Ctrl + 滚轮 → 捏合（PRD F23 C 期）：[windowMs]（50 ms）内的多格合并为一次捏合，每格缩放 [perNotch] 倍；
 * 滚轮向上（格数 < 0）= 放大。以指针为中心、起始间距 [baseSpan]（内容宽度的 25%）。不是线程安全的。
 */
class PinchWheelCoalescer(
    private val clock: () -> Long,
    private val windowMs: Long = 50,
    private val perNotch: Double = 1.25,
    private val baseSpan: Double = 0.25,
) {
    private var notches = 0.0
    private var firstAt = 0L
    private var nx = 0.5
    private var ny = 0.5

    fun scroll(x: Double, y: Double, deltaNotches: Double) {
        if (deltaNotches == 0.0) return
        if (notches == 0.0) firstAt = clock()
        notches += deltaNotches
        nx = x
        ny = y
    }

    fun tick(): PinchPlan? {
        if (notches == 0.0 || clock() - firstAt < windowMs) return null
        val factor = perNotch.pow(-notches).coerceIn(0.2, 5.0)
        notches = 0.0
        val to = (baseSpan * factor).coerceIn(PinchTracker.MIN_SPAN, 1.5)
        return PinchPlan(nx, ny, baseSpan, to, 0.0, 250)
    }
}

/**
 * 发送端：把 PINCH 合成为两根手指同时移动的一次性手势（屏幕像素）。
 * 两指沿 [ang] 方向对称分布在中点两侧；整体先平移、必要时再等比缩小间距，保证四个端点都在安全区内
 * （上下 [marginFraction] 12%、左右 6%，避开状态栏下拉与手势导航）。
 */
object PinchSynthesizer {
    fun synthesize(
        cx: Double,
        cy: Double,
        fromPx: Double,
        toPx: Double,
        ang: Double,
        durMs: Long,
        screenW: Int,
        screenH: Int,
        marginFraction: Double = 0.12,
    ): GestureCommand.Multi? {
        if (screenW <= 0 || screenH <= 0 || fromPx <= 0 || toPx <= 0) return null
        val left = screenW * 0.06
        val right = screenW * 0.94
        val top = screenH * marginFraction
        val bottom = screenH * (1 - marginFraction)
        val ux = cos(ang)
        val uy = sin(ang)
        // 能容纳的最大间距（沿 ang 方向穿过安全区）
        val maxSpan = minOf(
            if (abs(ux) > 1e-6) (right - left) / abs(ux) else Double.MAX_VALUE,
            if (abs(uy) > 1e-6) (bottom - top) / abs(uy) else Double.MAX_VALUE,
        ) * 0.98
        val big = maxOf(fromPx, toPx)
        val k = if (big > maxSpan) maxSpan / big else 1.0
        val f = fromPx * k
        val t = toPx * k
        val halfX = big * k / 2 * abs(ux)
        val halfY = big * k / 2 * abs(uy)
        val x = cx.coerceIn(left + halfX, right - halfX)
        val y = cy.coerceIn(top + halfY, bottom - halfY)
        val d = durMs.coerceIn(50, 1000)
        fun p(span: Double, sign: Double, time: Long) = TimedPoint(x + sign * span / 2 * ux, y + sign * span / 2 * uy, time)
        val a = listOf(p(f, -1.0, 0), p(t, -1.0, d))
        val b = listOf(p(f, 1.0, 0), p(t, 1.0, d))
        return GestureCommand.Multi(listOf(a, b), d)
    }
}

/** 由间距比值求缩放倍数（测试 / 日志用）。 */
fun PinchPlan.zoomFactor(): Double = to / from
