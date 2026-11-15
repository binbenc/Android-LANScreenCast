package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.control.ControlPhase
import com.benchan.wireless.screencasting.core.protocol.control.ControlReason
import com.benchan.wireless.screencasting.core.protocol.control.ControlStateMachine
import com.benchan.wireless.screencasting.core.protocol.control.ControlTransition
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStateMachineTest {

    @Test
    fun sharedControlSequences() {
        val cases = Vectors.load("state/control-sequences.json")["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            var now = 0L
            val initial = c["initial"]!!.jsonPrimitive.content
            val sm = ControlStateMachine({ now }, if (initial == "available") null else ControlReason.valueOf(initial))
            val events = c["events"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expect = c["expect"]!!.jsonArray.map { it.jsonPrimitive.content }
            val reasons = c["reason"]?.jsonArray?.map { if (it is JsonNull) null else it.jsonPrimitive.content }
            val inject = c["inject"]?.jsonArray?.map { it.jsonPrimitive.boolean }
            assertEquals(name, events.size, expect.size)
            events.forEachIndexed { i, e ->
                val parts = e.split(':')
                when (parts[0]) {
                    "avail" -> sm.setAvailability(null)
                    "unavail" -> sm.setAvailability(ControlReason.valueOf(parts[1]))
                    "req" -> sm.request(parts[1].toInt(), deviceLocked = parts.getOrNull(2) == "locked")
                    "allow" -> sm.allow()
                    "deny" -> sm.deny()
                    "tick" -> sm.tick()
                    "recv-end" -> sm.receiverEnd()
                    "revoke" -> sm.revoke()
                    "lock" -> sm.locked()
                    "pause" -> sm.setPaused(ControlReason.valueOf(parts[1]), true)
                    "resume" -> sm.setPaused(ControlReason.valueOf(parts[1]), false)
                    "end" -> sm.sessionEnded()
                    "wait" -> now += parts[1].toLong()
                    else -> error("unknown event $e in $name")
                }
                val snap = sm.snapshot()
                assertEquals("$name #$i ($e)", expect[i], snap.phase.name)
                reasons?.let { assertEquals("$name #$i ($e) reason", it[i], snap.reason?.name) }
                inject?.let { assertEquals("$name #$i ($e) inject", it[i], sm.canInject) }
            }
        }
    }

    @Test
    fun pendingReportsRemainingTimeAndCooldownRemaining() {
        var now = 1_000L
        val sm = ControlStateMachine({ now }, null)
        sm.request(1)
        now += 2_000
        assertEquals(28_000L, sm.snapshot().remainMs)
        sm.deny()
        now += 3_000
        val t: ControlTransition = sm.request(2)
        assertTrue(t.reply)
        assertEquals(ControlReason.COOLDOWN, t.snapshot.reason)
        assertEquals(7_000L, t.snapshot.remainMs)
    }

    @Test
    fun transitionsReportEndedReason() {
        var now = 0L
        val sm = ControlStateMachine({ now }, null)
        sm.request(1)
        sm.allow()
        val paused = sm.setPaused(ControlReason.PROTECTED, true)
        assertTrue(paused.changed)
        assertEquals(ControlPhase.PAUSED, paused.snapshot.phase)
        val locked = sm.locked()
        assertEquals(ControlReason.LOCKED, locked.ended)
        assertFalse(sm.canInject)
        // 已结束后再锁屏不重复报告
        now += 1
        assertEquals(null, sm.locked().ended)
    }
}
