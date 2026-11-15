package com.benchan.wireless.screencasting.core.control

/** 方向。 */
enum class Direction { UP, DOWN, LEFT, RIGHT }

/** 光标顶 / 底边继续按：向上 / 下滚动（每次 1/3 屏）。 */
data class EdgeScroll(val dy: Double)

/** 一帧的结果：光标是否移动、是否触发了边缘滚动、是否正按住在顶 / 底边。 */
data class CursorFrame(val moved: Boolean, val scroll: EdgeScroll? = null, val atEdge: Boolean = false)

/**
 * TV 光标模式（交互稿 R10，PRD F23 B 期）。光标在接收端本地绘制（不进视频流，零延迟），限制在视频内容区。
 *
 * - 速度按时间计算（px / s，与帧率无关）：按下立即移动 [stepDp]（8 dp）；按住的前 [accelDelayMs]（300 ms）以 [slowDpPerSec]（120 dp/s）慢速连续移动，
 *   之后在 [rampMs]（600 ms）内线性加速到“约 1.0 s 横穿内容区长边”的最高速度，速度连续不跳变；松开立即停。
 * - 顶 / 底边继续按：按下时已在边缘立即滚动一次；按住在边缘满 [edgeHoldMs]（300 ms）后每 [edgeRepeatMs]（500 ms）滚动 ±1/3 屏。
 * - [fadeDelayMs]（3 s）无操作淡至 40%（淡出动画由界面做），任意键恢复。
 *
 * 坐标为视图像素；不是线程安全的（主线程驱动）。时间由调用方传入（毫秒，单调）。
 */
class TvCursorModel(
    density: Float,
    stepDp: Double = 8.0,
    slowDpPerSec: Double = 120.0,
    private val accelDelayMs: Long = 300,
    private val rampMs: Long = 600,
    private val edgeHoldMs: Long = 300,
    private val edgeRepeatMs: Long = 500,
    private val fadeDelayMs: Long = 3_000,
    private val crossSeconds: Double = 1.0,
) {
    private val stepPx = stepDp * density
    private val slowPxPerSec = slowDpPerSec * density

    private var bounds = RectD(0.0, 0.0, 0.0, 0.0)
    private var held: Direction? = null
    private var heldSince = 0L
    private var lastFrameAt = 0L
    private var lastActivity = 0L
    private var edgeSince = -1L
    private var lastScrollAt = Long.MIN_VALUE / 2

    var x: Double = 0.0
        private set
    var y: Double = 0.0
        private set

    /** 最高速度：约 [crossSeconds] 横穿内容区长边（不低于慢速）。 */
    val maxPxPerSec: Double get() = maxOf(maxOf(bounds.width, bounds.height) / crossSeconds, slowPxPerSec)

    /** 视频内容区变化（首帧、旋转、视图尺寸）：首次放到中心，之后按比例保持并夹回区域内。 */
    fun setBounds(rect: RectD) {
        if (rect.width <= 0 || rect.height <= 0) return
        val first = bounds.width <= 0
        if (first) {
            x = rect.left + rect.width / 2
            y = rect.top + rect.height / 2
        } else {
            val fx = (x - bounds.left) / bounds.width
            val fy = (y - bounds.top) / bounds.height
            x = rect.left + fx * rect.width
            y = rect.top + fy * rect.height
        }
        bounds = rect
        clamp()
    }

    /**
     * 方向键按下（只处理首次按下，调用方忽略系统按键重复）。
     * @return 光标已在顶 / 底边且按向外 → 立即滚动一次；否则 null（光标移动一步）。
     */
    fun keyDown(dir: Direction, now: Long): EdgeScroll? {
        lastActivity = now
        held = dir
        heldSince = now
        lastFrameAt = now
        val edge = edgeScroll(dir)
        if (edge != null) {
            edgeSince = now
            lastScrollAt = now
            return edge
        }
        edgeSince = -1
        move(dir, stepPx)
        return null
    }

    /** 该方向是否正被按住（系统按键重复到来时据此判断首次按下是否被吞掉）。 */
    fun isHeld(dir: Direction): Boolean = held == dir

    fun keyUp(dir: Direction, now: Long) {
        if (held == dir) held = null
        edgeSince = -1
        lastActivity = now
    }

    /** 任意键：恢复光标不透明度。 */
    fun touch(now: Long) {
        lastActivity = now
    }

    /** 每帧调用：按时间移动、在边缘按住时重复滚动。 */
    fun frame(now: Long): CursorFrame {
        val d = held ?: return CursorFrame(false)
        val dt = (now - lastFrameAt).coerceIn(0, MAX_FRAME_GAP_MS) / 1_000.0
        lastFrameAt = now
        lastActivity = now
        val bx = x
        val by = y
        move(d, speedPxPerSec(now) * dt)
        val moved = bx != x || by != y
        var scroll: EdgeScroll? = null
        val edge = edgeScroll(d)
        if (edge != null) {
            if (edgeSince < 0) edgeSince = now
            if (now - edgeSince >= edgeHoldMs && now - lastScrollAt >= edgeRepeatMs) {
                lastScrollAt = now
                scroll = edge
            }
        } else {
            edgeSince = -1
        }
        return CursorFrame(moved, scroll, atEdge = edge != null)
    }

    /** 当前透明度目标：3 s 无操作淡至 0.4。 */
    fun alpha(now: Long): Float = if (now - lastActivity >= fadeDelayMs) FADED_ALPHA else 1f

    /** 当前速度（px / s）：未按住为 0；前 300 ms 慢速；之后线性加速到最高速度。 */
    fun speedPxPerSec(now: Long): Double {
        if (held == null) return 0.0
        val heldFor = now - heldSince
        if (heldFor < accelDelayMs) return slowPxPerSec
        val t = ((heldFor - accelDelayMs).toDouble() / rampMs).coerceIn(0.0, 1.0)
        return slowPxPerSec + (maxPxPerSec - slowPxPerSec) * t
    }

    private fun edgeScroll(dir: Direction): EdgeScroll? = when {
        dir == Direction.UP && y <= bounds.top + EDGE_EPS -> EdgeScroll(-SCROLL_FRACTION)
        dir == Direction.DOWN && y >= bounds.bottom - EDGE_EPS -> EdgeScroll(SCROLL_FRACTION)
        else -> null
    }

    private fun move(dir: Direction, px: Double) {
        when (dir) {
            Direction.UP -> y -= px
            Direction.DOWN -> y += px
            Direction.LEFT -> x -= px
            Direction.RIGHT -> x += px
        }
        clamp()
    }

    private fun clamp() {
        if (bounds.width <= 0) return
        x = x.coerceIn(bounds.left, bounds.right)
        y = y.coerceIn(bounds.top, bounds.bottom)
    }

    companion object {
        const val SCROLL_FRACTION = 1.0 / 3
        const val FADED_ALPHA = 0.4f
        private const val EDGE_EPS = 0.5

        /** 帧间隔上限：卡顿后不一次跳太远。 */
        private const val MAX_FRAME_GAP_MS = 50L
    }
}
