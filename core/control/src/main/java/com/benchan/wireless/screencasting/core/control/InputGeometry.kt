package com.benchan.wireless.screencasting.core.control

import kotlin.math.abs
import kotlin.math.min

/** 浮点点。 */
data class PointD(val x: Double, val y: Double)

/** 浮点矩形（左上闭、右下开）。 */
data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    operator fun contains(p: PointD): Boolean = p.x >= left && p.x < right && p.y >= top && p.y < bottom
}

/**
 * 发送端一次采集几何（PROTOCOL.md 6.3，对应 GEOM 消息）。
 *
 * @property captureWidth 采集帧宽（16 对齐后的 VirtualDisplay 尺寸，即编码帧尺寸）。
 * @property contentWidth 被采集内容的逻辑像素宽：整屏共享 = 屏幕宽；单应用共享 = 被共享窗口宽。
 */
data class CaptureGeometry(
    val gen: Int,
    val captureWidth: Int,
    val captureHeight: Int,
    val contentWidth: Int,
    val contentHeight: Int,
)

/**
 * 反向控制坐标换算（技术方案“反向控制”2 节）。
 *
 * 链路：接收端触点 →（去掉接收端 ASPECT_FIT 黑边）→ 相对解码帧的 0..1 归一化坐标 →
 * 发送端：帧内容区（去掉 16 对齐造成的几像素黑边：系统镜像时按原比例居中缩放内容）→ 内容逻辑像素 → 屏幕坐标。
 * WebRTC 自适应降分辨率只等比缩小帧，归一化坐标不受影响；用宽高比容差匹配几何代号。
 */
object InputGeometry {
    /** 帧宽高比与几何宽高比的相对容差：WebRTC 自适应缩放会按 2 / 4 对齐，宽高比有 <1% 的偏差。 */
    const val ASPECT_TOLERANCE = 0.015

    private const val EDGE_SLACK = 0.5

    /**
     * 接收端：触点（视图像素）→ 解码帧归一化坐标。视频按 SCALE_ASPECT_FIT 居中显示；落在黑边上返回 null。
     */
    fun viewToNormalized(viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int, x: Float, y: Float): PointD? {
        val rect = fitRect(viewWidth.toDouble(), viewHeight.toDouble(), frameWidth.toDouble(), frameHeight.toDouble()) ?: return null
        val p = PointD(x.toDouble(), y.toDouble())
        // 半个像素容差、右 / 下边缘按闭区间：手指正好压在视频边缘也算内容区
        if (p.x < rect.left - EDGE_SLACK || p.x > rect.right + EDGE_SLACK || p.y < rect.top - EDGE_SLACK || p.y > rect.bottom + EDGE_SLACK) return null
        return PointD(((p.x - rect.left) / rect.width).coerceIn(0.0, 1.0), ((p.y - rect.top) / rect.height).coerceIn(0.0, 1.0))
    }

    /** 接收端：视频在视图里的显示矩形（ASPECT_FIT 居中），用于画涟漪、判断黑边。 */
    fun videoRectInView(viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int): RectD? =
        fitRect(viewWidth.toDouble(), viewHeight.toDouble(), frameWidth.toDouble(), frameHeight.toDouble())

    /** 发送端：内容在采集帧里的矩形（按原比例居中缩放）。 */
    fun contentRectInCapture(g: CaptureGeometry): RectD? =
        fitRect(g.captureWidth.toDouble(), g.captureHeight.toDouble(), g.contentWidth.toDouble(), g.contentHeight.toDouble())

    /**
     * 发送端：归一化坐标 → 内容逻辑像素。落在采集帧黑边上返回 null。
     * 结果夹到 `[0, content - 1]`，避免注入到屏幕外。
     */
    fun normalizedToContent(g: CaptureGeometry, nx: Double, ny: Double): PointD? {
        val rect = contentRectInCapture(g) ?: return null
        val fx = nx * g.captureWidth
        val fy = ny * g.captureHeight
        // 半个像素的容差：边缘触点四舍五入后可能落在黑边外侧一点点
        if (fx < rect.left - 0.5 || fx > rect.right + 0.5 || fy < rect.top - 0.5 || fy > rect.bottom + 0.5) return null
        val scale = rect.width / g.contentWidth
        val x = ((fx - rect.left) / scale).coerceIn(0.0, g.contentWidth - 1.0)
        val y = ((fy - rect.top) / scale).coerceIn(0.0, g.contentHeight - 1.0)
        return PointD(x, y)
    }

    /**
     * 发送端：滚动锚点（W）专用——归一化坐标 → 内容逻辑像素，落在采集帧黑边上时**夹到最近的内容边缘**而不是拒绝。
     * 滚动最终合成为屏幕中部安全区内的滑动，锚点只用于选择滚动的位置，顶 / 底边（y = 0 / 1）与黑边都应能滚动。
     * 只有几何无效（尺寸为 0）时返回 null。
     */
    fun normalizedToContentClamped(g: CaptureGeometry, nx: Double, ny: Double): PointD? {
        val rect = contentRectInCapture(g) ?: return null
        val fx = (nx.coerceIn(0.0, 1.0) * g.captureWidth).coerceIn(rect.left, rect.right)
        val fy = (ny.coerceIn(0.0, 1.0) * g.captureHeight).coerceIn(rect.top, rect.bottom)
        val scale = rect.width / g.contentWidth
        val x = ((fx - rect.left) / scale).coerceIn(0.0, g.contentWidth - 1.0)
        val y = ((fy - rect.top) / scale).coerceIn(0.0, g.contentHeight - 1.0)
        return PointD(x, y)
    }

    /** 宽高比是否一致（相对误差 ≤ [tolerance]）。 */
    fun aspectMatches(w1: Int, h1: Int, w2: Int, h2: Int, tolerance: Double = ASPECT_TOLERANCE): Boolean {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false
        val a = w1.toDouble() / h1
        val b = w2.toDouble() / h2
        return abs(a - b) / b <= tolerance
    }

    /**
     * 接收端：为当前渲染帧挑选几何代号 —— 最近一次宽高比一致的 GEOM。都不一致（旋转瞬间收到旧帧）返回 null，此时不发送输入。
     */
    fun pickGeneration(frameWidth: Int, frameHeight: Int, candidates: List<CaptureGeometry>): Int? =
        candidates.lastOrNull { aspectMatches(frameWidth, frameHeight, it.captureWidth, it.captureHeight) }?.gen

    /**
     * 发送端：被采集内容是否就是整块屏幕（允许 [slackPx] 像素误差，两种朝向都接受）。
     * 不是全屏（分屏 / 小窗的单应用共享）时拿不到窗口在屏幕上的偏移，控制暂停（WINDOWED）。
     */
    fun coversScreen(contentWidth: Int, contentHeight: Int, screenWidth: Int, screenHeight: Int, slackPx: Int = 2): Boolean =
        abs(contentWidth - screenWidth) <= slackPx && abs(contentHeight - screenHeight) <= slackPx

    private fun fitRect(outerW: Double, outerH: Double, innerW: Double, innerH: Double): RectD? {
        if (outerW <= 0 || outerH <= 0 || innerW <= 0 || innerH <= 0) return null
        val s = min(outerW / innerW, outerH / innerH)
        val w = innerW * s
        val h = innerH * s
        val left = (outerW - w) / 2
        val top = (outerH - h) / 2
        return RectD(left, top, left + w, top + h)
    }
}
