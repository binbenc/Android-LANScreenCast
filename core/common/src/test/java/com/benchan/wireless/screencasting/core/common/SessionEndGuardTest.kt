package com.benchan.wireless.screencasting.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndGuardTest {

    /** 模拟控制器：超时 Job 触发 end，end 先取消超时 Job（也就是自己），再挂起关闭会话。 */
    private class FakeController(private val scope: kotlinx.coroutines.CoroutineScope) {
        val guard = SessionEndGuard()
        var timeoutJob: Job? = null
        var state = "Casting"
        var closeCalls = 0
        var serviceStopped = false

        fun start() {
            guard.begin()
            timeoutJob = scope.launch {
                delay(1_000)
                end("RECONNECT_FAILED")
            }
        }

        suspend fun end(reason: String) {
            guard.end {
                timeoutJob?.cancel() // 取消的正是调用 end 的协程
                withContext(Dispatchers.IO) { closeCalls++ } // 挂起点：没有守卫时这里会抛 CancellationException
                serviceStopped = true
                state = "Ended($reason)"
            }
        }

        fun lateCallback(gen: Long, reason: String) = scope.launch {
            if (guard.isCurrent(gen)) end(reason)
        }
    }

    @Test
    fun endTriggeredByTimeoutJobCompletesCleanup() = runTest {
        val c = FakeController(this)
        c.start()
        c.timeoutJob!!.join()
        assertEquals(1, c.closeCalls)
        assertTrue(c.serviceStopped)
        assertEquals("Ended(RECONNECT_FAILED)", c.state)
    }

    @Test
    fun endIsIdempotentAndLateCallbacksAreIgnored() = runTest {
        val c = FakeController(this)
        c.start()
        val gen = c.guard.current
        c.end("USER_STOPPED")
        c.lateCallback(gen, "PROJECTION_STOPPED").join()
        c.end("PROJECTION_STOPPED")
        assertEquals(1, c.closeCalls)
        assertEquals("Ended(USER_STOPPED)", c.state)
        assertFalse(c.guard.isCurrent(gen))
        // 新会话不受旧代号影响
        c.start()
        assertTrue(c.guard.isCurrent(c.guard.current))
        assertFalse(c.guard.isCurrent(gen))
    }
}
