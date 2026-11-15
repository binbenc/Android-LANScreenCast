package com.benchan.wireless.screencasting.core.control

import kotlin.math.hypot

/** 屏幕坐标上的一个采样；[dt] 是距本次手势 DOWN 的毫秒数（接收端时钟）。 */
data class TimedPoint(val x: Double, val y: Double, val dt: Long)

/** 交给注入器执行的命令（屏幕像素坐标）。 */
sealed interface GestureCommand {
    /** 一次完整的点击（按下后在 [durationMs] 内抬起）。 */
    data class Tap(val x: Double, val y: Double, val durationMs: Long) : GestureCommand

    /**
     * 一段笔画。[first] = 本次手势的第一段（按下）；[last] = 最后一段（抬起）；中间段为续接（willContinue）。
     * 续接段的第一个点一定等于上一段的最后一个点（AccessibilityService 的 continueStroke 要求）。
     */
    data class Segment(val points: List<TimedPoint>, val durationMs: Long, val first: Boolean, val last: Boolean) : GestureCommand

    /**
     * 中止进行中的笔画，**不得**产生点击 / 长按（用户决策 D5）：注入器派发一个新手势，让系统给进行中的笔画发 ACTION_CANCEL，
     * 而不是在最后位置抬起。
     */
    data object Abort : GestureCommand

    /**
     * 多指一次性手势（C 期 PINCH 宏）：[strokes] 中每一笔是一根手指的完整轨迹（屏幕像素），同时开始、同时在 [durationMs] 内结束。
     * 不使用多指续接（continueStroke 中途加指在各 ROM 上的可靠性无法保证），而是整段一次派发。
     */
    data class Multi(val strokes: List<List<TimedPoint>>, val durationMs: Long) : GestureCommand
}

/**
 * 发送端手势分段器（技术方案“反向控制”3 节）：把接收端的单指 DOWN / MOVE / UP 流变成可注入的命令。
 *
 * - DOWN 后先缓冲 [bufferMs]：期间抬起且几乎没动 → 一次 [GestureCommand.Tap]（点击延迟最低，也不走续接）；
 *   期间抬起但移动过 → 一段完整笔画（保留原始速度，fling 有效）。
 * - 超过缓冲时间仍按着 → 开始流式注入：第一段 first=true，此后每 [flushMs] 一段续接，抬起时 last=true。
 * - 段时长取接收端时间戳差（保留手势速度），夹在 1 ~ [maxSegmentMs]；
 *   落后超过 [catchUpMs]（注入跟不上）时把时长压到 [flushMs] 以追上。
 * - 按下后 [idleTimeoutMs] 收不到任何事件（接收端按住不动时每 250 ms 发一次保活 MOVE）→ 中止（[GestureCommand.Abort]）。
 * - 任何中止（取消、新的按下打断、超时）都输出 [GestureCommand.Abort]，从不在最后位置补抬起，避免误点。
 * - **正常抬起永远是 last=true 的续接段**（willContinue=false，系统发 ACTION_UP），只有中止才用 Abort。
 * - 续接段从不为零长度：静止按住 / 原地抬起改为 1 px 往返微动（见 [flush]）。
 *
 * 不是线程安全的：调用方单线程驱动；时间由 [clock] 提供（毫秒，单调）。
 */
class StrokeSegmenter(
    private val clock: () -> Long,
    private val bufferMs: Long = 30,
    private val flushMs: Long = 16,
    private val maxSegmentMs: Long = 300,
    private val catchUpMs: Long = 150,
    private val idleTimeoutMs: Long = 1_000,
    private val tapSlopPx: Double = 12.0,
    private val tapMaxMs: Long = 300,
) {
    private sealed interface State {
        data object Idle : State
        class Buffering(val downAt: Long, val samples: MutableList<TimedPoint>, var lastEventAt: Long) : State
        class Streaming(val downAt: Long, var last: TimedPoint, val pending: MutableList<TimedPoint>, var lastFlushAt: Long, var lastEventAt: Long) : State {
            /** 静止按住时微动的基准点（手指真实所在位置）。 */
            var anchor: TimedPoint? = null
        }
    }

    private var state: State = State.Idle

    /** 是否有手势进行中（按下未抬起）。 */
    val active: Boolean get() = state != State.Idle

    fun onDown(p: TimedPoint): List<GestureCommand> {
        val out = abort()
        val now = clock()
        state = State.Buffering(now, mutableListOf(p.copy(dt = 0)), now)
        return out
    }

    fun onMove(samples: List<TimedPoint>): List<GestureCommand> {
        if (samples.isEmpty()) return emptyList()
        val now = clock()
        return when (val s = state) {
            State.Idle -> emptyList()
            is State.Buffering -> {
                appendInOrder(s.samples, samples, s.samples.last().dt)
                s.lastEventAt = now
                if (now - s.downAt >= bufferMs) startStreaming(s, now) else emptyList()
            }
            is State.Streaming -> {
                appendInOrder(s.pending, samples, (s.pending.lastOrNull() ?: s.last).dt)
                s.lastEventAt = now
                if (now - s.lastFlushAt >= flushMs) flush(s, now, last = false) else emptyList()
            }
        }
    }

    fun onUp(p: TimedPoint): List<GestureCommand> {
        val now = clock()
        return when (val s = state) {
            State.Idle -> emptyList()
            is State.Buffering -> {
                state = State.Idle
                val pts = s.samples + p.copy(dt = maxOf(p.dt, s.samples.last().dt))
                val start = pts.first()
                val moved = pts.any { hypot(it.x - start.x, it.y - start.y) > tapSlopPx }
                val duration = pts.last().dt - start.dt
                if (!moved && duration <= tapMaxMs) {
                    listOf(GestureCommand.Tap(start.x, start.y, duration.coerceIn(MIN_TAP_MS, tapMaxMs)))
                } else {
                    listOf(GestureCommand.Segment(pts, duration.coerceIn(1, MAX_SINGLE_STROKE_MS), first = true, last = true))
                }
            }
            is State.Streaming -> {
                s.pending += p.copy(dt = maxOf(p.dt, (s.pending.lastOrNull() ?: s.last).dt))
                flush(s, now, last = true).also { state = State.Idle }
            }
        }
    }

    /** 接收端取消（CANCEL）或发送端需要中止当前手势（暂停、几何变化、会话结束）。 */
    fun onCancel(): List<GestureCommand> = abort()

    /** 定时调用（约每 8~16 ms）：缓冲到期开始流式注入、按时刷新续接段、空闲超时自动抬起。 */
    fun tick(): List<GestureCommand> {
        val now = clock()
        return when (val s = state) {
            State.Idle -> emptyList()
            is State.Buffering -> when {
                now - s.lastEventAt >= idleTimeoutMs -> abort()
                now - s.downAt >= bufferMs -> startStreaming(s, now)
                else -> emptyList()
            }
            is State.Streaming -> when {
                now - s.lastEventAt >= idleTimeoutMs -> abort()
                s.pending.isNotEmpty() && now - s.lastFlushAt >= flushMs -> flush(s, now, last = false)
                else -> emptyList()
            }
        }
    }

    /** 按时间戳顺序追加，丢弃比已有采样更早的（乱序 / 重复）。 */
    private fun appendInOrder(dst: MutableList<TimedPoint>, src: List<TimedPoint>, lastDt: Long) {
        var t = lastDt
        for (p in src) {
            if (p.dt < t) continue
            dst += p
            t = p.dt
        }
    }

    /** 中止：流式中输出 [GestureCommand.Abort]（取消而不是抬起）；缓冲中（还没注入任何东西）直接丢弃。 */
    private fun abort(): List<GestureCommand> {
        val s = state
        state = State.Idle
        return if (s is State.Streaming) {
            s.pending.clear()
            listOf(GestureCommand.Abort)
        } else {
            emptyList()
        }
    }

    private fun startStreaming(s: State.Buffering, now: Long): List<GestureCommand> {
        val pts = s.samples.toList()
        val duration = (pts.last().dt - pts.first().dt).coerceIn(1, maxSegmentMs)
        state = State.Streaming(s.downAt, pts.last(), mutableListOf(), now, s.lastEventAt)
        return listOf(GestureCommand.Segment(pts, duration, first = true, last = false))
    }

    private fun flush(s: State.Streaming, now: Long, last: Boolean): List<GestureCommand> {
        if (s.pending.isEmpty() && !last) return emptyList()
        val raw = listOf(s.last) + s.pending
        val end = raw.last()
        val pts: List<TimedPoint>
        var duration: Long
        // 手指是否移动：与手指真实位置比较（微动中的 s.last 偏离手指 1 px，不能拿它比）
        val finger = s.anchor ?: s.last
        val moved = s.pending.any { hypot(it.x - finger.x, it.y - finger.y) >= MIN_PATH_PX }
        if (moved) {
            pts = raw
            duration = (end.dt - s.last.dt).coerceIn(1, maxSegmentMs)
            // 注入落后于接收端时间轴：压缩时长追上（只影响追赶期间的速度）
            val lag = (now - s.downAt) - end.dt
            if (lag > catchUpMs) duration = duration.coerceAtMost(flushMs)
            s.anchor = null
        } else {
            // 静止（保活 MOVE / 原地抬起）：**不发零长度续接段**。模拟器（API 33）上静止按住后抬起不触发点击，
            // 零长度 continueStroke 是最可疑的环节（见开发进度 4.8.2）。改为在手指位置与其 1 px 邻点之间往返微动
            // （远小于触摸 slop，不会变成拖动），时长取 flushMs：静止的这段时间指针已经在真实时间里按着了，不需要重放。
            val base = finger
            val target = if (hypot(s.last.x - base.x, s.last.y - base.y) < MIN_PATH_PX) {
                base.copy(y = base.y + if (base.y >= 1.0) -NUDGE_PX else NUDGE_PX, dt = end.dt)
            } else {
                base.copy(dt = end.dt)
            }
            s.anchor = base
            pts = listOf(s.last, target)
            duration = flushMs
        }
        s.last = pts.last()
        s.pending.clear()
        s.lastFlushAt = now
        return listOf(GestureCommand.Segment(pts, duration, first = false, last = last))
    }

    private fun pathLength(points: List<TimedPoint>): Double =
        points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()

    private companion object {
        /** 系统识别为点击的最短按压时长（低于约 10 ms 部分 App 不认）。 */
        const val MIN_TAP_MS = 40L

        /** 缓冲期内完成的整段笔画（快速滑动 / fling）的时长上限。 */
        const val MAX_SINGLE_STROKE_MS = 2_000L

        /** 续接段的最短路径长度（px）：更短的视为静止，改用微动段，避免零长度续接。 */
        const val MIN_PATH_PX = 0.5

        /** 静止微动幅度（px）。 */
        const val NUDGE_PX = 1.0
    }
}
