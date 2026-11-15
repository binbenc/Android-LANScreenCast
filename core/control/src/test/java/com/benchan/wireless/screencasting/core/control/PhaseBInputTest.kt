package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures
import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.protocol.control.RejectReason
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseBInputTest {

    // —— TvCursorModel ——

    private fun cursor(): TvCursorModel = TvCursorModel(density = 2f).apply { setBounds(RectD(100.0, 0.0, 1100.0, 1000.0)) }

    @Test
    fun cursorStartsCenteredAndStepsEightDp() {
        val c = cursor()
        assertEquals(600.0, c.x, 0.0)
        assertEquals(500.0, c.y, 0.0)
        assertNull(c.keyDown(Direction.RIGHT, 0))
        assertEquals(616.0, c.x, 0.0) // 8 dp × 2
    }

    @Test
    fun cursorSpeedIsTimeBasedAndContinuous() {
        val c = cursor()
        c.keyDown(Direction.LEFT, 0)
        // 前 300 ms 慢速 120 dp/s（= 240 px/s），连续移动
        assertEquals(240.0, c.speedPxPerSec(100), 1e-9)
        val before = c.x
        assertTrue(c.frame(100).moved)
        assertEquals(before - 240.0 * 0.05, c.x, 1e-9) // 帧间隔上限 50 ms
        // 300 ms 处与慢速衔接（不跳变），之后线性加速到“约 1 s 横穿内容区长边”= 1000 px/s
        assertEquals(240.0, c.speedPxPerSec(300), 1e-9)
        assertEquals(620.0, c.speedPxPerSec(600), 1e-9)
        assertEquals(1000.0, c.speedPxPerSec(900), 1e-9)
        assertEquals(1000.0, c.maxPxPerSec, 1e-9)
        assertEquals(1000.0, c.speedPxPerSec(5_000), 1e-9)
        c.keyUp(Direction.LEFT, 950)
        assertFalse(c.frame(1_000).moved)
        assertEquals(0.0, c.speedPxPerSec(1_000), 0.0)
    }

    @Test
    fun cursorSpeedIndependentOfFrameRate() {
        fun run(frameMs: Long): Double {
            val c = cursor()
            c.keyDown(Direction.RIGHT, 0)
            var t = 0L
            while (t < 1_000) {
                t += frameMs
                c.frame(t)
            }
            return c.x
        }
        assertEquals(run(16), run(8), 3.0)
    }

    @Test
    fun cursorClampedToContentRect() {
        val c = cursor()
        c.keyDown(Direction.LEFT, 0)
        var t = 0L
        repeat(200) { t += 16; c.frame(t) }
        assertEquals(100.0, c.x, 0.0)
    }

    @Test
    fun edgeHoldScrollsAfter300msThenEvery500ms() {
        val c = cursor()
        c.keyDown(Direction.UP, 0)
        var t = 0L
        val scrolls = mutableListOf<Long>()
        var reachedAt = -1L
        while (t < 3_000) {
            t += 10
            val f = c.frame(t)
            if (reachedAt < 0 && c.y <= 0.0) reachedAt = t
            f.scroll?.let { assertEquals(-1.0 / 3, it.dy, 1e-9); scrolls += t }
        }
        assertTrue(reachedAt > 0)
        assertTrue(scrolls.isNotEmpty())
        assertTrue(scrolls.first() - reachedAt in 300..310) // 到达边缘后按住 300 ms 才滚动
        scrolls.zipWithNext { a, b -> assertEquals(500L, b - a) } // 之后每 500 ms
    }

    @Test
    fun pressAtEdgeScrollsImmediatelyThenRepeats() {
        val c = TvCursorModel(1f).apply { setBounds(RectD(0.0, 0.0, 100.0, 100.0)) }
        c.keyDown(Direction.DOWN, 0)
        var t = 0L
        repeat(100) { t += 10; c.frame(t) }
        c.keyUp(Direction.DOWN, t)
        val s = c.keyDown(Direction.DOWN, t + 10)
        assertEquals(1.0 / 3, s!!.dy, 1e-9)
        var u = t + 10
        var next: Long? = null
        while (next == null && u < t + 2_000) {
            u += 10
            if (c.frame(u).scroll != null) next = u
        }
        assertEquals(t + 10 + 500, next) // 按下即滚一次，按住 500 ms 后再滚
    }

    @Test
    fun heldStateAndEdgeFlagForTracing() {
        val c = TvCursorModel(1f).apply { setBounds(RectD(0.0, 0.0, 100.0, 100.0)) }
        assertFalse(c.isHeld(Direction.DOWN))
        c.keyDown(Direction.DOWN, 0)
        assertTrue(c.isHeld(Direction.DOWN))
        var t = 0L
        var edge = false
        repeat(100) { t += 10; edge = c.frame(t).atEdge }
        assertTrue(edge)
        c.keyUp(Direction.DOWN, t)
        assertFalse(c.isHeld(Direction.DOWN))
        assertFalse(c.frame(t + 10).atEdge)
    }

    @Test
    fun cursorFadesAfterThreeSecondsAndRestoresOnKey() {
        val c = cursor()
        c.touch(1_000)
        assertEquals(1f, c.alpha(3_999))
        assertEquals(0.4f, c.alpha(4_000))
        c.touch(4_100)
        assertEquals(1f, c.alpha(4_200))
    }

    @Test
    fun cursorKeepsRelativePositionOnBoundsChange() {
        val c = cursor()
        c.keyDown(Direction.RIGHT, 0) // x = 616 → 51.6%
        c.setBounds(RectD(0.0, 0.0, 2000.0, 1000.0))
        assertEquals(1032.0, c.x, 1e-6)
    }

    // —— KeyMapper：按发送端系统版本降级（29 / 33 / 36）——

    private val f29 = ControlFeatures.forSdk(29).toSet()
    private val f33 = ControlFeatures.forSdk(33).toSet()
    private val f36 = ControlFeatures.forSdk(36).toSet()

    @Test
    fun featuresBySdk() {
        assertEquals(setOf("tap", "stroke", "global", "wheel", "multi"), f29)
        assertTrue(ControlFeatures.DPAD in f33 && ControlFeatures.TEXT in f33 && ControlFeatures.MEDIA !in f33)
        assertTrue(ControlFeatures.MEDIA in f36)
    }

    @Test
    fun remoteInCursorModeMovesCursorOnAllVersions() {
        for (f in listOf(f29, f33, f36)) {
            assertEquals(KeyAction.Cursor(Direction.UP), KeyMapper.map(KeyInput(InKey.UP, KeySource.REMOTE), TvControlMode.CURSOR, f))
            assertEquals(KeyAction.CursorPress, KeyMapper.map(KeyInput(InKey.CENTER, KeySource.REMOTE), TvControlMode.CURSOR, f))
            assertEquals(KeyAction.CursorPress, KeyMapper.map(KeyInput(InKey.ENTER, KeySource.REMOTE), TvControlMode.CURSOR, f))
        }
    }

    @Test
    fun remoteInDpadModeNeedsAndroid13() {
        assertEquals(KeyAction.Send(RemoteKey.DPAD_LEFT), KeyMapper.map(KeyInput(InKey.LEFT, KeySource.REMOTE), TvControlMode.DPAD, f33))
        assertEquals(KeyAction.Send(RemoteKey.DPAD_CENTER), KeyMapper.map(KeyInput(InKey.CENTER, KeySource.REMOTE), TvControlMode.DPAD, f33))
        // 29：退回光标
        assertEquals(KeyAction.Cursor(Direction.LEFT), KeyMapper.map(KeyInput(InKey.LEFT, KeySource.REMOTE), TvControlMode.DPAD, f29))
        assertEquals(KeyAction.CursorPress, KeyMapper.map(KeyInput(InKey.CENTER, KeySource.REMOTE), TvControlMode.DPAD, f29))
    }

    @Test
    fun keyboardMapping() {
        val k = KeySource.KEYBOARD
        val m = TvControlMode.CURSOR
        assertEquals(KeyAction.Send(RemoteKey.DPAD_DOWN), KeyMapper.map(KeyInput(InKey.DOWN, k), m, f33))
        assertEquals(KeyAction.Unsupported(ControlFeatures.DPAD), KeyMapper.map(KeyInput(InKey.DOWN, k), m, f29))
        assertEquals(KeyAction.Send(RemoteKey.BACK), KeyMapper.map(KeyInput(InKey.ESCAPE, k), m, f29))
        assertEquals(KeyAction.Send(RemoteKey.HOME), KeyMapper.map(KeyInput(InKey.META, k), m, f29))
        assertEquals(KeyAction.Send(RemoteKey.RECENTS), KeyMapper.map(KeyInput(InKey.TAB, k, alt = true), m, f29))
        assertEquals(KeyAction.Ignore, KeyMapper.map(KeyInput(InKey.TAB, k), m, f33)) // 单独的 Tab 忽略
        assertEquals(KeyAction.Send(RemoteKey.ENTER), KeyMapper.map(KeyInput(InKey.ENTER, k), m, f33))
        assertEquals(KeyAction.Unsupported(ControlFeatures.TEXT), KeyMapper.map(KeyInput(InKey.ENTER, k), m, f29))
        assertEquals(KeyAction.Send(RemoteKey.DEL), KeyMapper.map(KeyInput(InKey.BACKSPACE, k), m, f33))
        assertEquals(KeyAction.Type('a'), KeyMapper.map(KeyInput(InKey.CHAR, k, 'a'), m, f33))
        assertEquals(KeyAction.Unsupported(ControlFeatures.TEXT), KeyMapper.map(KeyInput(InKey.CHAR, k, 'a'), m, f29))
        assertEquals(KeyAction.Ignore, KeyMapper.map(KeyInput(InKey.CHAR, k, 'c', ctrl = true), m, f33))
        assertEquals(KeyAction.Back, KeyMapper.map(KeyInput(InKey.BACK, KeySource.REMOTE), m, f29))
        assertEquals(KeyAction.ToggleBar, KeyMapper.map(KeyInput(InKey.MENU, KeySource.REMOTE), m, f29))
    }

    @Test
    fun playPauseNeedsAndroid16() {
        val p = KeyInput(InKey.PLAY_PAUSE, KeySource.REMOTE)
        assertEquals(KeyAction.Unsupported(ControlFeatures.MEDIA), KeyMapper.map(p, TvControlMode.CURSOR, f33))
        assertEquals(KeyAction.Send(RemoteKey.PLAY_PAUSE), KeyMapper.map(p, TvControlMode.CURSOR, f36))
    }

    @Test
    fun policyDegradesKeysByFeatures() {
        val ctx = InjectionContext(canInject = true, keyguardLocked = false, consentVisible = false, blocked = emptyList())
        assertNull(ControlPolicy.checkKey(RemoteKey.DPAD_UP, ctx, f33))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkKey(RemoteKey.DPAD_UP, ctx, f29))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkKey(RemoteKey.ENTER, ctx, f29))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkKey(RemoteKey.PLAY_PAUSE, ctx, f33))
        assertNull(ControlPolicy.checkKey(RemoteKey.PLAY_PAUSE, ctx, f36))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkKey(RemoteKey.MENU, ctx, f36))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkText(ctx, f29))
        assertNull(ControlPolicy.checkText(ctx, f33))
        assertEquals(RejectReason.PAUSED, ControlPolicy.checkText(ctx.copy(canInject = false), f33))
    }

    // —— 文字合并与密码框 ——

    @Test
    fun textMergedWithinFiftyMs() {
        var now = 0L
        val t = TextCoalescer({ now })
        assertNull(t.type('你'))
        now = 30
        assertNull(t.type('好'))
        now = 60
        assertNull(t.tick()) // 距最后一个字符 30 ms
        now = 80
        assertEquals("你好", t.tick())
        assertFalse(t.pending)
        assertNull(t.tick())
        t.type('a')
        assertEquals("a", t.flush()) // 其他按键前先发出
    }

    @Test
    fun textChunksRespectLimitAndSurrogates() {
        val emoji = "😀"
        val s = "a".repeat(999) + emoji + "b"
        val parts = TextCoalescer.chunks(s)
        assertEquals(s, parts.joinToString(""))
        assertTrue(parts.all { it.length <= InputCodec.MAX_TEXT_CHARS })
        assertEquals("a".repeat(999), parts[0]) // 不拆开代理对
        var now = 0L
        val t = TextCoalescer({ now })
        var out: String? = null
        repeat(InputCodec.MAX_TEXT_CHARS) { out = t.type('x') ?: out }
        assertEquals(InputCodec.MAX_TEXT_CHARS, out!!.length)
    }

    @Test
    fun passwordInputTypesRefused() {
        assertTrue(InputTypes.isPassword(0x81)) // TEXT | PASSWORD
        assertTrue(InputTypes.isPassword(0x91)) // TEXT | VISIBLE_PASSWORD
        assertTrue(InputTypes.isPassword(0xe1)) // TEXT | WEB_PASSWORD
        assertTrue(InputTypes.isPassword(0x12)) // NUMBER | NUMBER_PASSWORD
        assertFalse(InputTypes.isPassword(0x01)) // 普通文本
        assertFalse(InputTypes.isPassword(0x21)) // 邮箱
        assertFalse(InputTypes.isPassword(0x02)) // 数字
        assertFalse(InputTypes.isPassword(0x80 or 0x02)) // NUMBER 类里的 0x80 不是密码
    }

    // —— 滚轮合并与合成 ——

    @Test
    fun wheelNotchesMergedWithinFiftyMs() {
        var now = 0L
        val w = WheelCoalescer({ now })
        assertNull(w.scroll(0.5, 0.5, 1.0))
        now = 20
        assertNull(w.scroll(0.5, 0.6, 1.0))
        now = 40
        assertNull(w.tick())
        now = 50
        val s = w.tick()!!
        assertEquals(0.16, s.dy, 1e-9)
        assertEquals(0.6, s.y, 0.0)
        assertNull(w.tick())
    }

    @Test
    fun wheelDirectionChangeFlushesAndCapsAtMax() {
        var now = 0L
        val w = WheelCoalescer({ now })
        w.scroll(0.5, 0.5, 2.0)
        val flushed = w.scroll(0.5, 0.5, -1.0)!!
        assertEquals(0.16, flushed.dy, 1e-9)
        now = 60
        assertEquals(-0.08, w.tick()!!.dy, 1e-9)
        val big = w.scroll(0.5, 0.5, 20.0)!!
        assertEquals(0.6, big.dy, 1e-9)
    }

    @Test
    fun wheelSynthesizedSwipeStaysInSafeZoneAndSettles() {
        // dy > 0：手指上滑，长度 = 0.24 × 2000
        val cmds = WheelSynthesizer.synthesize(540.0, 1900.0, 0.24, 1080, 2000)
        val move = cmds[0] as GestureCommand.Segment
        val settle = cmds[1] as GestureCommand.Segment
        assertTrue(move.first && !move.last && settle.last && !settle.first)
        val start = move.points.first()
        val end = move.points.last()
        assertTrue(end.y < start.y)
        assertEquals(480.0, start.y - end.y, 1e-6)
        assertTrue(start.y <= 2000 * 0.88 + 1e-6 && end.y >= 2000 * 0.12 - 1e-6)
        assertEquals(end, settle.points.first())
        assertEquals(1.0, kotlin.math.abs(settle.points.last().y - end.y), 1e-9)
        // dy < 0：手指下滑；顶部指针被推进安全区
        val up = WheelSynthesizer.synthesize(10.0, 0.0, -1.0 / 3, 1080, 2000)
        val m2 = up[0] as GestureCommand.Segment
        assertTrue(m2.points.last().y > m2.points.first().y)
        assertTrue(m2.points.first().y >= 2000 * 0.12 - 1e-6)
        assertEquals(108.0, m2.points.first().x, 1e-6)
        assertTrue(WheelSynthesizer.synthesize(1.0, 1.0, 0.0, 100, 100).isEmpty())
    }

    /** 方向回归（模拟器复测）：dy > 0 → 手指自下而上；dy < 0 → 手指自上而下；滚轮向上（AXIS_VSCROLL > 0）→ dy < 0。 */
    @Test
    fun wheelDirectionMapping() {
        fun fingerDy(dy: Double, anchorY: Double): Double {
            val m = WheelSynthesizer.synthesize(540.0, anchorY, dy, 1080, 2400)[0] as GestureCommand.Segment
            return m.points.last().y - m.points.first().y
        }
        for (anchor in listOf(120.0, 1200.0, 2280.0)) {
            assertTrue("dy=+1/3 at $anchor: finger moves up", fingerDy(1.0 / 3, anchor) < 0)
            assertTrue("dy=-1/3 at $anchor: finger moves down", fingerDy(-1.0 / 3, anchor) > 0)
        }
        // 滚轮向上（远离用户）：AXIS_VSCROLL > 0 → 格数 < 0 → dy < 0 → 手指自上而下
        val notches = WheelDirection.notchesFromAxisVScroll(1f)
        assertTrue(notches < 0)
        var now = 0L
        val w = WheelCoalescer({ now })
        w.scroll(0.5, 0.5, notches)
        now = 60
        val s = w.tick()!!
        assertTrue(s.dy < 0)
        assertTrue(fingerDy(s.dy, 1200.0) > 0)
        assertEquals(1.0, WheelDirection.notchesFromComposeScrollDelta(1f), 0.0)
    }

    /** 顶边向上滚动不在标题栏 / 可折叠头部起手：滑动中心移到 40% 高度，起点至少在 20% 以下。 */
    @Test
    fun edgeAnchoredSwipesMoveTowardScreenMiddle() {
        val up = WheelSynthesizer.synthesize(540.0, 0.05 * 2400, -1.0 / 3, 1080, 2400)[0] as GestureCommand.Segment
        assertTrue(up.points.first().y >= 2400 * 0.2)
        assertEquals(2400 * 0.4, (up.points.first().y + up.points.last().y) / 2, 1e-6)
        val down = WheelSynthesizer.synthesize(540.0, 0.95 * 2400, 1.0 / 3, 1080, 2400)[0] as GestureCommand.Segment
        assertEquals(2400 * 0.6, (down.points.first().y + down.points.last().y) / 2, 1e-6)
        // 中部锚点不移动
        val mid = WheelSynthesizer.synthesize(540.0, 1000.0, 0.08, 1080, 2400)[0] as GestureCommand.Segment
        assertEquals(1000.0, (mid.points.first().y + mid.points.last().y) / 2, 1e-6)
    }
}
