package com.benchan.wireless.screencasting.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSegmenterTest {
    private var now = 0L
    private val seg = StrokeSegmenter({ now })

    @Test
    fun quickTapBecomesSingleTap() {
        assertTrue(seg.onDown(TimedPoint(100.0, 200.0, 0)).isEmpty())
        now += 10
        val out = seg.onUp(TimedPoint(102.0, 201.0, 60))
        assertEquals(listOf(GestureCommand.Tap(100.0, 200.0, 60)), out)
        assertFalse(seg.active)
    }

    @Test
    fun veryShortTapIsStretchedToMinimum() {
        seg.onDown(TimedPoint(1.0, 1.0, 0))
        val tap = seg.onUp(TimedPoint(1.0, 1.0, 5)).single() as GestureCommand.Tap
        assertEquals(40L, tap.durationMs)
    }

    @Test
    fun flingInsideBufferKeepsOriginalTiming() {
        seg.onDown(TimedPoint(500.0, 1500.0, 0))
        now += 10
        seg.onMove(listOf(TimedPoint(500.0, 1300.0, 8), TimedPoint(500.0, 1000.0, 16)))
        now += 10
        val out = seg.onUp(TimedPoint(500.0, 800.0, 24)).single() as GestureCommand.Segment
        assertTrue(out.first && out.last)
        assertEquals(24L, out.durationMs)
        assertEquals(4, out.points.size)
    }

    @Test
    fun longDragStreamsContinuousSegments() {
        seg.onDown(TimedPoint(100.0, 100.0, 0))
        now = 40
        val first = seg.tick().single() as GestureCommand.Segment
        assertTrue(first.first)
        assertFalse(first.last)
        now = 50
        assertTrue(seg.onMove(listOf(TimedPoint(100.0, 150.0, 48))).isEmpty()) // 未到刷新间隔
        now = 60
        val mid = seg.tick().single() as GestureCommand.Segment
        assertFalse(mid.first || mid.last)
        // 续接段从上一段终点开始
        assertEquals(first.points.last(), mid.points.first())
        assertEquals(48L, mid.durationMs)
        now = 80
        val end = seg.onUp(TimedPoint(100.0, 200.0, 78)).single() as GestureCommand.Segment
        assertTrue(end.last)
        assertEquals(mid.points.last(), end.points.first())
        assertEquals(30L, end.durationMs)
        assertFalse(seg.active)
    }

    @Test
    fun stationaryLongPressHeldByKeepAlive() {
        seg.onDown(TimedPoint(300.0, 300.0, 0))
        now = 30
        assertTrue((seg.tick().single() as GestureCommand.Segment).first)
        now = 250
        seg.onMove(listOf(TimedPoint(300.0, 300.0, 250)))
        now = 500
        seg.onMove(listOf(TimedPoint(300.0, 300.0, 500)))
        now = 700
        val up = seg.onUp(TimedPoint(300.0, 300.0, 700)).single() as GestureCommand.Segment
        assertTrue(up.last)
        assertTrue(seg.tick().isEmpty())
    }

    @Test
    fun idleTimeoutAbortsWithoutLifting() {
        seg.onDown(TimedPoint(10.0, 20.0, 0))
        now = 30
        seg.tick()
        now = 1_100
        assertEquals(listOf(GestureCommand.Abort), seg.tick())
        assertFalse(seg.active)
    }

    /** 用户决策 D5：发送端发起的中止（收回 / 锁屏 / 暂停 / 旋转 / 会话结束）不得在最后位置补抬起。 */
    @Test
    fun senderAbortNeverEmitsLiftOrTap() {
        // 长按中被中止
        seg.onDown(TimedPoint(300.0, 300.0, 0))
        now = 30
        seg.tick()
        now = 600
        seg.onMove(listOf(TimedPoint(300.0, 300.0, 570)))
        val out = seg.onCancel()
        assertEquals(listOf(GestureCommand.Abort), out)
        assertTrue(out.none { it is GestureCommand.Tap || (it is GestureCommand.Segment && it.last) })
        // 中止后迟到的抬起不再注入
        assertTrue(seg.onUp(TimedPoint(300.0, 300.0, 700)).isEmpty())
        // 缓冲期内中止：什么都没注入过，也不需要取消
        seg.onDown(TimedPoint(1.0, 1.0, 0))
        assertTrue(seg.onCancel().isEmpty())
    }

    @Test
    fun cancelWhileBufferingInjectsNothing() {
        seg.onDown(TimedPoint(10.0, 20.0, 0))
        assertTrue(seg.onCancel().isEmpty())
        assertTrue(seg.onUp(TimedPoint(10.0, 20.0, 50)).isEmpty())
    }

    @Test
    fun newDownAbortsStreamingGesture() {
        seg.onDown(TimedPoint(10.0, 20.0, 0))
        now = 40
        seg.tick()
        val out = seg.onDown(TimedPoint(50.0, 60.0, 0))
        assertEquals(listOf(GestureCommand.Abort), out)
        assertTrue(seg.active)
    }

    @Test
    fun outOfOrderSamplesAreDropped() {
        seg.onDown(TimedPoint(0.0, 0.0, 0))
        now = 40
        seg.tick()
        now = 60
        val out = seg.onMove(listOf(TimedPoint(0.0, 50.0, 50), TimedPoint(0.0, 40.0, 30))).single() as GestureCommand.Segment
        assertEquals(listOf(0L, 50L), out.points.map { it.dt })
    }

    /** 模拟器回归：静止按住 1.5 s 后抬起，最后一条必须是抬起（last 段），不是 Abort；续接段都不为零长度。 */
    @Test
    fun holdStillThenReleaseEndsWithUpNotAbort() {
        seg.onDown(TimedPoint(500.0, 800.0, 0))
        val out = mutableListOf<GestureCommand>()
        var t = 0L
        while (t < 1_500) {
            t += 8
            now = t
            if (t % 250 == 0L) out += seg.onMove(listOf(TimedPoint(500.0, 800.0, t))) // 接收端保活
            out += seg.tick()
        }
        out += seg.onUp(TimedPoint(500.0, 800.0, 1_500))
        assertFalse(seg.active)
        assertTrue(out.none { it is GestureCommand.Abort })
        val segments = out.filterIsInstance<GestureCommand.Segment>()
        assertTrue(segments.first().first)
        val end = out.last() as GestureCommand.Segment
        assertTrue(end.last && !end.first)
        // 续接段（除第一段按下外）都有非零路径，且微动不超过 1 px（远小于触摸 slop）
        segments.drop(1).forEach { sgm ->
            val len = sgm.points.zipWithNext { a, b -> kotlin.math.hypot(b.x - a.x, b.y - a.y) }.sum()
            assertTrue("zero-length continuation", len >= 0.5)
            sgm.points.forEach { p -> assertTrue(kotlin.math.hypot(p.x - 500.0, p.y - 800.0) <= 1.0 + 1e-9) }
        }
        // 每段续接都从上一段终点开始
        segments.zipWithNext { a, b -> assertEquals(a.points.last().x, b.points.first().x, 0.0); assertEquals(a.points.last().y, b.points.first().y, 0.0) }
        // 静止期间不重放时长：微动段时长 = 刷新间隔
        assertTrue(segments.drop(1).all { it.durationMs <= 16 })
    }

    @Test
    fun holdThenDragUsesRealMovementAndRecentTiming() {
        seg.onDown(TimedPoint(100.0, 100.0, 0))
        now = 30
        seg.tick()
        now = 250
        seg.onMove(listOf(TimedPoint(100.0, 100.0, 250)))
        now = 600
        val drag = seg.onMove(listOf(TimedPoint(100.0, 200.0, 600))).single() as GestureCommand.Segment
        assertEquals(100.0, drag.points.last().y - 100.0, 0.0)
        // 时长按最近一次采样（250 ms 保活）计算，被 maxSegmentMs 截断，而不是从按下算起
        assertTrue(drag.durationMs <= 350)
    }
}
