package com.benchan.wireless.screencasting.core.control

import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.control.PointerAction
import com.benchan.wireless.screencasting.core.protocol.control.RejectReason
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.protocol.control.history
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerCoalescerAndPolicyTest {
    private var now = 1_000L
    private val co = PointerCoalescer({ now })

    @Test
    fun movesAreThrottledWithHistory() {
        val down = co.down(0.1, 0.2, 7).single() as InputMessage.Pointer
        assertEquals(PointerAction.DOWN, down.a)
        assertEquals(7, down.g)
        now += 4
        assertNull(co.move(0.11, 0.2))
        now += 4
        assertNull(co.move(0.12, 0.2))
        now += 9
        val m = co.move(0.13, 0.2) as InputMessage.Pointer
        assertEquals(PointerAction.MOVE, m.a)
        assertEquals(17L, m.dt)
        assertEquals(listOf(4L, 8L), m.history.map { it.dt })
        assertTrue(InputCodec.isValid(m))
        now += 5
        assertNull(co.move(0.14, 0.2))
        val up = co.up(0.15, 0.2) as InputMessage.Pointer
        assertEquals(PointerAction.UP, up.a)
        assertEquals(listOf(22L), up.history.map { it.dt })
        assertTrue(up.s > m.s && m.s > down.s)
        assertFalse(co.active)
    }

    @Test
    fun keepAliveWhileHoldingStill() {
        co.down(0.5, 0.5, 1)
        now += 100
        assertNull(co.tick())
        now += 160
        val ka = co.tick() as InputMessage.Pointer
        assertEquals(PointerAction.MOVE, ka.a)
        assertEquals(260L, ka.dt)
        assertEquals(0.5, ka.x, 0.0)
    }

    @Test
    fun secondDownCancelsPrevious() {
        co.down(0.1, 0.1, 1)
        now += 30
        val out = co.down(0.9, 0.9, 1)
        assertEquals(listOf(PointerAction.CANCEL, PointerAction.DOWN), out.map { (it as InputMessage.Pointer).a })
        assertEquals(30L, (out[0] as InputMessage.Pointer).dt)
    }

    @Test
    fun keySharesSequence() {
        val d = co.down(0.1, 0.1, 1).single()
        val k = co.key(RemoteKey.BACK) as InputMessage.Key
        assertTrue(k.s > d.s)
    }

    @Test
    fun policyGates() {
        val capsule = RectD(1000.0, 800.0, 1080.0, 848.0)
        val statusBar = RectD(0.0, 0.0, 1080.0, 80.0)
        val ok = InjectionContext(canInject = true, keyguardLocked = false, consentVisible = false, blocked = listOf(capsule, statusBar))
        assertNull(ControlPolicy.checkDown(500.0, 500.0, ok))
        assertEquals(RejectReason.BLOCKED_TARGET, ControlPolicy.checkDown(1050.0, 820.0, ok))
        assertEquals(RejectReason.BLOCKED_TARGET, ControlPolicy.checkDown(500.0, 10.0, ok))
        assertEquals(RejectReason.PAUSED, ControlPolicy.checkDown(500.0, 500.0, ok.copy(canInject = false)))
        assertEquals(RejectReason.LOCKED, ControlPolicy.checkOngoing(ok.copy(keyguardLocked = true)))
        val all = com.benchan.wireless.screencasting.core.protocol.control.ControlFeatures.forSdk(36).toSet()
        assertEquals(RejectReason.BLOCKED_TARGET, ControlPolicy.checkKey(RemoteKey.BACK, ok.copy(consentVisible = true), all))
        assertNull(ControlPolicy.checkKey(RemoteKey.HOME, ok, all))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkKey(RemoteKey.NOTIFICATIONS, ok, all))
        assertEquals(RejectReason.UNSUPPORTED, ControlPolicy.checkKey(RemoteKey.UNKNOWN, ok, all))
    }

    @Test
    fun rejectLimiterAndSequenceGate() {
        val l = RejectLimiter({ now })
        assertTrue(l.shouldSend(RejectReason.PAUSED))
        assertFalse(l.shouldSend(RejectReason.PAUSED))
        assertTrue(l.shouldSend(RejectReason.GEOM_STALE))
        now += 1_000
        assertTrue(l.shouldSend(RejectReason.PAUSED))
        val g = SequenceGate()
        assertTrue(g.accept(1))
        assertTrue(g.accept(5))
        assertFalse(g.accept(5))
        assertFalse(g.accept(3))
        g.reset()
        assertTrue(g.accept(1))
    }
}
